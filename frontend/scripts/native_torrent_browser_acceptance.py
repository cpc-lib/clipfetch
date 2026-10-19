"""Real Chrome/Vue smoke test, isolated via mocked API responses.

No real user credentials, network torrent download, production DB, or qBittorrent calls.
Requires Python Playwright and an isolated Vite server at http://127.0.0.1:5175.
Run: python frontend/scripts/native_torrent_browser_acceptance.py
"""
import json
import argparse
import urllib.parse
from playwright.sync_api import sync_playwright, expect

TASK = "e2314347-303c-4bca-8bbb-2f57a91a0019"
JOB = "e2314347-303c-4bca-8bbb-2f57a91a0020"


class Api:
    def __init__(self):
        self.health = dict(configuredEnabled=True, taskControlEnabled=True,
            taskCreationEnabled=True, workerRoundEnabled=True,
            asyncWorkerEnabled=True, continuousWorkerEnabled=True,
            singleInstanceOrphanRecoveryEnabled=True)
        self.task = None
        self.job = None
        self.created = self.submitted = self.cancelled = self.recovered = 0

    def row(self):
        return dict(self.task, progressPercent=round(100 * self.task["verifiedBytes"] /
            self.task["selectedBytes"], 1)) if self.task else None

    def __call__(self, route):
        req = route.request
        parsed = urllib.parse.urlsplit(req.url)
        path = parsed.path
        if not path.startswith("/api/magnet/native/"):
            route.fulfill(status=404, json={"success": False, "error": "isolated browser mock"})
            return
        suffix = path[len("/api/magnet/native/"):]
        method = req.method
        try:
            data = req.post_data_json if "application/json" in req.headers.get("content-type", "") else {}
        except ValueError:
            data = {}
        version = data.get("expectedRevision") if isinstance(data, dict) else None
        result = None
        error = None
        status = 200
        if suffix == "engine/health" and method == "GET":
            result = dict(self.health)
        elif suffix == "torrent/inspect" and method == "POST":
            result = dict(infoHash="ab" * 20, name="Public test torrent", totalBytes=524288,
                pieceLength=262144, pieceCount=2, files=[
                    dict(index=0, path="alpha.iso", size=262144),
                    dict(index=1, path="beta.iso", size=262144)])
        elif suffix == "tasks" and method == "POST":
            self.created += 1
            self.task = dict(taskUuid=TASK, state="CREATED", revision=0,
                verifiedBytes=0, selectedBytes=524288, wireBytes=0)
            result = dict(taskUuid=TASK, state="CREATED", revision=0,
                infoHash="ab" * 20, selectedBytes=524288)
        elif suffix == "tasks" and method == "GET":
            result = [self.row()] if self.task else []
        elif suffix == f"tasks/{TASK}" and method == "GET":
            result = self.row()
        elif suffix == f"tasks/{TASK}/worker-job" and method == "GET":
            result = dict(self.job) if self.job else None
        elif suffix == f"tasks/{TASK}/prepare" and method == "POST":
            if self.task["revision"] != version or self.task["state"] not in ("CREATED", "PAUSED", "FAILED"):
                status, error = 409, "版本冲突"
            else:
                self.task.update(state="READY", revision=self.task["revision"]+1)
                result = self.row()
        elif suffix == f"tasks/{TASK}/rounds" and method == "POST":
            if self.task["revision"] != version or self.task["state"] != "READY" or self.job:
                status, error = 409, "作业已存在"
            else:
                self.submitted += 1
                self.task.update(state="RUNNING", revision=self.task["revision"]+1)
                self.job = dict(jobId=JOB, taskUuid=TASK, state="RUNNING",
                    candidatePeers=4, committedPieces=0, verifiedBytes=0,
                    error=None, createdAt="2026-10-15T12:00:00Z", terminal=False)
                result = dict(jobId=JOB, status=dict(self.job))
        elif suffix == f"tasks/{TASK}/recover-orphan" and method == "POST":
            if self.task["revision"] != version or self.task["state"] != "RUNNING" or self.job:
                status, error = 409, "不允许恢复"
            else:
                self.recovered += 1
                self.task.update(state="PAUSED", revision=self.task["revision"]+1)
                result = self.row()
        elif suffix == f"tasks/{TASK}/pause" and method == "POST":
            if self.task["revision"] != version:
                status, error = 409, "版本冲突"
            else:
                self.task.update(state="PAUSED", revision=self.task["revision"]+1)
                result = self.row()
        elif suffix == f"worker-jobs/{JOB}" and method == "GET":
            if self.job: result = dict(self.job)
            else: status, error = 404, "作业不存在"
        elif suffix == f"worker-jobs/{JOB}/metrics" and method == "GET":
            if self.job:
                result = dict(jobId=JOB, rounds=1, committedPieces=0,
                    recentBytesPerSecond=0, elapsedMillis=1250, selectedBytes=524288,
                    verifiedBytes=0, state=self.job["state"])
            else: status, error = 404, "作业不存在"
        elif suffix == f"worker-jobs/{JOB}" and method == "DELETE":
            if self.job:
                self.cancelled += 1
                self.task.update(state="PAUSED", revision=self.task["revision"]+1)
                self.job.update(state="CANCELLED", terminal=True)
                result = dict(self.job)
            else: status, error = 404, "作业不存在"
        elif suffix == f"tasks/{TASK}" and method == "DELETE":
            self.task = None
        else:
            status, error = 404, "测试 API 不存在"
        route.fulfill(status=status, json={"success":status==200,"data":result,"error":error})


