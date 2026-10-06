# -*- coding: utf-8 -*-
"""
QQ 音乐浏览器取流 sidecar。

为什么需要它：QQ 音乐 u6.y.qq.com 的 musics.fcg vkey 接口已改用 TmeWebSec
安全 SDK（页面 JS 运行时签名 + XXTEA 加密通道，Content-Type: text/plain），
纯 HTTP 请求全部返回 104003。签名器只存在于真实页面的 window.__TmeWebSec_sign /
__TmeWebSec_seccgi 对象上，因此用 Playwright 驱动系统 Chrome（Channel=chrome），
在页面上下文里完成签名、加密、请求、解密。

登录态：Chrome 持久化用户目录（--profile-dir），首次使用 /login 扫码后长期有效；
直链本身无需 cookie，下载由 Java 侧带 Referer 直接流式拉取。

仅依赖 playwright（pip install playwright，浏览器用系统 Chrome，无需 playwright install）。
仅用标准库 http.server 提供 JSON HTTP API，所有 Playwright 操作串行在一个 worker 线程。

接口：
  GET  /health   -> {"ok": true, "logged_in": bool}
  POST /resolve  {"songmid","media_mid"}
       -> {"uin": str, "qualities": {"c400": {"result","url"}|null, ...}}
  POST /login    （拉起有头 Chrome 等扫码，最长 240 秒）
       -> {"ok": true}
"""
import argparse
import json
import random
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.stdout.reconfigure(encoding="utf-8")

INIT_JS = r"""
window.__cap = {sign: null, seccgi: null};
['__TmeWebSec_sign', '__TmeWebSec_seccgi'].forEach((name, i) => {
  const slot = i === 0 ? 'sign' : 'seccgi';
  let v;
  Object.defineProperty(window, name, {
    configurable: true, get() { return v; },
    set(nv) { v = nv; window.__cap[slot] = nv; }
  });
});
"""

CALL_JS = r"""
async (args) => {
  const data = JSON.stringify(args);
  const sign = await window.__cap.sign.getSecuritySign(data);
  const enc = await window.__cap.seccgi.encrypt(data);
  const resp = await fetch('https://u6.y.qq.com/cgi-bin/musics.fcg?_=' + Date.now()
      + '&encoding=ag-1&sign=' + encodeURIComponent(sign),
    {method: 'POST', headers: {'Content-Type': 'text/plain'}, body: enc, credentials: 'include'});
  const buf = await resp.arrayBuffer();
  let text = new TextDecoder().decode(buf);
  if (text.trim().charAt(0) !== '{') text = await window.__cap.seccgi.decrypt(buf);
  return JSON.parse(text);
}
"""

WARM_URL = "https://y.qq.com/n/ryqq_v2/songDetail/002Fc5Be34LLWm"


def gtk(key):
    h = 5381
    for ch in key:
        h = (h + ((h << 5) & 0xFFFFFFFF) + ord(ch)) & 0xFFFFFFFF
    return h & 0x7FFFFFFF


