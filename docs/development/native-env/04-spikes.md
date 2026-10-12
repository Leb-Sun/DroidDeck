# 04: Phase 2 feasibility spikes — a tracer-free glibc

Phase 2 spike for the Gate-1 direction (`PLAN.md`, "Direction"): layers 3
(proot-aware glibc) and 4 (native, untraced mode). It answers one question:

> Can a stock-ABI glibc program run with **no tracer at all** under Android's
> app seccomp policy, with exec and path translation working, using (a) a
> patched glibc that never issues Android-blocked syscalls and translates its
> own path calls, and (b) an exec shim through the rootfs's `ld.so`?

**Short answer: yes, for the dynamically-linked glibc userland, measured.** A
glibc 2.43 built with a six-patch set runs bash, coreutils, Python (threads and
imports), `posix_spawn`/`system`/`popen`, `pthread`-heavy code and `faccessat`
with flags **untraced** under a simulation of the strictest Android app policy,
with exec through `ld.so` and in-process path translation. Per path call it is
~1 µs (native speed) against proot's ~30–60 µs. What still needs a tracer or a
rebuild is a bounded, named list (§S5).

Conventions as in `01`/`02`: **[measured]** is a number from a run in this
spike (host, see the caveat); **[inferred]** is reasoning. `path:line` cites the
repo. Spike code and its reruns live in `tools/native/` (prototype, not wired
into the app or CI; `tools/native/README.md`).

## Method and its limits

- **No device.** The container is x86_64. The spike builds and measures on the
  host. The patched glibc code is architecture-generic
  (`sysdeps/unix/sysv/linux`, `sysdeps/nptl`), so x86_64 exercises the same
  logic; every aarch64/Android difference found is in §"Verify on device".
- **glibc.** Upstream `glibc 2.43` (the release the device's Arch `2.43+r22`
  is cut from, `01:16`), built with Arch's configure flags, installed into a
  relocatable test rootfs made from an Ubuntu 26.04 userland (its own glibc is
  also 2.43, so the ABI matches) whose bash/coreutils/python3 pick up the
  patched libc. **[measured]** the patched loader reports
  `ld.so (GNU libc) stable release version 2.43`.
- **The filter.** `tools/native/launcher/seccomp-launch.c` installs a classic
  BPF filter that answers Android's app-policy blocklist (`02` §3) with
  `SECCOMP_RET_TRAP` (SIGSYS) and execs the target, so filters persist across
  `execve` exactly as on a device. Three profiles: `android14` (also `clone3`,
  `faccessat2`, `fchmodat2`, `openat2` blocked), `android15` (`clone3`
  allowed), `android16q` (the three `*at2`/`openat2` allowed too). The same
  table in aarch64 numbers is emitted by `seccomp-launch --dump aarch64` for an
  on-device probe (`policy.inc.h` carries both columns).
- **The "no tracer" test is `seccomp-launch` alone** (a trap kills the process,
  as on a device). `trap-trace.c` is a *diagnostic* that ptraces the tree only
  to name which syscall escaped — a SIGSYS handler cannot, because dispositions
  reset across `execve` (`02` §5.3), so it never sees ld.so's earliest calls.
- **Host-number caveat.** Absolute times are a relative x86_64 desktop picture,
  not phone numbers. Ratios (patched-untraced vs proot) are the transferable
  part, and they line up with `02` §1.5's device figure (metadata 33×).

## S0 — baseline: stock glibc dies at startup

Under the filter, with no tracer, a stock dynamic `/bin/true` **dies with
SIGSYS** (exit 159). `trap-trace` names the first escaped syscall:
`set_robust_list` (x86_64 273 / aarch64 99), from ld.so's TLS setup, then
`rseq` (334 / 293) — and nothing else for `true` or `bash -c true`.
**[measured]** This is exactly `01` §0's prediction
(`glibc:sysdeps/nptl/dl-tls_init_tp.c:95,111`): both fire in ld.so before any
preload exists, so no in-process `SIGSYS` handler can save them — only a
patched glibc or a tracer can.

