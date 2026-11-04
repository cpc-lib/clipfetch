#!/usr/bin/env python3
"""Deterministic end-to-end acceptance for the ClipFetch native v2 engine."""
import glob
import hashlib
import http.client
import json
import os
import socket
import sys
import time
import traceback

import requests

API = "http://api:8082"
SQLITE_API = "http://api-sqlite:8083"
DATA_ROOT = "/v2data"
FIXTURE_ROOT = "/fixtures"
DOCKER_SOCKET = "/var/run/docker.sock"
USER_A = ("acceptance-a@example.com", "acceptance-pass")
USER_B = ("acceptance-b@example.com", "acceptance-pass")


class Failure(AssertionError):
    pass


def check(condition, message):
    if not condition:
        raise Failure(message)


class Client:
    def __init__(self, base):
        self.base = base
        self.session = requests.Session()

    def request(self, method, path, token=None, timeout=20, **kwargs):
        headers = dict(kwargs.pop("headers", {}))
        if token:
            headers["Authorization"] = "Bearer " + token
        return self.session.request(method, self.base + path, headers=headers, timeout=timeout, **kwargs)

    def data(self, response, status=200):
        if response.status_code != status:
            raise Failure(f"{response.request.method} {response.request.path_url} -> "
                          f"{response.status_code}: {response.text[:300]}")
        body = response.json()
        check(body.get("success") is True, f"接口未成功: {body}")
        return body["data"]


def docker_request(method, path, timeout=30):
    conn = http.client.HTTPConnection("docker", timeout=timeout)
    conn.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    conn.sock.settimeout(timeout)
    conn.sock.connect(DOCKER_SOCKET)
    conn.request(method, path, body=b"")
    response = conn.getresponse()
    status = response.status
    response.read()
    conn.close()
    check(status in (200, 204, 304), f"docker {method} {path} -> {status}")


def restart_container(name):
    docker_request("POST", f"/containers/{name}/restart?t=10")


def stop_container(name):
    docker_request("POST", f"/containers/{name}/stop?t=10")


def start_container(name):
    docker_request("POST", f"/containers/{name}/start")


def register_and_login(client, email, password):
    payload = {"email": email, "password": password, "nickname": "acceptance"}
    client.request("POST", "/api/auth/register", json=payload)
    login = client.request("POST", "/api/auth/login", json={"email": email, "password": password})
    return client.data(login)["accessToken"]


def wait_for_api(client, email, password, timeout=300):
    deadline = time.time() + timeout
    last_error = None
    while time.time() < deadline:
        try:
            token = register_and_login(client, email, password)
            if token:
                return token
        except Exception as error:  # noqa: BLE001 - startup races are retried
            last_error = error
            time.sleep(2)
    raise Failure(f"API 未就绪: {last_error}")


def wait_health(client, token, connected, timeout=180):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            response = client.request("GET", "/api/magnet/v2/engine/health", token=token)
            if response.status_code == 200:
                data = response.json().get("data", {})
                last = data
                if data.get("configured") is True and bool(data.get("connected")) == connected:
                    return data
        except Exception:  # noqa: BLE001 - container restarts break connections
            last = None
        time.sleep(1)
    raise Failure(f"engine health 未达到 connected={connected}: {last}")


def submit_torrent(client, token, kind):
    path = os.path.join(FIXTURE_ROOT, "torrents", f"{kind}.torrent")
    with open(path, "rb") as handle:
        response = client.request("POST", "/api/magnet/v2/parses/torrent", token=token,
                                  files={"file": (f"{kind}.torrent", handle,
                                                  "application/octet-stream")})
    return client.data(response)["parseUuid"]


def submit_magnet(client, token, magnet):
    response = client.request("POST", "/api/magnet/v2/parses", token=token,
                              json={"magnetUri": magnet})
    return client.data(response)["parseUuid"]


def wait_parse(client, token, parse_uuid, timeout=180):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        detail = client.data(client.request("GET", f"/api/magnet/v2/parses/{parse_uuid}", token=token))
        last = detail
        if detail["status"] == "READY":
            return detail
        if detail["status"] == "FAILED":
            raise Failure(f"解析失败: {detail.get('errorCode')} {detail.get('errorMessage')}")
        time.sleep(0.25)
    raise Failure(f"解析超时: {last}")


def create_task(client, token, parse_uuid, indexes):
    response = client.request("POST", "/api/magnet/v2/tasks", token=token,
                              json={"parseUuid": parse_uuid, "selectedFileIndexes": indexes})
    return client.data(response)


