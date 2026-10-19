#!/usr/bin/env python3
"""Read-only signed HTTP Range gateway. Works on Windows or Linux (Python 3.9+)."""
import hashlib, hmac, ipaddress, os, re, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

ROOT = Path(os.environ.get("QBT_FILE_ROOT", "/srv/qbittorrent/downloads")).resolve()
SECRET = os.environ.get("QBT_FILE_GATEWAY_SECRET", "")
BIND = os.environ.get("QBT_FILE_GATEWAY_BIND", "127.0.0.1")
PORT = int(os.environ.get("QBT_FILE_GATEWAY_PORT", "18766"))
ALLOW_REMOTE = os.environ.get("QBT_FILE_GATEWAY_ALLOW_REMOTE", "false").lower() == "true"
ALLOWED_IPS = os.environ.get("QBT_FILE_GATEWAY_ALLOWED_IPS", "127.0.0.1,::1")
RANGE_PATTERN = re.compile(r"^bytes=(?:(\d+)-(\d*)|-(\d+))$")

def secure_path(relative: str) -> Path:
    if (not relative or relative.startswith("/") or "\\" in relative or "\x00" in relative
            or any(part in ("", ".", "..") for part in relative.split("/"))):
        raise ValueError("Invalid relative path")
    target = (ROOT / relative).resolve(strict=True)
    if not target.is_relative_to(ROOT) or not target.is_file():
        raise ValueError("Outside download directory")
    return target

def byte_range(header: str, size: int):
    if not header:
        return 0, size - 1, False
    match = RANGE_PATTERN.fullmatch(header)
    if not match or size < 1:
        raise ValueError("Invalid range")
    if match.group(3) is not None:
        suffix = int(match.group(3))
        if suffix < 1:
            raise ValueError("Invalid suffix")
        start, end = max(0, size - suffix), size - 1
    else:
        start = int(match.group(1))
        end = min(size - 1, int(match.group(2))) if match.group(2) else size - 1
        if start >= size or end < start:
            raise ValueError("Unsatisfiable range")
    return start, end, True

class GatewayHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "ClipFetchGateway/1.0"

    def error_response(self, status: int, content_range: str = ""):
        self.send_response(status)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", "0")
        if content_range:
            self.send_header("Content-Range", content_range)
        self.end_headers()

    def do_GET(self):
        self._headers_committed = False
        try:
            peer = ipaddress.ip_address(self.client_address[0])
            networks = [ipaddress.ip_network(item.strip(), strict=False)
                        for item in ALLOWED_IPS.split(",") if item.strip()]
            if not networks or not any(peer in network for network in networks):
                return self.error_response(403)
        except ValueError:
            return self.error_response(403)
        parsed = urlsplit(self.path)
        if parsed.path != "/internal/files":
            return self.error_response(404)
        try:
            args = parse_qs(parsed.query, keep_blank_values=True, strict_parsing=True)
            if set(args) != {"path"} or len(args["path"]) != 1:
                return self.error_response(400)
            relative = args["path"][0]
            timestamp = self.headers.get("X-Clipfetch-Time", "")
            signature = self.headers.get("X-Clipfetch-Signature", "")
            requested_range = self.headers.get("Range", "")
            if (not timestamp.isascii() or not timestamp.isdigit()
                    or len(timestamp) > 14 or abs(time.time() - int(timestamp)) > 90):
                return self.error_response(403)
            payload = "GET\n" + relative + "\n" + timestamp + "\n" + requested_range
            expected = hmac.new(SECRET.encode("utf-8"), payload.encode("utf-8"), hashlib.sha256).hexdigest()
            if not hmac.compare_digest(expected, signature):
                return self.error_response(403)
            file_path = secure_path(relative)
            size = file_path.stat().st_size
            try:
                start, end, partial = byte_range(requested_range, size)
            except ValueError:
                return self.error_response(416, f"bytes */{size}")
            count = end - start + 1
            self.send_response(206 if partial else 200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(count))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Cache-Control", "private, no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            if partial:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            self._headers_committed = True
            try:
                with file_path.open("rb") as f:
                    f.seek(start)
                    while count:
                        data = f.read(min(count, 256 * 1024))
                        if not data:
                            break
                        self.wfile.write(data)
                        count -= len(data)
            except (BrokenPipeError, ConnectionResetError, TimeoutError):
                pass
        except FileNotFoundError:
            if not self._headers_committed:
                self.error_response(404)
            else:
                self.close_connection = True
        except (ValueError, OSError):
            if not self._headers_committed:
                self.error_response(403)
            else:
                self.close_connection = True
        except Exception:
            # If response is already committed, do not send a second response.
            try:
                if not self.wfile.closed:
                    self.close_connection = True
            except OSError:
                pass

    def log_message(self, format, *args):
        # Do not log signed paths or source file names.
        pass

def main():
    if len(SECRET) < 32:
        raise SystemExit("QBT_FILE_GATEWAY_SECRET must be at least 32 characters")
    if not ROOT.exists() or not ROOT.is_dir():
        raise SystemExit("QBT_FILE_ROOT is missing or not a directory")
    if not (1 <= PORT <= 65535):
        raise SystemExit("QBT_FILE_GATEWAY_PORT must be between 1 and 65535")
    try:
        networks = [ipaddress.ip_network(item.strip(), strict=False)
                    for item in ALLOWED_IPS.split(",") if item.strip()]
    except ValueError:
        raise SystemExit("QBT_FILE_GATEWAY_ALLOWED_IPS must contain valid IPs/CIDRs")
    if not networks:
        raise SystemExit("QBT_FILE_GATEWAY_ALLOWED_IPS must not be empty")
    if any(network.prefixlen == 0 for network in networks):
        raise SystemExit("Refusing a global /0 IP allowlist")
    if BIND not in ("127.0.0.1", "::1", "localhost"):
        if not ALLOW_REMOTE:
            raise SystemExit("Remote binding requires QBT_FILE_GATEWAY_ALLOW_REMOTE=true")
        if not any(not network.is_loopback for network in networks):
            raise SystemExit("Remote binding requires the Spring Boot server IP in QBT_FILE_GATEWAY_ALLOWED_IPS")
    httpd = ThreadingHTTPServer((BIND, PORT), GatewayHandler)
    print(f"ClipFetch read-only gateway on {BIND}:{PORT}", flush=True)
    httpd.serve_forever()

if __name__ == "__main__":
    main()