class BrowserWorker:
    """所有 Playwright 操作都在此线程内串行执行（sync API 绑定创建线程）。"""

    def __init__(self, profile_dir):
        self.profile_dir = profile_dir
        self.guid = str(random.randint(1000000000, 2000000000))
        self.pw = None
        self.ctx = None
        self.page = None

    def _launch(self, headless):
        self.ctx = self.pw.chromium.launch_persistent_context(
            user_data_dir=self.profile_dir, channel="chrome", headless=headless,
            args=["--disable-blink-features=AutomationControlled"])
        self.ctx.add_init_script(INIT_JS)
        pages = self.ctx.pages
        self.page = pages[0] if pages else self.ctx.new_page()
        self.page.goto(WARM_URL, wait_until="domcontentloaded", timeout=60000)
        for _ in range(30):
            time.sleep(1)
            if self.page.evaluate("!!(window.__cap.sign && window.__cap.seccgi)"):
                return
        raise RuntimeError("QQ 音乐安全 SDK 加载超时")

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
        """浏览器/页面是否仍可用。is_closed 只反映本地状态，进程被强杀时可能漏判，
        因此必须做一次真实 CDP 往返（连接断开时立即抛 TargetClosedError，不会挂起）。"""
        if self.ctx is None or self.page is None:
            return False
        try:
            self.page.evaluate("1")
            return True
        except Exception:
            return False

    def ensure_browser(self):
        """浏览器被外部杀掉或崩溃后自动重启无头实例（Chrome 升级、手工清理进程等场景）。"""
        if not self.browser_alive():
            self._close_ctx()
            self._launch(headless=True)

    def restart_headless(self):
        self._close_ctx()
        self._launch(headless=True)

    def cookies(self):
        return {c["name"]: c["value"] for c in self.ctx.cookies("https://y.qq.com/")}

    def logged_in(self):
        ck = self.cookies()
        return "uin" in ck and "qm_keyst" in ck

    def resolve(self, songmid, media_mid):
        if not self.logged_in():
            return None
        ck = self.cookies()
        uin = int(ck["uin"].lstrip("o"))
        tk = gtk(ck["qm_keyst"])
        comm = {"cv": 4747474, "ct": 24, "format": "json", "inCharset": "utf-8",
                "outCharset": "utf-8", "notice": 0, "platform": "yqq.json",
                "needNewCode": 1, "uin": uin, "g_tk_new_20200303": tk, "g_tk": tk}
        mid = (media_mid or "").strip() or songmid
        cases = [("c400", "C400" + mid + ".m4a"),
                 ("m500", "M500" + mid + ".mp3"),
                 ("f000", "F000" + mid + ".flac")]
        qualities = {}
        for name, filename in cases:
            param = {"guid": self.guid, "songmid": [songmid], "songtype": [0],
                     "uin": str(uin), "loginflag": 1, "platform": "20", "xcdn": 1,
                     "filename": [filename]}
            body = {"comm": comm,
                    "req_0": {"module": "music.vkey.GetEVkey", "method": "GetUrl", "param": param}}
            j = self.page.evaluate(CALL_JS, body)
            data = j["req_0"]["data"]
            info = data["midurlinfo"][0]
            purl = info.get("purl") or ""
            qualities[name] = {
                "result": info.get("result"),
                "url": (data["sip"][0] + purl) if purl else None,
            }
        return {"uin": str(uin), "qualities": qualities}

    def _open_login_dialog(self):
        # 未登录时顶部登录入口（.top_login__link/.top_login__icon）点击后弹出 ptlogin 扫码框；
        # 已登录（有头像）则什么都不做。各步均容错，失败也保留完整页面供手动点登录
        try:
            clicked = self.page.evaluate("""() => {
              if (document.querySelector('.top_login__cover')) return false;
              const el = document.querySelector('.top_login__link')
                    || document.querySelector('.top_login__icon');
              if (!el) return false;
              el.click();
              return true;
            }""")
            if clicked:
                time.sleep(2)
        except Exception:
            pass

    def login(self, timeout_sec=240):
        # 已登录无需弹窗，直接成功（避免误点按钮时在桌面上开窗口）
        if self.logged_in():
            return "already"
        # 同一 profile 不能被两个 context 同时打开：先关无头实例，再有头扫码，最后恢复
        self._close_ctx()
        try:
            self._launch(headless=False)
            self.page.bring_to_front()
            self._open_login_dialog()
            deadline = time.time() + timeout_sec
            last_front = 0.0
            while time.time() < deadline:
                try:
                    has_avatar = self.page.evaluate(
                        "!!document.querySelector('.top_login__cover')")
                except Exception:
                    has_avatar = False
                if has_avatar and self.logged_in():
                    return True
                # 每 10 秒把扫码窗口前置一次，防止被其他窗口挡住
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
    """TCP 可连即说明已有 sidecar 进程占着端口（Windows 允许重复绑定，
    必须在应用层拒绝双开：旧实例浏览器崩溃时会自愈，无需第二个进程接管）。"""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1.0)
        return s.connect_ex(("127.0.0.1", port)) == 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8095)
    ap.add_argument("--profile-dir", required=True)
    args = ap.parse_args()

    if port_in_use(args.port):
        print("qqmusic sidecar already running on 127.0.0.1:%d, exit" % args.port, flush=True)
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
                    # 每个任务前自愈：浏览器被外部杀掉/崩溃时重启，避免 health 永久报错
                    worker.ensure_browser()
                if kind == "health":
                    out = {"ok": True, "logged_in": worker.logged_in()}
                elif kind == "resolve":
                    r = worker.resolve(payload["songmid"], payload.get("media_mid", ""))
                    if r is None:
                        out = {"ok": False, "error": "not_logged_in"}
                    else:
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
                # 已启动才查登录态；未启动时仅报存活
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
                if not payload.get("songmid"):
                    self._send(400, {"ok": False, "error": "songmid required"})
                    return
                self._send(200, submit("resolve", payload, wait_sec=90))
            elif path == "/login":
                self._send(200, submit("login", payload, wait_sec=300))
            else:
                self._send(404, {"ok": False, "error": "not found"})

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print("qqmusic sidecar listening on 127.0.0.1:%d" % args.port, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
