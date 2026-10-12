"""The glibc overlay's pieces that live outside the app (M13, docs/development/native-env/03-design.md §4):
the pacman hook and the state script it runs, tools/glibc/manifest.py's file set and manifest format,
and that the pins in tools/glibc agree with each other and with the runtime image's package list.
session/NativeGlibc.kt has its own unit tests (NativeGlibcTest).
"""
import configparser
import gzip
import hashlib
import importlib.util
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
GLIBC = REPO / "tools/glibc"
OVERLAY = REPO / "tools/linuxfs/overlay"
HOOK = OVERLAY / "usr/share/libalpm/hooks/zz-droiddeck-glibc.hook"
STATE_SCRIPT = OVERLAY / "usr/local/bin/droiddeck-glibc-state"
VERSION = "2.43+r22+g8362e8ce10b2-2"

spec = importlib.util.spec_from_file_location("glibc_manifest", GLIBC / "manifest.py")
manifest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manifest)


def elf(kind, body=b""):
    """A minimal 64-bit little-endian ELF header of e_type [kind] (3 = ET_DYN, 1 = ET_REL)."""
    return b"\x7fELF\x02\x01\x01" + b"\0" * 9 + struct.pack("<H", kind) + body


def sha(data):
    return hashlib.sha256(data).hexdigest()


def env_file(path):
    """A sourced env file's values, read the way bash would read them."""
    out = subprocess.run(["bash", "-c", 'set -a; . "$0"; env -0', str(path)], capture_output=True, check=True).stdout
    return dict(item.split("=", 1) for item in out.decode().split("\0") if "=" in item)


class HookTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def entry(self, name, version):
        desc = self.root / f"var/lib/pacman/local/{name}-{version}/desc"
        desc.parent.mkdir(parents=True)
        desc.write_text(f"%NAME%\n{name}\n\n%VERSION%\n{version}\n\n%BASE%\nglibc\n")

    def run_script(self):
        return subprocess.run(["bash", str(STATE_SCRIPT), str(self.root)], capture_output=True, text=True)

    def state(self):
        return (self.root / "etc/droiddeck/glibc-state").read_text()

    def test_the_hook_runs_the_script_after_glibc_is_installed_or_upgraded(self):
        hook = configparser.ConfigParser(strict=False, interpolation=None)
        hook.optionxform = str
        # pacman repeats Operation; configparser keeps the last, so read the lines themselves too.
        text = HOOK.read_text()
        hook.read_string(text)
        operations = [line.split("=", 1)[1].strip() for line in text.splitlines() if line.startswith("Operation")]
        self.assertEqual(sorted(operations), ["Install", "Upgrade"])
        self.assertEqual(hook["Trigger"]["Type"].strip(), "Package")
        self.assertEqual(hook["Trigger"]["Target"].strip(), "glibc")
        self.assertEqual(hook["Action"]["When"].strip(), "PostTransaction")
        exec_path = hook["Action"]["Exec"].strip()
        self.assertEqual(OVERLAY / exec_path.lstrip("/"), STATE_SCRIPT)
        self.assertTrue(os.access(STATE_SCRIPT, os.X_OK))
        # It sorts after glibc's own hooks, and the session stages both files.
        self.assertTrue(HOOK.name.startswith("zz-"))
        session_files = (REPO / "app/src/main/java/com/droiddeck/launcher/session/SessionFiles.kt").read_text()
        for path in [exec_path.lstrip("/"), "usr/share/libalpm/hooks/" + HOOK.name]:
            self.assertIn(f'"{path}" to "{path}"', session_files)

    def test_a_replaced_glibc_reads_as_mismatch_whatever_was_staged(self):
        self.entry("glibc", "2.44+r50+g1848099f063e-1")
        self.entry("glibc-locales", "2.44+r50+g1848099f063e-1")
        state = self.root / "etc/droiddeck/glibc-state"
        state.parent.mkdir(parents=True)
        for before in [f"patched {VERSION}\n", f"stock {VERSION}\n"]:
            state.write_text(before)
            result = self.run_script()
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(self.state(), "mismatch 2.44+r50+g1848099f063e-1\n")
        self.assertEqual(sorted(p.name for p in state.parent.iterdir()), ["glibc-state"])

    def test_no_state_yet_and_no_etc_droiddeck(self):
        self.entry("glibc", VERSION)
        self.assertEqual(self.run_script().returncode, 0)
        self.assertEqual(self.state(), f"mismatch {VERSION}\n")

    def test_without_a_glibc_entry_the_version_is_unknown(self):
        self.entry("glibc-locales", VERSION)
        self.assertEqual(self.run_script().returncode, 0)
        self.assertEqual(self.state(), "mismatch unknown\n")

    def test_an_unwritable_state_never_fails_the_transaction(self):
        self.entry("glibc", VERSION)
        (self.root / "etc").write_text("not a directory")
        result = self.run_script()
        self.assertEqual(result.returncode, 0)


class ManifestTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.tmp = Path(temporary.name)
        # A stock package: two shared objects, a program, a link-time object and a symlink.
        self.stock = self.tmp / "stock"
        self.files = {
            "usr/lib/libc.so.6": elf(3, b"stock libc"),
            "usr/lib/gconv/IBM1390.so": elf(3, b"stock gconv"),
            "usr/bin/ldconfig": elf(3, b"stock ldconfig"),
            "usr/lib/crt1.o": elf(1, b"crt1"),
            "usr/share/i18n/SUPPORTED": b"en_US.UTF-8 UTF-8\n",
        }
        for rel, data in self.files.items():
            path = self.stock / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            path.chmod(0o755 if data.startswith(b"\x7fELF") and data[16] == 3 else 0o644)
        (self.stock / "usr/lib/libc.so").symlink_to("libc.so.6")
        db = self.stock / f"var/lib/pacman/local/glibc-{VERSION}"
        db.mkdir(parents=True)
        listed = sorted(list(self.files) + ["usr/lib/libc.so"])
        (db / "files").write_text("%FILES%\nusr/\nusr/lib/\n" + "\n".join(listed) + "\n\n%BACKUP%\netc/gai.conf\tx\n")
        mtree = "#mtree\n" + "".join(f"./{rel} time=1.0 mode=755 size={len(d)} sha256digest={sha(d)}\n" for rel, d in self.files.items())
        with gzip.open(db / "mtree", "wt") as f:
            f.write(mtree)
        self.db = db

    def stock_list(self):
        lines = manifest.stock_list(str(self.stock), str(self.db))
        path = self.tmp / "stock.sha256"
        path.write_text("\n".join(lines) + "\n")
        return path

    def flavor(self, name, changes):
        root = self.tmp / name
        for rel in ["usr/lib/libc.so.6", "usr/lib/gconv/IBM1390.so", "usr/bin/ldconfig"]:
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(changes.get(rel, self.files[rel]))
            path.chmod(0o755)
        return root

    def test_the_overlay_is_every_et_dyn_file_and_each_is_the_packages_own(self):
        lines = manifest.stock_list(str(self.stock), str(self.db))
        self.assertEqual([line.split()[1] for line in lines], ["usr/bin/ldconfig", "usr/lib/gconv/IBM1390.so", "usr/lib/libc.so.6"])
        (self.stock / "usr/lib/libc.so.6").write_bytes(elf(3, b"edited in the image"))
        with self.assertRaises(manifest.ManifestError):
            manifest.stock_list(str(self.stock), str(self.db))

    def test_write_parse_and_check_round_trip(self):
        stock = manifest.read_list(self.stock_list())
        rebuild = self.flavor("rebuild", {"usr/lib/libc.so.6": elf(3, b"rebuilt libc")})
        patched = self.flavor("patched", {"usr/lib/libc.so.6": elf(3, b"patched libc"), "usr/bin/ldconfig": elf(3, b"patched ldconfig")})
        text = manifest.write(stock, str(self.stock), str(rebuild), str(patched), VERSION, "8362e8ce10b24068bacc19552c128dd10e082fd9",
                              "__ASSUME_FACCESSAT2=0 __ASSUME_FCHMODAT2=0 __ASSUME_SET_ROBUST_LIST=1 kernel=3.7.0", ["0001-a.patch", "0007-b.patch"])
        lines = text.splitlines()
        self.assertEqual(lines[0], "droiddeck-glibc-manifest 1")
        self.assertEqual(lines[1], "version " + VERSION)
        parsed = manifest.parse(text)
        self.assertEqual(parsed["version"], VERSION)
        self.assertEqual(parsed["patches"], ["0001-a.patch", "0007-b.patch"])
        files = {f[0]: f for f in parsed["files"]}
        libc = files["usr/lib/libc.so.6"]
        self.assertEqual(libc[1:4], (0o755, len(self.files["usr/lib/libc.so.6"]), sha(self.files["usr/lib/libc.so.6"])))
        self.assertEqual(libc[4:], (sha(elf(3, b"rebuilt libc")), sha(elf(3, b"patched libc"))))
        gconv = files["usr/lib/gconv/IBM1390.so"]
        self.assertEqual(gconv[3], gconv[4])  # the rebuild's gconv module is the stock one
        # Every file line: "file <mode> <size> <stock> <rebuild> <patched> <path>", as NativeGlibc parses it.
        for line in lines[5:]:
            words = line.split(" ")
            self.assertEqual((words[0], len(words)), ("file", 7), line)
        # The apk build's check of what it unpacked.
        assets = self.tmp / "assets"
        (assets).mkdir()
        (assets / "manifest").write_text(text)
        shutil.copytree(rebuild, assets / "rebuild")
        shutil.copytree(patched, assets / "patched")
        _, problems = manifest.check(str(assets))
        self.assertEqual(problems, [])
        (assets / "patched/usr/lib/libc.so.6").write_bytes(b"tampered")
        (assets / "rebuild/usr/lib/extra.so").write_bytes(b"x")
        (assets / "rebuild/usr/bin/ldconfig").chmod(0o700)
        _, problems = manifest.check(str(assets))
        self.assertEqual(sorted(problems), [
            "patched: usr/lib/libc.so.6 does not match the manifest",
            "rebuild: usr/bin/ldconfig has mode 700, not 755",
            "rebuild: usr/lib/extra.so is not in the manifest",
        ])

    def test_a_build_with_another_file_set_is_refused(self):
        stock = manifest.read_list(self.stock_list())
        built = self.flavor("built", {})
        (built / "usr/lib/libnew.so.1").write_bytes(elf(3))
        with self.assertRaises(manifest.ManifestError):
            manifest.collect(stock, str(built), str(self.tmp / "out"))
        (built / "usr/lib/libnew.so.1").unlink()
        (built / "usr/bin/ldconfig").unlink()
        with self.assertRaises(manifest.ManifestError):
            manifest.collect(stock, str(built), str(self.tmp / "out"))

    def test_malformed_manifests_are_refused(self):
        good = ("droiddeck-glibc-manifest 1\nversion " + VERSION + "\n"
                "file 755 10 " + "a" * 64 + " " + "b" * 64 + " " + "c" * 64 + " usr/lib/libc.so.6\n")
        self.assertEqual(len(manifest.parse(good)["files"]), 1)
        for bad in [
            good.replace("usr/lib/libc.so.6", "../etc/passwd"),
            good.replace("usr/lib/libc.so.6", "/usr/lib/libc.so.6"),
            good.replace(" 755 ", " 9 "),
            good.replace("a" * 64, "A" * 64),
            good.replace("droiddeck-glibc-manifest 1", "droiddeck-glibc-manifest 2"),
            good + good.splitlines()[2] + "\n",
            "droiddeck-glibc-manifest 1\nversion " + VERSION + "\n",
        ]:
            with self.assertRaises(manifest.ManifestError, msg=bad):
                manifest.parse(bad)

    @unittest.skipUnless(shutil.which("readelf"), "needs readelf")
    def test_symbols_of_a_file_against_itself_are_identical(self):
        target = Path(shutil.which("true")).resolve()
        listing = self.tmp / "one.sha256"
        rel = str(target).lstrip("/")
        listing.write_text(f"{manifest.sha256(target)}  {rel}\n")
        self.assertEqual(manifest.compare_symbols(manifest.read_list(listing), "/", "/"), [])
        self.assertTrue(any(item.startswith("NEEDED ") for item in manifest.symbols(str(target))))


