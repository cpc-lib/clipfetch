#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""酷播（kubb.tv）浏览器 sidecar。

酷播的 1080p / 720p 是 VIP 专属清晰度，接口会按登录态回退到游客可用的 576p；
登录页接 Cloudflare Turnstile，无法在服务端模拟，因此用真实 Chrome（Playwright +
persistent profile）让用户手动登录一次，再把 sessionStorage 里的登录 token
（uid/expire/gid/sign/token）交给 Java 侧拼进接口请求。

接口：
  GET  /health  -> {"ok": true, "logged_in": bool}
  POST /login   -> 弹出有头 Chrome 等用户手动登录（最多 300 秒）
  GET  /token   -> {"ok": true, "logged_in": bool, "token": {...} | null, "user_agent": str}
  POST /logout  -> 清空 cookie 与 storage
"""

import argparse
import json
import os
import sys
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SITE = "https://www.kubb.tv/"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

STATE = {"logged_in": False, "last_error": ""}
STATE_LOCK = threading.Lock()
BROWSER_LOCK = threading.RLock()


def log(msg):
    print(msg, flush=True)


class KubbBrowser:
    def __init__(self, profile_dir):
        self.profile_dir = profile_dir
        self._pw = None
        self.ctx = None
        self.page = None

    def ensure(self):
        if self.ctx is not None:
            try:
                _ = self.ctx.pages
                if self.page is None or self.page.is_closed():
                    self.page = self.ctx.pages[0] if self.ctx.pages else self.ctx.new_page()
                return
            except Exception:
                self.ctx = None
                self.page = None
        from playwright.sync_api import sync_playwright
        os.makedirs(self.profile_dir, exist_ok=True)
        log("启动酷播 sidecar Chrome: profile=%s" % self.profile_dir)
        self._pw = sync_playwright().start()
        self.ctx = self._pw.chromium.launch_persistent_context(
            user_data_dir=self.profile_dir,
            channel="chrome",
            headless=False,
            no_viewport=True,
            locale="zh-CN",
            user_agent=UA,
            args=["--disable-blink-features=AutomationControlled"],
        )
        self.page = self.ctx.pages[0] if self.ctx.pages else self.ctx.new_page()
        self.page.set_default_timeout(45000)

    def _on_site(self):
        try:
            return self.page.url.startswith("https://www.kubb.tv")
        except Exception:
            return False

    def open_site(self):
        self.ensure()
        if not self._on_site():
            self.page.goto(SITE, wait_until="domcontentloaded", timeout=90000)
        try:
            self.page.bring_to_front()
        except Exception:
            pass

    def read_token(self):
        try:
            raw = self.page.evaluate(
                "() => { try { return sessionStorage.getItem('__t') } catch (e) { return null } }")
        except Exception:
            return None
        if not raw:
            return None
        try:
            obj = json.loads(raw)
        except Exception:
            return None
        if not isinstance(obj, dict) or not obj.get("token"):
            return None
        return obj

    def token(self):
        """被动检查：仅读取当前已有的登录 token，不打开站点、不等待。

        未登录（含重启后 sessionStorage 为空）时立即返回 None；由调用方决定是否
        走 login() 流程。避免在下载链路里弹出 Chrome / 长轮询阻塞用户操作。
        """
        self.ensure()
        return self.read_token()

    def login(self, timeout=300):
        self.open_site()
        deadline = time.time() + timeout
        while time.time() < deadline:
            tok = self.read_token()
            if tok:
                return tok
            time.sleep(2)
        return None

    def logout(self):
        self.ensure()
        try:
            self.ctx.clear_cookies()
        except Exception:
            pass
        try:
            self.page.evaluate(
                "() => { try { sessionStorage.clear(); localStorage.clear() } catch (e) {} }")
        except Exception:
            pass
        try:
            self.page.goto(SITE, wait_until="domcontentloaded", timeout=60000)
        except Exception:
            pass
        return True


class Handler(BaseHTTPRequestHandler):
    browser = None

    def log_message(self, fmt, *args):
        log("[kubb-sidecar] " + (fmt % args))

    def _send(self, payload, status=200):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _mark(self, tok, error=""):
        with STATE_LOCK:
            STATE["logged_in"] = bool(tok)
            STATE["last_error"] = error

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/health":
            with STATE_LOCK:
                self._send({"ok": True, "logged_in": STATE["logged_in"]})
            return
        if path == "/token":
            try:
                with BROWSER_LOCK:
                    tok = self.browser.token()
                self._mark(tok)
                self._send({"ok": True, "logged_in": bool(tok), "token": tok, "user_agent": UA})
            except Exception as e:
                log(traceback.format_exc())
                self._mark(None, str(e))
                self._send({"ok": False, "logged_in": False, "error": str(e)})
            return
        self._send({"ok": False, "error": "unknown path"}, 404)

    def do_POST(self):
        path = self.path.split("?")[0]
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        if path == "/login":
            try:
                with BROWSER_LOCK:
                    tok = self.browser.login()
                self._mark(tok)
                if tok:
                    self._send({"ok": True, "logged_in": True})
                else:
                    self._send({"ok": False, "error": "等待登录超时（300 秒），请重试"})
            except Exception as e:
                log(traceback.format_exc())
                self._mark(None, str(e))
                self._send({"ok": False, "error": str(e)})
            return
        if path == "/logout":
            try:
                with BROWSER_LOCK:
                    self.browser.logout()
                self._mark(None)
                self._send({"ok": True})
            except Exception as e:
                log(traceback.format_exc())
                self._send({"ok": False, "error": str(e)})
            return
        self._send({"ok": False, "error": "unknown path"}, 404)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--profile-dir", default="kubb-profile")
    args = parser.parse_args()

    Handler.browser = KubbBrowser(args.profile_dir)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    log("酷播 sidecar 监听 127.0.0.1:%d" % args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
