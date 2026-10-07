# -*- coding: utf-8 -*-
"""
酷狗音乐浏览器取流 sidecar。

为什么需要它：酷狗 PC 播放接口 wwwapi.kugou.com/play/songinfo 已要求
页面运行时 H5 签名（window.infSign，签名器只在真实页面 JS 上下文中存在），
且匿名请求一律返回 err_code=30020（需登录）。纯 HTTP 无法复刻。

本 sidecar 用 Playwright 驱动系统 Chrome（channel=chrome）：
- 打开歌曲 mixsong 页面，读取内嵌 dataFromSmarty 取得 encode_album_audio_id/hash
- 在页面上下文调用 window.infSign 完成签名，fetch play/songinfo 取直链
- 登录态保存在 Chrome 持久化用户目录（--profile-dir），首次 /login 扫码后长期有效；
  登录后 bInfo.token/userid 自动带上，VIP 歌曲按账号权益返回 play_url

仅依赖 playwright（pip install playwright，浏览器用系统 Chrome，无需 playwright install）。
仅用标准库 http.server 提供 JSON HTTP API，所有 Playwright 操作串行在一个 worker 线程。

接口：
  GET  /health   -> {"ok": true, "logged_in": bool}
  POST /resolve  {"url": "<mixsong 页面链接>"}
       -> {"ok": true, "data": {audio_name, hash, play_url, filesize, bitrate, timelength, img, ...}}
  POST /login    （拉起有头 Chrome 等扫码，最长 240 秒）
       -> {"ok": true}
"""
import argparse
import json
import re
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.stdout.reconfigure(encoding="utf-8")

WARM_URL = "https://www.kugou.com/"

# 页面上下文内完成：getBaseInfo 取设备信息 -> infSign H5 签名 -> fetch play/songinfo
SONGINFO_JS = r"""
async (args) => {
  if (typeof window.infSign !== 'function') return {error: 'no infSign'};
  if (typeof window.getBaseInfo !== 'function') return {error: 'no getBaseInfo'};
  const bInfo = await new Promise(res => window.getBaseInfo(1014, res));
  let userid = '0';
  try {
    if (window.KgUser && window.KgUser.Cookie) {
      userid = window.KgUser.Cookie.read('KuGoo', 'KugooID') || '0';
    }
  } catch (e) {}
  const params = {
    encode_album_audio_id: args.encodeId,
    hash: args.hash || undefined,
    appid: 1014, platid: 4,
    dfid: bInfo.dfid, mid: bInfo.mid, uuid: bInfo.mid,
    token: bInfo.token ? bInfo.token : '',
    userid: userid
  };
  Object.keys(params).forEach(k => params[k] === undefined && delete params[k]);
  const signed = await new Promise(res =>
    window.infSign(params, null, {useH5: true, postType: 'json', callback: res}));
  const qs = Object.entries(signed).map(([k, v]) => k + '=' + encodeURIComponent(v)).join('&');
  const resp = await fetch('https://wwwapi.kugou.com/play/songinfo?' + qs);
  const text = await resp.text();
  try { return JSON.parse(text); } catch (e) { return {http: resp.status, raw: text.slice(0, 300)}; }
}
"""


