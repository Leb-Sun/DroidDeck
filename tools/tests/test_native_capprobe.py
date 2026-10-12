"""The capability probe (app/src/main/cpp/hostsys/capprobe.c) under a simulated Android app filter.

tools/native/launcher/seccomp-launch installs the spike's policy (android14, android15, android16q)
with SECCOMP_RET_TRAP, as zygote does for an app, and execs capprobe_harness.c, which runs the
probe exactly as the app's JNI call does. A blocked call must come back "blocked" and kill only
the test that made it: the harness lives on, and every test after it still reports.

The harness is a stock glibc program, which issues set_robust_list and rseq while it starts
(04-spikes.md S0). rseq is turned off with GLIBC_TUNABLES; set_robust_list is let through with
--allow, so the probe's own set_robust_list test is expected to come back allowed here.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

TESTS = Path(__file__).resolve().parent
REPO = TESTS.parents[1]
HOSTSYS = REPO / "app/src/main/cpp/hostsys"
LAUNCHER = REPO / "tools/native/launcher"
PROFILES = ["android14", "android15", "android16q"]
ALLOW = "set_robust_list"
ENV = dict(os.environ, GLIBC_TUNABLES="glibc.pthread.rseq=0")

# The system call each test is about; the others make only calls no Android blocks (mmap, mprotect).
CALLS = {
    "openat2": "openat2", "resolve_in_root": "openat2", "faccessat2": "faccessat2",
    "fchmodat2": "fchmodat2", "clone3": "clone3", "close_range": "close_range",
    "landlock": "landlock_create_ruleset", "uclamp": "sched_setattr", "userfaultfd": "userfaultfd",
    "memfd_create": "memfd_create", "setresuid": "setresuid", "rseq": "rseq",
    "set_robust_list": "set_robust_list", "get_robust_list": "get_robust_list",
    "futex_waitv": "futex_waitv", "io_uring_setup": "io_uring_setup", "setuid": "setuid",
}
TESTS_IN_ORDER = ["openat2", "resolve_in_root", "faccessat2", "fchmodat2", "clone3", "close_range",
                  "landlock", "uclamp", "fp_stub", "execmem", "exec_data", "userfaultfd",
                  "memfd_create", "setresuid", "rseq", "set_robust_list", "get_robust_list",
                  "futex_waitv", "io_uring_setup", "setuid"]


def parse(report):
    """The probe's report: its status, and {test: (state, {field: value})}."""
    lines = report.strip().splitlines()
    head = lines[0].split(" ", 2)
    results = {}
    for line in lines[1:]:
        words = line.split()
        fields = dict(w.split("=", 1) for w in words[2:] if "=" in w)
        results[words[0]] = (words[1], fields)
    return head[2] if head[:2] == ["capprobe", "1"] else None, results


