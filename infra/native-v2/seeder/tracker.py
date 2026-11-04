#!/usr/bin/env python3
"""Minimal deterministic HTTP tracker used only by the local acceptance swarm."""
import struct
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PEERS = {}
INTERVAL = 30


class AnnounceHandler(BaseHTTPRequestHandler):
    server_version = "clipfetch-acceptance-tracker"

    def log_message(self, fmt, *args):
        # 只记录方法+路径：announce 查询串含 info_hash/peer_id，不写入日志。
        print("[tracker] " + (fmt % args).split("?", 1)[0], flush=True)

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path != "/announce":
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"clipfetch acceptance tracker\n")
            return

        params = parse_qs(parsed.query, keep_blank_values=True)
        info_hash = params.get("info_hash", [""])[0]
        peer_id = params.get("peer_id", [""])[0]
        port = int(params.get("port", ["0"])[0])
        event = params.get("event", [""])[0]
        if not info_hash or port <= 0:
            self.send_response(400)
            self.end_headers()
            return

        if event == "stopped":
            PEERS.setdefault(info_hash, {}).pop(peer_id, None)
        else:
            ip = params.get("ipaddress", [""])[0] or self.client_address[0]
            PEERS.setdefault(info_hash, {})[peer_id] = (ip, port)

        compact = b"".join(
            socket_address(ip, peer_port)
            for other_id, (ip, peer_port) in PEERS.get(info_hash, {}).items()
            if other_id != peer_id
        )
        body = (b"d8:completei0e10:incompletei0e8:intervali" + str(INTERVAL).encode()
                + b"e12:min intervali10e5:peers" + str(len(compact)).encode() + b":" + compact + b"e")
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def socket_address(ip, port):
    octets = [int(part) for part in ip.split(".")]
    if len(octets) != 4 or any(value < 0 or value > 255 for value in octets):
        return b""
    return bytes(octets) + struct.pack(">H", port)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8000), AnnounceHandler).serve_forever()
    sys.exit(0)
