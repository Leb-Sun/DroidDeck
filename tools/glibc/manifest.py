#!/usr/bin/env python3
"""The glibc overlay's file set and its manifest (tools/glibc, session/NativeGlibc.kt).

The overlay is every ELF shared object and executable (ET_DYN) the glibc package owns, from one
build: libc, ld.so, libm, libresolv, libnss_*, the gconv modules and the programs share
GLIBC_PRIVATE interfaces, so they are swapped together or not at all. The link-time objects
(crt*.o, libmcheck.a: ET_REL) are never loaded and stay stock.

The manifest names each file's stock, rebuild and patched sha256; the app hashes the rootfs
against the stock column before it renames anything into place. Its format, one record a line:

    droiddeck-glibc-manifest 1
    version <the glibc package's pacman version, e.g. 2.43+r22+g8362e8ce10b2-2>
    commit <glibc git commit>
    assume <__ASSUME_* values of the build, for the record>
    patches <the patches the patched flavor carries>
    file <mode, octal> <stock size> <stock sha256> <rebuild sha256> <patched sha256> <path>

Commands (stdlib only; build-in-arch.sh and build.yml run them):
    stock-list ROOT DBDIR              the stock package's ET_DYN files under ROOT as sha256sum
                                       lines, each checked against the package's own mtree
    collect STOCKLIST PKGROOT DEST     copy a built package's ET_DYN files to DEST, failing unless
                                       they are exactly the stock list's paths
    symbols STOCKLIST STOCKROOT ROOT   compare every file's dynamic symbols, SONAME and NEEDED
    write [--...] STOCKLIST STOCKROOT REBUILD PATCHED
                                       print the manifest
    check DIR                          DIR/manifest against DIR/rebuild and DIR/patched: every
                                       file present with its sha256 and mode, nothing else
"""
import argparse
import gzip
import hashlib
import os
import re
import shutil
import struct
import subprocess
import sys

MAGIC = "droiddeck-glibc-manifest 1"
FLAVORS = ("rebuild", "patched")
HEX64 = re.compile(r"[0-9a-f]{64}")


class ManifestError(ValueError):
    pass


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def elf_type(path):
    """The ELF e_type of [path] (3 = ET_DYN), or None when it is not an ELF file."""
    with open(path, "rb") as f:
        head = f.read(18)
    if len(head) < 18 or head[:4] != b"\x7fELF":
        return None
    return struct.unpack_from("<H" if head[5] == 1 else ">H", head, 16)[0]


def valid_path(path):
    """A relative path inside the rootfs: no absolute path, no '..', no empty or dot segment."""
    if not path or path.startswith("/") or path.endswith("/"):
        return False
    return all(part not in ("", ".", "..") for part in path.split("/"))


def dyn_files(root):
    """Every regular (not symlinked) ET_DYN file under [root], as relative paths."""
    found = []
    for directory, dirs, files in os.walk(root):
        dirs.sort()
        for name in files:
            path = os.path.join(directory, name)
            if os.path.islink(path) or not os.path.isfile(path):
                continue
            if elf_type(path) == 3:
                found.append(os.path.relpath(path, root))
    return sorted(found)


def read_list(path):
    """A sha256sum-style list: {relative path: sha256}."""
    entries = {}
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            digest, rel = line.split(None, 1)
            rel = rel.lstrip("*").strip()
            if not HEX64.fullmatch(digest) or not valid_path(rel) or rel in entries:
                raise ManifestError("bad list line: " + line)
            entries[rel] = digest
    return entries


def package_files(dbdir):
    """The paths the pacman package in [dbdir] owns (its files list), directories left out."""
    paths, on = [], False
    with open(os.path.join(dbdir, "files")) as f:
        for line in f:
            line = line.rstrip("\n")
            if line.startswith("%"):
                on = line == "%FILES%"
            elif on and line and not line.endswith("/"):
                paths.append(line)
    return paths