class PinsTest(unittest.TestCase):
    def test_source_env_names_the_runtime_images_glibc(self):
        source = env_file(GLIBC / "source.env")
        version = source["GLIBC_PKGVER"] + "-" + source["GLIBC_PKGREL"]
        packages = (REPO / "tools/desktop-kde/runtime-packages.txt").read_text().splitlines()
        self.assertIn("glibc " + version, packages)
        # Arch's version string carries the commit: 2.43+r22+g<12 hex>.
        self.assertTrue(source["GLIBC_COMMIT"].startswith(source["GLIBC_PKGVER"].rsplit("+g", 1)[1]))
        self.assertRegex(source["GLIBC_COMMIT"], r"^[0-9a-f]{40}$")
        self.assertRegex(source["ALARM_PKGBUILDS_COMMIT"], r"^[0-9a-f]{40}$")
        self.assertRegex(source["STOCK_ROOTFS_SHA256"], r"^[0-9a-f]{64}$")
        listing = self.write(source["GLIBC_STOCK_ELF"])
        stock = manifest.read_list(listing)
        for needed in ["usr/lib/libc.so.6", "usr/lib/ld-linux-aarch64.so.1", "usr/lib/libm.so.6", "usr/lib/libresolv.so.2",
                       "usr/lib/libnss_files.so.2", "usr/bin/ldconfig"]:
            self.assertIn(needed, stock)
        self.assertGreater(sum(rel.startswith("usr/lib/gconv/") for rel in stock), 200)
        self.assertFalse([rel for rel in stock if rel.endswith(".o") or rel.endswith(".a")])

    def write(self, text):
        temporary = tempfile.NamedTemporaryFile("w", suffix=".sha256", delete=False)
        self.addCleanup(os.unlink, temporary.name)
        temporary.write(text.strip() + "\n")
        temporary.close()
        return temporary.name

    def test_release_env_has_every_key_the_apk_build_reads(self):
        release = env_file(GLIBC / "release.env")
        for key in ["GLIBC_REPO", "GLIBC_TAG", "GLIBC_MANIFEST_SHA256", "GLIBC_REBUILD_SHA256", "GLIBC_PATCHED_SHA256"]:
            self.assertIn(key, release)
        self.assertEqual(release["GLIBC_REPO"], "Leb-Sun/DroidDeck")
        if release["GLIBC_TAG"]:
            for key in ["GLIBC_MANIFEST_SHA256", "GLIBC_REBUILD_SHA256", "GLIBC_PATCHED_SHA256"]:
                self.assertRegex(release[key], r"^[0-9a-f]{64}$")

    def test_patches_are_numbered_and_ours(self):
        patches = sorted(p.name for p in (GLIBC / "patches").glob("*.patch"))
        self.assertEqual([p[:4] for p in patches], [f"{n:04d}" for n in range(1, 8)])
        for name in patches:
            text = (GLIBC / "patches" / name).read_text()
            self.assertIn("From: DroidDeck <droiddeck@users.noreply.github.com>", text)
            self.assertNotIn("spike", text.lower(), name)
        gate = (GLIBC / "patches" / patches[-1]).read_text()
        self.assertIn("#define BL_BLK_CLOSE_RANGE 0x8", gate)

    def test_the_apps_close_range_bit_is_the_patches(self):
        probe = (REPO / "app/src/main/java/com/droiddeck/launcher/core/CapProbe.kt").read_text()
        self.assertIn('"close_range" to 0x8', probe)
        bits = {"CLONE3": "0x1", "FACCESSAT2": "0x2", "FCHMODAT2": "0x4"}
        gate = (GLIBC / "patches").glob("0001-*.patch").__next__().read_text()
        for name, bit in bits.items():
            self.assertRegex(gate, rf"#define BL_BLK_{name} +{bit}")


if __name__ == "__main__":
    unittest.main()
