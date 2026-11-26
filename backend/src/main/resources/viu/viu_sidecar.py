#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Viu（viu.com）浏览器登录与取流 sidecar。

用 Playwright 驱动系统 Chrome（channel=chrome + 持久化 profile）：
1. POST /login 弹出有头 Chrome 打开 https://www.viu.com/ott/sg/zh 供用户手动登录 Viu 账号
   （支持邮箱/Google/Facebook/Apple 登录及人机验证），轮询确认 /api/user/info 返回有效 user_id 后，
   将完整 Cookie 持久化到 profile 目录（含 .sidecar_cookies.json 备份）并切回无头常驻。
2. GET /session 被动读取当前登录态（token、user_level、cookie_header、cookies_txt），
   供 Java ViuParser / DownloadController / PlaybackSourceService 直接用于 VIP 与高清流请求。
3. POST /logout 清空上下文 Cookie、Storage 及磁盘 Cookie 缓存，便于切换账号。

接口：
  GET  /health   -> {"ok": true, "logged_in": bool}
  GET  /session  -> {"ok": true, "logged_in": bool, "token": str|None, "user_level": int,
                     "cookie_header": str, "cookies_txt": str, "user_agent": str}
  POST /login    -> {"ok": true, "logged_in": true, "user_level": int}
  POST /logout   -> {"ok": true}