def mtree_digests(dbdir):
    digests = {}
    with gzip.open(os.path.join(dbdir, "mtree"), "rt") as f:
        for line in f:
            if not line.startswith("./"):
                continue
            words = line.split()
            fields = dict(w.split("=", 1) for w in words[1:] if "=" in w)
            if "sha256digest" in fields:
                digests[words[0][2:]] = fields["sha256digest"]
    return digests


def stock_list(root, dbdir):
    mtree = mtree_digests(dbdir)
    lines = []
    for rel in package_files(dbdir):
        path = os.path.join(root, rel)
        if os.path.islink(path) or not os.path.isfile(path) or elf_type(path) != 3:
            continue
        digest = sha256(path)
        if mtree.get(rel) != digest:
            raise ManifestError(f"{rel}: {digest} is not the package's own file ({mtree.get(rel)})")
        lines.append(f"{digest}  {rel}")
    if not lines:
        raise ManifestError("no ELF file found for the package")
    return sorted(lines, key=lambda line: line.split(None, 1)[1])


def collect(stock, pkgroot, dest):
    built = dyn_files(pkgroot)
    missing = sorted(set(stock) - set(built))
    extra = sorted(set(built) - set(stock))
    if missing or extra:
        raise ManifestError("the build's ELF files differ from the stock package's: missing "
                            + (" ".join(missing) or "none") + "; extra " + (" ".join(extra) or "none"))
    for rel in built:
        target = os.path.join(dest, rel)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        shutil.copy2(os.path.join(pkgroot, rel), target)
    return built


def symbols(path, readelf="readelf"):
    """The ABI-visible surface of one ELF file: SONAME, NEEDED and the defined dynamic symbols."""
    dynamic = subprocess.run([readelf, "-d", "-W", path], capture_output=True, text=True, check=True).stdout
    table = subprocess.run([readelf, "--dyn-syms", "-W", path], capture_output=True, text=True, check=True).stdout
    surface = set()
    for line in dynamic.splitlines():
        match = re.search(r"\((SONAME|NEEDED)\)\s+\S+.*\[(.*)\]", line)
        if match:
            surface.add(f"{match.group(1)} {match.group(2)}")
    for line in table.splitlines():
        words = line.split()
        # Num: Value Size Type Bind Vis [other, e.g. VARIANT_PCS on aarch64] Ndx Name
        if len(words) < 8 or not words[0].endswith(":"):
            continue
        vis = words[5]
        if words[6].startswith("["):
            vis += " " + words[6]
            words = words[:6] + words[7:]
        if len(words) < 8 or words[6] == "UND":
            continue
        size = words[2] if words[3] in ("OBJECT", "TLS") else "-"
        surface.add(f"SYM {words[7]} {words[3]} {words[4]} {vis} {size}")
    return surface


def compare_symbols(stock, stockroot, root):
    problems = []
    for rel in sorted(stock):
        a = symbols(os.path.join(stockroot, rel))
        b = symbols(os.path.join(root, rel))
        for item in sorted(a - b):
            problems.append(f"{rel}: - {item}")
        for item in sorted(b - a):
            problems.append(f"{rel}: + {item}")
    return problems


def write(stock, stockroot, rebuild, patched, version, commit, assume, patches):
    lines = [MAGIC, "version " + version, "commit " + commit, "assume " + assume, "patches " + " ".join(patches)]
    for rel in sorted(stock):
        source = os.path.join(stockroot, rel)
        if sha256(source) != stock[rel]:
            raise ManifestError(rel + ": the stock file does not match the stock list")
        mode = os.stat(source).st_mode & 0o7777
        digests = [sha256(os.path.join(tree, rel)) for tree in (rebuild, patched)]
        lines.append(f"file {mode:o} {os.path.getsize(source)} {stock[rel]} {digests[0]} {digests[1]} {rel}")
    return "\n".join(lines) + "\n"


