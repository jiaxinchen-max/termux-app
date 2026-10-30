# SPDX-License-Identifier: MIT
import base64
import errno
import http.client
import importlib.util
import os
from pathlib import Path
import socket
import sys
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parent.parent
LIBRARY = sys.argv.pop(1)
spec = importlib.util.spec_from_file_location("filesystem", ROOT / "filesystem.py")
module = importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class FilesystemTests(unittest.TestCase):
    def test_volume_capacity_and_sync(self):
        before = self.fs.volume_info()
        self.assertEqual(before["type"], "FAT32")
        self.assertTrue(before["writable"])
        self.assertGreater(before["size"], 32 * 1024 * 1024)
        self.assertLessEqual(before["free"], before["size"])
        self.fs.write("/space.bin", b"x" * 131123, truncate=True)
        after = self.fs.volume_info()
        self.assertGreater(before["free"], after["free"])
        self.fs.check(self.fs.lib.tusb_fs_sync())

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.image = str(Path(self.temp.name) / "disk.img")
        creator = module.Filesystem(library=LIBRARY)
        creator.check(creator.lib.tusb_fs_create_image(os.fsencode(self.image), 64 * 1024 * 1024, 0))
        self.fs = module.Filesystem(image=self.image, writable=True, library=LIBRARY)

    def tearDown(self):
        self.fs.close();self.temp.cleanup()

    def test_fat_unicode_binary_and_persistence(self):
        self.fs.mkdir("/目录")
        data = bytes(range(256)) * 500
        self.fs.write("/目录/hello.bin", data, truncate=True)
        self.assertEqual(self.fs.read("/目录/hello.bin", 99999, 321), data[321:321+99999])
        self.assertEqual(self.fs.stat("/目录/hello.bin")["size"], len(data))
        self.assertEqual(self.fs.list("/")[0]["name"], "目录")
        self.fs.rename("/目录/hello.bin", "/目录/renamed.bin")
        self.fs.close()
        self.fs = module.Filesystem(image=self.image, library=LIBRARY)
        self.assertEqual(self.fs.read("/目录/renamed.bin", len(data)), data)
        with self.assertRaises(OSError) as error: self.fs.write("/x", b"x")
        self.assertEqual(error.exception.errno, errno.EROFS)

    def test_exfat_roundtrip(self):
        self.fs.close()
        image = str(Path(self.temp.name) / "exfat.img")
        self.fs.check(self.fs.lib.tusb_fs_create_image(os.fsencode(image), 64 * 1024 * 1024, 1))
        self.fs = module.Filesystem(image=image, writable=True, library=LIBRARY)
        self.fs.mkdir("/exfat")
        self.fs.write("/exfat/长文件名-😀.txt", b"exfat", truncate=True)
        self.assertEqual(self.fs.read("/exfat/长文件名-😀.txt", 100), b"exfat")

    def test_truncate_patch_delete_and_no_overwrite_image(self):
        self.fs.write("/data", b"abcdefgh", truncate=True)
        self.fs.write("/data", b"XY", 2)
        self.assertEqual(self.fs.read("/data", 20), b"abXYefgh")
        self.fs.write("/data", b"a", truncate=True)
        self.assertEqual(self.fs.read("/data", 20), b"a")
        self.fs.unlink("/data")
        with self.assertRaises(FileNotFoundError): self.fs.stat("/data")
        self.fs.close()
        with self.assertRaises(FileExistsError):
            self.fs.check(self.fs.lib.tusb_fs_create_image(os.fsencode(self.image), 64 * 1024 * 1024, 0))

    def test_invalid_image_and_paths(self):
        for path in ("../x", "/../x", "/a/../x", "/0:/x", "/a\\b"):
            with self.assertRaises(OSError): self.fs.stat(path)
        self.fs.close()
        invalid = Path(self.temp.name) / "invalid.img";invalid.write_bytes(b"invalid")
        with self.assertRaises(OSError): module.Filesystem(image=str(invalid), library=LIBRARY)