class BrowserWorker:
    """所有 Playwright 操作都在此线程内串行执行（sync API 绑定创建线程）。"""

    def __init__(self, profile_dir):
        self.profile_dir = profile_dir
        self.pw = None
        self.ctx = None
        self.page = None

    def _launch(self, headless):
        self.ctx = self.pw.chromium.launch_persistent_context(
            user_data_dir=self.profile_dir, channel="chrome", headless=headless,
            args=["--disable-blink-features=AutomationControlled"])
        pages = self.ctx.pages
        self.page = pages[0] if pages else self.ctx.new_page()
        self.page.goto(WARM_URL, wait_until="domcontentloaded", timeout=60000)

    def _close_ctx(self):
        if self.ctx is not None:
            try:
                self.ctx.close()
            except Exception:
                pass
        self.ctx = None
        self.page = None

    def start(self):
        from playwright.sync_api import sync_playwright
        self.pw = sync_playwright().start()
        self._launch(headless=True)

    def browser_alive(self):
        if self.ctx is None or self.page is None:
            return False
        try:
            self.page.evaluate("1")
            return True
        except Exception:
            return False

    def ensure_browser(self):
        if not self.browser_alive():
            self._close_ctx()
            self._launch(headless=True)

    def restart_headless(self):
        self._close_ctx()
        self._launch(headless=True)

    def cookies(self):
        return {c["name"]: c["value"] for c in self.ctx.cookies("https://www.kugou.com/")}

    def logged_in(self):
        return "KuGoo" in self.cookies()

    def resolve(self, url):
        """打开 mixsong 页面，用页面签名能力请求 play/songinfo。"""
        self.page.goto(url, wait_until="domcontentloaded", timeout=60000)
        # 等待页面签名 SDK 就绪
        for _ in range(20):
            time.sleep(1)
            try:
                if self.page.evaluate(
                        "typeof window.infSign === 'function' && typeof window.getBaseInfo === 'function'"):
                    break
            except Exception:
                pass
        else:
            raise RuntimeError("酷狗页面签名组件加载超时")
        info = self.page.evaluate("() => (window.dataFromSmarty || [])[0] || null")
        if info and info.get("hash"):
            call_args = {"encodeId": info.get("encode_album_audio_id") or "",
                         "hash": info["hash"]}
            page_meta = {"song_name": info.get("song_name"),
                         "author_name": info.get("author_name"),
                         "timelength": info.get("timelength")}
        else:
            # 页面无 dataFromSmarty（付费歌曲页面为 SPA 空壳）：从 URL 提取参数
            # 优先 hash（32位十六进制），其次 mixsong 短 id（非纯数字）
            hash_match = re.search(r"([0-9a-fA-F]{32})", url.split("#", 1)[-1])
            id_match = re.search(r"/mixsong/([0-9a-zA-Z]{4,32})\.html", url)
            if hash_match:
                call_args = {"encodeId": "", "hash": hash_match.group(1).upper()}
            elif id_match:
                call_args = {"encodeId": id_match.group(1), "hash": ""}
            else:
                raise RuntimeError("页面中未找到歌曲信息，且 URL 中无歌曲 hash/ID")
            page_meta = {}
        res = self.page.evaluate(SONGINFO_JS, call_args)
        if not isinstance(res, dict) or "data" not in res:
            raise RuntimeError("songinfo 返回异常: " + json.dumps(res, ensure_ascii=False)[:200])
        data = res.get("data") or {}
        # hash 直调可能返回空 play_url 但带 encode_album_audio_id，用 encodeId 重调一次
        if not data.get("play_url") and data.get("encode_album_audio_id") and not call_args.get("encodeId"):
            res2 = self.page.evaluate(SONGINFO_JS, {
                "encodeId": data["encode_album_audio_id"],
                "hash": call_args.get("hash") or data.get("hash") or ""})
            if isinstance(res2, dict) and (res2.get("data") or {}).get("play_url"):
                data = res2["data"]
        data["__page__"] = page_meta
        return {"err_code": res.get("err_code"), "status": res.get("status"), "data": data}

    def login(self, timeout_sec=240):
        if self.logged_in():
            return "already"
        # 同一 profile 不能被两个 context 同时打开：先关无头实例，再有头扫码，最后恢复
        self._close_ctx()
        try:
            self._launch(headless=False)
            self.page.bring_to_front()
            deadline = time.time() + timeout_sec
            last_front = 0.0
            while time.time() < deadline:
                if self.logged_in():
                    return True
                if time.time() - last_front > 10:
                    try:
                        self.page.bring_to_front()
                    except Exception:
                        pass
                    last_front = time.time()
                time.sleep(2)
            return False
        finally:
            self.restart_headless()


def port_in_use(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1.0)
        return s.connect_ex(("127.0.0.1", port)) == 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8096)
    ap.add_argument("--profile-dir", required=True)
    args = ap.parse_args()

    if port_in_use(args.port):
        print("kugou sidecar already running on 127.0.0.1:%d, exit" % args.port, flush=True)
        sys.exit(0)

    worker = BrowserWorker(args.profile_dir)
    queue = []
    cond = threading.Condition()

    def worker_loop():
        started = False
        while True:
            with cond:
                while not queue:
                    cond.wait()
                kind, payload, result_box = queue.pop(0)
            try:
                if not started:
                    worker.start()
                    started = True
                else:
                    worker.ensure_browser()
                if kind == "health":
                    out = {"ok": True, "logged_in": worker.logged_in()}
                elif kind == "resolve":
                    r = worker.resolve(payload["url"])
                    out = {"ok": True, **r}
                elif kind == "login":
                    result = worker.login()
                    if result == "already":
                        out = {"ok": True, "already": True}
                    elif result:
                        out = {"ok": True}
                    else:
                        out = {"ok": False, "error": "timeout"}
                else:
                    out = {"ok": False, "error": "unknown"}
            except Exception as e:
                out = {"ok": False, "error": type(e).__name__ + ": " + str(e)[:300]}
            with cond:
                result_box["out"] = out
                cond.notify_all()

    threading.Thread(target=worker_loop, daemon=True).start()

    def submit(kind, payload=None, wait_sec=120):
        box = {}
        with cond:
            queue.append((kind, payload or {}, box))
            cond.notify_all()
            end = time.time() + wait_sec
            while "out" not in box:
                if not cond.wait(timeout=max(0.1, end - time.time())) and time.time() > end:
                    return {"ok": False, "error": "sidecar 处理超时"}
        return box["out"]

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _send(self, code, obj):
            data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path.split("?")[0] == "/health":
                try:
                    self._send(200, submit("health", wait_sec=30))
                except Exception as e:
                    self._send(200, {"ok": True, "logged_in": False, "note": str(e)[:100]})
            else:
                self._send(404, {"ok": False, "error": "not found"})

        def do_POST(self):
            path = self.path.split("?")[0]
            n = int(self.headers.get("Content-Length") or 0)
            try:
                payload = json.loads(self.rfile.read(n).decode("utf-8")) if n else {}
            except Exception:
                self._send(400, {"ok": False, "error": "bad json"})
                return
            if path == "/resolve":
                if not payload.get("url"):
                    self._send(400, {"ok": False, "error": "url required"})
                    return
                self._send(200, submit("resolve", payload, wait_sec=90))
            elif path == "/login":
                self._send(200, submit("login", payload, wait_sec=300))
            else:
                self._send(404, {"ok": False, "error": "not found"})

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print("kugou sidecar listening on 127.0.0.1:%d" % args.port, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
