import hashlib
import hmac
import os
import tempfile
import threading
import time
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen

import gateway


class FileGatewayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        gateway.ROOT = Path(cls.temp.name).resolve()
        gateway.SECRET = "0123456789abcdef0123456789abcdef"
        (gateway.ROOT / "job").mkdir()
        (gateway.ROOT / "job" / "hello.bin").write_bytes(b"0123456789abcdef")
        # Sparse file: verify Range works above 2 GiB without loading entire file.
        with (gateway.ROOT / "job" / "large.iso").open("wb") as f:
            f.truncate(3 * 1024**3)
            f.seek(3 * 1024**3 - 4)
            f.write(b"LAST")
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), gateway.GatewayHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.server.server_port}/internal/files?path="

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.temp.cleanup()

    def request(self, path, byte_range="", timestamp=None, corrupt=False):
        stamp = str(int(time.time()) if timestamp is None else timestamp)
        payload = f"GET\n{path}\n{stamp}\n{byte_range}"
        signature = hmac.new(gateway.SECRET.encode(), payload.encode(), hashlib.sha256).hexdigest()
        if corrupt:
            signature = "0" * 64
        headers = {"X-Clipfetch-Time": stamp, "X-Clipfetch-Signature": signature}
        if byte_range:
            headers["Range"] = byte_range
        request = Request(self.base + quote(path, safe=""), headers=headers)
        try:
            with urlopen(request, timeout=10) as resp:
                return resp.status, dict(resp.headers), resp.read()
        except HTTPError as ex:
            return ex.code, dict(ex.headers), ex.read()

    def test_small_full_download(self):
        status, headers, body = self.request("job/hello.bin")
        self.assertEqual(200, status)
        self.assertEqual(b"0123456789abcdef", body)
        self.assertEqual("bytes", headers.get("Accept-Ranges"))

    def test_resume_partial_download(self):
        status, headers, body = self.request("job/hello.bin", "bytes=4-8")
        self.assertEqual(206, status)
        self.assertEqual(b"45678", body)
        self.assertEqual("bytes 4-8/16", headers.get("Content-Range"))

    def test_over_two_gigabytes_range(self):
        end = 3 * 1024**3 - 1
        status, headers, body = self.request("job/large.iso", f"bytes={end - 3}-{end}")
        self.assertEqual(206, status)
        self.assertEqual(b"LAST", body)
        self.assertEqual(f"bytes {end - 3}-{end}/{3 * 1024**3}", headers.get("Content-Range"))

    def test_source_ip_allowlist_rejects_untrusted_client(self):
        original = gateway.ALLOWED_IPS
        try:
            gateway.ALLOWED_IPS = "198.51.100.24/32"
            self.assertEqual(403, self.request("job/hello.bin")[0])
        finally:
            gateway.ALLOWED_IPS = original

    def test_bad_signature_rejected(self):
        self.assertEqual(403, self.request("job/hello.bin", corrupt=True)[0])

    def test_expired_signature_rejected(self):
        self.assertEqual(403, self.request("job/hello.bin", timestamp=1)[0])

    def test_path_traversal_rejected(self):
        self.assertEqual(403, self.request("../secret.txt")[0])

    def test_invalid_range(self):
        status, headers, body = self.request("job/hello.bin", "bytes=200-300")
        self.assertEqual(416, status)
        self.assertEqual("bytes */16", headers.get("Content-Range"))


if __name__ == "__main__":
    unittest.main()