class WebDAVTests(unittest.TestCase):
    def setUp(self):
        FilesystemTests.setUp(self)
        self.server = module.ThreadingHTTPServer(("127.0.0.1", 0), module.WebDAV)
        self.server.filesystem,self.server.password = self.fs,"test-secret"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True);self.thread.start()

    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.thread.join();FilesystemTests.tearDown(self)

    def request(self, method, path, body=None, headers=None, authenticated=True):
        headers = dict(headers or {})
        if authenticated: headers["Authorization"] = "Basic " + base64.b64encode(b"tusb:test-secret").decode()
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse();result=(response.status,response.read(),dict(response.getheaders()))
        connection.close();return result

    def test_webdav_auth_and_methods(self):
        self.assertEqual(self.request("GET", "/", authenticated=False)[0], 401)
        self.assertEqual(self.request("MKCOL", "/folder")[0], 201)
        data = bytes(range(256)) * 1000
        self.assertEqual(self.request("PUT", "/folder/file", data)[0], 201)
        self.assertEqual(self.request("GET", "/folder/file")[1], data)
        status, body, headers = self.request("GET", "/folder/file", headers={"Range": "bytes=100-199"})
        self.assertEqual((status,body), (206,data[100:200]));self.assertIn("Content-Range", headers)
        self.assertEqual(self.request("PROPFIND", "/folder", headers={"Depth":"1"})[0],207)
        self.assertIn(b"file",self.request("PROPFIND", "/folder")[1])
        self.assertEqual(self.request("COPY", "/folder/file", headers={"Destination":"/copied"})[0],201)
        self.assertEqual(self.request("MOVE", "/copied", headers={"Destination":"/moved"})[0],201)
        self.assertEqual(self.request("GET", "/moved")[1],data)
        self.assertEqual(self.request("DELETE", "/folder")[0],204)
        self.assertEqual(self.request("GET", "/folder/file")[0],404)

    def test_webdav_replace_and_reject_cross_server_move(self):
        self.assertEqual(self.request("PUT", "/file", b"old")[0],201)
        self.assertEqual(self.request("PUT", "/file", b"new")[0],204)
        self.assertEqual(self.request("GET", "/file")[1],b"new")
        self.assertEqual(self.request("MOVE", "/file", headers={"Destination":"http://other.example/file"})[0],400)
        self.assertEqual(self.request("GET", "/%2e%2e/file")[0],400)
        self.assertEqual(self.request("GET", "/file", headers={"Range":"bytes=99-100"})[0],416)

    def test_kio_destination_userinfo_and_origin_checks(self):
        self.assertEqual(self.request("PUT", "/file", b"kio payload")[0], 201)
        origin = f"http://tusb:test-secret@127.0.0.1:{self.server.server_port}"
        self.assertEqual(self.request("MOVE", "/file", headers={"Destination": origin + "/renamed"})[0], 201)
        self.assertEqual(self.request("COPY", "/renamed", headers={"Destination": origin + "/copied"})[0], 201)
        self.assertEqual(self.request("GET", "/copied")[1], b"kio payload")
        for target in (f"http://tusb:test-secret@other.example:{self.server.server_port}/escaped",
                       f"http://tusb:test-secret@127.0.0.1:{self.server.server_port - 1}/escaped"):
            self.assertEqual(self.request("MOVE", "/renamed", headers={"Destination": target})[0], 400)
        self.assertEqual(self.request("GET", "/renamed")[1], b"kio payload")
        self.assertEqual(self.request("MOVE", "/renamed", headers={"Destination": origin + "/unauthenticated"},
                                      authenticated=False)[0], 401)

    def test_large_unauthenticated_upload_can_retry(self):
        payload = bytes(range(256)) * 4096
        self.assertEqual(self.request("PUT", "/upload", payload, authenticated=False)[0], 401)
        self.assertEqual(self.request("GET", "/upload")[0], 404)
        self.assertEqual(self.request("PUT", "/upload", payload)[0], 201)
        self.assertEqual(self.request("GET", "/upload")[1], payload)

    def test_expect_continue_checks_auth_before_request_body(self):
        for authenticated in (False, True):
            with socket.create_connection(("127.0.0.1", self.server.server_port), timeout=2) as sock:
                headers = ["PUT /expect HTTP/1.1", f"Host: 127.0.0.1:{self.server.server_port}",
                           "Content-Length: 3", "Expect: 100-continue", "Connection: close"]
                if authenticated:
                    headers.append("Authorization: Basic " + base64.b64encode(b"tusb:test-secret").decode())
                sock.sendall(("\r\n".join(headers) + "\r\n\r\n").encode())
                with sock.makefile("rb") as stream:
                    def status():
                        line = stream.readline()
                        while stream.readline() not in (b"\r\n", b"\n", b""):
                            pass
                        return int(line.split()[1])
                    self.assertEqual(status(), 100 if authenticated else 401)
                    if authenticated:
                        sock.sendall(b"abc")
                        self.assertEqual(status(), 201)
        self.assertEqual(self.request("GET", "/expect")[1], b"abc")


if __name__ == "__main__": unittest.main(verbosity=2)