## S1 — a glibc that never issues blocked syscalls

**Result: with the patch set, the dynamic userland trips the filter zero times
under the strictest profile (`android14`). [measured]** The exhaustive
`trap-trace --continue` sweep (following every fork/vfork/clone/thread) reports
**0 traps** for `/bin/true`, `bash` (pipes, subshells), the coreutils script
(`ls`/`wc`/`md5sum`/`date`/`cp`/`unlink`/`od`), `python3 -I` (8 threads +
`json`/`ssl`/`subprocess`), and the `s1_battery` (16 pthreads, posix_spawn,
system, popen, faccessat flags, the set*id family). All five pass untraced on
all three profiles (`tools/native/tests/s1_battery.c`,
`tools/native/spike.sh s1`).

### Minimum patch set (one line each)

Against upstream 2.43, in `tools/native/glibc-patches/` (apply `0001`…`0006`,
`-p1`; verified to apply cleanly and reproduce the built tree byte-for-byte):

1. **`0001`** — runtime gate `__bl_blocked()` reading a hex env mask
   `BL_NATIVE_BLOCK` once (`sysdeps/unix/sysv/linux/bl-seccomp.{c,h}`), plus the
   `setfsuid`/`setfsgid` override sources and the Makefile/`syscalls.list`
   wiring. The app sets the mask from its session-start probe (`02` §9.1); unset
   = "all version-gated syscalls blocked" (the Android-14 worst case, always safe).
2. **`0002`** — never issue `rseq` or `set_robust_list`. Three sites, because
   `__ASSUME_SET_ROBUST_LIST` is defined for the minimum kernel so glibc would
   otherwise call it *unconditionally*: ld.so TLS init
   (`sysdeps/nptl/dl-tls_init_tp.c`), every new thread (`nptl/pthread_create.c`),
   and every fork child (`sysdeps/nptl/_Fork.c`). The robust list stays linked
   in userspace; only kernel owner-death cleanup is lost.
3. **`0003`** — `clone3` falls back to `clone` without ever trapping when the
   gate marks it blocked. Two sites: `__clone3_internal`
   (`sysdeps/unix/sysv/linux/clone-internal.c`) **and** `posix_spawn`, which
   calls `__clone3` directly (`sysdeps/unix/sysv/linux/spawni.c`) — the second
   was found only because `system`/`popen`/`posix_spawn` kept trapping after the
   first was fixed. Issued with all signals blocked, so this must be in glibc,
   not a handler (`01` P10).
4. **`0004`** — `faccessat2`/`fchmodat2` take glibc's existing portable fallback
   instead of issuing the syscall when the gate marks them blocked
   (`sysdeps/unix/sysv/linux/{faccessat,fchmodat}.c`). Safe because the minimum
   kernel leaves `__ASSUME_FACCESSAT2`/`__ASSUME_FCHMODAT2` at 0, so the
   fallback is compiled in.
5. **`0005`** — `setuid→setresuid(u,u,u)`, `setgid→setresgid(g,g,g)`,
   `setreuid→setresuid(r,e,-1)`, `setregid→setresgid(r,e,-1)` (all allowed;
   the setxid broadcast still runs); `setgroups→`no-op success;
   `setfsuid/setfsgid→`return the current id. Divergences (unprivileged
   `setuid` to real/saved uid; `setgroups` not really applied) do not arise in
   the single-uid app environment (`01` P12).
6. **`0006`** — SysV `shmget/semget/msgget` return `ENOSYS` (the gateway; the
   `shmat`/`semop`/... paths are then unreachable with a real id, like upstream
   Termux). io_uring needs no patch: glibc issues no `io_uring_*` itself.

Nothing in the patched dynamic userland reaches the filter through a raw
`syscall()`. **[measured]**

## S2 — exec without a tracer

**Result: exec through `ld.so` works untraced, including shebangs, the whole
exec family + `posix_spawn`, and `/proc/self/exe`. [measured]**
(`tools/native/execshim/execshim.c`, `spike.sh s2`.)

