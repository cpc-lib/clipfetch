# -*- coding: utf-8 -*-
"""
SpankBang 浏览器取流 sidecar。

为什么需要它：SpankBang 的 Cloudflare 防护为 Bot Management 级别，cf_clearance
绑定真实 Chrome 的 TLS 指纹——yt-dlp/curl 即便带合法 cookies 也返回 403
（实测：同一出口 IP 浏览器正常，yt-dlp 带cookies+UA 对齐仍 403）。
因此用 Playwright 驱动系统 Chrome（channel=chrome）加载真实视频页，
让 CF 挑战在真实浏览器里自动通过，再读取页面内嵌的 stream_data 全局变量
（含各档清晰度的 CDN 直链 mp4/m3u8），交由 yt-dlp 直接下载 CDN（CDN 无 CF 挑战）。

无登录需求；首次访问的年龄门由 profile 持久化的 age_pass cookie 放行，
未放行时点击入口按钮兜底。被墙站点需 --proxy 走代理。

仅依赖 playwright（pip install playwright，浏览器用系统 Chrome，无需 playwright install）。
仅用标准库 http.server 提供 JSON HTTP API，所有 Playwright 操作串行在一个 worker 线程。

接口：
  GET  /health   -> {"ok": true}
  POST /resolve  {"url": "<视频页 URL>"}
       -> {"ok": true, "title": str, "duration": float, "thumbnail": str,
            "formats": [{"quality": "720p", "url": "https://.../720p.mp4",
                          "ext": "mp4", "filesize": 123}]}
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

# 掩盖自动化特征（与腾讯 sidecar 同源）：CF 会读 navigator.webdriver
_INIT_JS = """
(() => {
  try { Object.defineProperty(navigator, 'webdriver', {get: () => false}); } catch(e) {}
})();
"""


class BrowserWorker:
    """所有 Playwright 操作都在此线程内串行执行（sync API 绑定创建线程）。"""

    def __init__(self, profile_dir, proxy):
        self.profile_dir = profile_dir
        self.proxy = proxy
        self.pw = None
        self.ctx = None
        self.page = None

    def _launch(self):
        # CF Bot Management 对 headless Chrome 不放行（挑战永远停在"请稍候…"），
        # 必须有头真实窗口才会自动通过——与腾讯 sidecar 解析用有头同因
        args = ["--start-maximized", "--window-position=0,0"]
        launch_kw = dict(
            user_data_dir=self.profile_dir, channel="chrome", headless=False,
            args=args, no_viewport=True,
            chromium_sandbox=(sys.platform == "win32"),
            # --enable-automation 是自动化特征，Playwright 默认注入需移除
            ignore_default_args=["--disable-component-update"])
        if self.proxy:
            launch_kw["proxy"] = {"server": self.proxy}
        self.ctx = self.pw.chromium.launch_persistent_context(**launch_kw)
        self.ctx.add_init_script(_INIT_JS)
        pages = self.ctx.pages
        self.page = pages[0] if pages else self.ctx.new_page()

    def start(self):
        from playwright.sync_api import sync_playwright
        self.pw = sync_playwright().start()
        self._launch()

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
            try:
                if self.ctx is not None:
                    self.ctx.close()
            except Exception:
                pass
            self.ctx = None
            self.page = None
            self._launch()

    # 提取 stream_data + 标题/时长/封面。stream_data 是页面内联脚本的全局变量：
    # {quality: [{url, ext, filesize, duration, ...}], 'm3u8': [...], 'id': ...}
    _JS_EXTRACT = """
    () => {
        let sd = null;
        try {
            if (typeof stream_data !== 'undefined' && stream_data) {
                sd = JSON.parse(JSON.stringify(stream_data));
            }
        } catch (e) { sd = null; }
        const og = (t) => {
            const el = document.querySelector('meta[property="og:' + t + '"]');
            return el ? (el.content || '') : '';
        };
        return {
            sd: sd,
            title: og('title') || (document.title || ''),
            thumb: og('image') || og('thumbnail') || ''
        };
    }
    """

    # 年龄门/CF 挑战兜底：点击常见入口按钮（不存在时静默跳过）
    _JS_CLICK_AGE_GATE = """
    () => {
        const sels = ['#intro-overlaybtn', '.intro-overlaybtn', 'a.enter',
                      '.btn-enter', '[class*="age"] button', 'button[class*="enter"]'];
        for (const sel of sels) {
            const el = document.querySelector(sel);
            if (el) { try { el.click(); return sel; } catch (e) {} }
        }
        return null;
    }
    """

    @staticmethod
    def _quality_height(q):
        m = re.match(r"(\d+)", str(q))
        return int(m.group(1)) if m else 0

    def resolve(self, url):
        """加载视频页，等待 CF 挑战自动通过，提取 stream_data 各档直链。"""
        started = time.monotonic()
        page = self.page
        print("[resolve] 导航: %s" % url, flush=True)
        try:
            page.goto(url, wait_until="domcontentloaded", timeout=60000)
        except Exception as e:
            raise RuntimeError("页面加载失败: " + str(e)[:200])

        data = None
        reloaded = False
        deadline = time.time() + 60
        cf_seen = False
        while time.time() < deadline:
            try:
                title_low = (page.title() or "").lower()
                if "just a moment" in title_low or "请稍候" in title_low \
                        or "attention required" in title_low:
                    if not cf_seen:
                        cf_seen = True
                        print("[resolve] CF 挑战中，等待自动通过...", flush=True)
                    time.sleep(1)
                    continue
                got = page.evaluate(self._JS_EXTRACT)
                if got and got.get("sd"):
                    data = got
                    break
                # 无挑战也无数据：可能是年龄门，点一次入口按钮
                clicked = page.evaluate(self._JS_CLICK_AGE_GATE)
                if clicked:
                    print("[resolve] 点击年龄门: %s" % clicked, flush=True)
                    time.sleep(2)
                    continue
                elapsed = time.monotonic() - started
                if not reloaded and elapsed > 25:
                    print("[resolve] 25s 未取到 stream_data，重载页面", flush=True)
                    reloaded = True
                    page.goto(url, wait_until="domcontentloaded", timeout=60000)
                    continue
                time.sleep(1)
            except Exception as e:
                print("[resolve] 提取异常: %s" % str(e)[:120], flush=True)
                time.sleep(1)

        if not data or not data.get("sd"):
            raise RuntimeError(
                "60 秒内未获取到视频数据（页面标题=%s）。若反复出现，请在 sidecar 的 "
                "Chrome profile 中手动通过一次人机验证" % ((page.title() or "无")[:60]))

        sd = data["sd"]

        # stream_data 结构（实测）：档位键（'240p'~'4k'）的值是 mp4 直链字符串列表；
        # 'm3u8_<quality>' 是对应 HLS 清单；'m3u8' 为主清单；'length' 为时长秒数；
        # 'cover_image'/'thumbnail' 为封面；旧版结构（dict 列表含 url/ext/filesize）兼容保留
        def first_url(key):
            v = sd.get(key)
            if isinstance(v, list):
                for e in v:
                    if isinstance(e, str) and e.startswith("http"):
                        return e
                    if isinstance(e, dict) and e.get("url"):
                        return e["url"]
            return None

        formats = []
        for key in sd:
            if re.fullmatch(r"\d{3,4}p", str(key)):
                u = first_url(key)
                if u:
                    formats.append({"quality": str(key), "url": u, "ext": "mp4", "filesize": 0})
        for key in sd:
            m = re.fullmatch(r"m3u8_(\d{3,4}p)", str(key))
            if m and not any(f["quality"] == m.group(1) for f in formats):
                u = first_url(key)
                if u:
                    formats.append({"quality": m.group(1), "url": u, "ext": "m3u8", "filesize": 0})
        if not formats:
            u = first_url("m3u8")
            if u:
                formats.append({"quality": "hls", "url": u, "ext": "m3u8", "filesize": 0})
        if not formats:
            raise RuntimeError("stream_data 中没有可用的流地址")
        formats.sort(key=lambda f: self._quality_height(f["quality"]), reverse=True)

        duration = float(sd.get("length") or 0)
        thumbnail = sd.get("cover_image") or sd.get("thumbnail") or ""
        title = (data.get("title") or "").strip()
        print("[resolve] 成功: %s 个档位, 时长=%.0fs, 耗时%.1fs" % (
            len(formats), duration, time.monotonic() - started), flush=True)
        return {"title": title, "duration": duration,
                "thumbnail": thumbnail, "formats": formats}


def port_in_use(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1.0)
        return s.connect_ex(("127.0.0.1", port)) == 0


def kill_stale_sidecar(port):
    """若端口被旧版 spankbang_sidecar 占用，则杀掉它。"""
    try:
        import subprocess
        result = subprocess.run(["netstat", "-ano", "-p", "tcp"],
                                capture_output=True, text=True, timeout=10)
        target_pid = None
        for line in result.stdout.splitlines():
            parts = line.split()
            if len(parts) >= 5 and f":{port}" in parts[1] and "LISTENING" in line.upper():
                target_pid = parts[-1]
                break
        if not target_pid:
            return
        cmdline = ""
        try:
            ps = f"(Get-CimInstance Win32_Process -Filter \"ProcessId={target_pid}\").CommandLine"
            r = subprocess.run(["powershell", "-NoProfile", "-Command", ps],
                               capture_output=True, text=True, timeout=15)
            cmdline = r.stdout or ""
        except Exception:
            cmdline = ""
        if "spankbang_sidecar" in cmdline:
            subprocess.run(["taskkill", "/F", "/T", "/PID", target_pid],
                           capture_output=True, timeout=10)
            print("killed stale spankbang sidecar pid=%s" % target_pid, flush=True)
            time.sleep(2)
    except Exception:
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8098)
    ap.add_argument("--profile-dir", required=True)
    ap.add_argument("--proxy", default="")
    args = ap.parse_args()

    if port_in_use(args.port):
        kill_stale_sidecar(args.port)
    if port_in_use(args.port):
        print("spankbang sidecar already running on 127.0.0.1:%d, exit" % args.port, flush=True)
        sys.exit(0)

    worker = BrowserWorker(args.profile_dir, args.proxy)
    queue = []
    cond = threading.Condition()
    state = {"busy": False, "started": False}

    def worker_loop():
        started = False
        while True:
            with cond:
                while not queue:
                    cond.wait()
                kind, payload, result_box = queue.pop(0)
                state["busy"] = True
            try:
                if not started:
                    worker.start()
                    started = True
                    with cond:
                        state["started"] = True
                else:
                    worker.ensure_browser()
                if kind == "health":
                    out = {"ok": True, "busy": False}
                elif kind == "resolve":
                    r = worker.resolve(payload["url"])
                    out = {"ok": True, **r}
                else:
                    out = {"ok": False, "error": "unknown"}
            except Exception as e:
                out = {"ok": False, "error": type(e).__name__ + ": " + str(e)[:300]}
            with cond:
                state["busy"] = False
                result_box["out"] = out
                cond.notify_all()

    threading.Thread(target=worker_loop, daemon=True).start()

    def submit(kind, payload=None, wait_sec=120, fast_health=False):
        box = {}
        with cond:
            if fast_health and (state["started"] or state["busy"] or queue):
                return {"ok": True, "busy": bool(state["busy"] or queue)}
            queue.append((kind, payload or {}, box))
            cond.notify_all()
            end = time.time() + wait_sec
            while "out" not in box:
                if not cond.wait(timeout=max(0.1, end - time.time())) and time.time() > end:
                    return {"ok": False, "error": "sidecar 处理超时"}
        return box["out"]

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *a):
            pass

        def handle_error(self, request, client_address):
            if isinstance(sys.exc_info()[1], (ConnectionError, TimeoutError)):
                return
            super().handle_error(request, client_address)

        def _send(self, code, obj):
            data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            try:
                self.send_response(code)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            except (ConnectionError, TimeoutError):
                pass

        def do_GET(self):
            path = self.path.split("?")[0]
            if path == "/health":
                try:
                    self._send(200, submit("health", wait_sec=30, fast_health=True))
                except Exception as e:
                    self._send(503, {"ok": False, "error": str(e)[:100]})
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
                self._send(200, submit("resolve", payload, wait_sec=120))
            else:
                self._send(404, {"ok": False, "error": "not found"})

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print("spankbang sidecar listening on 127.0.0.1:%d" % args.port, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
