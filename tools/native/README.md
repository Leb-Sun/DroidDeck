# tools/native — native-environment Phase 2 feasibility spike

**PROTOTYPE. Not wired into the DroidDeck app or CI.** This directory holds the
code and patches for the Phase 2 spike that answers: *can a stock-ABI glibc
program run with no tracer under Android's app seccomp policy, with exec and
path translation working, using (a) a patched glibc that never issues blocked
syscalls and translates its own path calls, and (b) an exec shim through the
rootfs's `ld.so`?* The findings, cost table and go/no-go are in
[`docs/development/native-env/04-spikes.md`](../../docs/development/native-env/04-spikes.md).

The spike runs on an **x86_64 Linux host** (there is no device in the cloud
container). The patched glibc logic is architecture-generic
(`sysdeps/unix/sysv/linux`, `sysdeps/nptl`), so x86_64 is a valid testbed;
aarch64 differences are recorded in the doc and emitted by
`seccomp-launch --dump aarch64`. Toolchains, glibc sources and rootfs tarballs
live **outside the repo** (under `/opt/nspike` during the spike), never here.

## Pieces

| path | what |
|---|---|
| `launcher/seccomp-launch.c` | Installs a seccomp filter reproducing Android's app-policy blocklist with `SECCOMP_RET_TRAP` (SIGSYS), then execs the target. Profiles `android14`/`android15`/`android16q`, and `android12` (android14 plus `close_range`, for glibc patch 0007). `--dump {x86_64,aarch64}` prints the policy as numbers. This is the "no tracer" harness: a trapped syscall kills the process, as on a device. |
| `launcher/trap-trace.c` | Diagnostic only: ptraces the target (following fork/vfork/clone/exec) and reports which blocked syscall each SIGSYS came from. A SIGSYS handler cannot do this — it resets across `execve` (02 §5.3) — so a tracer is the only way to *name* an escape. Not part of the runtime. |
| `launcher/policy.inc.h` | The shared blocklist table (x86_64 + aarch64 numbers) and BPF builder. |
| `glibc-patches/*.patch` | The minimum glibc 2.43 patch set (see below), against upstream `glibc_2.43.orig.tar.xz`. Apply `0001`…`0006` in order with `patch -p1`. The shipping set, rebased onto Arch's commit with `0007` added, is `tools/glibc/patches` (`tools/glibc/PATCHES.md`). |
| `execshim/execshim.c` | `LD_PRELOAD` exec shim: rewrites exec of a rootfs ELF into `$BL_LOADER --argv0 <a0> <prog>`, resolves shebangs inside the rootfs, and emulates `/proc/self/exe` (readlink/open/execve) and `/proc/self/comm`. Covers the whole exec family and `posix_spawn`. |
| `pathxlat/pathxlat_core.h` | Header-only guest↔host path translator matching `tools/proot/fastpath/fastpath.c`'s longest-prefix / identity / nested-override bind semantics. Freestanding, so the same logic can go into ld.so. |
| `pathxlat/libpathxlat.c` | `LD_PRELOAD` built on the core: translates the libc path wrappers for the relocated-rootfs case; table from `BL_XLAT_ROOT`/`BL_XLAT_BINDS`. |
| `tests/test_pathxlat_core.c` | Unit test for the translator (identity, remap, nested override, single-file, component-boundary, round-trip). |
| `tests/s1_battery.c` | Userland battery: pthreads, posix_spawn/system/popen, faccessat flags, the set*id family. |
| `tests/close_range_test.c` | glibc patch 0007: `close_range`, `closefrom` and posix_spawn's closefrom action with `close_range` trapped (`spike.sh s7`). |
| `bench/micro.c`, `bench/cost-matrix.sh` | Per-call / workload microbench and the S4 cost harness (native vs patched-untraced vs proot). |
| `spike.sh` | Re-runs the spike steps (see `spike.sh help`). |

## Minimum glibc patch set (what each does)

- `0001` — adds the `BL_NATIVE_BLOCK` runtime gate (`bl-seccomp.{c,h}`), the
  `setfsuid`/`setfsgid` override sources, and the Makefile/`syscalls.list` wiring.
- `0002` — rseq and `set_robust_list` are never issued (ld.so TLS setup,
  `pthread_create`, `_Fork`); they are blocked on every Android 10–17.
- `0003` — `clone3` falls back to `clone` (never trapping) when the gate marks
  it blocked (Android 14); covers both `__clone3_internal` and `posix_spawn`.
- `0004` — `faccessat2`/`fchmodat2` take glibc's portable fallback instead of
  issuing the syscall when the gate marks them blocked (before Android 16 QPR1).
- `0005` — `setuid/setgid/setreuid/setregid` map onto the allowed
  `setresuid/setresgid`; `setgroups` is a no-op success; `setfsuid/setfsgid`
  return the current id without the blocked syscall.
- `0006` — SysV `shmget/semget/msgget` return `ENOSYS` (the gateway refusal;
  the ops are then unreachable with a real id). io_uring needs no patch —
  glibc has no io_uring wrappers.

`BL_NATIVE_BLOCK` is a hex mask the app sets per detected Android version
(`0x1` clone3, `0x2` faccessat2, `0x4` fchmodat2); unset = all blocked
(the Android-14 worst case, always safe). rseq/`set_robust_list` need no gate.

## How to rebuild the patched glibc (outline)

1. Fetch upstream `glibc_2.43.orig.tar.xz` (sha256 `d9c86c6b…`) and extract.
2. `for p in glibc-patches/000*.patch; do patch -p1 <$p; done`.
3. Configure like Arch (`--prefix=/usr --enable-bind-now --enable-fortify-source
   --disable-multi-arch --enable-stack-protector=strong --disable-nscd
   --without-selinux`), with a `configparms` that sets `slibdir`/`rtlddir` to the
   target rootfs's multiarch layout, and `MAKEINFO=:`.
4. `make -j && make install DESTDIR=…`, overlay onto a relocatable test rootfs
   (an Ubuntu/Arch/debootstrap export whose bash/coreutils/python3 pick up the
   new libc), run `ldconfig`.

On device the glibc is Arch Linux ARM `2.43+r22`; build from the matching
upstream 2.43 release branch (the GitHub `bminor/glibc` mirror stops at
`2.42.9000`, so use a distro's `2.43` orig tarball or sourceware when reachable)
plus Arch's configure flags. See `docs/development/native-env/04-spikes.md` for
the aarch64/device caveats.