An `LD_PRELOAD` shim rewrites exec of a rootfs ELF into
`$BL_LOADER --argv0 <orig argv0> <program> <args>` and resolves shebangs inside
the rootfs. Under the filter, with no tracer:

- a bash tree (`#!/usr/bin/env bash` → `env` → nested bash) runs; **[measured]**
- a Python `subprocess` tree runs — CPython uses `posix_spawn`, so the shim's
  `posix_spawn` hook (not a plain `execve` interposer) is what catches it, the
  case `01` P7/`02` §5.1 flag; **[measured]**
- a program that `readlink`s **and re-execs** `/proc/self/exe` gets the guest
  program both times, not `ld.so`. **[measured]**

Identity of the running program, after `ld.so prog` (`tools/native/` `ident`):

| source | direct exec | raw `ld.so prog` | `ld.so`+shim |
|---|---|---|---|
| `/proc/self/exe` | program | **ld.so** (wrong) | program (shim readlink/open/execve) |
| `AT_EXECFN` | program | **program** (ld.so fixes the auxv) | program |
| `/proc/self/comm` | program | **ld-linux-x86-64** | program (shim `prctl(PR_SET_NAME)`) |

So `AT_EXECFN` is already correct via `ld.so`'s own auxv fix-up (refines the
open worry in `02` §2.2); only `/proc/self/exe` (a kernel link) and
`/proc/self/comm` need emulation, and both are handled. The real exe is carried
to children in `$BL_EXEC_REAL`.

**Loader outside the rootfs** (the targetSdk-proof native-library-dir form,
`02` §6.3): a copy of `ld.so` at `/opt/altloader/ld-boot.so` run as the loader
**finds the rootfs's libraries and runs the program**. **[measured]** glibc's
`ld.so` copes with being executed from a foreign path (it resolves libraries
from `/etc/ld.so.cache` + defaults, or the given `--library-path`), so a loader
shipped in the APK's native-library directory is viable.

**Preload vs in-glibc:** the shim should ultimately live in the patched glibc's
`execve`/`posix_spawn`, not a preload — a preload misses raw `execve` syscalls
(static binaries, Go) and must re-list every entry point. The preload proved the
mechanism and measured it.

## S3 — path translation for glibc-internal calls

**Result: `ls /usr/lib`, `bash -c 'cd /tmp && pwd'`, `python3 -c 'import json,
ssl'` and a `dlopen` of an absolute guest path all work with the rootfs at an
odd path, no chroot and no proot, translating in-process. [measured]**
(`tools/native/pathxlat/`, `spike.sh s3`.)

- **The core** (`pathxlat_core.h`) reproduces `fastpath.c`'s longest-prefix /
  identity / nested-override bind semantics. Its unit test
  (`tests/test_pathxlat_core.c`, 20 cases) passes: rootfs prefix, identity
  binds, a remap (`/dev/shm`→`cache/shm`), a **nested override** bind inside a
  parent (the `SecondaryLibrary.kt` compatdata pattern, `01` P3), a single-file
  bind, a component-boundary near-miss, and `to_host`→`to_guest` round-trips.
