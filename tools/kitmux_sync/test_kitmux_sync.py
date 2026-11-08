"""Tests: python -m unittest test_kitmux_sync (desde tools/kitmux_sync)."""

import json
import os
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock
from urllib.parse import unquote

import kitmux_sync as ks


class PureLogicTest(unittest.TestCase):
    def test_is_syncable(self):
        self.assertTrue(ks.is_syncable("Rock/Canción.MP3"))
        self.assertFalse(ks.is_syncable("Rock/cover.jpg"))
        self.assertFalse(ks.is_syncable(".oculta/a.mp3"))
        self.assertFalse(ks.is_syncable("Rock/.a.mp3"))
        self.assertFalse(ks.is_syncable("Rock/a:b.mp3"))
        self.assertFalse(ks.is_syncable("sin_extension"))

    def test_files_url_encodes_utf8_and_plus(self):
        self.assertEqual(ks.files_url("Rock/Canción +1.mp3"), "/files/Rock/Canci%C3%B3n%20%2B1.mp3")

    def test_plan_uploads_new_and_changed_and_deletes_missing(self):
        local = {"a.mp3": (10, 1, 7), "b.mp3": (20, 2, 8), "c.mp3": (30, 3, 9)}
        remote = {"a.mp3": 10, "b.mp3": 99, "old.mp3": 5}
        state = {"a.mp3": (10, 1), "b.mp3": (99, 1), "old.mp3": (5, 9)}
        plan = ks.compute_plan(local, set(), remote, state)
        self.assertEqual(plan.uploads, ["b.mp3", "c.mp3"])
        self.assertEqual(plan.deletes, ["old.mp3"])
        self.assertEqual(plan.moves, [])

    def test_plan_detects_same_size_modification_via_state(self):
        plan = ks.compute_plan({"a.mp3": (10, 2, 7)}, set(), {"a.mp3": 10}, {"a.mp3": (10, 1, 7)})
        self.assertEqual(plan.uploads, ["a.mp3"])

    def test_plan_adopts_identical_files_without_state(self):
        plan = ks.compute_plan({"a.mp3": (10, 1, 7)}, set(), {"a.mp3": 10}, {})
        self.assertTrue(plan.is_empty)
        self.assertEqual(plan.adopt, ["a.mp3"])

    def test_plan_detects_rename_as_move(self):
        local = {"Nuevo/a.mp3": (10, 1, 7)}
        plan = ks.compute_plan(local, set(), {"Viejo/a.mp3": 10}, {"Viejo/a.mp3": (10, 1, 7)})
        self.assertEqual(plan.moves, [("Viejo/a.mp3", "Nuevo/a.mp3")])
        self.assertEqual(plan.uploads, [])
        self.assertEqual(plan.deletes, [])

    def test_plan_no_move_when_ambiguous_or_extension_changes(self):
        local = {"x.mp3": (10, 1, 7), "y.mp3": (10, 1, 7)}
        plan = ks.compute_plan(local, set(), {"a.mp3": 10}, {"a.mp3": (10, 1, 7)})
        self.assertEqual(plan.moves, [])
        plan = ks.compute_plan({"a.flac": (10, 1, 7)}, set(), {"a.mp3": 10}, {"a.mp3": (10, 1, 7)})
        self.assertEqual(plan.moves, [])

    def test_plan_rename_one_and_delete_twin_with_same_size_and_date(self):
        # Caso real: dos archivos con igual tamaño y fecha; se renombra uno y se borra el otro.
        state = {"La.wav": (10, 1, 100), "Mi.wav": (10, 1, 200)}
        plan = ks.compute_plan({"Mi nuevo.wav": (10, 1, 200)}, set(), {"La.wav": 10, "Mi.wav": 10}, state)
        self.assertEqual(plan.moves, [("Mi.wav", "Mi nuevo.wav")])
        self.assertEqual(plan.deletes, ["La.wav"])

    def test_plan_copy_with_preserved_date_is_not_a_move(self):
        # cp -p conserva tamaño y fecha pero crea otro inodo: debe enviarse, no moverse.
        plan = ks.compute_plan({"copia.mp3": (10, 1, 999)}, set(), {"a.mp3": 10}, {"a.mp3": (10, 1, 7)})
        self.assertEqual(plan.moves, [])
        self.assertEqual(plan.uploads, ["copia.mp3"])

    def test_plan_without_inode_in_old_state_never_moves(self):
        plan = ks.compute_plan({"b.mp3": (10, 1, 7)}, set(), {"a.mp3": 10}, {"a.mp3": (10, 1)})
        self.assertEqual(plan.moves, [])

    def test_plan_keeps_files_still_being_written_and_honors_no_delete(self):
        plan = ks.compute_plan({}, {"copiando.mp3"}, {"copiando.mp3": 3, "x.mp3": 1}, {})
        self.assertEqual(plan.deletes, ["x.mp3"])
        plan = ks.compute_plan({}, set(), {"x.mp3": 1}, {}, allow_delete=False)
        self.assertEqual(plan.deletes, [])

    def test_scan_local_filters_and_marks_unstable(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "Rock").mkdir()
            (root / ".oculta").mkdir()
            for rel in ("Rock/a.mp3", "Rock/cover.jpg", ".oculta/b.mp3", "nuevo.flac"):
                (root / rel).write_bytes(b"x" * 4)
            old = time.time() - 60
            os.utime(root / "Rock/a.mp3", (old, old))
            stable, unstable = ks.scan_local(root, time.time())
            self.assertEqual(set(stable), {"Rock/a.mp3"})
            self.assertEqual(unstable, {"nuevo.flac"})


