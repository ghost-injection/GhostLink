import hashlib
import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from native_share import Client


class TransferTests(unittest.TestCase):
    def setUp(self):
        self.content = b'\x00\xffGhost Link\r\n' * 20000
        self.item = {'id': '12345678-1234-1234-1234-123456789abc', 'name': 'sample.bin',
                     'size': len(self.content), 'sha256': hashlib.sha256(self.content).hexdigest()}
        self.uploaded = None
        self.truncate = False
        test = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_GET(self):
                if self.headers.get('Authorization') != 'Bearer ' + 'a' * 48:
                    self.send_error(403); return
                self.send_response(200)
                self.send_header('Content-Length', str(len(test.content)))
                self.end_headers()
                self.wfile.write(test.content[:100] if test.truncate else test.content)
            def do_POST(self):
                test.uploaded = self.rfile.read(int(self.headers['Content-Length']))
                sha = hashlib.sha256(test.uploaded).hexdigest()
                if self.headers.get('X-File-SHA256') != sha:
                    self.send_error(400); return
                body = json.dumps({'size': len(test.uploaded), 'sha256': sha}).encode()
                self.send_response(201)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.client = Client({'phone_ip': '203.0.113.10', 'token': 'a' * 48})
        self.client.connection = lambda: http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=3)
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'file.bin'

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.temp.cleanup()

    def test_binary_upload_and_download_integrity(self):
        self.path.write_bytes(self.content)
        self.client.upload(self.path)
        self.assertEqual(self.uploaded, self.content)
        self.client.download(self.item, self.path)
        self.assertEqual(self.path.read_bytes(), self.content)

    def test_bad_checksum_preserves_existing_destination(self):
        self.path.write_bytes(b'original')
        self.item['sha256'] = '0' * 64
        with self.assertRaisesRegex(RuntimeError, 'checksum'):
            self.client.download(self.item, self.path)
        self.assertEqual(self.path.read_bytes(), b'original')
        self.assertEqual(list(Path(self.temp.name).iterdir()), [self.path])

    def test_interrupted_download_preserves_existing_destination(self):
        self.truncate = True
        self.path.write_bytes(b'original')
        with self.assertRaises((RuntimeError, http.client.IncompleteRead)):
            self.client.download(self.item, self.path)
        self.assertEqual(self.path.read_bytes(), b'original')
        self.assertEqual(list(Path(self.temp.name).iterdir()), [self.path])

    def test_authentication_failure_does_not_write_file(self):
        self.client.token = 'wrong'
        with self.assertRaises(RuntimeError):
            self.client.download(self.item, self.path)
        self.assertFalse(self.path.exists())

    def test_untrusted_address_and_file_path_are_rejected(self):
        with self.assertRaises(ValueError):
            Client({'phone_ip': '192.168.1.1', 'token': 'a' * 48})
        self.item['id'] = '../../private'
        with self.assertRaises(ValueError):
            self.client.download(self.item, self.path)
        self.assertFalse(self.path.exists())


if __name__ == '__main__': unittest.main()
