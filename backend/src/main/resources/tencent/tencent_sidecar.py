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
import os
import re
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.stdout.reconfigure(encoding="utf-8")

WARM_URL = "https://v.qq.com/"

# 播放页常含多个 <video>（正片 + 隐藏的广告槽），统一取面积最大的可见元素，
# 否则 wait_for_selector("video") / querySelector('video') 命中隐藏广告节点，
# 表现为「等待 video 可见超时」或「播放器区域不可见」。
# 腾讯 thumbplayer 的 WASM 解码模式下 <video> 是 0 尺寸（画面渲染到 canvas），
# 故 __pick 按固有分辨率(videoWidth)取最大，与 DOM 尺寸解耦；
# 鼠标定位用 __player_rect：可见 video 优先，否则取播放器容器/canvas。
_JS_MAIN_VIDEO = """
    const __all_videos = () => {
        const vs = [];
        const walk = (root) => {
            root.querySelectorAll('video').forEach(v => vs.push(v));
            root.querySelectorAll('*').forEach(el => { if (el.shadowRoot) walk(el.shadowRoot); });
        };
        walk(document);
        return vs;
    };
    const __pick = () => {
        let best = null, bestRes = -1;
        for (const v of __all_videos()) {
            const res = (v.videoWidth || 0) * (v.videoHeight || 0);
            if (res > bestRes) { best = v; bestRes = res; }
        }
        // 全部还没解码出分辨率时，退取 DOM 面积最大的
        if (!best) {
            let bestArea = 0;
            for (const v of __all_videos()) {
                const r = v.getBoundingClientRect();
                const a = r.width * r.height;
                if (a > bestArea) { best = v; bestArea = a; }
            }
        }
        if (!best) best = __all_videos()[0] || null;
        return best;
    };
    const __player_rect = () => {
        // 1) 有尺寸的正片 video 优先（普通解码模式）
        const v = __pick();
        if (v) {
            const r = v.getBoundingClientRect();
            if (r.width > 200 && r.height > 100) return {x:r.x,y:r.y,w:r.width,h:r.height};
        }
        // 2) 播放器容器兜底：腾讯常用 id/class 列表，按面积取最大
        const selList = [
            '#mod_player', '#player_container', '.txp_player', '#player',
            '.txp_videos_container', '.player-area', '[data-role="player"]',
            '[class*="player"][id*="player"]'
        ];
        let best = null, bestArea = 0;
        for (const sel of selList) {
            const el = document.querySelector(sel);
            if (!el) continue;
            const r = el.getBoundingClientRect();
            const a = r.width * r.height;
            if (r.width > 200 && a > bestArea) { best = r; bestArea = a; }
        }
        if (best) return {x:best.x,y:best.y,w:best.width,h:best.height};
        // 3) 终极兜底：扫描面积最大且包含 video/canvas 的 div
        let ult = null, ultArea = 0;
        for (const el of document.querySelectorAll('div')) {
            const r = el.getBoundingClientRect();
            if (r.width < 300 || r.height < 150) continue;
            const hasMedia = el.querySelector('video, canvas');
            const a = r.width * r.height;
            if (hasMedia && a > ultArea) { ult = r; ultArea = a; }
        }
        return ult ? {x:ult.x,y:ult.y,w:ult.width,h:ult.height} : null;
    };
"""


def _vjs(body, arg=""):
    return "(" + arg + ") => {" + _JS_MAIN_VIDEO + body + "}"


def _each_frame(page, fn):
    """对页面全部 frame 执行 fn(frame)，返回第一个非 None 结果。
    腾讯正片 video 有时渲染在子 iframe 里，只在主 frame 找会漏。"""
    for f in page.frames:
        try:
            r = fn(f)
            if r is not None:
                return r
        except Exception:
            pass
    return None