def task_detail(client, token, task_uuid):
    return client.data(client.request("GET", f"/api/magnet/v2/tasks/{task_uuid}", token=token))


def wait_task(client, token, task_uuid, predicate, timeout, interval=0.2, observe=None):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = task_detail(client, token, task_uuid)
        if observe is not None:
            observe(last)
        if predicate(last):
            return last
        if last["status"] == "FAILED":
            raise Failure(f"任务失败: {last.get('errorCode')} {last.get('errorMessage')}")
        time.sleep(interval)
    raise Failure(f"任务状态超时: {last}")


def wait_completed(client, token, task_uuid, timeout=240):
    seen = set()
    result = wait_task(client, token, task_uuid, lambda value: value["status"] == "COMPLETED",
                       timeout, observe=lambda value: seen.add(value["status"]))
    return result, seen


def issue_ticket(client, token, task_uuid, file_index):
    response = client.request("POST", f"/api/magnet/v2/tasks/{task_uuid}/file-ticket",
                              token=token, json={"fileIndex": file_index})
    return client.data(response)["url"]


def stream(url, headers=None):
    return requests.get(API + url, headers=headers or {}, timeout=30)


def index_by_suffix(detail, suffix):
    for item in detail["files"]:
        if item["relativePath"].endswith(suffix):
            return item
    raise Failure(f"未找到文件 {suffix}")


def wait_progress(client, token, task_uuid, minimum, timeout=90):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = task_detail(client, token, task_uuid)
        if last["status"] == "FAILED":
            raise Failure(f"任务失败: {last.get('errorCode')} {last.get('errorMessage')}")
        if last["verifiedBytes"] > minimum:
            return last
        time.sleep(0.2)
    raise Failure(f"进度未推进: {last}")


def fixture_file(fixture_name, rel):
    roots = glob.glob(os.path.join(DATA_ROOT, "resources", "*", fixture_name, rel))
    check(roots, f"未找到落盘文件 {fixture_name}/{rel}")
    return max(roots, key=os.path.getmtime)


def newest_resume():
    paths = glob.glob(os.path.join(DATA_ROOT, "resume", "*.dat"))
    check(paths, "未找到 resume 文件")
    return max(paths, key=os.path.getmtime)


def sha256_bytes(payload):
    return hashlib.sha256(payload).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def assert_fixture_files(token, client, task_uuid, fixture):
    detail = task_detail(client, token, task_uuid)
    for item in fixture["files"]:
        index = index_by_suffix(detail, os.path.basename(item["path"]))["fileIndex"]
        url = issue_ticket(client, token, task_uuid, index)
        response = stream(url)
        check(response.status_code == 200, f"ticket 下载失败 {item['path']}: {response.status_code}")
        check(sha256_bytes(response.content) == item["sha256"],
              f"下载内容哈希不匹配 {item['path']}")
    return detail