class FakePhone:
    """Implementa el protocolo del receptor de KitMux en memoria."""

    TOKEN = "secreto"

    def __init__(self):
        self.files: dict[str, bytes] = {}
        self.calls: list[str] = []
        phone = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _reply(self, status, obj):
                body = json.dumps(obj).encode()
                self.send_response(status)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def _body(self):
                return self.rfile.read(int(self.headers.get("Content-Length", 0)))

            def _authorized(self):
                if self.headers.get("Authorization") != f"Bearer {FakePhone.TOKEN}":
                    self._reply(401, {"error": "no emparejado"})
                    return False
                return True

            def do_GET(self):
                if self.path == "/info":
                    return self._reply(200, {"app": "kitmux", "id": "tel-1", "name": "KitMux Test"})
                if not self._authorized():
                    return
                phone.calls.append("manifest")
                files = [{"path": p, "size": len(b)} for p, b in phone.files.items()]
                self._reply(200, {"files": files})

            def do_PUT(self):
                body = self._body()
                if not self._authorized():
                    return
                path = unquote(self.path.removeprefix("/files/"))
                phone.calls.append(f"put {path}")
                phone.files[path] = body
                self._reply(200, {"ok": True})

            def do_DELETE(self):
                self._body()
                if not self._authorized():
                    return
                path = unquote(self.path.removeprefix("/files/"))
                phone.calls.append(f"delete {path}")
                if phone.files.pop(path, None) is None:
                    return self._reply(404, {"error": "no existe"})
                self._reply(200, {"ok": True})

            def do_POST(self):
                data = json.loads(self._body() or b"{}")
                if self.path == "/pair":
                    if data.get("code") != "123456":
                        return self._reply(403, {"error": "código incorrecto"})
                    return self._reply(200, {"token": FakePhone.TOKEN, "id": "tel-1", "name": "KitMux Test"})
                if not self._authorized():
                    return
                phone.calls.append(f"move {data['from']} {data['to']}")
                phone.files[data["to"]] = phone.files.pop(data["from"])
                self._reply(200, {"ok": True})

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class EndToEndTest(unittest.TestCase):
    def setUp(self):
        self.phone = FakePhone()
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name) / "musica"
        self.root.mkdir()
        self.device = ks.Device("127.0.0.1", self.phone.port, token=FakePhone.TOKEN)
        self.state: dict = {}

    def tearDown(self):
        self.phone.close()
        self.tmp.cleanup()

    def write(self, rel: str, data: bytes):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        old = time.time() - 60
        os.utime(path, (old, old))
        return path

    def sync(self):
        self.phone.calls.clear()
        ks.reconcile(self.device, self.root, self.state)
        return [c for c in self.phone.calls if c != "manifest"]

    def test_full_cycle(self):
        self.write("Rock/a.mp3", b"aaaa")
        self.write("Canción.flac", b"bb")
        self.write("cover.jpg", b"ignorado")
        self.assertEqual(sorted(self.sync()), ["put Canción.flac", "put Rock/a.mp3"])
        self.assertEqual(len(self.state["Rock/a.mp3"]), 3)  # tamaño, fecha, inodo
        self.assertEqual(self.phone.files["Rock/a.mp3"], b"aaaa")
        self.assertEqual(self.sync(), [])  # nada que hacer

        (self.root / "Rock").rename(self.root / "Metal")  # renombrar carpeta
        self.assertEqual(self.sync(), ["move Rock/a.mp3 Metal/a.mp3"])

        (self.root / "Canción.flac").unlink()
        self.assertEqual(self.sync(), ["delete Canción.flac"])

        path = self.write("Metal/a.mp3", b"AAAA")  # mismo tamaño, contenido nuevo
        os.utime(path, (time.time() - 30, time.time() - 30))
        self.assertEqual(self.sync(), ["put Metal/a.mp3"])
        self.assertEqual(self.phone.files, {"Metal/a.mp3": b"AAAA"})

    def test_bad_token_raises_auth_error(self):
        self.write("a.mp3", b"x")
        with self.assertRaises(ks.AuthError):
            ks.reconcile(ks.Device("127.0.0.1", self.phone.port, token="malo"), self.root, {})

    def test_unreachable_phone_raises_device_error(self):
        port = self.phone.port
        self.phone.close()
        with self.assertRaises(ks.DeviceError):
            ks.Device("127.0.0.1", port, token="x", timeout=1).manifest()

    def test_pair_command_saves_token(self):
        with mock.patch.dict(os.environ, {"KITMUX_SYNC_HOME": self.tmp.name}):
            code = ks.main(["pair", "--host", f"127.0.0.1:{self.phone.port}", "--code", "123456"])
            self.assertEqual(code, 0)
            config = json.loads((Path(self.tmp.name) / "config.json").read_text())
            self.assertEqual(config["devices"]["tel-1"]["token"], FakePhone.TOKEN)
            self.assertEqual(oct(os.stat(Path(self.tmp.name) / "config.json").st_mode & 0o777), "0o600")
            self.write("a.mp3", b"x")
            code = ks.main(["sync", str(self.root), "--once", "--host", f"127.0.0.1:{self.phone.port}"])
            self.assertEqual(code, 0)
            self.assertIn("a.mp3", self.phone.files)


if __name__ == "__main__":
    unittest.main()
