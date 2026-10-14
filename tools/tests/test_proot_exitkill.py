"""proot's exit kill (patch 0017, PROOT_EXIT_KILL) and the SIGQUIT teardown the app uses with it,
under a proot built for this host by tools/proot/bench/host-proot.sh:

    tools/proot/bench/host-proot.sh /tmp/host-proot
    DROIDDECK_HOST_PROOT=/tmp/host-proot python3 -m unittest discover -s tools/tests -p 'test_proot_exitkill.py'

Skipped without one. Each test runs sh with three sleeping descendants (two children, and a
grandchild through a subshell) under `proot --kill-on-exit`, as the app runs a session, and finds
the tracees in /proc by their TracerPid once all five run.
"""
import os
from pathlib import Path
import platform
import signal
import subprocess
import tempfile
import time
import unittest

HOST_PROOT = os.environ.get("DROIDDECK_HOST_PROOT", "")
# sh, its two sleeping children, the subshell and the subshell's sleeping child.
TREE = 5
# The app waits this long (SessionService.GRACE_MS) before it SIGKILLs a proot that will not go.
GRACE_S = 1.2


def stat_fields(pid):
    """The fields of /proc/<pid>/stat after the command name, or None once the process is gone."""
    try:
        stat = Path(f"/proc/{pid}/stat").read_text()
    except OSError:
        return None
    return stat[stat.rindex(")") + 2:].split()


def alive(pid, started):
    """Whether the process that started at `started` still runs: there, the same one, not a zombie."""
    fields = stat_fields(pid)
    return fields is not None and fields[0] != "Z" and int(fields[19]) == started


def tracer_of(pid):
    try:
        status = Path(f"/proc/{pid}/status").read_text()
    except OSError:
        return None
    return int(next(line.split()[1] for line in status.splitlines() if line.startswith("TracerPid:")))


def tracees(tracer):
    """(pid, start time) of every live process `tracer` traces."""
    found = []
    for name in os.listdir("/proc"):
        if name.isdigit() and tracer_of(name) == tracer:
            fields = stat_fields(name)
            if fields is not None and fields[0] != "Z":
                found.append((int(name), int(fields[19])))
    return sorted(found)


@unittest.skipUnless(HOST_PROOT and (Path(HOST_PROOT) / "proot").is_file() and platform.machine() in ("x86_64", "aarch64"),
                     "DROIDDECK_HOST_PROOT (tools/proot/bench/host-proot.sh) is needed")
class ProotExitKillTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.dir = Path(temp.name)
        self.spawned = []
        self.addCleanup(self.kill_spawned)

    def kill_spawned(self):
        # Today's behaviour leaves the tree running: never past the test.
        for pid, started in self.spawned:
            if alive(pid, started):
                os.kill(pid, signal.SIGKILL)

    def start_tree(self, exit_kill, kill_on_exit=True):
        ready = self.dir / "ready"
        ready.unlink(missing_ok=True)
        env = {"PATH": "/usr/bin:/bin", "PROOT_LOADER": str(Path(HOST_PROOT) / "loader")}
        if exit_kill:
            env["PROOT_EXIT_KILL"] = "1"
        options = ["--kill-on-exit"] if kill_on_exit else []
        script = f"sleep 300 & sleep 300 & (sleep 300 & wait) & : > {ready}; wait"
        # Not a pipe: the tracees that outlive proot would hold it open.
        with open(self.dir / "proot.err", "ab") as err:
            proot = subprocess.Popen([str(Path(HOST_PROOT) / "proot")] + options + ["-r", "/", "/bin/sh", "-c", script],
                                     env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=err)
        self.addCleanup(lambda: proot.poll() is None and (proot.kill(), proot.wait()))
        deadline = time.monotonic() + 30
        tree = []
        while time.monotonic() < deadline and proot.poll() is None:
            tree = tracees(proot.pid)
            if len(tree) == TREE and ready.exists():
                break
            time.sleep(0.05)
        self.spawned += tree
        self.assertEqual(TREE, len(tree), (self.dir / "proot.err").read_text(errors="replace"))
        return proot, tree

    def survivors(self, tree, within):
        """The processes of `tree` still alive once they are all gone, or after `within` seconds."""
        deadline = time.monotonic() + within
        while True:
            left = [p for p in tree if alive(*p)]
            if not left or time.monotonic() >= deadline:
                return left
            time.sleep(0.02)

    def test_a_sigkilled_proot_takes_its_tracees_with_it(self):
        proot, tree = self.start_tree(exit_kill=True)
        proot.kill()
        proot.wait(timeout=10)
        self.assertEqual([], self.survivors(tree, within=5))

    def test_without_exit_kill_the_tracees_outlive_a_sigkilled_proot(self):
        # Today's behaviour, which the toggle is for: every tracee runs on, with no tracer. The
        # variable alone changes nothing: it takes --kill-on-exit as well.
        for exit_kill, kill_on_exit in ((False, True), (True, False)):
            with self.subTest(exit_kill=exit_kill, kill_on_exit=kill_on_exit):
                proot, tree = self.start_tree(exit_kill, kill_on_exit)
                proot.kill()
                proot.wait(timeout=10)
                time.sleep(1)
                self.assertEqual(tree, [p for p in tree if alive(*p)])
                self.assertEqual([0] * TREE, [tracer_of(pid) for pid, _ in tree])

    def test_sigquit_tears_the_tree_down_promptly_where_sigterm_is_ignored(self):
        for exit_kill in (False, True):
            with self.subTest(exit_kill=exit_kill):
                proot, tree = self.start_tree(exit_kill)
                proot.send_signal(signal.SIGTERM)
                with self.assertRaises(subprocess.TimeoutExpired):
                    proot.wait(timeout=0.5)
                self.assertEqual(tree, [p for p in tree if alive(*p)])

                sent = time.monotonic()
                proot.send_signal(signal.SIGQUIT)
                proot.wait(timeout=10)
                left = self.survivors(tree, within=5)
                elapsed = time.monotonic() - sent
                self.assertEqual([], left)
                # It left through its own event loop, not by dying of the signal.
                self.assertGreaterEqual(proot.returncode, 0)
                self.assertLess(elapsed, GRACE_S)


if __name__ == "__main__":
    unittest.main()