def open_native(page,url):
    page.goto(url,wait_until="domcontentloaded")
    page.get_by_role("button",name="原生 BT（实验）").click()
    expect(page.get_by_role("heading",name="原生 BT 下载")).to_be_visible()


def run(url,chrome):
    api=Api()
    with sync_playwright() as p:
        browser=p.chromium.launch(headless=True,executable_path=chrome)
        try:
            anon=browser.new_context()
            anon.route("**/api/magnet/native/**",api)
            page=anon.new_page()
            open_native(page,url)
            expect(page.get_by_text("请先登录后管理自己的原生下载任务。")).to_be_visible()
            anon.close()
            print("PASS: guest denied Native management")

            ctx=browser.new_context(viewport={"width":1400,"height":900})
            ctx.add_init_script("""localStorage.setItem('fvd_auth',JSON.stringify({
              accessToken:'mocked-browser-token',refreshToken:'',
              user:{id:71,email:'ui-test@example.test',nickname:'UI Test'}}))""")
            ctx.route("**/api/magnet/native/**",api)
            page=ctx.new_page()
            open_native(page,url)
            expect(page.get_by_text("我的 Native 任务")).to_be_visible()
            page.locator('input[type="file"]').set_input_files({
                "name":"public.torrent","mimeType":"application/x-bittorrent","buffer":b"d3:fooe"})
            expect(page.get_by_text("Public test torrent")).to_be_visible()
            expect(page.get_by_text("文件选择：2 / 2")).to_be_visible()
            page.get_by_role("button",name="仅创建任务").click()
            expect(page.get_by_text("已创建任务",exact=False)).to_be_visible()
            assert api.created==1 and api.submitted==0
            print("PASS: upload, file selection, CREATED without network submit")

            page.get_by_role("button",name="启动有限下载").click()
            expect(page.get_by_text("已提交有限下载作业",exact=False)).to_be_visible()
            assert api.submitted==1 and api.task["state"]=="RUNNING"
            print("PASS: bounded worker submitted exactly once")

            open_native(page,url)
            expect(page.get_by_text("作业：下载中",exact=False)).to_be_visible(timeout=9000)
            page.get_by_role("button",name="暂停").click()
            expect(page.get_by_text("暂停操作已提交",exact=False)).to_be_visible()
            assert api.cancelled==1 and api.task["state"]=="PAUSED"
            print("PASS: refresh reattaches active job and cancels")

            # The current page initially sees a READY task with no job. Another
            # browser tab subsequently submits it; refreshing must not reuse
            # the earlier negative lookup as a permanent cache.
            api.task.update(state="READY", revision=api.task["revision"]+1)
            api.job=None
            open_native(page,url)
            expect(page.get_by_text("待执行",exact=True)).to_be_visible()
            api.task.update(state="RUNNING",revision=api.task["revision"]+1)
            api.job = dict(jobId=JOB,taskUuid=TASK,state="RUNNING",
                candidatePeers=2,committedPieces=0,verifiedBytes=0,
                error=None,createdAt="2026-10-15T12:30:00Z",terminal=False)
            page.get_by_role("button",name="刷新列表").click()
            expect(page.get_by_text("作业：下载中",exact=False)).to_be_visible(timeout=9000)
            page.get_by_role("button",name="暂停").click()
            expect(page.get_by_text("暂停操作已提交",exact=False)).to_be_visible()
            assert api.cancelled == 2
            print("PASS: cross-tab Worker creation invalidates prior negative job lookup")

            api.task.update(state="RUNNING",revision=api.task["revision"]+1)
            api.job=None  # JVM restart: persistent task remains, volatile Job disappears
            open_native(page,url)
            expect(page.get_by_text("数据库显示 RUNNING",exact=False)).to_be_visible(timeout=9000)
            page.once("dialog",lambda dialog:dialog.accept())
            page.get_by_role("button",name="恢复孤儿任务").click()
            expect(page.get_by_text("已将孤儿任务恢复到暂停状态",exact=False)).to_be_visible()
            assert api.recovered==1 and api.task["state"]=="PAUSED"
            print("PASS: simulated restart, explicit confirmed orphan recovery")

            api.health["singleInstanceOrphanRecoveryEnabled"]=False
            api.task.update(state="RUNNING",revision=api.task["revision"]+1)
            open_native(page,url)
            expect(page.get_by_text("数据库显示 RUNNING",exact=False)).to_be_visible(timeout=9000)
            assert page.get_by_role("button",name="恢复孤儿任务").count()==0
            print("PASS: disabled recovery flag hides dangerous action")

            api.health["taskControlEnabled"]=False
            open_native(page,url)
            expect(page.get_by_text("当前服务器尚未开启原生任务管理",exact=False)).to_be_visible()
            assert page.get_by_role("button",name="启动有限下载").count()==0
            print("PASS: disabled Native feature hides control UI")
            ctx.close()
        finally:
            browser.close()
    print("PASS: all eight isolated real Chrome scenarios")


if __name__=="__main__":
    cli=argparse.ArgumentParser()
    cli.add_argument("--url",default="http://127.0.0.1:5175")
    cli.add_argument("--chrome",default="C:/Program Files/Google/Chrome/Application/chrome.exe")
    args=cli.parse_args()
    run(args.url,args.chrome)
