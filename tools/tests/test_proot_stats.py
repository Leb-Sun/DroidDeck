"""proot's stop counters (patch 0015, PROOT_STATS) and the fast path's live counter files
(PROOT_FP_STATS_DIR), under a proot built for this host by tools/proot/bench/host-proot.sh:

    tools/proot/bench/host-proot.sh /tmp/host-proot
    DROIDDECK_HOST_PROOT=/tmp/host-proot python3 -m unittest discover -s tools/tests -p 'test_proot_stats.py'

Skipped without one. The readers here follow the layouts in tools/proot/PATCHES.md, which the app's
PerfCounters reads too.
"""
import os
from pathlib import Path
import platform
import shutil
import struct
import subprocess
import tempfile
import time
import unittest

TOOLS = Path(__file__).resolve().parents[1]
FASTPATH = TOOLS / "proot/fastpath/fastpath.c"
HOST_PROOT = os.environ.get("DROIDDECK_HOST_PROOT", "")

KINDS = ["seccomp", "sysenter", "sysexit", "sigsys", "clone", "fork", "vfork", "vfork_done",
         "exec", "exit", "signal", "gone"]
FAMILIES = ["open", "stat", "access", "path", "write", "socket", "other"]


def read_stats(path):
    data = Path(path).read_bytes()
    if data[:8] != b"PRSTATS\0":
        raise AssertionError(f"no PRSTATS magic in {path}")
    version, header_size, nb_kinds, nb_sysnums, name_size, _ = struct.unpack_from("<6I", data, 8)
    pid, started, updated, stops, ns = struct.unpack_from("<5Q", data, 32)
    kind_stops = struct.unpack_from("<16Q", data, 72)
    kind_ns = struct.unpack_from("<16Q", data, 72 + 128)
    names_at = header_size + 32 * nb_sysnums
    syscalls = {}
    for i in range(nb_sysnums):
        enter, exit_, emulated, sys_ns = struct.unpack_from("<4Q", data, header_size + 32 * i)
        name = data[names_at + i * name_size:names_at + (i + 1) * name_size].split(b"\0")[0].decode()
        syscalls[name] = {"enter": enter, "exit": exit_, "emulated": emulated, "ns": sys_ns}
    return {"version": version, "nb_kinds": nb_kinds, "pid": pid, "started": started, "updated": updated,
            "stops": stops, "ns": ns, "kinds": dict(zip(KINDS, kind_stops)),
            "kind_ns": dict(zip(KINDS, kind_ns)), "syscalls": syscalls}


def read_counters(path):
    data = Path(path).read_bytes()
    magic, version, families, pid, _ = struct.unpack_from("<8s4I", data, 0)
    if magic != b"FPSTATS\0":
        raise AssertionError(f"no FPSTATS magic in {path}")
    counts = struct.unpack_from(f"<{2 * families}Q", data, 56)
    return {"version": version, "pid": pid, "name": data[24:56].split(b"\0")[0].decode(),
            "hits": dict(zip(FAMILIES, counts[:families])), "misses": dict(zip(FAMILIES, counts[families:]))}


# A syscall the tracee's own filter refuses with SIGSYS, the way Android's app policy refuses
# set_robust_list or clone3: proot answers setgroups(0, NULL) with 0.
SIGSYS_PROGRAM = r"""
#define _GNU_SOURCE
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>
int main(int argc, char **argv) {
  int n = atoi(argv[1]), ok = 0;
  struct sock_filter f[] = {
      BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
      BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_setgroups, 0, 1),
      BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
      BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
  };
  struct sock_fprog prog = {sizeof f / sizeof f[0], f};
  if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) || prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog)) return 2;
  for (int i = 0; i < n; i++) ok += syscall(SYS_setgroups, 0, NULL) == 0;
  return ok == n ? 0 : 1;
}
"""

# n stat()s of a file that exists and n access()es of one that does not, then a ready file, then it
# waits for stdin to close: its counters are read while it is alive.
PATH_PROGRAM = r"""
#include <fcntl.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <unistd.h>
int main(int argc, char **argv) {
  int n = atoi(argv[1]);
  struct stat st;
  for (int i = 0; i < n; i++) {
    if (stat(argv[2], &st) != 0) return 3;
    if (access(argv[3], F_OK) == 0) return 4;
  }
  int fd = open(argv[4], O_WRONLY | O_CREAT | O_TRUNC, 0644);
  if (fd < 0) return 5;
  close(fd);
  char c;
  while (read(0, &c, 1) > 0) {}
  return 0;
}
"""


@unittest.skipUnless(HOST_PROOT and (Path(HOST_PROOT) / "proot").is_file() and shutil.which("cc")
                     and platform.machine() in ("x86_64", "aarch64"),
                     "DROIDDECK_HOST_PROOT (tools/proot/bench/host-proot.sh) and a C compiler are needed")
class ProotStatsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.dir = Path(cls.temp.name)
        cls.proot = Path(HOST_PROOT) / "proot"
        cls.loader = Path(HOST_PROOT) / "loader"
        for name, source in (("sigsys", SIGSYS_PROGRAM), ("paths", PATH_PROGRAM)):
            (cls.dir / f"{name}.c").write_text(source)
            subprocess.run(["cc", "-O2", "-o", str(cls.dir / name), str(cls.dir / f"{name}.c")], check=True)
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-pthread", "-o", str(cls.dir / "libfastpath.so"),
                        str(FASTPATH), "-ldl"], check=True)
        cls.root = cls.dir / "rootfs"
        cls.root.mkdir()
        cls.binds = [p for p in ("/usr", "/lib", "/lib64", "/bin", "/etc") if os.path.exists(p)] + [str(cls.dir)]

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_proot(self, guest, extra_env=None, stdin=None, rootfs=False):
        env = {"PATH": "/usr/bin:/bin", "PROOT_LOADER": str(self.loader)}
        env.update(extra_env or {})
        options = ["-r", str(self.root), "-w", "/"] + sum((["-b", b] for b in self.binds), []) if rootfs else ["-r", "/"]
        return subprocess.Popen([str(self.proot)] + options + guest, env=env, stdin=stdin,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def finish(self, process):
        _, err = process.communicate(timeout=60)
        self.assertEqual(0, process.returncode, err.decode(errors="replace"))

    def test_execs_forks_and_syscalls_are_counted(self):
        script = self.dir / "execs.sh"
        script.write_text("i=0\nwhile [ $i -lt 40 ]; do /bin/true; i=$((i+1)); done\n")
        stats = self.dir / "execs.stats"
        process = self.run_proot(["/bin/sh", str(script)], {"PROOT_STATS": str(stats)})
        self.finish(process)
        s = read_stats(stats)
        self.assertEqual(1, s["version"])
        self.assertEqual(len(KINDS), s["nb_kinds"])
        # sh and forty /bin/true.
        self.assertGreaterEqual(s["kinds"]["exec"], 41)
        self.assertGreaterEqual(s["syscalls"]["execve"]["enter"], 41)
        self.assertGreaterEqual(s["kinds"]["fork"] + s["kinds"]["vfork"] + s["kinds"]["clone"], 40)
        self.assertEqual(s["stops"], sum(s["kinds"].values()))
        self.assertGreater(s["ns"], 0)
        self.assertGreaterEqual(s["ns"], sum(c["ns"] for c in s["syscalls"].values()))
        self.assertGreaterEqual(s["updated"], s["started"])

    def test_refused_syscalls_are_counted_as_emulated(self):
        stats = self.dir / "sigsys.stats"
        process = self.run_proot([str(self.dir / "sigsys"), "25"], {"PROOT_STATS": str(stats)})
        self.finish(process)
        s = read_stats(stats)
        self.assertEqual(25, s["kinds"]["sigsys"])
        self.assertEqual(25, s["syscalls"]["setgroups"]["emulated"])

    def test_nothing_is_written_without_proot_stats(self):
        before = set(os.listdir(self.dir))
        process = self.run_proot([str(self.dir / "sigsys"), "3"])
        self.finish(process)
        self.assertEqual(before, set(os.listdir(self.dir)))

    def path_run(self, label, fast_path):
        counters = self.dir / f"fp-{label}"
        counters.mkdir()
        ready = self.dir / f"ready-{label}"
        stats = self.dir / f"{label}.stats"
        (self.dir / "exists").write_text("x")
        guest = ["/usr/bin/env", "-i", f"PROOT_FP_ROOT={self.root}", "PROOT_FP_KEY=test",
                 "PROOT_FP_BINDS=" + "|".join(f"{b}:{b}" for b in self.binds),
                 f"PROOT_FP_STATS_DIR={counters}", f"LD_PRELOAD={self.dir / 'libfastpath.so'}"]
        if not fast_path:
            guest.append("PROOT_FP_OFF=1")
        guest += [str(self.dir / "paths"), "150", str(self.dir / "exists"), str(self.dir / "missing"), str(ready)]
        process = self.run_proot(guest, {"PROOT_STATS": str(stats), "PROOT_FASTPATH": "test"},
                                 stdin=subprocess.PIPE, rootfs=True)
        deadline = time.monotonic() + 30
        while not ready.exists() and process.poll() is None and time.monotonic() < deadline:
            time.sleep(0.05)
        live = {f.name: read_counters(f) for f in counters.iterdir()}
        self.finish(process)
        return live, read_stats(stats)

    def test_fast_path_counts_live_and_takes_the_stops_away(self):
        live, stats = self.path_run("on", fast_path=True)
        self.assertEqual(1, len(live), live)
        (name, counters), = live.items()
        self.assertTrue(name.startswith("paths."), name)
        self.assertEqual("paths", counters["name"])
        self.assertEqual(1, counters["version"])
        self.assertGreaterEqual(counters["hits"]["stat"], 150)
        self.assertGreaterEqual(counters["hits"]["access"], 150)
        self.assertEqual(0, sum(counters["misses"].values()))
        traced = stats["syscalls"]["newfstatat"]["enter"] + stats["syscalls"]["faccessat"]["enter"]
        self.assertLess(traced, 20)

        live, stats = self.path_run("off", fast_path=False)
        self.assertEqual({}, live)
        traced = stats["syscalls"]["newfstatat"]["enter"] + stats["syscalls"]["faccessat"]["enter"]
        self.assertGreaterEqual(traced, 300)


if __name__ == "__main__":
    unittest.main()
