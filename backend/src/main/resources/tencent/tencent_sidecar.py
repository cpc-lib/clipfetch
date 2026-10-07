# -*- coding: utf-8 -*-
"""
腾讯视频浏览器取流 sidecar。

为什么需要它：腾讯视频高档清晰度（1080P/4K）的 cKey 由播放器 JS 动态生成
（无法在服务端模拟），yt-dlp 只能拿到 480P/720P。因此用 Playwright 驱动
系统 Chrome（channel=chrome）加载真实播放页，在播放器里逐档切换清晰度，
拦截各档 .ts 分片的签名地址并反推出官方 CDN 的 m3u8 直链。
VIP 内容需在持久化 profile 中手动登录。4K 档位仅在有头窗口才可能出现，故解析用有头模式。

登录态：Chrome 持久化用户目录（--profile-dir），首次使用 /login 扫码后长期有效；
HLS 直链本身由 yt-dlp 带 Referer 直接下载。

仅依赖 playwright（pip install playwright，浏览器用系统 Chrome，无需 playwright install）。
仅用标准库 http.server 提供 JSON HTTP API，所有 Playwright 操作串行在一个 worker 线程。

接口：
  GET  /health   -> {"ok": true, "logged_in": bool}
  POST /resolve  {"url": "<播放页 URL>"}
       -> {"ok": true, "title": str, "duration": float,
            "formats": [{"defn": "480p|720p|1080p|zhencai_1080|4k|zhencai_max_4k60",
                          "name": "480P 标清", "url": "https://...m3u8", "filesize": 0}]}
  POST /login    （拉起有头 Chrome 等扫码，最长 240 秒）
       -> {"ok": true}
  GET  /cookies  -> {"ok": true, "cookies_txt": "<Netscape cookies.txt 文本>"}
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

WARM_URL = "https://v.qq.com/"


class BrowserWorker:
    """所有 Playwright 操作都在此线程内串行执行（sync API 绑定创建线程）。"""

    def __init__(self, profile_dir):
        self.profile_dir = profile_dir
        self.pw = None
        self.ctx = None
        self.page = None
        self.is_headless = True

    def _launch(self, headless):
        args = ["--disable-blink-features=AutomationControlled"]
        if not headless:
            # 有头模式：最大化窗口并固定位置，确保用户能看到扫码窗口
            args += ["--start-maximized", "--window-position=0,0"]
        self.is_headless = headless
        last_err = None
        for attempt in range(3):
            try:
                # headless 给大视口：播放器只在画面够大时才渲染 4K/臻彩MAX 菜单项
                viewport = {"width": 1920, "height": 1080} if headless else None
                self.ctx = self.pw.chromium.launch_persistent_context(
                    user_data_dir=self.profile_dir, channel="chrome", headless=headless,
                    args=args, viewport=viewport, no_viewport=not headless)
                pages = self.ctx.pages
                self.page = pages[0] if pages else self.ctx.new_page()
                self.page.goto(WARM_URL, wait_until="domcontentloaded", timeout=60000)
                return
            except Exception as e:
                last_err = e
                # profile 锁未释放：清理锁文件后重试
                self._clean_profile_locks()
                time.sleep(2)
        raise RuntimeError("Chrome 启动失败（可能 profile 被占用）：" + str(last_err)[:300])

    def _close_ctx(self):
        if self.ctx is not None:
            try:
                for p in self.ctx.pages:
                    try:
                        p.close()
                    except Exception:
                        pass
            except Exception:
                pass
            try:
                self.ctx.close()
            except Exception:
                pass
        self.ctx = None
        self.page = None
        # 等待 Chrome 进程完全退出并释放 profile 锁
        time.sleep(3)

    def _clean_profile_locks(self):
        """删除 Chrome profile 锁文件（SingletonLock 等），防止残留锁导致启动失败。
        不杀进程，避免破坏 Playwright driver 的管道（EPIPE）。"""
        import os
        import glob
        for pattern in ("SingletonLock", "SingletonCookie", "SingletonSocket", "Singleton*"):
            for f in glob.glob(os.path.join(self.profile_dir, pattern)):
                try:
                    os.remove(f)
                except Exception:
                    pass

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
        return {c["name"]: c["value"] for c in self.ctx.cookies("https://v.qq.com/")}

    def logged_in(self):
        """腾讯登录态 cookie：v_vusession 或 v_t_access_token 存在即视为已登录。"""
        ck = self.cookies()
        return "v_vusession" in ck or "v_t_access_token" in ck or "v_vuserid" in ck

    def export_cookies(self):
        """导出 qq.com 域全部 cookie（含 httpOnly）为 Netscape cookies.txt 文本。

        sidecar Chrome 常驻运行时独占锁定 profile 的 cookie 数据库，
        yt-dlp --cookies-from-browser 无法复制该文件（报 Could not copy Chrome
        cookie database，yt-dlp #7271），故改由 Playwright API 导出。
        """
        if self.ctx is None:
            return ""
        try:
            all_cookies = self.ctx.cookies()
        except Exception:
            return ""
        lines = ["# Netscape HTTP Cookie File", "# Generated by clipfetch tencent sidecar", ""]
        seen = set()
        for c in all_cookies:
            domain = c.get("domain") or ""
            if not domain.endswith("qq.com"):
                continue
            include_sub = "TRUE" if domain.startswith(".") else "FALSE"
            path = c.get("path") or "/"
            secure = "TRUE" if c.get("secure") else "FALSE"
            expires = int(c.get("expires") or 0)   # Playwright 会话 cookie 为 -1，统一写 0
            if expires < 0:
                expires = 0
            name, value = c.get("name") or "", c.get("value") or ""
            key = (domain, path, name)
            if key in seen:
                continue
            seen.add(key)
            # httpOnly cookie 用 Mozilla 扩展前缀 #HttpOnly_，yt-dlp 支持
            dom = ("#HttpOnly_" + domain) if c.get("httpOnly") else domain
            lines.append("\t".join([dom, include_sub, path, secure, str(expires), name, value]))
        return "\n".join(lines) + "\n"

    # 分片 URL 形如 .../0227_gzc_xxx.f322364.8.ts?index=...&ver=4&token=...
    _F_SEG_RE = re.compile(r"(f\d+)\.\d+\.ts")

    def resolve(self, url):
        # 4K/臻彩MAX 菜单项在 headless 下被播放器隐藏，解析必须用有头窗口；
        # 解析完成后恢复常驻 headless。
        switch_to_headed = self.is_headless
        if switch_to_headed:
            self._close_ctx()
            self._launch(headless=False)
        try:
            return self._resolve_impl(url)
        finally:
            if switch_to_headed:
                self.restart_headless()

    def _resolve_impl(self, url):
        """加载播放页，在真实播放器里逐档切换清晰度，拦截 .ts 分片地址，
        反推出每档对应的官方 CDN m3u8 播放列表 URL。

        高档清晰度（1080P/4K）的 cKey 只能由播放器 JS 生成，yt-dlp 拿不到，
        必须让播放器真正切到该档后从它请求的分片签名地址推导 m3u8。"""
        page = self.page
        media = []

        # 分片 CDN 节点不固定：smtcdns.com / ltsyd.qq.com / ltsbdy.gtimg.com 等
        media_hosts = ("smtcdns", "ltsyd", "ltsbdy", "apdcdn", "gtimg", "tc.qq.com")

        def on_request(req):
            u = req.url
            if (".ts" in u or ".m3u8" in u) and any(h in u for h in media_hosts):
                media.append(u)

        page.on("request", on_request)

        # 禁用磁盘缓存：profile 反复解析同一视频后分片会命中缓存，浏览器不发
        # 网络请求（Playwright route 在缓存查找之后，改 no-cache 头也拦不住），
        # 必须用 CDP Network.setCacheDisabled。
        cdp = page.context.new_cdp_session(page)
        cdp.send("Network.enable", {})
        cdp.send("Network.setCacheDisabled", {"cacheDisabled": True})
        try:
            page.goto(url, wait_until="domcontentloaded", timeout=60000)
        except Exception as e:
            raise RuntimeError("页面加载失败: " + str(e)[:200])

        try:
            # 等待视频元素并起播
            page.wait_for_selector("video", timeout=30000)
            page.evaluate("() => document.querySelector('video').play().catch(()=>{})")
            time.sleep(8)
            page.evaluate("() => { const v=document.querySelector('video'); if(v){v.muted=true;v.play().catch(()=>{});} }")
            time.sleep(4)

            duration = page.evaluate("() => { const v=document.querySelector('video'); return v&&v.duration||0; }") or 0

            def video_rect():
                for _ in range(8):
                    r = page.evaluate("""() => {
                        const r = document.querySelector('video').getBoundingClientRect();
                        return r.width > 0 ? {x:r.x,y:r.y,w:r.width,h:r.height} : null;
                    }""")
                    if r:
                        return r
                    time.sleep(1)
                raise RuntimeError("播放器区域不可见")

            def current_label():
                try:
                    return (page.locator(".txp_btn_definition .txp_label").first.inner_text(timeout=2000)
                            or "").strip()
                except Exception:
                    return ""

            def close_menu():
                try:
                    vr = video_rect()
                    page.mouse.move(vr["x"] + vr["w"] / 2, vr["y"] + vr["h"] / 2, steps=5)
                    time.sleep(1.0)
                except Exception:
                    pass

            def open_menu():
                """唤出控制栏并悬停清晰度按钮，返回当前可见的清晰度菜单项。
                4K/臻彩MAX 两行的渲染不稳定，每次打开可能不同。"""
                for _ in range(4):
                    vr = video_rect()
                    page.mouse.move(vr["x"] + vr["w"] * 0.25, vr["y"] + vr["h"] - 50, steps=8)
                    time.sleep(0.5)
                    page.mouse.move(vr["x"] + vr["w"] * 0.55, vr["y"] + vr["h"] - 50, steps=8)
                    time.sleep(0.8)
                    box = page.locator(".txp_btn_definition").bounding_box()
                    if box:
                        page.mouse.move(box["x"] + box["width"] / 2,
                                        box["y"] + box["height"] / 2, steps=8)
                        time.sleep(1.8)
                        items = page.evaluate("""() => Array.from(document.querySelectorAll('.txp_menuitem')).map(el => {
                            const r = el.getBoundingClientRect();
                            return {text: el.innerText.trim().replace(/\\s+/g,' '),
                                    x: r.x + r.width/2, y: r.y + r.height/2, w: r.width, h: r.height};
                        // 4K/臻彩MAX 行文本是「4K 超高清 SDR」「最新支持 4K … 60帧」，
                        // 不含 P 字样，需 \dK 与 60帧 覆盖这两行
                        }).filter(o => o.w > 0 && o.h > 0 && /(\d{3,4}P|\dK|臻彩|60帧)/.test(o.text))""")
                        if len(items) >= 4:
                            return items
                    time.sleep(1)
                raise RuntimeError("清晰度菜单无法展开（未登录或播放器未起播）")

            def collect_targets(prefill=None):
                """多次打开菜单取并集，尽量凑齐 6 档（含时隐时现的 4K 行）。"""
                union = dict(prefill or {})
                miss = 0
                for _ in range(6):
                    items = open_menu()
                    new = 0
                    for it in items:
                        key, _ = defn_of(it["text"])
                        if key not in union:
                            union[key] = it
                            new += 1
                    if new:
                        miss = 0
                    else:
                        miss += 1
                    if len(union) >= 6 or miss >= 3:
                        break
                    close_menu()
                return union

            def locate_item(target_key):
                """重新打开菜单找到指定档的可点击坐标。"""
                for _ in range(4):
                    for it in open_menu():
                        if defn_of(it["text"])[0] == target_key:
                            return it
                    close_menu()
                return None

            def fnum(u):
                m = self._F_SEG_RE.search(u)
                return m.group(1) if m else None

            def defn_of(text):
                if text.startswith("最新") or "60帧" in text or text.startswith("臻彩MAX"):
                    return "zhencai_max_4k60", "臻彩MAX 4K 60帧"
                if "臻彩" in text:
                    return "zhencai_1080", "臻彩1080P 高清增强"
                if text.startswith("4K"):
                    return "4k", "4K 超高清"
                if "增强" in text:
                    return "zhencai_1080", "臻彩1080P 高清增强"
                if text.startswith("1080P"):
                    return "1080p", "1080P 高清"
                if text.startswith("720P"):
                    return "720p", "720P 准高清"
                if text.startswith("480P"):
                    return "480p", "480P 标清"
                m = re.search(r"(\d{3,4}P)", text)
                return (m.group(1), text[:20]) if m else (text[:8], text[:20])

            # label -> (defn, name, sample_ts_url)
            found = {}

            def remember(f, u, defn, name):
                if f and defn not in found:
                    found[defn] = (name, u)

            # 冷启动默认档：等首批分片到达（缓存已禁用，正常几秒内到），用按钮标签映射
            cold_wait = time.time() + 10
            while time.time() < cold_wait and not any(fnum(u) for u in media):
                page.evaluate("() => { const v=document.querySelector('video'); if(v){v.muted=true;v.play().catch(()=>{});} }")
                time.sleep(1)
            cold_label = current_label()
            cold_fs = {fnum(u) for u in media if fnum(u)}
            # 起播早期抢一次菜单：4K/臻彩MAX 行只在部分时机渲染
            early_union = {}
            try:
                for eit in open_menu():
                    k, _ = defn_of(eit["text"])
                    early_union.setdefault(k, eit)
                close_menu()
            except Exception:
                pass
            if cold_label:
                defn, name = defn_of(cold_label)
                for f in cold_fs:
                    u = next(x for x in reversed(media) if fnum(x) == f)
                    remember(f, u, defn, name)

            targets = collect_targets(early_union)
            print("[resolve] 菜单档位:", sorted(targets.keys()), "默认档:", cold_label, flush=True)
            seek_t = 500
            max_seek = max(1200, duration - 120)
            for defn_key in ["480p", "720p", "1080p", "zhencai_1080", "4k", "zhencai_max_4k60"]:
                if defn_key not in targets or defn_key in found:
                    continue
                it = locate_item(defn_key)
                if it is None:
                    print(f"[resolve] {defn_key}: 菜单项定位失败，跳过", flush=True)
                    continue
                name = defn_of(it["text"])[1]
                before_n = len(media)
                before_fs = {fnum(u) for u in media if fnum(u)}
                page.mouse.click(it["x"], it["y"])
                time.sleep(2)
                got = None
                for _tick in range(18):
                    page.evaluate("""(t) => {
                        const v = document.querySelector('video');
                        v.muted = true; v.play().catch(()=>{});
                        if (Math.abs(v.currentTime - t) > 20) v.currentTime = t;
                    }""", seek_t)
                    time.sleep(1)
                    fresh = [(fnum(u), u) for u in media[before_n:]
                             if fnum(u) and fnum(u) not in before_fs]
                    if fresh:
                        got = fresh[-1]
                        break
                # 理论上 no-cache 后一定有请求；兜底取窗口内最多的 f
                if not got and media[before_n:]:
                    from collections import Counter
                    pairs = [(fnum(u), u) for u in media[before_n:] if fnum(u)]
                    if pairs:
                        target = Counter(f for f, _ in pairs).most_common(1)[0][0]
                        got = next((f, u) for f, u in reversed(pairs) if f == target)
                if got:
                    remember(got[0], got[1], defn_key, name)
                else:
                    print(f"[resolve] {defn_key}: 未捕获到分片，跳过该档", flush=True)
                seek_t = min(max_seek, seek_t + 700)

            if not found:
                raise RuntimeError("未捕获到任何清晰度的视频流")

            title = page.title() or ""
            title = re.sub(r"[-_].{0,20}腾讯视频.*$", "", title).strip(" -_")
            if not title:
                title = "腾讯视频"

            order = ["480p", "720p", "1080p", "zhencai_1080", "4k", "zhencai_max_4k60"]
            formats = []
            for key in order:
                if key not in found:
                    continue
                name, seg_url = found[key]
                base = seg_url.split("?", 1)[0]
                m3u8 = re.sub(r"(f\d+)\.\d+\.ts$", r"\1.ts.m3u8?ver=4", base)
                formats.append({"defn": key, "name": name, "url": m3u8, "filesize": 0})

            print("[resolve] 成功档位:", [f["name"] for f in formats], flush=True)
            return {"title": title, "duration": float(duration or 0), "formats": formats}
        finally:
            try:
                page.remove_listener("request", on_request)
            except Exception:
                pass
            try:
                cdp.detach()
            except Exception:
                pass

    def login(self, timeout_sec=300):
        """打开 v.qq.com 页面供用户手动登录，等待登录态 cookie 出现后返回。"""
        if self.logged_in():
            return "already"
        self._close_ctx()
        self._clean_profile_locks()
        try:
            self._launch(headless=False)
            self.page.bring_to_front()
            # 不自动点击登录按钮，让用户手动操作
            deadline = time.time() + timeout_sec
            last_front = 0.0
            while time.time() < deadline:
                if self.logged_in():
                    return True
                if time.time() - last_front > 15:
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


def kill_stale_sidecar(port):
    """若端口被旧版 tencent_sidecar 占用，则杀掉它（避免后端重启后连到旧进程）。"""
    try:
        import subprocess
        result = subprocess.run(
            ["netstat", "-ano", "-p", "tcp"],
            capture_output=True, text=True, timeout=10)
        target_pid = None
        for line in result.stdout.splitlines():
            parts = line.split()
            if len(parts) >= 5 and f":{port}" in parts[1] and "LISTENING" in line.upper():
                target_pid = parts[-1]
                break
        if not target_pid:
            return
        # 确认该进程是 tencent_sidecar.py（wmic 在新版 Windows 可能缺失/失效，回退 PowerShell）
        cmdline = ""
        try:
            r = subprocess.run(
                ["wmic", "process", "where", f"ProcessId={target_pid}", "get", "CommandLine"],
                capture_output=True, text=True, timeout=10)
            cmdline = r.stdout or ""
        except Exception:
            cmdline = ""
        if "tencent_sidecar" not in cmdline:
            try:
                ps = f"(Get-CimInstance Win32_Process -Filter \"ProcessId={target_pid}\").CommandLine"
                r = subprocess.run(
                    ["powershell", "-NoProfile", "-Command", ps],
                    capture_output=True, text=True, timeout=15)
                cmdline = r.stdout or ""
            except Exception:
                cmdline = ""
        if "tencent_sidecar" in cmdline:
            # /T 连子进程（Chrome）一起结束：只杀 Python 会留下持锁的孤儿 Chrome，
            # 新 sidecar 用同一 profile 启动时会产生进程争用
            subprocess.run(["taskkill", "/F", "/T", "/PID", target_pid],
                           capture_output=True, timeout=10)
            print("killed stale tencent sidecar pid=%s" % target_pid, flush=True)
            time.sleep(2)
    except Exception:
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8097)
    ap.add_argument("--profile-dir", required=True)
    args = ap.parse_args()

    if port_in_use(args.port):
        kill_stale_sidecar(args.port)
    if port_in_use(args.port):
        print("tencent sidecar already running on 127.0.0.1:%d, exit" % args.port, flush=True)
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
                elif kind == "cookies":
                    out = {"ok": True, "cookies_txt": worker.export_cookies()}
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

        def handle_error(self, request, client_address):
            # 后端 health 轮询在 worker 忙于 resolve 时会排队，Java 端 30s 超时后
            # 主动断连，sidecar 写响应时抛 ConnectionAbortedError(WinError 10053)。
            # 这是正常现象，不打印 traceback 刷日志。
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
                # 客户端已提前断开（health 超时等），响应写不出去，忽略
                pass

        def do_GET(self):
            path = self.path.split("?")[0]
            if path == "/health":
                try:
                    self._send(200, submit("health", wait_sec=30))
                except Exception as e:
                    self._send(200, {"ok": True, "logged_in": False, "note": str(e)[:100]})
            elif path == "/cookies":
                self._send(200, submit("cookies", wait_sec=30))
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
            elif path == "/login":
                self._send(200, submit("login", payload, wait_sec=300))
            else:
                self._send(404, {"ok": False, "error": "not found"})

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print("tencent sidecar listening on 127.0.0.1:%d" % args.port, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
