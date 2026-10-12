"""droiddeck-bwrap against a fake SpawnServer on an abstract socket: which socket it finds, the
request it sends, the frames it answers to, and the signals it hands on (runtime/SpawnServer.kt)."""
import json
import os
import resource
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import unittest
import uuid
from pathlib import Path

BWRAP = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-bwrap"
LEGACY = "com.droiddeck.launcher.bwrap"


def frame(kind, payload=b""):
    return kind + struct.pack(">I", len(payload)) + payload


def recv_exact(conn, n):
    buf = b""
    while len(buf) < n:
        b = conn.recv(n - len(buf))
        if not b:
            return None
        buf += b
    return buf


def read_frame(conn):
    head = recv_exact(conn, 5)
    if head is None:
        return None
    size = struct.unpack(">I", head[1:])[0]
    return head[:1], recv_exact(conn, size) if size else b""


def ancestors_name_the_socket():
    """True when this process or one above it carries BL_SPAWN_SOCKET, as inside a DroidDeck guest."""
    if os.environ.get("BL_SPAWN_SOCKET"):
        return True
    pid = os.getpid()
    while pid > 1:
        try:
            if b"BL_SPAWN_SOCKET=" in Path("/proc/%d/environ" % pid).read_bytes():
                return True
            stat = Path("/proc/%d/stat" % pid).read_bytes()
            pid = int(stat[stat.rindex(b")") + 2:].split()[1])
        except (OSError, ValueError):
            return False
    return False


class FakeServer:
    """A listener on an abstract name that serves one stand-in at a time, as the app would."""

    def __init__(self, name):
        self.name = name
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.bind("\0" + name)
        self.sock.listen(1)
        self.sock.settimeout(15)

    def accept(self):
        conn, _ = self.sock.accept()
        conn.settimeout(15)
        kind, body = read_frame(conn)
        return conn, kind, json.loads(body)

    def close(self):
        self.sock.close()


class SpawnProtocolTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.app = self.tmp / "app"
        self.app.mkdir()
        self.name = "droiddeck-test-%s.spawn" % uuid.uuid4().hex
        self.server = FakeServer(self.name)
        self.addCleanup(self.server.close)

    def argv(self, *extra):
        # A sandbox that remaps something, so it goes to the app rather than running here.
        return [sys.executable, str(BWRAP), *extra, "--ro-bind", str(self.app), "/app", "--", "/app/bin/game", "--level", "2"]

    def start(self, env, *extra, **kwargs):
        return subprocess.Popen(self.argv(*extra), env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                preexec_fn=lambda: resource.setrlimit(resource.RLIMIT_CORE, (0, 0)), **kwargs)

    def env(self, **extra):
        return {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "BL_SPAWN_SOCKET": self.name, **extra}

    def test_the_socket_is_named_by_bl_spawn_socket_and_the_request_is_a_sandbox(self):
        stand_in = self.start(self.env(FLATPAK_ID="org.example.Game"))
        conn, kind, request = self.server.accept()
        with conn:
            self.assertEqual(b"R", kind)
            self.assertEqual("sandbox", request["kind"])
            self.assertEqual(["/app/bin/game", "--level", "2"], request["argv"])
            self.assertEqual("org.example.Game", request["env"]["FLATPAK_ID"])
            self.assertEqual([{"t": "bind", "src": os.path.realpath(self.app), "dest": "/app", "try": False, "ro": True}],
                             request["ops"])
            conn.sendall(frame(b"P", b"4242") + frame(b"O", b"hello\n") + frame(b"O", b"\0raw\xff") + frame(b"X", b"0"))
            out, err = stand_in.communicate(timeout=15)
        self.assertEqual(0, stand_in.returncode, err)
        self.assertEqual(b"hello\n\0raw\xff", out)

    def test_the_name_is_found_up_the_tree_when_flatpak_empties_the_environment(self):
        # Flatpak execs bwrap with no environment at all; the process that ran it still has the name.
        parent = [sys.executable, "-c",
                  "import subprocess, sys; sys.exit(subprocess.call(sys.argv[1:], env={}))", *self.argv()]
        stand_in = subprocess.Popen(parent, env=self.env(), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        conn, kind, request = self.server.accept()
        with conn:
            self.assertEqual("sandbox", request["kind"])
            self.assertNotIn("BL_SPAWN_SOCKET", request["env"])
            conn.sendall(frame(b"P", b"7") + frame(b"X", b"0"))
            _, err = stand_in.communicate(timeout=15)
        self.assertEqual(0, stand_in.returncode, err)

    @unittest.skipIf(ancestors_name_the_socket(), "run inside a guest that names its own socket")
    def test_without_the_variable_anywhere_it_uses_the_fixed_name(self):
        try:
            legacy = FakeServer(LEGACY)
        except OSError:
            self.skipTest("something on this machine already listens on the fixed name")
        self.addCleanup(legacy.close)
        stand_in = self.start({"PATH": os.environ.get("PATH", "/usr/bin:/bin")})
        conn, kind, request = legacy.accept()
        with conn:
            self.assertEqual(b"R", kind)
            conn.sendall(frame(b"P", b"7") + frame(b"X", b"0"))
            _, err = stand_in.communicate(timeout=15)
        self.assertEqual(0, stand_in.returncode, err)

    def test_the_exit_status_comes_back(self):
        for status in (0, 3, 127):
            with self.subTest(status=status):
                stand_in = self.start(self.env())
                conn, _, _ = self.server.accept()
                with conn:
                    conn.sendall(frame(b"P", b"7") + frame(b"X", str(status).encode()))
                    stand_in.communicate(timeout=15)
                self.assertEqual(status, stand_in.returncode)

    def test_the_status_reaches_json_status_fd(self):
        read, write = os.pipe()
        stand_in = self.start(self.env(), "--json-status-fd", str(write), pass_fds=(write,))
        os.close(write)
        conn, _, _ = self.server.accept()
        with conn:
            conn.sendall(frame(b"P", b"4242") + frame(b"X", b"5"))
            stand_in.communicate(timeout=15)
        with os.fdopen(read, "rb") as status:
            lines = [json.loads(line) for line in status.read().splitlines()]
        self.assertEqual([{"child-pid": 4242}, {"exit-code": 5}], lines)
        self.assertEqual(5, stand_in.returncode)

    def test_an_error_is_said_on_stderr(self):
        stand_in = self.start(self.env())
        conn, _, _ = self.server.accept()
        with conn:
            conn.sendall(frame(b"E", "unknown request kind 'x'".encode()) + frame(b"X", b"1"))
            _, err = stand_in.communicate(timeout=15)
        self.assertEqual(1, stand_in.returncode)
        self.assertIn(b"bwrap: unknown request kind 'x'", err)

    def test_a_fall_back_fails_a_sandbox_with_the_reason(self):
        stand_in = self.start(self.env())
        conn, _, _ = self.server.accept()
        with conn:
            conn.sendall(frame(b"F", b"not here"))
            conn.shutdown(socket.SHUT_WR)
            _, err = stand_in.communicate(timeout=15)
        self.assertEqual(1, stand_in.returncode)
        self.assertIn(b"did not start the sandbox: not here", err)

    def test_the_app_going_away_ends_the_stand_in(self):
        stand_in = self.start(self.env())
        conn, _, _ = self.server.accept()
        conn.sendall(frame(b"P", b"7"))
        conn.close()
        _, err = stand_in.communicate(timeout=15)
        self.assertEqual(1, stand_in.returncode)
        self.assertIn(b"DroidDeck closed the sandbox", err)

    def test_no_app_listening_is_said(self):
        self.server.close()
        stand_in = self.start(self.env())
        _, err = stand_in.communicate(timeout=15)
        self.assertEqual(1, stand_in.returncode)
        self.assertIn(b"DroidDeck is not answering", err)

    def test_a_signal_goes_to_the_app_before_the_stand_in_ends_as_it_always_has(self):
        # SIGTERM, SIGINT and SIGHUP end it with 143; the others by the signal, as with no handler.
        ends = {signal.SIGTERM: 143, signal.SIGINT: 143, signal.SIGHUP: 143,
                signal.SIGQUIT: -signal.SIGQUIT, signal.SIGUSR1: -signal.SIGUSR1}
        for sig, status in ends.items():
            with self.subTest(signal=sig.name):
                read, write = os.pipe()
                stand_in = self.start(self.env(), "--info-fd", str(write), pass_fds=(write,))
                os.close(write)
                conn, _, _ = self.server.accept()
                with conn:
                    conn.sendall(frame(b"P", b"4242"))
                    # The pid on --info-fd means P was read, so the handlers are in place.
                    with os.fdopen(read, "rb") as info:
                        self.assertEqual({"child-pid": 4242}, json.loads(info.readline()))
                    stand_in.send_signal(sig)
                    self.assertEqual((b"K", str(int(sig)).encode()), read_frame(conn))
                    stand_in.communicate(timeout=15)
                    self.assertIsNone(read_frame(conn))
                self.assertEqual(status, stand_in.returncode)


if __name__ == "__main__":
    unittest.main()