"""

import argparse
import glob
import json
import os
import queue
import subprocess
import sys
import threading
import time
import traceback
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# 清除宿主机可能配置的欧美 HTTP_PROXY / HTTPS_PROXY，避免 Viu 将出口识别为不支持地区（/ott/no-service/）
for _env_key in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"):
    os.environ.pop(_env_key, None)

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

SITE_URL = "https://www.viu.com/ott/sg/zh"
GATEWAY = "https://api-gateway-global.viu.com"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")

_INIT_STEALTH_JS = """
(() => {
  try { Object.defineProperty(navigator, 'webdriver', {get: () => false}); } catch (e) {}
})();
"""

STATE = {"logged_in": False, "user_level": 1}
STATE_LOCK = threading.Lock()


def log(msg):
    try:
        print(msg, flush=True)
    except Exception:
        pass


def _clean_token(val):
    if not val:
        return ""
    v = val.strip()
    if "%" in v:
        try:
            v = urllib.parse.unquote(v).strip()
        except Exception:
            pass
    if len(v) >= 2 and v.startswith('"') and v.endswith('"'):
        v = v[1:-1].strip()
    return v


def _verify_viu_user_info(token, cookie_header=""):
    """调用 Viu /api/user/info 验证当前 token 是否属于已登录用户，并返回 (logged_in, user_level)。"""
    if not token:
        return False, 1
    url = (GATEWAY + "/api/user/info?platform_flag_label=web&area_id=2&language_flag_id=2"
           "&platformFlagLabel=web&areaId=2&languageFlagId=2&countryCode=SG&os_flag_id=1")
    req = urllib.request.Request(url, method="GET")
    req.add_header("User-Agent", UA)
    req.add_header("Accept", "application/json, text/plain, */*")
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Origin", "https://www.viu.com")
    req.add_header("Referer", "https://www.viu.com/ott/sg/zh")
    req.add_header("Cache-Control", "no-cache, no-store, max-age=0")
    if cookie_header:
        req.add_header("Cookie", cookie_header)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=10) as resp:
            if resp.status != 200:
                return False, 1
            data = json.loads(resp.read().decode("utf-8", errors="replace"))
            user = (data.get("data") or {}).get("user") or {}
            uid = str(user.get("user_id") or user.get("userId") or "").strip()
            level = int(user.get("user_level") or user.get("userLevel") or 1)
            if uid and uid != "0" and uid.lower() != "null":
                return True, max(level, 1)
    except Exception as e:
        log("[viu-sidecar] /api/user/info 校验异常: %s" % str(e)[:160])
    return False, 1


class ViuBrowserWorker:
    """单线程串行执行全部 Playwright 操作，避免跨线程调用 sync_playwright。"""

    def __init__(self, profile_dir):
        self.profile_dir = profile_dir
        self.pw = None
        self.ctx = None
        self.page = None
        self.is_headless = True
        self._cookies = []
        self._tasks = queue.Queue()
        self._thread = threading.Thread(target=self._loop, name="viu-playwright-worker", daemon=True)
        self._thread.start()

    def submit(self, fn, *args, timeout=330):
        done = queue.Queue(maxsize=1)
        self._tasks.put((fn, args, done))
        ok, payload = done.get(timeout=timeout)
        if ok:
            return payload
        raise payload

    def _loop(self):
        while True:
            fn, args, done = self._tasks.get()
            try:
                res = fn(*args)
                done.put((True, res))
            except Exception as e:
                done.put((False, e))

    def _cookie_backup_path(self):
        return os.path.join(self.profile_dir, ".sidecar_cookies.json")

    def _profile_chrome_pids(self):
        if sys.platform != "win32":
            return []
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

    def _clean_profile_locks(self):
        for pattern in ("SingletonLock", "SingletonCookie", "SingletonSocket", "Singleton*"):
            for f in glob.glob(os.path.join(self.profile_dir, pattern)):
                try:
                    os.remove(f)
                except Exception:
                    pass

    def _ensure_profile_released(self):
        deadline = time.time() + 6
        while time.time() < deadline:
            if not self._profile_chrome_pids():
                break
            time.sleep(0.8)
        for pid in self._profile_chrome_pids():
            try:
                subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)],
                               capture_output=True, timeout=8)
            except Exception:
                pass
        self._clean_profile_locks()

    def _ensure_pw(self):
        if self.pw is None:
            from playwright.sync_api import sync_playwright
            self.pw = sync_playwright().start()

    def _launch(self, headless):
        os.makedirs(self.profile_dir, exist_ok=True)
        self._ensure_pw()
        args = ["--no-proxy-server"]
        if not headless:
            args += ["--start-maximized", "--window-position=0,0"]
        self.is_headless = headless
        last_err = None
        for _ in range(3):
            try:
                viewport = {"width": 1440, "height": 900} if headless else None
                self.ctx = self.pw.chromium.launch_persistent_context(
                    user_data_dir=self.profile_dir,
                    channel="chrome",
                    headless=headless,
                    args=args,
                    viewport=viewport,
                    no_viewport=not headless,
                    user_agent=UA,
                    ignore_default_args=["--enable-automation", "--disable-component-update"],
                )
                self.ctx.add_init_script(_INIT_STEALTH_JS)
                if not self._cookies:
                    backup = self._cookie_backup_path()
                    if os.path.exists(backup):
                        try:
                            with open(backup, "r", encoding="utf-8") as f:
                                self._cookies = json.load(f)
                        except Exception:
                            self._cookies = []
                if self._cookies:
                    try:
                        self.ctx.add_cookies(self._cookies)
                    except Exception:
                        pass
                self.page = self.ctx.pages[0] if self.ctx.pages else self.ctx.new_page()
                self.page.set_default_timeout(45000)
                return
            except Exception as e:
                last_err = e
                self._clean_profile_locks()
                time.sleep(1.5)
        raise RuntimeError("启动 Viu Chrome 失败（可能 profile 被占用）：" + str(last_err)[:260])

    def _close_ctx(self, skip_export=False):
        if self.ctx is not None:
            if not skip_export:
                try:
                    self._cookies = self.ctx.cookies()
                    with open(self._cookie_backup_path(), "w", encoding="utf-8") as f:
                        json.dump(self._cookies, f)
                except Exception:
                    pass
            try:
                for p in list(self.ctx.pages):
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
        self._ensure_profile_released()

    def _ensure_browser(self, headless=True):
        if self.ctx is not None and self.page is not None:
            try:
                self.page.evaluate("1")
                if self.is_headless == headless:
                    return
            except Exception:
                pass
        self._close_ctx()
        self._launch(headless=headless)

    def _extract_viu_cookies(self):
        if self.ctx is None:
            return [], "", ""
        try:
            all_cookies = self.ctx.cookies()
        except Exception:
            return [], "", ""
        viu_cookies = [c for c in all_cookies if "viu.com" in (c.get("domain") or "").lower()]
        header_parts = []
        lines = ["# Netscape HTTP Cookie File", "# Generated by clipfetch viu sidecar", ""]
        seen = set()
        token_val = ""
        user_id_cookie = ""
        user_level_cookie = 1
        for c in viu_cookies:
            domain = c.get("domain") or ""
            path = c.get("path") or "/"
            name = c.get("name") or ""
            value = c.get("value") or ""
            if not name:
                continue
            key = (domain, path, name)
            if key in seen:
                continue
            seen.add(key)
            header_parts.append("%s=%s" % (name, value))
            lname = name.lower()
            if lname == "token":
                token_val = _clean_token(value)
            elif lname == "authtoken" and not token_val:
                token_val = _clean_token(value)
            elif lname == "user_id":
                user_id_cookie = _clean_token(value)
            elif lname == "user_level":
                try:
                    user_level_cookie = int(_clean_token(value))
                except Exception:
                    pass
            include_sub = "TRUE" if domain.startswith(".") else "FALSE"
            secure = "TRUE" if c.get("secure") else "FALSE"
            expires = int(c.get("expires") or 0)
            if expires < 0:
                expires = 0
            dom = ("#HttpOnly_" + domain) if c.get("httpOnly") else domain
            lines.append("\t".join([dom, include_sub, path, secure, str(expires), name, value]))
        cookie_header = "; ".join(header_parts)
        cookies_txt = "\n".join(lines) + "\n" if len(lines) > 3 else ""
        return viu_cookies, cookie_header, cookies_txt, token_val, user_id_cookie, user_level_cookie

    def _persist_now(self):
        if self.ctx is not None:
            try:
                self._cookies = self.ctx.cookies()
                with open(self._cookie_backup_path(), "w", encoding="utf-8") as f:
                    json.dump(self._cookies, f)
            except Exception:
                pass

    def _inspect_auth(self):
        viu_cookies, cookie_header, cookies_txt, token_val, uid_ck, ulevel_ck = self._extract_viu_cookies()
        if not token_val:
            return {
                "logged_in": False,
                "token": None,
                "user_level": 1,
                "cookie_header": "",
                "cookies_txt": "",
                "user_agent": UA,
            }
        logged_in, api_level = _verify_viu_user_info(token_val, cookie_header)
        if not logged_in and uid_ck and uid_ck not in ("0", "null", "undefined"):
            logged_in = True
            api_level = max(ulevel_ck, 1)
        if not logged_in:
            return {
                "logged_in": False,
                "token": None,
                "user_level": 1,
                "cookie_header": "",
                "cookies_txt": "",
                "user_agent": UA,
            }
        return {
            "logged_in": True,
            "token": token_val,
            "user_level": max(api_level, ulevel_ck, 1),
            "cookie_header": cookie_header,
            "cookies_txt": cookies_txt,
            "user_agent": UA,
        }

    def session(self):
        self._ensure_browser(headless=True)
        info = self._inspect_auth()
        if not info["logged_in"]:
            # 若磁盘有历史 cookie 但 token 需要页面刷新，尝试静默打开一次站点触发前端刷新
            if os.path.exists(self._cookie_backup_path()):
                try:
                    self.page.goto(SITE_URL, wait_until="domcontentloaded", timeout=30000)
                    time.sleep(2)
                    info = self._inspect_auth()
                    if info["logged_in"]:
                        self._persist_now()
                except Exception as e:
                    log("[viu-sidecar] 无头刷新会话异常: %s" % str(e)[:120])
        with STATE_LOCK:
            STATE["logged_in"] = info["logged_in"]
            STATE["user_level"] = info["user_level"]
        return info

    def login(self, timeout=300):
        self._ensure_browser(headless=False)
        try:
            self.page.goto(SITE_URL, wait_until="domcontentloaded", timeout=60000)
            try:
                self.page.bring_to_front()
            except Exception:
                pass
            deadline = time.time() + timeout
            while time.time() < deadline:
                # 若用户手动关闭了窗口则提前退出
                if not self.ctx.pages or self.page.is_closed():
                    break
                info = self._inspect_auth()
                if info["logged_in"]:
                    self._persist_now()
                    with STATE_LOCK:
                        STATE["logged_in"] = True
                        STATE["user_level"] = info["user_level"]
                    log("[viu-sidecar] 登录成功: user_level=%s" % info["user_level"])
                    return info
                time.sleep(2)
            return None
        finally:
            # 登录结束（成功或超时）均切回无头常驻，避免桌面残留有头窗口
            try:
                self._close_ctx(skip_export=False)
                self._launch(headless=True)
            except Exception as e:
                log("[viu-sidecar] 恢复无头实例异常: %s" % str(e)[:160])

    def logout(self):
        if self.ctx is not None:
            try:
                if self.page and not self.page.is_closed():
                    self.page.evaluate(
                        "() => { try { localStorage.clear(); sessionStorage.clear(); } catch (e) {} }")
            except Exception:
                pass
            try:
                self.ctx.clear_cookies()
            except Exception:
                pass
        self._close_ctx(skip_export=True)
        self._cookies = []
        deadline = time.time() + 5
        while True:
            files = []
            for pattern in ("Default/Network/Cookies*", "Default/Cookies*", ".sidecar_cookies.json"):
                files.extend(glob.glob(os.path.join(self.profile_dir, pattern)))
            if not files:
                break
            for f in files:
                try:
                    os.remove(f)
                except Exception:
                    pass
            if time.time() >= deadline:
                break
            time.sleep(0.4)
        self._launch(headless=True)
        with STATE_LOCK:
            STATE["logged_in"] = False
            STATE["user_level"] = 1
        return True


class Handler(BaseHTTPRequestHandler):
    worker = None

    def log_message(self, fmt, *args):
        log("[viu-sidecar] " + (fmt % args))

    def _send(self, payload, status=200):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/health":
            with STATE_LOCK:
                self._send({
                    "ok": True,
                    "logged_in": STATE["logged_in"],
                    "user_level": STATE["user_level"],
                })
            return
        if path == "/session":
            try:
                info = self.worker.submit(self.worker.session, timeout=45)
                payload = {"ok": True}
                payload.update(info)
                self._send(payload)
            except Exception as e:
                log(traceback.format_exc())
                self._send({"ok": False, "logged_in": False, "error": str(e)})
            return
        self._send({"ok": False, "error": "unknown path"}, 404)

    def do_POST(self):
        path = self.path.split("?")[0]
        length = int(self.headers.get("Content-Length") or 0)
        if length > 0:
            self.rfile.read(length)
        if path == "/login":
            try:
                info = self.worker.submit(self.worker.login, timeout=330)
                if info and info.get("logged_in"):
                    self._send({
                        "ok": True,
                        "logged_in": True,
                        "user_level": info.get("user_level", 2),
                    })
                else:
                    self._send({"ok": False, "error": "等待登录超时或窗口已关闭，请重试"})
            except Exception as e:
                log(traceback.format_exc())
                self._send({"ok": False, "error": str(e)})
            return
        if path == "/logout":
            try:
                self.worker.submit(self.worker.logout, timeout=90)
                self._send({"ok": True})
            except Exception as e:
                log(traceback.format_exc())
                self._send({"ok": False, "error": str(e)})
            return
        self._send({"ok": False, "error": "unknown path"}, 404)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8100)
    parser.add_argument("--profile-dir", default="viu-profile")
    args = parser.parse_args()

    Handler.worker = ViuBrowserWorker(args.profile_dir)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    log("Viu sidecar 监听 127.0.0.1:%d (profile=%s)" % (args.port, args.profile_dir))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
