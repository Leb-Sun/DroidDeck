# glibc patches carried by the app

Layer 3 of the native environment (`docs/development/native-env/03-design.md` §4): the runtime's own
glibc, Arch Linux ARM's `2.43+r22+g8362e8ce10b2-2`, rebuilt so that a process with no tracer never
issues a syscall Android's app seccomp filter answers with `SIGSYS`. Under proot nothing changes
for the guest: each patched call takes a path glibc already has for older kernels.

## How it is built and shipped

- `source.env` pins glibc commit `8362e8ce10b2` on `release/2.43/master` (the commit in Arch's
  version string), Arch Linux ARM's PKGBUILD at the commit that published that pkgrel, the runtime
  image the stock package is taken from, and the sha256 of every ELF shared object and executable
  (`ET_DYN`) in the stock package. Those 292 files are the overlay: `libc`, `ld.so`, `libm`,
  `libresolv`, `libnss_*`, the gconv modules and the programs share `GLIBC_PRIVATE`, so they come
  from one build and are swapped together. The link-time objects (`crt*.o`, `libmcheck.a`) stay
  stock.
- `.github/workflows/build-glibc.yml` runs `build-in-arch.sh` in an Arch Linux ARM container on an
  arm64 runner: `makepkg` with Arch's own PKGBUILD, twice, at the same path. `rebuild` is Arch's
  build with no patch of ours (it shows the pipeline and the staging change nothing); `patched`
  adds `patches/` after Arch's own `prepare()`. Each flavor must have exactly the stock package's
  `ET_DYN` file set, the same dynamic symbols, `SONAME` and `NEEDED` for every file
  (`manifest.py symbols`), and `abidiff` clean for every shared object (`abidiff.sh`: in the
  container when Arch Linux ARM has libabigail, and on the runner with Ubuntu's). Arch Linux ARM
  ships no debug info, so both compare ELF symbol tables and versions, not types. The build's
  `__ASSUME_FACCESSAT2`, `__ASSUME_FCHMODAT2` and `__ASSUME_SET_ROBUST_LIST` are read from its own
  `kernel-features.h` under its `config.h`, recorded, and must be 0, 0 and 1 for `patched`
  (`04-spikes.md`, device check 2). The outputs: `glibc-rebuild.tzst`, `glibc-patched.tzst`,
  `glibc-manifest.txt` (each file's mode and stock size, and its stock, rebuild and patched sha256)
  and `build-record.txt`.
- A push publishes them as a release on this repository named after the inputs
  (`glibc-2.43-r22-2-<hash>`), never replacing one that exists; the run's summary prints the
  `release.env` lines that pin it. With no tag in `release.env` the apk builds without the overlay.
- `build.yml` downloads the pinned assets, checks their sha256, unpacks both flavors into
  `assets/linuxfs/glibc/<version>/{rebuild,patched}/` beside the manifest, and checks every file
  against it (`manifest.py check`).
- The app (`session/NativeGlibc.kt`, Performance > Patched glibc, off by default) stages a flavor
  at session start only when the rootfs's pacman entry names this version and every file still
  hashes to the stock column; the stock file is kept as `<name>.droiddeck-stock` and the state is
  written to `/etc/droiddeck/glibc-state`. A pacman hook (`zz-droiddeck-glibc.hook`) marks it
  `mismatch` when glibc is upgraded. `glibcFlavor` (`droiddeckctl prefs set session glibcFlavor
  rebuild`) picks the flavor.

A new runtime glibc means a new `source.env` (version, commit, PKGBUILD commit, runtime image and
its stock list, which `build-in-arch.sh` prints when it differs) and the patches checked against the
new tree.

## The patches

All of them are against `8362e8ce10b2` with Arch Linux ARM's six `prepare()` patches applied, as
`makepkg` applies them. `0001`–`0006` are the feasibility spike's (`tools/native/glibc-patches`,
`04-spikes.md` S1) rebased: none of the 22 commits since `glibc-2.43` touches a file they change,
they apply without offset, and their code is the spike's; only comments changed. `BL_NATIVE_BLOCK`
is the hex mask the app sets from its capability probe (`core/CapProbe.kt`, `BLOCK_BITS`); unset,
every gated call counts as blocked.

- `0001-bl-native-block-gate.patch` - the gate: `BL_NATIVE_BLOCK` read once (an ELF constructor,
  and lazily on the first query) into a hidden cache that `__bl_blocked ()` tests, `0x1` `clone3`,
  `0x2` `faccessat2`, `0x4` `fchmodat2`. `setfsuid` and `setfsgid`, blocked on every release with
  no replacement, return the current effective id without the syscall, which is what the fsuid of a
  process that cannot change its ids is.
- `0002-no-rseq-no-set-robust-list.patch` - `rseq` and `set_robust_list` are never issued. Android
  blocks both on every release, and `ld.so` issues them while it sets up the first thread's TLS,
  before any preload exists: the reason a stock glibc dies untraced (`04` S0). Three sites: `ld.so`'s
  TLS set-up, `pthread_create` and the fork child, where glibc issues `set_robust_list`
  unconditionally because `__ASSUME_SET_ROBUST_LIST` holds for the minimum kernel. The robust list
  stays linked in userspace; only the kernel's owner-death cleanup of robust mutexes is lost.
  `sched_getcpu` falls back to `getcpu`.
- `0003-clone3-fallback.patch` - with `0x1`, `__clone3_internal` takes its `clone` fallback as if
  `clone3` had answered `ENOSYS`, and so does `posix_spawn`, which calls `__clone3` directly (blocked
  before Android 15; `pthread_create` issues it with every signal blocked, so no handler could catch
  it).
- `0004-faccessat2-fchmodat2-fallback.patch` - with `0x2` or `0x4`, `faccessat` and `fchmodat`
  take glibc's existing fallback instead of `faccessat2`/`fchmodat2` (blocked before Android 16
  QPR1). The fallback is compiled in only while `__ASSUME_FACCESSAT2` and `__ASSUME_FCHMODAT2` are
  0, which the build checks.