- `getcwd` and `readlink` results are **detranslated** host→guest (`cd /tmp`
  then `pwd` prints `/tmp`; Python's `sys.prefix` is `/usr`). **[measured]**
- **Where the table lives: the environment** (`BL_XLAT_ROOT`/`BL_XLAT_BINDS`),
  read once. Justification: `ld.so` runs before any preload and needs the table
  too; it has the environment on its initial stack but no file yet, so an env
  table is the only source available to *both* `ld.so` and libc without a
  chicken-and-egg open. An mmap'd file would need an open to find it; building
  the binds into `ld.so` would need a rebuild per bind set.
- **Scope boundary (important):** a preload cannot translate **`ld.so`'s own
  library search** — it loads after ld.so. In this spike ld.so's search was
  satisfied with `--library-path`/`--inhibit-cache` as a stand-in, and the
  preload translated the program's runtime opens. The real layer-3 work is to
  put the same core into the patched glibc's `ld.so` open/stat/readlink
  chokepoints (and libc's), reading the env table at startup. That is a glibc
  patch this spike did **not** build (see the verdict's condition), but the
  core, the env-table decision and the cost are settled here.

**Per-call cost of translation [measured]:** the core `px_to_host` is **~80
ns/op** (dominated by `snprintf`; a hand-rolled concat would cut it). End to
end, a translated `lstat` of a guest path in the relocated rootfs is **1767
ns** vs **1031 ns** native — the ~0.7 µs gap is mostly the kernel resolving the
*deeper* relocated host path, not CPU in the translator. Still ~37× cheaper
than proot's 65 µs for the same call.

## S4 — cost, side by side

`tools/native/bench/cost-matrix.sh`, this host, patched glibc 2.43, patched
proot built from `tools/proot` (`host-proot.sh`, reused unmodified). **Host
numbers are relative only.** [measured]

| workload | native (glibc at /) | patched glibc, untraced, under filter | proot | proot ÷ untraced |
|---|---|---|---|---|
| `stat` (ns/op) | ~1000 | ~1030 | ~58000–66000 | **~60×** |
| `open`+close (ns/op) | ~1700 | ~1800–1960 | ~61000–72000 | **~35×** |
| `fork`+`exec`+`wait` `/bin/true` (µs/op) | ~1650 | ~1670 | ~2560–2930 | **~1.6×** |
| `python3 -c 'import json,ssl,os,subprocess'` (ms) | ~48 | ~49–53 | ~72–76 | **~1.5×** |
| `tar` 3000 files → /dev/null (ms) | ~11–13 | ~23–24 | ~790 | **~34×** |

Reading: **the untraced patched glibc is at native speed**; the seccomp BPF
filter adds no measurable per-call cost. proot's tracer tax is largest exactly
where `01`/`02` said it would be — per path call and metadata-heavy work (the
`tar` 34× matches `02` §1.5's device 33×) — and smallest on fork/exec and
Python import, where binary loading dominates and the tracer is a minority of
the time (consistent with `01` §8.1: steady state is already nearly untraced).
The relocated-rootfs translator (S3) adds ~0.3–0.7 µs per translated path call,
still ~30–40× under proot.

## S5 — what still escapes, and how to handle it

The dynamic patched-glibc userland is clean. These remain, each confirmed or
reasoned, with the recommended handling:

| escape | evidence | handling |
|---|---|---|
| **Static binaries** (their glibc is baked in, so the patches never reach it). Wine's static `wine-preloader` (`files/lib/wine/aarch64-unix/wine-preloader`, `01` P2) is the live case; a static test binary **dies on `rseq`+`set_robust_list`** under the filter [measured] | `trap-trace` on a static test ELF: 2×`set_robust_list` + `rseq` | Build the preloader against the patched glibc, or statically patch out those calls; until then **keep the Wine tree under proot** (the Gate-1 net). Wine 10/11: verify whether the arm64 preloader still issues them (device). |
| **Raw `svc` in non-glibc code** (FEX's `syscall()` passthrough for x86 guests; Go) | `02` §5.1; not reached by a libc patch | FEX path calls → `robust.c`'s `syscall()` hook (`01` P2, not yet wired); otherwise proot. |
| **`vfork` children** running blocked syscalls before `execve` | [inferred]; `posix_spawn`'s own clone path is now gated (`0003`) | glibc's own `vfork`+exec paths are covered by `0003`/exec shim; a program doing raw `vfork`+raw-syscall needs proot. |
| **`clone` without glibc** (a runtime that threads via raw `clone`/`clone3`, then `set_robust_list`/`rseq`) | [inferred] | Not glibc's to fix; proot, or the runtime avoids them. No such case in the Steam/CEF/Python tree. |
| **setuid semantics** beyond the single-uid mapping (`0005`): an unprivileged `setuid` to the real/saved uid, real `setgroups` | [inferred] | The identity-mode mapping suffices for the app's one uid (`01` P12); a true privilege change still needs proot's `fake_id0`/`-0`. |
| **Socket `sun_path` ≥ 108 bytes**, `/proc`&`/sys` stand-ins, long-path binds beyond the table | `01` P9/P4 | Unchanged from today: app-side short paths and the in-process stand-ins; proot as the net. |

**The safety net holds:** everything that escapes falls to proot, which is the
Gate-1 design (`PLAN.md`). Nothing here requires removing proot.

## Verify on device (aarch64 / Android)

Everything below is x86_64-tested logic that needs one confirmation on hardware:

1. **aarch64 syscall numbers.** The gate and filter are number-driven; the
   aarch64 column (`seccomp-launch --dump aarch64`, `policy.inc.h`) gives e.g.
   `set_robust_list=99`, `rseq=293`, `clone3=435`, `faccessat2=439`,
   `fchmodat2=452`, `setresuid=147`, `futex_waitv=449`. Confirm against the
   device headers before trusting the on-device probe.
2. **`__ASSUME_*` on the device glibc.** The patches rely on Arch aarch64's
   minimum kernel (3.7) leaving `__ASSUME_FACCESSAT2`/`FCHMODAT2` at 0 (so the
   fallback compiles in) and `__ASSUME_SET_ROBUST_LIST` at 1 (so all three
   `set_robust_list` sites are the unconditional ones `0002` edits). Re-check
   against Arch's actual `--enable-kernel` for `2.43+r22`.
3. **The session-start seccomp probe → `BL_NATIVE_BLOCK`.** The app forks a
   child (so a SIGSYS kills only the child, `02` §9.1), probes `clone3`,
   `faccessat2`, `fchmodat2`, and sets the mask in the guest env. Confirm the
   three profiles match the device's real policy per Android version.
4. **`ld.so` from the native-library directory** actually executing on the
   device domain (`apk_data_file` mmap `PROT_EXEC`, `02` §3), and `execve` of
   the loader being allowed (it is, `02` §3).
5. **`wine-preloader` in Wine 10/11 on arm64**: does it still issue
   `rseq`/`set_robust_list`/`clone3`, i.e. must the game tree stay traced.
6. **The in-glibc `ld.so`/libc path-translation patch** (the piece S3 stubbed
   with `--library-path`): build it, and confirm library search, `ld.so.cache`,
   preloads, nss, `realpath`, fts and `posix_spawn` translate at native speed on
   device, replacing the `--library-path` stand-in.
7. **16 KB pages**: unrelated to this spike but gating FEX/Proton (`02` §6.2);
   check rootfs ELF `LOAD` alignment.

## Verdict

- **Layer 3 (proot-aware glibc): GO.** A six-patch glibc 2.43 makes an untraced
  dynamic process *possible* and costs ~0 for the rewritten calls; the dynamic
  userland trips the filter zero times under the strictest profile [measured].
  **Condition:** the in-`ld.so`/libc path-translation patch (S3's stubbed half)
  must be written and measured on device — the mechanism, the env-table
  decision and the ~80 ns core cost are settled, the glibc entry-point patch is
  not. Gate-2 owner check before rebuilding the rootfs glibc (`PLAN.md`).
- **Layer 4 (native mode): GO WITH CONDITIONS.** Exec through `ld.so` with
  `--argv0`, shebangs, `posix_spawn`, `/proc/self/exe`/`comm` emulation and a
  loader outside the rootfs all work untraced [measured], at native exec cost
  and ~35–60× under proot on path calls. Conditions:
  1. start with the **Steam client + CEF tree** (largest exec/path load), keep
     **proot as the net for the game/Wine tree** — `wine-preloader` and any raw
     `svc` still need it until rebuilt;
  2. fold the exec shim and translator into the patched glibc / `libblsession`,
     not a preload, so raw `execve` and `ld.so`'s own opens are covered;
  3. validate on device against the baseline the harness produces (`harness.md`)
     before any toggle defaults on.

No result here contradicts Gate 1; it quantifies it. The tracer-free path is
real for the glibc userland, the escapes are few and named, and proot remains
the safety net for all of them.