def parse(text):
    """{version, commit, assume, patches, files: [(path, mode, size, stock, rebuild, patched)]}."""
    lines = text.splitlines()
    if not lines or lines[0] != MAGIC:
        raise ManifestError("not a glibc overlay manifest")
    result = {"version": None, "commit": None, "assume": "", "patches": [], "files": []}
    seen = set()
    for line in lines[1:]:
        key, _, rest = line.partition(" ")
        if key == "file":
            words = rest.split(" ")
            if len(words) != 6:
                raise ManifestError("bad file line: " + line)
            mode, size, stock, rebuild, patched, rel = words
            if not re.fullmatch(r"[0-7]{1,4}", mode) or not size.isdigit() \
                    or not all(HEX64.fullmatch(h) for h in (stock, rebuild, patched)) \
                    or not valid_path(rel) or rel in seen:
                raise ManifestError("bad file line: " + line)
            seen.add(rel)
            result["files"].append((rel, int(mode, 8), int(size), stock, rebuild, patched))
        elif key in ("version", "commit"):
            result[key] = rest
        elif key == "assume":
            result["assume"] = rest
        elif key == "patches":
            result["patches"] = rest.split()
    if not result["version"] or not result["files"]:
        raise ManifestError("the manifest names no version or no file")
    return result


def check(directory):
    """Every file the manifest names, in each flavor's tree, with its sha256 and mode; nothing else."""
    with open(os.path.join(directory, "manifest")) as f:
        manifest = parse(f.read())
    problems = []
    for index, flavor in enumerate(FLAVORS):
        tree = os.path.join(directory, flavor)
        named = {rel for rel, *_ in manifest["files"]}
        present = set()
        for base, _, files in os.walk(tree):
            for name in files:
                present.add(os.path.relpath(os.path.join(base, name), tree))
        for rel in sorted(present - named):
            problems.append(f"{flavor}: {rel} is not in the manifest")
        for rel, mode, _, _, *hashes in manifest["files"]:
            path = os.path.join(tree, rel)
            if not os.path.isfile(path) or os.path.islink(path):
                problems.append(f"{flavor}: {rel} is missing")
            elif sha256(path) != hashes[index]:
                problems.append(f"{flavor}: {rel} does not match the manifest")
            elif os.stat(path).st_mode & 0o7777 != mode:
                problems.append(f"{flavor}: {rel} has mode {os.stat(path).st_mode & 0o7777:o}, not {mode:o}")
    return manifest, problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("stock-list")
    p.add_argument("root")
    p.add_argument("dbdir")
    p = commands.add_parser("collect")
    p.add_argument("stocklist")
    p.add_argument("pkgroot")
    p.add_argument("dest")
    p = commands.add_parser("symbols")
    p.add_argument("stocklist")
    p.add_argument("stockroot")
    p.add_argument("root")
    p = commands.add_parser("write")
    p.add_argument("--version", required=True)
    p.add_argument("--commit", required=True)
    p.add_argument("--assume", required=True)
    p.add_argument("--patches", default="", help="the patched flavor's patches, space-separated")
    p.add_argument("stocklist")
    p.add_argument("stockroot")
    p.add_argument("rebuild")
    p.add_argument("patched")
    p = commands.add_parser("check")
    p.add_argument("directory")
    args = parser.parse_args(argv)
    try:
        if args.command == "stock-list":
            print("\n".join(stock_list(args.root, args.dbdir)))
        elif args.command == "collect":
            files = collect(read_list(args.stocklist), args.pkgroot, args.dest)
            print(f"{len(files)} ELF files collected")
        elif args.command == "symbols":
            problems = compare_symbols(read_list(args.stocklist), args.stockroot, args.root)
            for line in problems:
                print(line)
            if problems:
                return 1
            print("dynamic symbols, SONAME and NEEDED identical")
        elif args.command == "write":
            sys.stdout.write(write(read_list(args.stocklist), args.stockroot, args.rebuild, args.patched,
                                   args.version, args.commit, args.assume, args.patches.split()))
        elif args.command == "check":
            manifest, problems = check(args.directory)
            for line in problems:
                print(line)
            if problems:
                return 1
            print(f"glibc {manifest['version']}: {len(manifest['files'])} files in each of {', '.join(FLAVORS)} match")
    except (ManifestError, OSError, subprocess.CalledProcessError) as error:
        print(f"{args.command}: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