- `0005-setid-through-setres.patch` - `setuid`, `setgid`, `setreuid` and `setregid` go through the
  allowed `setresuid`/`setresgid` (still via the setxid broadcast to every thread); `setgroups`
  reports success. They differ from the originals only for an unprivileged `setuid` to the real or
  saved id and a `setgroups` read back, neither of which arises with the app's single uid.
- `0006-sysv-ipc-get-enosys.patch` - `shmget`, `semget` and `msgget` answer `ENOSYS`: Android traps
  SysV IPC and GKI kernels have none, so no id can exist and the other calls are never reached. In a
  session `libblsession`'s `sysv.c` interposes all three ahead of libc with its file-backed objects,
  so Steam's semaphore and mangoapp's queue are still answered there.
- `0007-close-range-gate.patch` - new: `0x8` gates `close_range`, which Android 12's filter traps.
  `close_range` then fails with `ENOSYS` without the syscall, as on a kernel older than 5.9, and
  `posix_spawn`'s closefrom action does the same; both, and `closefrom`, fall back to glibc's own
  `__closefrom_fallback`, which closes what `/proc/self/fd` lists. Programs that call `close_range`
  themselves (CPython's `subprocess`, GLib) treat `ENOSYS` the same way. The generic close loop is
  not used: it would close every number up to `RLIMIT_NOFILE`, which Steam raises to the hard limit.

## Checked on the host (x86_64)

The rebased set, built for x86_64 from the same tree with the spike's configuration, runs the
spike's userland (`/bin/true`, bash, coreutils, Python with threads, imports and `subprocess`,
`tests/s1_battery.c`, `tests/close_range_test.c`) untraced under `tools/native/launcher`'s
`android14`, `android15`, `android16q` and `android12` profiles with 0 traps across every process
(`trap-trace --continue`). Under `android12` with `BL_NATIVE_BLOCK=7` (close_range not marked) the
close_range test dies of `SIGSYS` and Python's `subprocess` child with it: the gate is what keeps
them alive. Against an unpatched build of the same tree, every one of the 293 `ET_DYN` files keeps
its dynamic symbols, `SONAME` and `NEEDED`, and `abidiff` is clean for the 275 shared objects once
both are stripped as `makepkg` strips them. With DWARF, `abidiff` lists `__close_range`, `setfsuid`
and `setfsgid` as changed from `void f ()`: the stock ones are assembly syscall stubs with no type
information, ours are C with the public prototypes.