class Acceptance:
    def __init__(self):
        self.manifest = json.load(open(os.path.join(FIXTURE_ROOT, "manifest.runtime.json"),
                                      encoding="utf-8"))
        self.client = Client(API)
        self.sqlite = Client(SQLITE_API)
        self.token = None
        self.token_b = None
        self.results = []
        self.parses = {}
        self.fixtures = self.manifest["fixtures"]
        self.tasks = {}

    # ------------------------------------------------------------------ cases

    def case_auth_and_health(self):
        health = self.client.data(self.client.request("GET", "/api/magnet/v2/engine/health",
                                                      token=self.token))
        check(health["configured"] is True, "MySQL 模式应启用 native v2")
        check(health["connected"] is True, "Worker 应在线")
        check(health.get("version"), "健康响应缺少 worker version")
        unauthenticated = self.client.request("GET", "/api/magnet/v2/tasks")
        check(unauthenticated.status_code == 401, "无 JWT 访问应返回 401")
        tasks = self.client.data(self.client.request("GET", "/api/magnet/v2/tasks", token=self.token))
        check(isinstance(tasks, list), "任务列表应为数组")

    def case_parse_v1(self):
        self.parses["v1"] = wait_parse(self.client, self.token,
                                       submit_torrent(self.client, self.token, "v1"))
        self.assert_parse("v1")

    def case_parse_v2(self):
        self.parses["v2"] = wait_parse(self.client, self.token,
                                       submit_torrent(self.client, self.token, "v2"))
        self.assert_parse("v2")

    def case_parse_hybrid(self):
        self.parses["hybrid"] = wait_parse(self.client, self.token,
                                           submit_torrent(self.client, self.token, "hybrid"))
        self.assert_parse("hybrid")

    def case_parse_magnet(self):
        magnet = self.fixtures["v1"]["magnet"]
        detail = wait_parse(self.client, self.token,
                            submit_magnet(self.client, self.token, magnet), timeout=240)
        check(detail["inputType"] == "MAGNET", "磁力解析 inputType 应为 MAGNET")
        check(len(detail["files"]) == len(self.fixtures["v1"]["files"]),
              "磁力解析文件数不匹配")
        self.parses["magnet"] = detail["parseUuid"]

    def assert_parse(self, label):
        detail = wait_parse(self.client, self.token, self.parses[label])
        fixture = self.fixtures[label]
        check(detail["name"] == fixture["name"], f"{label} 资源名不匹配")
        check(detail["totalBytes"] == fixture["totalBytes"], f"{label} 总大小不匹配")
        check(len(detail["files"]) == len(fixture["files"]), f"{label} 文件数不匹配")
        for item in fixture["files"]:
            expected = fixture["name"] + "/" + item["path"]
            check(any(file["relativePath"] == expected for file in detail["files"]),
                  f"{label} 缺少路径 {expected}")

    def case_full_download_and_ticket(self):
        detail = wait_parse(self.client, self.token, self.parses["v1"])
        indexes = sorted(file["fileIndex"] for file in detail["files"])
        task = create_task(self.client, self.token, self.parses["v1"], indexes)
        completed, seen = wait_completed(self.client, self.token, task["taskUuid"])
        check("VERIFYING" in seen, "完成前应观察到 VERIFYING（两轮确认）")
        check(completed["progressPercent"] == 100.0, "完成进度应为 100")
        self.tasks["full"] = completed["taskUuid"]
        self.assert_fixture_files(self.token, self.client, completed["taskUuid"], self.fixtures["v1"])

        first = detail["files"][0]
        fixture = self.fixtures["v1"]
        matched = next(item for item in fixture["files"]
                       if item["path"].endswith(first["relativePath"].split("/", 1)[1]))
        url = issue_ticket(self.client, self.token, completed["taskUuid"], first["fileIndex"])
        whole = stream(url)
        check(whole.status_code == 200, "ticket 全量下载失败")
        check(whole.headers.get("Accept-Ranges") == "bytes", "应声明 Accept-Ranges")
        expected_path = os.path.join(FIXTURE_ROOT, "data", fixture["name"], matched["path"])
        expected = open(expected_path, "rb").read()
        check(sha256_bytes(whole.content) == sha256_bytes(expected), "全量内容不匹配")
        check("attachment" in whole.headers.get("Content-Disposition", ""), "应下载而非内联")

        ranged = stream(url, headers={"Range": "bytes=100-199"})
        check(ranged.status_code == 206, f"Range 应返回 206，实际 {ranged.status_code}")
        check(ranged.headers.get("Content-Range") == f"bytes 100-199/{len(expected)}",
              "Content-Range 不匹配")
        check(ranged.content == expected[100:200], "Range 内容不匹配")

    def case_selected_files_only(self):
        detail = wait_parse(self.client, self.token, self.parses["v1"])
        beta = index_by_suffix(detail, "beta.bin")
        task = create_task(self.client, self.token, self.parses["v1"], [beta["fileIndex"]])
        completed, _ = wait_completed(self.client, self.token, task["taskUuid"])
        self.tasks["selected"] = completed["taskUuid"]
        fixture = self.fixtures["v1"]
        beta_fixture = next(item for item in fixture["files"] if item["path"] == "beta.bin")
        path = fixture_file(fixture["name"], "beta.bin")
        check(sha256_file(path) == beta_fixture["sha256"], "选中文件哈希不匹配")

        alpha = index_by_suffix(detail, "alpha.bin")
        response = self.client.request("POST", f"/api/magnet/v2/tasks/{completed['taskUuid']}/file-ticket",
                                       token=self.token, json={"fileIndex": alpha["fileIndex"]})
        check(response.status_code == 409, "未选中文件不应签发 ticket")
        alpha_paths = glob.glob(os.path.join(DATA_ROOT, "resources", "*", fixture["name"], "alpha.bin"))
        if alpha_paths:
            check(os.path.getsize(max(alpha_paths, key=os.path.getmtime)) < alpha["bytes"],
                  "未选中文件不应完整落盘")

    def case_pause_resume(self):
        detail = wait_parse(self.client, self.token, self.parses["v2"])
        indexes = sorted(file["fileIndex"] for file in detail["files"])
        task = create_task(self.client, self.token, self.parses["v2"], indexes)
        wait_progress(self.client, self.token, task["taskUuid"], 0, timeout=60)

        paused = self.client.data(self.client.request("POST", f"/api/magnet/v2/tasks/{task['taskUuid']}/pause",
                                                      token=self.token))
        paused = wait_task(self.client, self.token, task["taskUuid"],
                           lambda value: value["status"] == "PAUSED", timeout=30)
        check(paused["verifiedBytes"] > 0, "暂停前应有进度")
        time.sleep(2)
        stable = task_detail(self.client, self.token, task["taskUuid"])
        check(stable["verifiedBytes"] == paused["verifiedBytes"], "暂停后进度不应变化")

        self.client.data(self.client.request("POST", f"/api/magnet/v2/tasks/{task['taskUuid']}/resume",
                                             token=self.token))
        progressed = wait_task(self.client, self.token, task["taskUuid"],
                               lambda value: value["verifiedBytes"] > paused["verifiedBytes"], timeout=60)
        check(progressed["verifiedBytes"] > paused["verifiedBytes"], "恢复后进度应推进")
        completed, _ = wait_completed(self.client, self.token, task["taskUuid"])
        self.tasks["pause"] = completed["taskUuid"]

    def case_api_restart(self):
        detail = wait_parse(self.client, self.token, self.parses["hybrid"])
        indexes = sorted(file["fileIndex"] for file in detail["files"])
        task = create_task(self.client, self.token, self.parses["hybrid"], indexes)
        wait_task(self.client, self.token, task["taskUuid"],
                  lambda value: value["verifiedBytes"] > 0, timeout=60)
        restart_container("nv2-api")
        wait_for_api(self.client, USER_A[0], USER_A[1])
        wait_health(self.client, self.token, True, timeout=120)
        completed, _ = wait_completed(self.client, self.token, task["taskUuid"], timeout=240)
        self.tasks["api_restart"] = completed["taskUuid"]
        self.assert_fixture_files(self.token, self.client, completed["taskUuid"], self.fixtures["hybrid"])

    def case_worker_restart(self):
        detail = wait_parse(self.client, self.token, self.parses["v2"])
        indexes = sorted(file["fileIndex"] for file in detail["files"])
        task = create_task(self.client, self.token, self.parses["v2"], indexes)
        wait_task(self.client, self.token, task["taskUuid"],
                  lambda value: value["verifiedBytes"] > 0, timeout=60)
        restart_container("nv2-worker")
        wait_health(self.client, self.token, True, timeout=180)
        completed, _ = wait_completed(self.client, self.token, task["taskUuid"], timeout=300)
        self.tasks["worker_restart"] = completed["taskUuid"]
        self.assert_fixture_files(self.token, self.client, completed["taskUuid"], self.fixtures["v2"])

    def case_missing_resume(self):
        detail = wait_parse(self.client, self.token, self.parses["hybrid"])
        indexes = sorted(file["fileIndex"] for file in detail["files"])
        task = create_task(self.client, self.token, self.parses["hybrid"], indexes)
        wait_task(self.client, self.token, task["taskUuid"],
                  lambda value: value["verifiedBytes"] > 0, timeout=60)
        self.client.data(self.client.request("POST", f"/api/magnet/v2/tasks/{task['taskUuid']}/pause",
                                             token=self.token))
        wait_task(self.client, self.token, task["taskUuid"],
                  lambda value: value["status"] == "PAUSED", timeout=30)
        os.remove(newest_resume())
        restart_container("nv2-worker")
        wait_health(self.client, self.token, True, timeout=180)
        self.client.data(self.client.request("POST", f"/api/magnet/v2/tasks/{task['taskUuid']}/resume",
                                             token=self.token))
        completed, _ = wait_completed(self.client, self.token, task["taskUuid"], timeout=300)
        self.assert_fixture_files(self.token, self.client, completed["taskUuid"],
                                  self.fixtures["hybrid"])

    def case_corrupt_block(self):
        detail = wait_parse(self.client, self.token, self.parses["v1"])
        indexes = sorted(file["fileIndex"] for file in detail["files"])
        task = create_task(self.client, self.token, self.parses["v1"], indexes)
        wait_task(self.client, self.token, task["taskUuid"],
                  lambda value: value["verifiedBytes"] > 40960, timeout=90)
        self.client.data(self.client.request("POST", f"/api/magnet/v2/tasks/{task['taskUuid']}/pause",
                                             token=self.token))
        paused = wait_task(self.client, self.token, task["taskUuid"],
                           lambda value: value["status"] == "PAUSED", timeout=30)
        before = paused["verifiedBytes"]

        fixture = self.fixtures["v1"]
        for item in fixture["files"]:
            path = fixture_file(fixture["name"], item["path"])
            with open(path, "wb") as handle:
                handle.write(b"\x00" * item["bytes"])
        os.remove(newest_resume())
        restart_container("nv2-worker")
        wait_health(self.client, self.token, True, timeout=180)
        self.client.data(self.client.request("POST", f"/api/magnet/v2/tasks/{task['taskUuid']}/resume",
                                             token=self.token))
        minimum = {"value": before}

        def observe(value):
            if value["verifiedBytes"] < minimum["value"]:
                minimum["value"] = value["verifiedBytes"]

        completed, _ = wait_task(self.client, self.token, task["taskUuid"],
                                 lambda value: value["status"] == "COMPLETED", timeout=300,
                                 observe=observe)
        check(minimum["value"] < before,
              f"破坏数据后 verified 未回退: before={before} min={minimum['value']}")
        self.assert_fixture_files(self.token, self.client, completed["taskUuid"], fixture)

    def case_ticket_boundaries(self):
        full = self.tasks["full"]
        foreign = self.client.request("GET", f"/api/magnet/v2/tasks/{full}", token=self.token_b)
        check(foreign.status_code == 404, "跨用户任务详情应 404")
        foreign_pause = self.client.request("POST", f"/api/magnet/v2/tasks/{full}/pause",
                                            token=self.token_b)
        check(foreign_pause.status_code == 404, "跨用户暂停应 404")
        foreign_parse = self.client.request("GET", f"/api/magnet/v2/parses/{self.parses['v1']}",
                                            token=self.token_b)
        check(foreign_parse.status_code == 404, "跨用户解析应 404")

        selected = self.tasks["selected"]
        detail = wait_parse(self.client, self.token, self.parses["v1"])
        alpha = index_by_suffix(detail, "alpha.bin")
        unselected = self.client.request("POST", f"/api/magnet/v2/tasks/{selected}/file-ticket",
                                         token=self.token, json={"fileIndex": alpha["fileIndex"]})
        check(unselected.status_code == 409, "未选中文件 ticket 应 409")

        unfinished = create_task(self.client, self.token, self.parses["v2"], [0, 1])
        ticket = self.client.request("POST", f"/api/magnet/v2/tasks/{unfinished['taskUuid']}/file-ticket",
                                     token=self.token, json={"fileIndex": 0})
        check(ticket.status_code == 409, "未完成任务 ticket 应 409")
        self.client.data(self.client.request("POST", f"/api/magnet/v2/tasks/{unfinished['taskUuid']}/pause",
                                             token=self.token))

        beta = index_by_suffix(detail, "beta.bin")
        url = issue_ticket(self.client, self.token, full, beta["fileIndex"])
        time.sleep(7)
        expired = stream(url)
        check(expired.status_code == 404, f"过期 ticket 应 404，实际 {expired.status_code}")

        traversal = requests.get(API + "/api/magnet/v2/downloads/stream/..%2f..%2fetc%2fpasswd",
                                 timeout=20)
        check(traversal.status_code in (400, 404), "路径穿越 token 应被拒绝")
        check(b"root:" not in traversal.content, "路径穿越不应泄漏文件")

    def case_worker_offline(self):
        detail = wait_parse(self.client, self.token, self.parses["hybrid"])
        indexes = sorted(file["fileIndex"] for file in detail["files"])
        active = create_task(self.client, self.token, self.parses["hybrid"], indexes)
        wait_task(self.client, self.token, active["taskUuid"],
                  lambda value: value["verifiedBytes"] > 0, timeout=60)

        stop_container("nv2-worker")
        wait_health(self.client, self.token, False, timeout=60)
        health = self.client.data(self.client.request("GET", "/api/magnet/v2/engine/health",
                                                      token=self.token))
        check(health["configured"] is True and health["connected"] is False,
              "Worker 离线时 health 应 200 且 connected=false")
        listing = self.client.request("GET", "/api/magnet/v2/tasks", token=self.token)
        check(listing.status_code == 200, "Worker 离线时任务列表仍应可读")
        blocked = self.client.request("POST", "/api/magnet/v2/tasks", token=self.token,
                                      json={"parseUuid": self.parses["v2"], "selectedFileIndexes": [0]})
        check(blocked.status_code == 503, f"Worker 离线时新任务应 503，实际 {blocked.status_code}")

        paused = self.client.data(self.client.request(
            "POST", f"/api/magnet/v2/tasks/{active['taskUuid']}/pause", token=self.token))
        check(paused["status"] == "PAUSING", "Worker 离线时暂停应受理为 PAUSING")

        start_container("nv2-worker")
        wait_health(self.client, self.token, True, timeout=180)
        settled = wait_task(self.client, self.token, active["taskUuid"],
                            lambda value: value["status"] == "PAUSED", timeout=90)
        check(settled["status"] == "PAUSED", "Worker 恢复后应先落实暂停")
        time.sleep(2)
        stable = task_detail(self.client, self.token, active["taskUuid"])
        check(stable["verifiedBytes"] == settled["verifiedBytes"], "暂停落实后进度不应变化")

        self.client.data(self.client.request("POST", f"/api/magnet/v2/tasks/{active['taskUuid']}/resume",
                                             token=self.token))
        completed, _ = wait_completed(self.client, self.token, active["taskUuid"], timeout=300)
        self.assert_fixture_files(self.token, self.client, completed["taskUuid"],
                                  self.fixtures["hybrid"])

    def case_sqlite(self):
        token = wait_for_api(self.sqlite, "acceptance-sqlite@example.com", "acceptance-pass", 120)
        health = self.sqlite.data(self.sqlite.request("GET", "/api/magnet/v2/engine/health",
                                                      token=token))
        check(health["configured"] is False, "SQLite 模式下 v2 应 configured=false")
        check(health["connected"] is False, "SQLite 模式下不应连接 Worker")
        tasks = self.sqlite.request("GET", "/api/magnet/v2/tasks", token=token)
        check(tasks.status_code == 404, f"SQLite 模式下 v2 任务路由应不可用，实际 {tasks.status_code}")
        parses = self.sqlite.request("GET", "/api/magnet/v2/parses", token=token)
        check(parses.status_code == 404, "SQLite 模式下 v2 解析路由应不可用")

    # ------------------------------------------------------------------ runner

    def setup(self):
        self.token = wait_for_api(self.client, USER_A[0], USER_A[1])
        self.token_b = wait_for_api(self.client, USER_B[0], USER_B[1])
        wait_health(self.client, self.token, True, timeout=180)

    def run(self):
        cases = [
            ("01-auth-health-jwt", self.case_auth_and_health),
            ("02-parse-v1-torrent", self.case_parse_v1),
            ("03-parse-v2-torrent", self.case_parse_v2),
            ("04-parse-hybrid-torrent", self.case_parse_hybrid),
            ("05-parse-magnet-metadata", self.case_parse_magnet),
            ("06-full-download-ticket-range", self.case_full_download_and_ticket),
            ("07-selected-files-only", self.case_selected_files_only),
            ("08-pause-resume-progress", self.case_pause_resume),
            ("09-api-restart-resumes", self.case_api_restart),
            ("10-worker-restart-reattach", self.case_worker_restart),
            ("11-missing-resume-recheck", self.case_missing_resume),
            ("12-corrupt-block-repaired", self.case_corrupt_block),
            ("13-ticket-boundaries", self.case_ticket_boundaries),
            ("14-worker-offline-contract", self.case_worker_offline),
            ("15-sqlite-smoke", self.case_sqlite),
        ]
        self.setup()
        for name, case in cases:
            started = time.time()
            try:
                case()
                self.results.append((name, True, ""))
                print(f"[PASS] {name} ({time.time() - started:.1f}s)", flush=True)
            except Exception as error:  # noqa: BLE001 - report every case
                self.results.append((name, False, str(error)))
                print(f"[FAIL] {name}: {error}", flush=True)
                traceback.print_exc()
        failed = [name for name, passed, _ in self.results if not passed]
        print(json.dumps({"passed": len(self.results) - len(failed), "failed": failed}, indent=2),
              flush=True)
        return 0 if not failed else 1


def main():
    try:
        return Acceptance().run()
    except Exception as error:  # noqa: BLE001 - fatal setup failure
        print(f"[FATAL] {error}", flush=True)
        traceback.print_exc()
        return 2


if __name__ == "__main__":
    sys.exit(main())