@unittest.skipUnless(shutil.which("cc"), "needs a C compiler")
@unittest.skipUnless(os.uname().machine == "x86_64", "the spike's filter is built for x86_64 hosts")
class CapProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.tmp.cleanup)
        tmp = Path(cls.tmp.name)
        cls.harness = str(tmp / "capprobe_harness")
        cls.launch = str(tmp / "seccomp-launch")
        subprocess.run(["cc", "-std=gnu11", "-O2", "-Wall", "-Wextra", "-Werror", "-I", str(HOSTSYS),
                        str(TESTS / "capprobe_harness.c"), "-o", cls.harness],
                       check=True, capture_output=True, text=True)
        subprocess.run(["cc", "-O2", str(LAUNCHER / "seccomp-launch.c"), "-o", cls.launch],
                       check=True, capture_output=True, text=True)
        # The files the app puts in cache/capprobe (core/CapProbe.kt, prepare).
        cls.dir = tmp / "capprobe"
        (cls.dir / "root").mkdir(parents=True)
        (cls.dir / "file").write_text("capprobe\n")
        (cls.dir / "file").chmod(0o600)
        (cls.dir / "root/inside").write_text("")
        os.symlink("/inside", cls.dir / "root/link")
        # Where seccomp cannot be installed (a sandbox's own filter), nothing here can be checked.
        smoke = subprocess.run([cls.launch, "--quiet", "--profile", "android16q", "--allow", ALLOW, "--", "/bin/true"],
                               env=ENV, capture_output=True, text=True)
        cls.filters = smoke.returncode == 0

    def blocked_by(self, profile):
        """The calls the spike's policy blocks under [profile], from seccomp-launch --dump."""
        out = subprocess.run([self.launch, "--dump", "x86_64"], check=True, capture_output=True, text=True).stdout
        column = 2 + PROFILES.index(profile)
        rows = [line.split() for line in out.splitlines() if line and not line.startswith("#")]
        return {row[0] for row in rows if row[column] == "1"} - {ALLOW}

    def probe(self, *prefix):
        result = subprocess.run([*prefix, self.harness, str(self.dir)], env=ENV, capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        status, results = parse(result.stdout)
        self.assertEqual(status, "ok", result.stdout)
        self.assertEqual(list(results), TESTS_IN_ORDER, result.stdout)
        return results

    def test_without_a_filter_every_call_is_allowed(self):
        results = self.probe()
        for name, (state, _) in results.items():
            self.assertEqual(state, "allowed", (name, results[name]))
        self.assertGreaterEqual(int(results["openat2"][1]["ret"]), 0)
        self.assertEqual(results["resolve_in_root"][1]["ret"], "1")
        self.assertEqual(results["clone3"][1]["errno"], "22")
        self.assertEqual(results["fp_stub"][1]["ret"], "1")
        self.assertEqual(results["execmem"][1]["ret"], "0")
        self.assertEqual(results["exec_data"][1]["ret"], "0")
        self.assertEqual(results["setresuid"][1]["ret"], "0")

    def test_each_profile_blocks_its_calls_and_only_the_test_dies(self):
        if not self.filters:
            self.skipTest("seccomp filters cannot be installed here")
        for profile in PROFILES:
            with self.subTest(profile=profile):
                blocked = self.blocked_by(profile)
                results = self.probe(self.launch, "--quiet", "--profile", profile, "--allow", ALLOW, "--")
                for name, (state, fields) in results.items():
                    expected = "blocked" if CALLS.get(name) in blocked else "allowed"
                    self.assertEqual(state, expected, (profile, name, fields))
                # The gated calls, as BL_NATIVE_BLOCK reads them (core/CapProbe.kt).
                gated = {name: results[name][0] for name in ["clone3", "faccessat2", "fchmodat2", "close_range"]}
                self.assertEqual(gated, {
                    "android14": {"clone3": "blocked", "faccessat2": "blocked", "fchmodat2": "blocked", "close_range": "allowed"},
                    "android15": {"clone3": "allowed", "faccessat2": "blocked", "fchmodat2": "blocked", "close_range": "allowed"},
                    "android16q": {"clone3": "allowed", "faccessat2": "allowed", "fchmodat2": "allowed", "close_range": "allowed"},
                }[profile])
                if profile == "android16q":
                    self.assertEqual(results["resolve_in_root"][1]["ret"], "1")

    def test_a_test_that_never_returns_is_cut_off(self):
        started = time.monotonic()
        result = subprocess.run([self.harness, "--hang", str(self.dir)], capture_output=True, text=True, timeout=30)
        self.assertLess(time.monotonic() - started, 5)
        status, results = parse(result.stdout)
        self.assertEqual(status, "timeout", result.stdout)
        self.assertEqual(results["setresuid"][0], "allowed")
        self.assertEqual(result.stdout.splitlines()[2:], ["hang error unfinished step=1", "clone3 error not-run"])
        # The hung test goes with the helper (PR_SET_PDEATHSIG): at most a zombie is left.
        deadline = time.monotonic() + 2
        while True:
            alive = []
            for pid in filter(str.isdigit, os.listdir("/proc")):
                try:
                    stat = Path(f"/proc/{pid}/stat").read_text()
                except OSError:
                    continue
                if "(capprobe_harnes)" in stat and stat.rsplit(")", 1)[1].split()[0] != "Z":
                    alive.append(pid)
            if not alive or time.monotonic() > deadline:
                break
            time.sleep(0.05)
        self.assertEqual(alive, [])

    def test_the_numbers_are_the_spike_policys(self):
        numbers = {}
        for line in subprocess.run([self.harness, "--numbers"], check=True, capture_output=True, text=True).stdout.splitlines():
            name, x86_64, aarch64 = line.split()
            numbers[name] = (int(x86_64), int(aarch64))
        for arch, column in [("x86_64", 0), ("aarch64", 1)]:
            out = subprocess.run([self.launch, "--dump", arch], check=True, capture_output=True, text=True).stdout
            policy = {row[0]: int(row[1]) for row in (line.split() for line in out.splitlines() if line and not line.startswith("#"))}
            shared = set(numbers) & set(policy)
            self.assertGreaterEqual(len(shared), 10)
            for name in shared:
                self.assertEqual(numbers[name][column], policy[name], (arch, name))


if __name__ == "__main__":
    unittest.main()
