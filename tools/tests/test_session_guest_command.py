"""Exercise the literal launch prefix from SessionService without an Android runtime.

This is a focused source/exec regression check, not a Kotlin or PRoot integration
test. PRoot executes the first guest argument directly; KEY=VALUE is meaningful
only after /usr/bin/env. Read the production prefix rather than duplicate it.
"""
import json
from pathlib import Path
import re
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "app/src/main/java/com/droiddeck/launcher/session/SessionService.kt"
SPAWN = ROOT / "app/src/main/java/com/droiddeck/launcher/runtime/SpawnServer.kt"

VARIANTS = Path(__file__).resolve().parents[1] / "release/variants.txt"
PACKAGES = [line.split()[1] for line in VARIANTS.read_text().splitlines()
            if line.strip() and not line.startswith("#")]


def spawn_assignment(package):
    """BL_SPAWN_SOCKET=<package>.spawn, read from SpawnServer rather than copied here."""
    text = SPAWN.read_text()
    env = re.search(r'const val ENV = "([^"]+)"', text)
    suffix = re.search(
        r'fun socketName\(context: Context\): String = context\.packageName \+ "([^"]+)"', text)
    if env is None or suffix is None:
        raise AssertionError("SpawnServer no longer names the socket as the package plus a suffix")
    return env[1] + "=" + package + suffix[1]


def guest_prefix(package):
    source = SERVICE.read_text()
    before_environment = source.split("val guest = ArrayList<String>()", 1)[1].split(
        "addClientEnvironment(guest, steamHere", 1)[0]
    environment = source.split("private fun addClientEnvironment(", 1)[1].split(
        'guest.add("HOME=/root")', 1)[0]
    body = source.split("private fun addClientEnvironment(", 1)[1].split("\n    private fun ", 1)[0]
    if "guest.add(SpawnServer.guestEnv(this))" not in body:
        raise AssertionError("addClientEnvironment no longer names the spawn socket")
    if "DROIDDECK_BWRAP_SOCKET" in body or "BwrapSpawner.socket" in body:
        raise AssertionError("the in-process bwrap socket is back in the guest environment")
    prefix = []
    for line in (before_environment + environment).splitlines():
        line = line.strip()
        if not line.startswith("guest.add("):
            continue
        expression = line[len("guest.add("):-1]
        match = re.fullmatch(r'"([^"\\]*)"', expression)
        if match is None:
            raise AssertionError("Launch prefix changed; update its regression harness: " + line)
        prefix.append(json.loads(match[0]))
    prefix.append(spawn_assignment(package))
    return prefix


class SessionGuestCommandTest(unittest.TestCase):
    def test_env_is_the_executable_for_every_package(self):
        for package in PACKAGES:
            with self.subTest(package=package):
                self.assertEqual(["/usr/bin/env", "-i"], guest_prefix(package)[:2])

    @unittest.skipUnless(Path("/usr/bin/env").is_file(), "needs /usr/bin/env")
    def test_child_receives_its_package_socket_after_environment_is_cleared(self):
        for package in PACKAGES:
            with self.subTest(package=package):
                result = subprocess.run(
                    guest_prefix(package), capture_output=True, text=True, check=True,
                    timeout=5, env={
                        "BL_SPAWN_SOCKET": "wrong.spawn",
                        "DROIDDECK_BWRAP_SOCKET": "wrong.bwrap",
                        "STALE_HOST": "1",
                    })
                self.assertEqual(
                    [spawn_assignment(package)],
                    result.stdout.splitlines())


if __name__ == "__main__":
    unittest.main()