def _await_main_video(page, timeout=30):
    """等待正片 video 出现（任意 frame，含 shadow DOM）。
    __pick 按固有分辨率/可见面积/存在性三级回退选取，WASM 0 尺寸 video 也能命中。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if _each_frame(page, lambda f: True if f.evaluate(_vjs("return !!__pick();")) else None):
            return
        time.sleep(1)
    # 诊断：报告页面真实状态（风控/验证/白屏）与各 frame 的 video 分布
    url = page.url
    title = page.title() or ""
    frames_info = []
    for f in page.frames:
        try:
            n = f.evaluate(_vjs("return __all_videos().length;"))
        except Exception:
            n = -1
        frames_info.append("%s(videos=%s)" % (f.url[:60], n))
    shot = r"D:\TEMP\tencent_no_video.png"
    try:
        page.screenshot(path=shot)
    except Exception:
        shot = "(截图失败)"
    raise RuntimeError("播放器未加载出可见视频（URL=%s，标题=%s，frames=[%s]，截图=%s）"
                       % (url, title, "; ".join(frames_info), shot))

# 腾讯指纹脚本在 headless 下会写入 fp_bot=headless_chrome cookie，
# 该 cookie 随 vinfo 请求上行后服务端只下发 3 档（无 4K/臻彩MAX）。
# 注入拦截 document.cookie 对 fp_bot 的写入，并在启动后清除历史值。
_FP_BOT_BLOCK_JS = """
(() => {
  // 掩盖自动化特征：ptlogin 检测 navigator.webdriver，命中后会快速作废会话 cookie
  try { Object.defineProperty(navigator, 'webdriver', {get: () => false}); } catch(e) {}
  try {
    let desc = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie');
    if (!desc || !desc.set) {
      desc = Object.getOwnPropertyDescriptor(HTMLDocument.prototype, 'cookie');
    }
    if (!desc || !desc.set) return;
    Object.defineProperty(document, 'cookie', {
      get: desc.get.bind(document),
      set: (v) => { if (v && v.startsWith('fp_bot=')) return; return desc.set.call(document, v); },
      configurable: true
    });
  } catch(e) {}
})();
"""


class BrowserWorker:
    """所有 Playwright 操作都在此线程内串行执行（sync API 绑定创建线程）。"""

    def __init__(self, profile_dir):
        self.profile_dir = profile_dir
        self.pw = None
        self.ctx = None
        self.page = None
        self.is_headless = True
        # 扫码下发的 session cookie（v_vusession 等）不会持久化到磁盘，Chrome 正常
        # close() 后会从内存清除。每次 close 前把 cookie 导出到内存，启动新 ctx 时
        # 重新注入，保证无头↔有头切换间登录态不丢失。
        self._cookies = []

    def _launch(self, headless):
        # 不加 --disable-blink-features=AutomationControlled：Chrome 154 已移除该 blink
        # 特性，只会弹「不受支持的命令行标记」警告条。真正的自动化标记是 Playwright 默认
        # 注入的 --enable-automation（navigator.webdriver=true + 信息条），在下方
        # ignore_default_args 中移除——否则腾讯 ptlogin 判定自动化浏览器，
        # 扫码下发的会话 cookie 会在几分钟内被服务端作废（实测两次解析间 cookie 消失）。
        args = []
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
                    args=args, viewport=viewport, no_viewport=not headless,
                    chromium_sandbox=(sys.platform == "win32"),
                    # --disable-component-update 会阻止 Widevine CDM 组件加载
                    # （requestMediaKeySystemAccess 直接 NotSupported），腾讯播放器探测不到
                    # DRM 能力就不渲染 4K/臻彩MAX 档位；--enable-automation 是自动化特征。
                    ignore_default_args=["--disable-component-update"])
                self.ctx.add_init_script(_FP_BOT_BLOCK_JS)
                # 注入 cookie：优先内存中的（同进程切换），其次落盘文件（进程重启后恢复）
                if not self._cookies:
                    cookie_file = os.path.join(self.profile_dir, ".sidecar_cookies.json")
                    try:
                        if os.path.exists(cookie_file):
                            with open(cookie_file, "r", encoding="utf-8") as f:
                                self._cookies = json.load(f)
                    except Exception:
                        pass
                if self._cookies:
                    try:
                        self.ctx.add_cookies(self._cookies)
                    except Exception:
                        pass
                pages = self.ctx.pages
                self.page = pages[0] if pages else self.ctx.new_page()
                return
            except Exception as e:
                last_err = e
                # profile 锁未释放：清理锁文件后重试
                self._clean_profile_locks()
                time.sleep(2)
        raise RuntimeError("Chrome 启动失败（可能 profile 被占用）：" + str(last_err)[:300])

    def _profile_chrome_pids(self):
        """仍在运行、命令行引用本 profile 目录的 chrome.exe PID
        （ctx.close() 后残留的 renderer/gpu 子进程会继续持有 profile 锁）。"""
        import subprocess
        try:
            like = self.profile_dir.replace("'", "''").replace("*", "`*").replace("?", "`?")
            ps = ("Get-CimInstance Win32_Process -Filter \"Name='chrome.exe'\" | "
                  "Where-Object { $_.CommandLine -like '*" + like + "*' } | "
                  "ForEach-Object { $_.ProcessId }")
            r = subprocess.run(["powershell", "-NoProfile", "-Command", ps],
                               capture_output=True, text=True, timeout=15)
            return [int(x) for x in (r.stdout or "").split() if x.strip().isdigit()]
        except Exception:
            return []

    def _ensure_profile_released(self):
        """ctx.close() 后等 Chrome 自行退出；仍残留则 taskkill 强杀，最后清锁文件。
        否则紧接着 _launch(headless=False) 会因 profile 被占退出（exitCode=21 /
        Target closed），表现为扫码窗口或有头解析起不来。"""
        deadline = time.time() + 8
        while time.time() < deadline:
            if not self._profile_chrome_pids():
                break
            time.sleep(1)
        for pid in self._profile_chrome_pids():
            try:
                import subprocess
                subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)],
                               capture_output=True, timeout=10)
            except Exception:
                pass
        if self._profile_chrome_pids():
            time.sleep(2)
        self._clean_profile_locks()

    def _close_ctx(self):
        if self.ctx is not None:
            try:
                # 导出 cookie 到内存 + 落盘：session cookie 不落盘，close 后丢失；
                # 落盘到 profile 目录，sidecar 进程重启后可恢复
                self._cookies = self.ctx.cookies()
                cookie_file = os.path.join(self.profile_dir, ".sidecar_cookies.json")
                with open(cookie_file, "w", encoding="utf-8") as f:
                    json.dump(self._cookies, f)
            except Exception:
                pass
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
        # 等待 Chrome 进程完全退出并释放 profile 锁（残留子进程强杀兜底）
        self._ensure_profile_released()

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

    def _scrub_fp_bot(self):
        """从 profile Cookies 数据库直接删除 fp_bot 行（Playwright 无单删 API）。"""
        import os, sqlite3
        candidates = [
            os.path.join(self.profile_dir, "Default", "Network", "Cookies"),
            os.path.join(self.profile_dir, "Default", "Cookies"),
        ]
        for db in candidates:
            if not os.path.exists(db):
                continue
            try:
                conn = sqlite3.connect(db, timeout=5)
                conn.execute("DELETE FROM cookies WHERE name='fp_bot'")
                conn.commit()
                conn.close()
            except Exception:
                pass

    def start(self):
        from playwright.sync_api import sync_playwright
        self.pw = sync_playwright().start()
        self._scrub_fp_bot()
        try:
            self._launch(headless=True)
        except Exception:
            # Chrome 拉起失败时必须回收 Playwright 实例，否则同线程重试
            # start() 会报 "Sync API inside the asyncio loop"
            try:
                self.pw.stop()
            except Exception:
                pass
            self.pw = None
            raise

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
        resolve_started = time.monotonic()
        switch_to_headed = self.is_headless
        if switch_to_headed:
            self._close_ctx()
            self._launch(headless=False)
            print("[resolve] 切换有头浏览器: %.1fs" % (time.monotonic() - resolve_started), flush=True)
        try:
            return self._resolve_impl(url)
        finally:
            if switch_to_headed:
                body_elapsed = time.monotonic() - resolve_started
                self.restart_headless()
                print("[resolve] 恢复无头浏览器: %.1fs（解析主体 %.1fs）"
                      % (time.monotonic() - resolve_started, body_elapsed), flush=True)

    def _resolve_impl(self, url):
        """加载播放页，在真实播放器里逐档切换清晰度，拦截 .ts 分片地址，
        反推出每档对应的官方 CDN m3u8 播放列表 URL。

        高档清晰度（1080P/4K）的 cKey 只能由播放器 JS 生成，yt-dlp 拿不到，
        必须让播放器真正切到该档后从它请求的分片签名地址推导 m3u8。"""
        phase_started = time.monotonic()
        page = self.page
        print("[resolve] _resolve_impl 入口: url=%s, page=%s" % (url, page.url if page else "None"), flush=True)
        media = []

        # 分片 CDN 节点不固定：smtcdns.com / ltsyd.qq.com / ltsbdy.gtimg.com 等
        media_hosts = ("smtcdns", "ltsyd", "ltsbdy", "apdcdn", "gtimg", "tc.qq.com")

        def on_request(req):
            u = req.url
            if (".ts" in u or ".m3u8" in u) and any(h in u for h in media_hosts):
                media.append(u)

        page.on("request", on_request)

        # 拦截 WASM 播放器 iframe：该模式把 video 换成 canvas，DOM 里没有可操作的
        # <video>，菜单/seek 全部失效。阻断后播放器回退到普通 video 元素模式。
        def block_wasm(route):
            route.abort()
        wasm_route = "**/thumbplayer/txv/wasm/**"
        page.route(wasm_route, block_wasm)

        # 禁用磁盘缓存：profile 反复解析同一视频后分片会命中缓存，浏览器不发
        # 网络请求（Playwright route 在缓存查找之后，改 no-cache 头也拦不住），
        # 必须用 CDP Network.setCacheDisabled。
        cdp = page.context.new_cdp_session(page)
        cdp.send("Network.enable", {})
        cdp.send("Network.setCacheDisabled", {"cacheDisabled": True})
        # 清除可能通过 HTTP Set-Cookie 头下发的 fp_bot（JS 拦截无法覆盖 HTTP 层）
        try:
            all_ck = cdp.send("Network.getAllCookies", {})
            for c in all_ck.get("cookies", []):
                if c.get("name") == "fp_bot":
                    cdp.send("Network.deleteCookies", {
                        "name": "fp_bot",
                        "domain": c.get("domain", ""),
                        "path": c.get("path", "/")})
        except Exception:
            pass
        try:
            print("[resolve] 开始导航: %s" % url, flush=True)
            page.goto(url, wait_until="domcontentloaded", timeout=60000)
            print("[resolve] 页面 DOM 就绪: %.1fs, 当前URL=%s" % (
                time.monotonic() - phase_started, page.url), flush=True)
            # 页面视角的登录态：profile 有 cookie 不代表页面 JS 能读到
            # （httpOnly 除外），以此判断「页面是否真把用户当登录态」
            try:
                page_ck = page.evaluate("() => document.cookie")
                print("[resolve] 页面可见登录cookie: v_vusession=%s, v_t_access_token=%s" % (
                    "v_vusession" in page_ck, "v_t_access_token" in page_ck), flush=True)
            except Exception:
                pass
        except Exception as e:
            raise RuntimeError("页面加载失败: " + str(e)[:200])

        try:
            # 等待正片 video 可见并起播；首次加载失败（风控页/渲染慢）重载一次。
            # 不能 wait_for_selector("video")：隐藏广告槽 video 会被命中而超时。
            # 正片 video 有时在子 iframe 里，全部操作走 _each_frame。
            try:
                _await_main_video(page, timeout=30)
            except Exception:
                page.goto(url, wait_until="domcontentloaded", timeout=60000)
                _await_main_video(page, timeout=30)
            _each_frame(page, lambda f: f.evaluate(_vjs(
                "const v=__pick(); if(v){v.muted=true;v.play().catch(()=>{});}")))
            # 最多仍等 12 秒，但正片已加载（长视频 duration > 60）就立即继续；
            # 短视频至少给 6 秒完成 metadata，避免原先无条件空等 8+4 秒。
            ready_deadline = time.time() + 12
            ready_started = time.time()
            duration = 0
            while time.time() < ready_deadline:
                duration = (_each_frame(page, lambda f: f.evaluate(
                    _vjs("const v=__pick(); return v ? (v.duration||0) : null;"))) or 0)
                if duration > 60 or (duration > 0 and time.time() - ready_started >= 6):
                    break
                time.sleep(0.2)
            print("[resolve] 播放器就绪: %.1fs" % (time.monotonic() - phase_started), flush=True)

            # cookie 过期时页面会弹扫码登录框遮挡播放器（按钮 rect 全 0，菜单失效），
            # 且顶栏出现「登录」按钮。cookie 存在 ≠ 登录有效，以页面实际状态为准。
            # 顶栏渲染时机不稳定（播放器 1.4s 就绪时顶栏可能未渲染），轮询直到出现
            # 「登录」按钮或头像（有结论），最多 6 秒；超时无法判定时按已登录处理。
            page_logged_in = True
            try:
                st = {}
                for _ in range(12):
                    st = page.evaluate("""() => {
                        const vis = el => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
                        const inHeader = el => el.getBoundingClientRect().top < 120;
                        const pop = Array.from(document.querySelectorAll(
                            'iframe[src*="ptlogin"], [class*="login_pop"], [id*="login_pop"], [class*="LoginPop"]'))
                            .find(vis);
                        const topLogin = Array.from(document.querySelectorAll('a,button,span,div'))
                            .find(el => (el.innerText || '').trim() === '登录' && vis(el) && inHeader(el));
                        const avatar = Array.from(document.querySelectorAll(
                            '[class*="avatar"] img, [class*="user"] img, img[src*="qlogo"]'))
                            .find(el => vis(el) && inHeader(el));
                        return {pop: !!pop, topLogin: !!topLogin, avatar: !!avatar};
                    }""")
                    if st.get("pop") or st.get("topLogin") or st.get("avatar"):
                        break
                    time.sleep(0.5)
                if st.get("pop"):
                    print("[resolve] 检测到登录弹窗遮挡播放器，尝试关闭", flush=True)
                    for sel in ('[class*="login_pop"] [class*="close"]',
                                '[id*="login_pop"] [class*="close"]',
                                '.login_pop_close', '.mod_login_pop .close'):
                        try:
                            el = page.query_selector(sel)
                            if el:
                                el.click(timeout=1000)
                                break
                        except Exception:
                            pass
                    try:
                        page.keyboard.press("Escape")
                    except Exception:
                        pass
                    time.sleep(0.5)
                if st.get("avatar"):
                    page_logged_in = True
                elif st.get("pop") or st.get("topLogin"):
                    page_logged_in = False
                else:
                    print("[resolve] 页面登录态无法判定（顶栏未渲染），按已登录继续", flush=True)
                print("[resolve] 页面登录态: %s（登录弹窗=%s, 顶栏登录按钮=%s, 头像=%s）" % (
                    page_logged_in, st.get("pop"), st.get("topLogin"), st.get("avatar")), flush=True)
            except Exception as e:
                print("[resolve] 页面登录态检测异常: %s" % str(e)[:100], flush=True)

            def video_rect():
                # 广告、正片和切档之间播放器会替换 <video>，节点可短暂不存在。
                # WASM 解码模式下 video 为 0 尺寸（画面渲染到 canvas），
                # 用 __player_rect 兜底取播放器容器。
                # iframe 内坐标需叠加 iframe 在页面中的偏移（鼠标用页面坐标）。
                for _ in range(30):
                    for f in page.frames:
                        try:
                            r = f.evaluate(_vjs("return __player_rect();"))
                        except Exception:
                            continue
                        if not r:
                            continue
                        if f != page.main_frame:
                            try:
                                box = f.frame_element().bounding_box()
                                if box:
                                    r = {"x": r["x"] + box["x"], "y": r["y"] + box["y"],
                                         "w": r["w"], "h": r["h"]}
                            except Exception:
                                pass
                        return r
                    time.sleep(1)
                # 诊断：列出每个 frame 的 video 详情，定位 video 到底在哪
                for f in page.frames:
                    try:
                        info = f.evaluate(_vjs("""
                            return __all_videos().map(v => {
                                const r = v.getBoundingClientRect();
                                return {vw: v.videoWidth, vh: v.videoHeight, w: r.width, h: r.height,
                                        parent: v.parentElement ? String(v.parentElement.className).slice(0,50) : ''};
                            });
                        """))
                        print("[video_rect] frame %s videos=%s" % (f.url[:70], info), flush=True)
                    except Exception as e:
                        print("[video_rect] frame %s 评估失败: %s" % (f.url[:70], str(e)[:100]), flush=True)
                raise RuntimeError("播放器区域不可见")

            def current_label():
                # 清晰度按钮可能在子 frame，跨 frame 查找
                for f in page.frames:
                    try:
                        t = f.evaluate("""() => {
                            const el = document.querySelector('.txp_btn_definition .txp_label');
                            return el ? (el.innerText || '').trim() : '';
                        }""")
                        if t:
                            return t
                    except Exception:
                        pass
                return ""

            def close_menu():
                try:
                    vr = video_rect()
                    page.mouse.move(vr["x"] + vr["w"] / 2, vr["y"] + vr["h"] / 2, steps=5)
                    time.sleep(0.2)
                except Exception:
                    pass

            def open_menu():
                """唤出控制栏并悬停清晰度按钮，返回当前可见的清晰度菜单项。
                4K/臻彩MAX 两行的渲染不稳定，每次打开可能不同。
                按钮/菜单可能在子 iframe，跨 frame 查找。"""
                for _ in range(4):
                    vr = video_rect()
                    page.mouse.move(vr["x"] + vr["w"] * 0.25, vr["y"] + vr["h"] - 50, steps=8)
                    time.sleep(0.15)
                    page.mouse.move(vr["x"] + vr["w"] * 0.55, vr["y"] + vr["h"] - 50, steps=8)
                    time.sleep(0.2)
                    # 找到含清晰度按钮的 frame（bounding_box 为 frame 内坐标，需换算）
                    for f in page.frames:
                        try:
                            box = f.evaluate("""() => {
                                const el = document.querySelector('.txp_btn_definition');
                                if (!el) return null;
                                const r = el.getBoundingClientRect();
                                return r.width > 0 && r.height > 0
                                    ? {x:r.x,y:r.y,width:r.width,height:r.height} : null;
                            }""")
                        except Exception:
                            box = None
                        if not box:
                            continue
                        if f != page.main_frame:
                            try:
                                fe_box = f.frame_element().bounding_box()
                                if fe_box:
                                    box = {"x": box["x"] + fe_box["x"], "y": box["y"] + fe_box["y"],
                                           "width": box["width"], "height": box["height"]}
                            except Exception:
                                pass
                        page.mouse.move(box["x"] + box["width"] / 2,
                                        box["y"] + box["height"] / 2, steps=8)
                        # 菜单项坐标同样换算到页面坐标
                        fe_off = {"x": 0, "y": 0}
                        if f != page.main_frame:
                            try:
                                fe_box2 = f.frame_element().bounding_box()
                                if fe_box2:
                                    fe_off = fe_box2
                            except Exception:
                                pass
                        # hover 菜单通常数百毫秒出现；连续两次内容一致才返回，
                        # 避免 4K/臻彩MAX 较晚渲染时只拿到半份菜单。
                        last_items = []
                        last_signature = None
                        stable_reads = 0
                        for _ in range(12):
                            try:
                                items = f.evaluate("""(off) => Array.from(document.querySelectorAll('.txp_menuitem')).map(el => {
                                    const r = el.getBoundingClientRect();
                                    return {text: el.innerText.trim().replace(/\\s+/g,' '),
                                            x: r.x + r.width/2 + off.x, y: r.y + r.height/2 + off.y,
                                            w: r.width, h: r.height};
                                // 4K/臻彩MAX 行文本是「4K 超高清 SDR」「最新支持 4K … 60帧」，
                                // 不含 P 字样，需 \dK 与 60帧 覆盖这两行
                                }).filter(o => o.w > 0 && o.h > 0 && /(\d{3,4}P|\dK|臻彩|60帧)/.test(o.text))""",
                                                   {"x": fe_off["x"], "y": fe_off["y"]})
                            except Exception:
                                items = []
                            if items:
                                signature = tuple(sorted(it["text"] for it in items))
                                stable_reads = stable_reads + 1 if signature == last_signature else 0
                                last_signature = signature
                                last_items = items
                                if stable_reads >= 1:
                                    return items
                            time.sleep(0.15)
                        if last_items:
                            return last_items
                    time.sleep(0.2)
                # 菜单确实展开但拿不到任何清晰度项：报告真实上下文便于定位
                label = ""
                try:
                    label = (page.locator(".txp_btn_definition .txp_label").first.inner_text(timeout=2000) or "").strip()
                except Exception:
                    pass
                # 诊断：不过滤正则， dump 全部 menuitem 文本 + 清晰度按钮状态 + 截图
                try:
                    all_items = page.evaluate("""() => Array.from(document.querySelectorAll('.txp_menuitem')).map(el => {
                        const r = el.getBoundingClientRect();
                        return (el.innerText||'').trim().replace(/\\s+/g,' ').slice(0,40)
                               + ' [' + Math.round(r.width) + 'x' + Math.round(r.height) + ']';
                    })""")
                    print("[open_menu] 失败诊断: 全部menuitem=%s" % all_items, flush=True)
                except Exception as e:
                    print("[open_menu] 失败诊断: menuitem dump 异常 %s" % str(e)[:100], flush=True)
                try:
                    btn_info = page.evaluate("""() => {
                        const el = document.querySelector('.txp_btn_definition');
                        if (!el) return '按钮不存在';
                        const r = el.getBoundingClientRect();
                        return 'rect=' + JSON.stringify({x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)})
                               + ' visible=' + (r.width>0 && r.height>0);
                    }""")
                    print("[open_menu] 失败诊断: 清晰度按钮 %s" % btn_info, flush=True)
                except Exception as e:
                    print("[open_menu] 失败诊断: 按钮 dump 异常 %s" % str(e)[:100], flush=True)
                shot = r"D:\TEMP\tencent_menu_fail.png"
                try:
                    page.screenshot(path=shot)
                    print("[open_menu] 失败诊断: 截图已存 %s" % shot, flush=True)
                except Exception:
                    pass
                raise RuntimeError(
                    "清晰度菜单未出现任何档位（当前清晰度按钮=%s，登录=%s，视频时长=%ss）；"
                    "未登录或该影片需要 VIP，请先在「Cookies」弹窗扫码登录腾讯视频"
                    % (label or "无", self.logged_in(), duration))

            def collect_targets(prefill=None):
                """多次打开菜单取并集；结果稳定即停止，档位数不作固定假设。"""
                union = dict(prefill or {})
                stable = 0
                for _ in range(4):
                    items = open_menu()
                    new = 0
                    for it in items:
                        key, _ = defn_of(it["text"])
                        if key not in union:
                            union[key] = it
                            new += 1
                    if new:
                        stable = 0
                    else:
                        stable += 1
                    if len(union) >= 6 or (union and stable >= 2):
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
                _each_frame(page, lambda f: f.evaluate(_vjs("const v=__pick(); if(v){v.muted=true;v.play().catch(()=>{});}")))
                time.sleep(1)
            cold_label = current_label()
            cold_fs = {fnum(u) for u in media if fnum(u)}
            # 起播早期抢一次菜单：4K/臻彩MAX 行只在部分时机渲染
            early_union = {}
            early_menu_failed = False
            try:
                for eit in open_menu():
                    k, _ = defn_of(eit["text"])
                    early_union.setdefault(k, eit)
                close_menu()
            except Exception:
                early_menu_failed = True
            if cold_label:
                defn, name = defn_of(cold_label)
                for f in cold_fs:
                    u = next(x for x in reversed(media) if fnum(x) == f)
                    remember(f, u, defn, name)

            # 单档视频可能没有可展开的清晰度菜单；默认流已捕获时直接返回该档。
            # 但首次打开失败可能只是播放器未就绪/广告遮挡：延迟后补试一轮，
            # 仍失败才按单档处理，避免多档视频被误判。
            if early_menu_failed and found:
                time.sleep(3)
                _each_frame(page, lambda f: f.evaluate(_vjs(
                    "const v=__pick(); if(v){v.muted=true;v.play().catch(()=>{});}")))
                try:
                    for eit in open_menu():
                        k, _ = defn_of(eit["text"])
                        early_union.setdefault(k, eit)
                    close_menu()
                    early_menu_failed = not early_union
                except Exception:
                    pass
            targets = {} if early_menu_failed and found else collect_targets(early_union)
            print("[resolve] 菜单档位:", sorted(targets.keys()), "默认档:", cold_label, flush=True)
            print("[resolve] 菜单收集完成: %.1fs" % (time.monotonic() - phase_started), flush=True)
            # 从低到高逐级切档，最后进入会切换 DRM 管线的臻彩MAX。
            # 每档使用不同时间点，避免直接复用上一档缓冲。
            capture_order = ["480p", "720p", "1080p", "zhencai_1080", "4k", "zhencai_max_4k60"]
            if duration > 240:
                seek_points = [120 + (duration - 240) * (i + 1) / (len(capture_order) + 1)
                               for i in range(len(capture_order))]
            else:
                seek_points = [max(0, duration) * (i + 1) / (len(capture_order) + 1)
                               for i in range(len(capture_order))]
            for index, defn_key in enumerate(capture_order):
                if defn_key not in targets or defn_key in found:
                    continue
                target_started = time.monotonic()
                before_n = len(media)
                switched = defn_of(current_label())[0] == defn_key
                name = defn_of(targets[defn_key]["text"])[1]
                for _select_attempt in range(3 if defn_key in ("4k", "zhencai_max_4k60") else 2):
                    if switched:
                        break
                    # 重试前按 Escape 关闭可能遮挡的弹窗（如"会员专区"提示），
                    # 否则 locate_item → open_menu 会因控件被遮挡而失败
                    if _select_attempt > 0:
                        try:
                            page.keyboard.press("Escape")
                            time.sleep(0.5)
                        except Exception:
                            pass
                    it = locate_item(defn_key)
                    if it is None:
                        continue
                    name = defn_of(it["text"])[1]
                    page.mouse.click(it["x"], it["y"])
                    # 标签刷新偶发超过 2.4s（toast 已提示"正在切换"但标签未更新）；
                    # DRM 档（4K/臻彩MAX）切换需重建解密管线更慢（实测臻彩MAX达 8.8s）。
                    # 成功即提前跳出，不影响正常档位速度。
                    wait_rounds = 45 if defn_key in ("4k", "zhencai_max_4k60") else 30
                    for _ in range(wait_rounds):
                        time.sleep(0.2)
                        if defn_of(current_label())[0] == defn_key:
                            switched = True
                            break
                    # 等待结束后再查一次：标签可能在等待刚结束后才更新
                    if not switched:
                        time.sleep(0.5)
                        switched = defn_of(current_label())[0] == defn_key
                if not switched:
                    # 诊断：点击无反应通常是 SVIP 付费弹窗遮挡或 DRM 管线不可用
                    try:
                        popups = page.evaluate("""() => {
                            const sels = ['.txp_popup', '.txp_dialog', '.txp_toast',
                                          '[class*="vip"]', '[class*="dialog"]',
                                          '[class*="toast"]', '[class*="modal"]'];
                            const out = [];
                            for (const sel of sels) {
                                document.querySelectorAll(sel).forEach(el => {
                                    const r = el.getBoundingClientRect();
                                    const t = (el.innerText || '').trim().replace(/\\s+/g, ' ').slice(0, 80);
                                    if (r.width > 0 && r.height > 0 && t) out.push(sel + ' => ' + t);
                                });
                            }
                            return out.slice(0, 8);
                        }""")
                        print(f"[resolve] {defn_key}: 切换失败时可见弹窗={popups}", flush=True)
                    except Exception:
                        pass
                    try:
                        wv = page.evaluate("""async () => {
                            try {
                                await navigator.requestMediaKeySystemAccess('com.widevine.alpha',
                                    [{initDataTypes:['cenc'],
                                      videoCapabilities:[{contentType:'video/mp4; codecs="avc1.640028"'}]}]);
                                return 'ok';
                            } catch(e) { return 'fail: ' + e; }
                        }""")
                        print(f"[resolve] {defn_key}: Widevine EME 检测={wv}", flush=True)
                    except Exception as e:
                        print(f"[resolve] {defn_key}: Widevine 检测异常={str(e)[:100]}", flush=True)
                    try:
                        page.screenshot(path=r"D:\TEMP\tencent_switch_fail_%s.png" % defn_key)
                    except Exception:
                        pass
                    print(f"[resolve] {defn_key}: 点击后未切换到目标档，跳过", flush=True)
                    continue
                switched_elapsed = time.monotonic() - target_started
                time.sleep(0.2)
                got = None
                seek_t = seek_points[index]
                capture_deadline = time.time() + 18
                _tick = 0
                while time.time() < capture_deadline:
                    probe_t = min(max(0, duration - 5), seek_t + _tick * 5) if duration else seek_t + _tick * 5
                    _each_frame(page, lambda f: f.evaluate(_vjs("""
                        const v = __pick();
                        if (!v) return false;
                        v.muted = true; v.play().catch(()=>{});
                        if (Math.abs(v.currentTime - t) > 5) v.currentTime = t;
                        return true;
                    """, "t"), probe_t))
                    time.sleep(0.25)
                    # 同一视频的不同清晰度可能复用 f 编号；切档后最新的分片请求
                    # 才是目标流，不能以“新 f 编号”作为成功条件。
                    fresh = [(fnum(u), u) for u in media[before_n:] if fnum(u)]
                    if fresh:
                        got = fresh[-1]
                        break
                    _tick += 1
                # 理论上 no-cache 后一定有请求；兜底取窗口内最多的 f
                if not got and media[before_n:]:
                    from collections import Counter
                    pairs = [(fnum(u), u) for u in media[before_n:] if fnum(u)]
                    if pairs:
                        target = Counter(f for f, _ in pairs).most_common(1)[0][0]
                        got = next((f, u) for f, u in reversed(pairs) if f == target)
                if got:
                    remember(got[0], got[1], defn_key, name)
                    target_elapsed = time.monotonic() - target_started
                    print("[resolve] 捕获 %s: %.1fs（切档 %.1fs，取流 %.1fs）" %
                          (defn_key, time.monotonic() - phase_started,
                           switched_elapsed, target_elapsed - switched_elapsed), flush=True)
                else:
                    print(f"[resolve] {defn_key}: 未捕获到分片，跳过该档", flush=True)

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
            print("[resolve] 解析主体完成: %.1fs" % (time.monotonic() - phase_started), flush=True)
            # logged_in 以页面实际状态为准（cookie 过期时 worker_loop 的 cookie 检测会误报已登录）
            return {"title": title, "duration": float(duration or 0), "formats": formats,
                    "logged_in": page_logged_in}
        finally:
            try:
                page.remove_listener("request", on_request)
            except Exception:
                pass
            try:
                page.unroute(wasm_route, block_wasm)
            except Exception:
                pass
            try:
                cdp.detach()
            except Exception:
                pass

    def login(self, timeout_sec=300):
        """打开 v.qq.com 页面供用户手动登录，等待页面登录态出现后返回。
        判定以页面为准（顶栏「登录」按钮消失）：cookie 可能过期残留，
        按 cookie 判定会出现「已登录」误报，导致扫码窗口一闪而过。"""
        self._close_ctx()
        self._clean_profile_locks()
        try:
            self._launch(headless=False)
            self.page.goto(WARM_URL, wait_until="domcontentloaded", timeout=30000)
            self.page.bring_to_front()

            def page_logged_in_here():
                # 头像出现 = 确定的已登录信号（首页顶栏未登录时只有「登录」按钮，无头像）
                try:
                    return bool(self.page.evaluate("""() => {
                        const vis = el => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
                        return !!Array.from(document.querySelectorAll(
                            '[class*="avatar"] img, [class*="user"] img, img[src*="qlogo"]'))
                            .find(el => vis(el) && el.getBoundingClientRect().top < 120);
                    }"""))
                except Exception:
                    return False

            deadline = time.time() + timeout_sec
            last_front = 0.0
            start = time.time()
            while time.time() < deadline:
                if page_logged_in_here():
                    # 等 Chrome 把登录 cookie 刷到磁盘（profile 的 Cookies SQLite），
                    # 否则 finally 里 restart_headless() 立即杀进程，下次解析读不到登录态
                    time.sleep(5)
                    return True
                # 兜底：头像加载失败（网络慢/被拦）时，cookie 有效且顶栏登录按钮
                # 已消失也视为登录成功；8 秒内不采信（顶栏可能尚未渲染）
                if time.time() - start > 8:
                    try:
                        top_login = bool(self.page.evaluate("""() => {
                            const vis = el => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
                            return !!Array.from(document.querySelectorAll('a,button,span,div'))
                                .find(el => (el.innerText || '').trim() === '登录' && vis(el)
                                       && el.getBoundingClientRect().top < 120);
                        }"""))
                    except Exception:
                        top_login = True  # 页面异常时不采信，继续等待头像信号
                    if self.logged_in() and not top_login:
                        # 同样需要等 cookie 落盘
                        time.sleep(5)
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
    # worker 正忙时 /health 直接回缓存态，避免排队 30s 超时后表现为「无法访问」
    state = {"busy": False, "logged_in": False, "started": False}

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
                # 首个任务可能就是 resolve；在耗时解析前刷新登录缓存，避免
                # /health 忙时把 state 初始值 false 误报成已登出。
                logged_in = worker.logged_in()
                with cond:
                    state["logged_in"] = logged_in
                if kind == "health":
                    out = {"ok": True, "logged_in": logged_in, "busy": False}
                elif kind == "resolve":
                    r = worker.resolve(payload["url"])
                    # resolve 内部以页面实际状态（登录弹窗/顶栏按钮）判定 logged_in，
                    # 比 cookie 检测更准确（cookie 过期但残留时 cookie 检测会误报 true）
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
                try:
                    logged_in = worker.logged_in()
                    with cond:
                        state["logged_in"] = logged_in
                except Exception:
                    pass
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
            # 忙碌判断与入队必须在同一把锁内，避免 health 刚判断空闲，
            # resolve 随即抢占 worker 后 health 又排队 30 秒。
            if fast_health and (state["started"] or state["busy"] or queue):
                return {"ok": True, "logged_in": state["logged_in"],
                        "busy": bool(state["busy"] or queue)}
            queue.append((kind, payload or {}, box))
            cond.notify_all()
            end = time.time() + wait_sec
            while "out" not in box:
                if not cond.wait(timeout=max(0.1, end - time.time())) and time.time() > end:
                    return {"ok": False, "error": "sidecar 处理超时"}
        return box["out"]

    class Handler(BaseHTTPRequestHandler):
        # HTTP/1.1 keep-alive：避免 Java HttpClient 在 health→resolve 间复用
        # 已关闭的 HTTP/1.0 连接，导致 "header parser received no bytes"。
        protocol_version = "HTTP/1.1"

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
                    self._send(200, submit("health", wait_sec=30, fast_health=True))
                except Exception as e:
                    self._send(503, {"ok": False, "logged_in": state["logged_in"],
                                     "error": str(e)[:100]})
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
                self._send(200, submit("resolve", payload, wait_sec=240))
            elif path == "/login":
                self._send(200, submit("login", payload, wait_sec=300))
            else:
                self._send(404, {"ok": False, "error": "not found"})

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print("tencent sidecar listening on 127.0.0.1:%d" % args.port, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
