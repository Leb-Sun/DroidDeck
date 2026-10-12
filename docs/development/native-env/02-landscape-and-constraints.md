# Native Linux environment on Android: landscape and hard constraints

This is the Phase 1 external research for the native-environment work. It covers prior art, recent
platform changes and the hard limits for a non-root app. It builds on
[`proot-performance.md`](../proot-performance.md) and does not repeat its measurements. Section 10
lists where that document is now wrong or out of date.

Research date: 2026-10-12. "Today" means Android 17 is released (API 37), mainline Linux is at
7.3-rc6, and the newest GKI branch is `android17-6.18`.

**How the claims were checked.** Where possible, each claim was read from source at a pinned
commit, and those pins are listed in the References.
- `android.googlesource.com`, `source.android.com`, `lwn.net` and some other hosts could not be
  reached from the research sandbox. AOSP code was therefore read from the **LineageOS** and
  **GrapheneOS** GitHub mirrors, and each claim names the branch:
  - `lineage-23.0` = Android 16, `lineage-23.1` = 16 QPR1, `lineage-23.2` = 16 QPR2, `lineage-24.0` = 17;
  - GrapheneOS branches are named `16`, `16-qpr1` and `17`.
- The canonical AOSP URL is still given so a reader can follow it.
- Status markers:
  - **[src]** read in source or a binary;
  - **[doc]** vendor documentation;
  - **[sec]** secondary source (blog, forum, news);
  - **[unverified]** inference or untested.

---

## 0. The findings that matter most

1. **Android 16 QPR1 and later allow `openat2`, `faccessat2` and `fchmodat2` for apps. Android
   16 and later allow Landlock.** [src]
   - AOSP bionic commit [`1d1e54b`](https://android.googlesource.com/platform/bionic/+/1d1e54b2f63916f329512da4f67b00d62a52e7ae)
     (2025-03-31) added `openat2`, `faccessat2`, `fchmodat2` and `*xattrat` to
     `SECCOMP_ALLOWLIST_COMMON.TXT`.
   - It is in `lineage-23.1`, `lineage-23.2` and `lineage-24.0`, and in GrapheneOS `16-qpr1` and
     `17`. It is not in `lineage-23.0` or GrapheneOS `16`.
   - On those devices `openat2(RESOLVE_IN_ROOT)` is usable. `proot-performance.md` measured it at
     about 1.0 µs. glibc's `faccessat()` also stops costing a SIGSYS round trip.
2. **proot's own design already survives W^X at any targetSdk.** [src]
   - The kernel only ever execs proot's loader from the native-library directory (`apk_data_file`,
     which may be executed at every targetSdk). The loader then `mmap`s the program with
     `PROT_EXEC`, which is allowed on `app_data_file` at every targetSdk.
   - UserLAnd ships proot as "fake .so" executables at **targetSdk 37**. Termux's Play build
     targets 37 using `/system/bin/linker64`.
   - Only designs that `execve()` rootfs files directly, which is what every "no proot" design
     does, depend on targetSdk 28.
3. **No shipping project runs a glibc stack without proot at targetSdk ≥ 29.** [src]
   - GameNative's targetSdk-36 "modern" build removes glibc containers and runs only bionic builds
     through `/system/bin/linker64`.
   - Every glibc-native project (Winlator, GameNative legacy, Mobox, steamclienttermux) is
     targetSdk 28 or runs inside Termux, which is targetSdk 28.
4. **A process limit and a memory limit both apply to DroidDeck's single proot tree.** [src]
   - The phantom-process killer allows 32 child processes in total (Android 12+).
   - Android 17's new `MemoryLimiter` puts cgroup v2 `memory.high` and `memory.swap.max` limits on
     each app process, keyed to its process state (visible or not). On a device whose vendor ships
     the config, the AOSP example gives a ≥14 GB phone 8 GB plus 4 GB of swap while visible, and
     4 GB plus 4 GB as a foreground service. A process over the limit is killed 30 s later.
   - Forked children stay in the parent's cgroup [unverified for this limiter]. That would put
     Steam, CEF and the game under one budget.
5. **ADPF hint sessions accept threads from any process with the app's UID.** [src]
   - `HintManagerService.checkTidValid` says: "Make sure all tids belongs to the same UID …
     tids can belong to different application processes".
   - So the app can register the game's and gamescope's threads, which live in proot child
     processes, and report frame times. This is the officially supported way to get CPU boosts
     for the Linux side.
6. **The kernel and policy routes to a "real container" are closed, as are the cheap in-process
   tracer replacements.** [src]
   - Closed in GKI: `USER_NS` (and therefore user namespaces), `PID_NS`, `SYSVIPC` and `FHANDLE`.
   - `chroot`, `mount`, the new mount API and `fanotify` are blocked by seccomp.
   - Syscall User Dispatch is x86-only in mainline as of 7.3-rc6, so it is in no arm64 kernel.
   - Third-party apps cannot use AVF without an adb grant, and Snapdragon phones mostly report
     "Only protected VMs are supported".
7. **16 KB pages threaten the arm64ec FEX and Proton path whatever the environment design.**
   [doc/sec]
   - FEX supports only 4 KB-page hosts. Wine's ARM64EC needs 4 KB pages; Wine 11 only partly
     simulates them.
   - Box64's 2026 preview fakes a 4 KB page size.
   - proot itself is page-size agnostic: it reads `sysconf(_SC_PAGE_SIZE)`.

---

## 1. Landscape table

"Steam" means Valve's Linux client (steamrtarm64 with CEF) unless the row says otherwise.

| Project | What it runs | Mechanism | exec | Absolute paths (`/tmp`, `/usr`, `/etc`) | Android-blocked syscalls | Gives up | Performance claims | Source |
|---|---|---|---|---|---|---|---|---|
| **DroidDeck (today)** | Linux Steam (Big Picture, gamescope) and Valve ARM64 Proton with FEX as arm64ec | One patched proot (ptrace + seccomp) plus an in-process fast path | proot's loader maps `PT_INTERP` from the rootfs | proot binds | proot answers the SIGSYS stops | Tracer cost on path calls, exec, brk and threads | See proot-performance.md | this repo |
| **Winlator ≥ 10** (brunodev85) | Wine only: x86-64 Wine under box64 | glibc **built with the absolute prefix** `/data/data/com.winlator/files/rootfs` (formerly `imagefs`); no proot | Direct `execve` (targetSdk 28). box64 is aarch64 with `PT_INTERP` and `RUNPATH` inside the rootfs. x86 children are run by box64 | Compiled into glibc and Wine: `ld.so.cache`, `/tmp`, `/dev/shm`→`rootfs/tmp/shm`, `/bin/sh`, `/etc/*`. Wine's server dir is `rootfs/tmp/.wine-%u` | Termux-pacman glibc patches; SysV shm over a socket to an app-side server | Not relocatable: forks rebuild for their own package. No Linux Steam client | Changelog 10.0 Beta 1: "native GLIBC for better I/O performance" (no numbers) | [releases](https://github.com/brunodev85/winlator/releases), [launcher](https://github.com/brunodev85/winlator-app/blob/main/app/src/main/java/com/winlator/xenvironment/components/GuestProgramLauncherComponent.java), [RootFS.java](https://github.com/brunodev85/winlator-app/blob/main/app/src/main/java/com/winlator/xenvironment/RootFS.java), binary strings [src] |
| **GameNative** (formerly Pluvia) | Wine only, with **JavaSteam** for login, library and downloads. Optional "Real Steam" mode runs Valve's *Windows* Steam client inside Wine. Never the Linux client | Two launchers: glibc (Winlator-style relocated prefix) and **bionic** (bionic box64 and Wine). Pluvia's proot launcher is dead code | Legacy flavour (targetSdk 28): direct exec. **Modern flavour (targetSdk 36): every command runs as `/system/bin/linker64 <elf>`** | glibc box64 has `PT_INTERP` `/data/data/app.gamenative/files/imagefs/usr/lib/ld-linux-aarch64.so.1`. The bionic side uses `libredirect-bionic(-wx).so`, an LD_PRELOAD that redirects paths and **patches `openat` syscall sites in bionic libc and linker64** [src, from symbols] | Bionic side: n/a. glibc side: Termux patches | Modern flavour has **no glibc containers**. A W^X shim strips `PROT_EXEC` and re-maps pages executable on fault ("rebake") | None found | [build.gradle.kts](https://github.com/utkarshdalal/GameNative/blob/master/app/build.gradle.kts), [ProcessHelper.java](https://github.com/utkarshdalal/GameNative/blob/master/app/src/main/java/com/winlator/core/ProcessHelper.java), [ContainerConfigDialog.kt](https://github.com/utkarshdalal/GameNative/blob/master/app/src/main/java/app/gamenative/ui/component/dialog/ContainerConfigDialog.kt) [src] |
| **Winlator Bionic lineage** (Pipetto-crypto; cmod `cmod_bionic`; Star → WinHub) | Wine only: ARM64/arm64ec Wine **built against bionic**, FEX as `libarm64ecfex.dll` / `libwow64fex.dll`, or `wowbox64.dll` | Native Android executables; no glibc, no proot | Direct exec at targetSdk 28 | Wine built with an imagefs prefix, plus redirect preloads | n/a: bionic only makes allowed calls | No glibc Linux software at all | None | [Pipetto-crypto/winlator](https://github.com/Pipetto-crypto/winlator), [star-emu/star](https://github.com/star-emu/star) [src] |
| **WinNative** (maxjivi05) | Winlator Bionic and Pluvia merged; Wine for Windows games. A **Linux client** (Linux Steam + GE/CachyOS ARM64 Proton) taken from Bannerlator | Windows side: bionic Wine called directly. **Linux client: one proot** ("one proot holding gamescope, the Steam client, its web helper and a game") | Windows: direct (targetSdk 28). Linux: proot | Linux: proot binds | Linux: proot | Linux side has the same tracer costs as DroidDeck. Pressure-vessel is bypassed because the Runtime needs user namespaces | None | [README](https://github.com/maxjivi05/WinNative), [LINUX-PROTONS.md](https://github.com/maxjivi05/WinNative/blob/main/docs/LINUX-PROTONS.md), [ChildProcessRestrictions.kt](https://github.com/maxjivi05/WinNative/blob/main/app/src/main/runtime/linux/ChildProcessRestrictions.kt) [src] |
| **Bannerlator** (The412Banner; DroidDeck's credits name maxjivi05) | Continuation of Winlator Star Bionic, plus a "linux-gamescope-runtime" (Linux Steam under proot) | As WinNative | As WinNative | As WinNative | As WinNative | As WinNative | None | [Bannerlator](https://github.com/The412Banner/Bannerlator) [src] |
| **Mobox** | Wine (x86) with box64/box86 inside **Termux** | Termux-pacman glibc prefix (`$PREFIX/glibc`); no proot | Termux (targetSdk 28) | Termux-relocated prefix | Termux glibc patches | Needs Termux and Termux:X11; inactive since Nov 2024 | None | [olegos2/mobox](https://github.com/olegos2/mobox) [src] |
| **Box64Droid** | Wine and box64 in Termux | proot-distro rootfs, or Termux glibc | Termux | proot, or prefix | proot, or patches | Archived: "components are stuck at versions as of the end of 2024" | None | [Ilya114/Box64Droid](https://github.com/Ilya114/Box64Droid) [src] |
| **steamclienttermux + termux-glibc-compat** (huntergdavis) | **Linux Steam and CEF natively**; games through Proton ARM64, FEX and DXVK | Termux glibc 2.44 (relocated to the Termux prefix) plus `tgcompat`: an exec shim, LD_PRELOAD shims and a SysV-semaphore broker. **Since 2026-08-18 a "direct dispatcher" runs the Runtime's Python, Proton, Wine, FEX and allow-listed games outside proot too** | `libtgcompat-exec.so` wraps `execv*`, `execl*` and `posix_spawn` of aarch64 ELFs as `$TGCOMPAT_LD_SO prog`, preserving `argv[0]`, and redirects `/bin/sh`. Termux is targetSdk 28 | LD_PRELOAD `/tmp` shim; synthetic `/proc/net` and `/proc/stat`; Pressure-vessel's bwrap plan is captured under a short proot, then replayed outside it on a **prebuilt `/usr` tree built from the Runtime's mtree** | Termux glibc's `fakesyscall` table; robust-list emulation for Steam's `get_robust_list(0)` check; SysV semaphores through a same-UID broker; `userfaultfd` → `ENOSYS`; `flock` → `fcntl` fallback; `mprotect` EACCES → anonymous copy | A setup proot remains, parked for lifecycle. The dispatcher **allow-lists exact commands and file hashes** per game. No Android app; Termux:X11; Steam UI renders CEF in software | Runtime request to first game window: 407.2 s → 58.3 s (**6.99×**). Native Steam host alone: 22.2 → 23.4 FPS (**+5.4%**). Direct dispatcher: 25.167 → **30.400 FPS (+20.8%)**, Tomb Raider, Tab S8+. The old tracer used 60–65% of a core while the GPU sat at 12–16% | [README](https://github.com/huntergdavis/steamclienttermux), [tgcompat README](https://github.com/huntergdavis/termux-glibc-compat), [optimisation plan](https://github.com/huntergdavis/steamclienttermux/blob/main/docs/TOMB_RAIDER_OPTIMIZATION_PLAN.md) [src] |
| **UserLAnd** | Generic distributions (CLI and VNC) | proot, shipped as "fake .so" executables (`useLegacyPackaging`) | proot loader. **targetSdk 37** | proot | proot | Tracer costs | n/a | [app/build.gradle](https://github.com/CypherpunkArmory/UserLAnd/blob/master/app/build.gradle) [src] |
| **udocker F modes** (desktop Linux; reference only) | Docker images without root | patchelf (`PT_INTERP` and rpath into the container) plus fakechroot (LD_PRELOAD path rewriting) | Patched ELFs | fakechroot wrappers | n/a | Not portable across hosts; libc wrappers only | n/a | [udocker](https://github.com/grycap/udocker), [rootlesscontaine.rs](https://rootlesscontaine.rs/getting-started/udocker/) [sec] |

### 1.1 Winlator (brunodev85)

- **When proot went away.** The 10.0 changelog (Beta 1, the release finalised on 2025-02-28)
  says: "Added native GLIBC for better I/O performance … Root FS restructured: each library is now
  compiled individually and only those needed by Box64/Wine are kept."
  ([releases](https://github.com/brunodev85/winlator/releases)) [doc]
  - The proot sources stayed in the old monorepo's tags up to v11.0.0, so those tags do not show
    what shipped.
  - The current app source ([`winlator-app`](https://github.com/brunodev85/winlator-app), first
    commit 2025-08-22) has no proot. It runs `rootDir + "/usr/local/bin/box64 " + guestExecutable`
    through `ProcessHelper.exec`. [src]
- **How the prefix is relocated** [src, read from the shipped `rootfs.tzst` and `box64-0.4.4.tzst`]:
  - `box64` has `PT_INTERP` `/data/data/com.winlator/files/rootfs/lib/ld-linux-aarch64.so.1` and
    `RUNPATH` `/data/data/com.winlator/files/rootfs/lib`.
  - `ld-linux-aarch64.so.1` contains `/data/data/com.winlator/files/rootfs/etc/ld.so.cache`,
    `…/etc/ld.so.preload` and `…/lib/`.
  - `libc.so.6` contains `…/rootfs/tmp`, `…/rootfs/tmp/shm/sem.XXXXXX` (so `/dev/shm` is moved),
    `…/rootfs/bin/sh`, `…/rootfs/etc/resolv.conf`, `nsswitch.conf` and `localtime`, plus
    `ANDROID_SYSVSHM_SERVER`.
  - `wineserver` contains `/data/data/com.winlator/files/rootfs/tmp/.wine-%u`, so Wine is rebuilt
    with the prefix too.
  - This is **compile-time relocation, not padded binary patching.** The `RootFS.find` code renames
    the legacy `files/imagefs` to `files/rootfs`.
- **exec.** Direct `execve` is possible because the app is targetSdk 28 (`winlator-app`
  `app/build.gradle`). Windows binaries are x86-64 and run by box64, which handles exec of x86
  ELFs itself.
- **Blocked syscalls.** The README credits "GLIBC Patches by Termux Pacman". The only patches kept
  in the repo are the SysV shm ones
  ([`glibc_patches/`](https://github.com/brunodev85/winlator/tree/main/glibc_patches)), which talk
  to an app-side server. [src]
- **What it gives up.** One package name per glibc build. No Linux Steam client (Winlator 11 can
  install the Windows Steam client).

### 1.2 GameNative / Pluvia

- **Steam integration.** Pluvia and GameNative use **JavaSteam** for login, library, cloud saves
  and downloads (`app/build.gradle.kts`). [src]
  - "Real Steam mode" extracts Valve's *Windows* client ("Valve Windows client tree … headless
    steam.exe for Real Steam mode", `PluviaMain.kt`) and runs it in Wine.
  - There is also a "Bionic Steam" option. There is no Linux client. [src]
- **Environment.** Pluvia (2026-02) still started `libproot.so` with `PROOT_LOADER`. GameNative
  constructs only `GlibcProgramLauncherComponent` or `BionicProgramLauncherComponent`
  (`XServerScreen.kt`). [src]
- **The decision-relevant part: the targetSdk-36 "modern" flavour.**
  - `ProcessHelper` prefixes every command with `/system/bin/linker64`
    (`if (BuildConfig.MODERN_ANDROID) command = "/system/bin/linker64 " + command`).
  - The container dialog filters out `Container.GLIBC`.
  - It preloads `libredirect-bionic-wx.so`, which the comments describe as "required to run Wine on
    a strict W^X kernel".
  - Its strings and symbols show what it does: `mprotect_strip`, `rebake_mapping_executable`,
    `pc_in_rebaked`, `shim_segv_handler`, and "openat syscall-site patches: libc=%d linker64=%d".
    It also carries a redirect table for foreign prefixes: `/data/data/com.winlator/files/imagefs/usr`,
    `/data/data/com.winlator.cmod/files/imagefs`, `/data/data/com.winemu/files/usr`,
    `/data/data/com.termux/files/usr`.
  - **Inferred** [unverified: no source in the repo]: it emulates W^X for file-backed pages, which
    would otherwise need `execmod`, by copying them into anonymous executable memory on the first
    exec fault. It also rewrites `svc` sites in bionic's `openat` to branch into its redirect code.
  - This is on-device prior art for both syscall-site patching and the targetSdk ≥ 29 W^X problem.
- **Performance claims.** None.

### 1.3 WinNative and Bannerlator

- **WinNative** ([README](https://github.com/maxjivi05/WinNative)) describes itself as "unifying
  the best of **Winlator Bionic** and **Pluvia**".
  - It ships four package-name variants (Vanilla, Ludashi, Antutu, Pubg). The last three exist to
    trigger OEM performance modes.
  - Its Windows path calls ARM64 Wine "directly with a fully-qualified path"
    (`GuestProgramLauncherComponent.java`). [src]
- **The Linux client.** It came from Bannerlator's `feat/linux-gamescope-runtime`.
  - It runs under **one proot**.
  - It removes the Proton manifests' `require_tool_appid` (Steam Linux Runtime) dependency because
    "the original dependency invokes pressure-vessel/user namespaces, unavailable inside this
    Android runtime" ([LINUX-PROTONS.md](https://github.com/maxjivi05/WinNative/blob/main/docs/LINUX-PROTONS.md)).
    [src]
- **WinNative documents the phantom-process limit for this exact architecture:**
  > A session is one proot holding gamescope, the Steam client, its web helper and a game, which
  > passes the thirty-two child processes Android 12 and later allow an app; over that the system
  > kills them, and killing proot ends the session.

  ([ChildProcessRestrictions.kt](https://github.com/maxjivi05/WinNative/blob/main/app/src/main/runtime/linux/ChildProcessRestrictions.kt))
- **Bannerlator** ([The412Banner/Bannerlator](https://github.com/The412Banner/Bannerlator)) is "a
  personal continuation of the Winlator *Star Bionic* project". Its Linux runtime is the source of
  DroidDeck's and WinNative's Linux sessions. Neither project has removed proot from the Linux
  side.

### 1.4 Mobox, Box64Droid and the Winlator forks

- **Mobox** runs inside Termux on the termux-pacman glibc prefix, with no proot. Its last commit
  was 2024-11-23. [src]
- **Box64Droid** used proot-distro and is archived. [src]
- **The Winlator forks** split into a glibc lineage (relocated prefix, no proot) and a bionic
  lineage (Wine built with the NDK).
  - Cmod's bionic work is the `cmod_bionic` branch of [coffincolors/winlator](https://github.com/coffincolors/winlator).
  - "Frost" was not located [unverified].
  - None of the forks does anything mechanically different from §1.1 and §1.2.

### 1.5 steamclienttermux and termux-glibc-compat

This is the only project that runs the Linux Steam client without proot, so it is the closest
prior art. State at [`dee92b8`](https://github.com/huntergdavis/steamclienttermux) (2026-08-26) and
[`termux-glibc-compat@8d63206`](https://github.com/huntergdavis/termux-glibc-compat) (2026-08-24).

- **glibc.** Termux's `glibc-packages` 2.44 at
  [`954c6b2`](https://github.com/termux/glibc-packages/commit/954c6b200aa001088fcc420550b9304dd81229b8),
  plus an overlay that sends `semget/semctl/semop/semtimedop` to a broker.
  - The broker is a Unix socket, same UID, authenticated with `SO_PEERCRED`.
  - It implements `SEM_UNDO` and `semtimedop` deadlines and measured about 9.2k `GETVAL`/s.
  - Steam needed working semaphores. Upstream Termux glibc returns `ENOSYS` for them.
- **exec.** `libtgcompat-exec.so` reads the target's ELF header and, for an aarch64 Linux ELF, execs
  `TGCOMPAT_LD_SO` with the program as its argument.
  - It covers `execv`, `execvp`, `execvpe`, `execl` and `posix_spawn`, and preserves `argv[0]`.
  - It also redirects direct exec of the guest interpreter path, and optionally `/bin/sh`.
  - "No `LD_PRELOAD` claim for glibc-internal raw syscalls" is one of its design constraints.
- **Shims.**
  - `get_robust_list(0, …)`: Steam's IPC aborts on `ENOSYS`, so a per-thread fake head is
    returned.
  - `/proc/self/root` read-open is denied, so it is retried as `O_PATH`.
  - `/proc/net` and `/proc/stat` are denied, so synthetic files are served.
  - `userfaultfd` (and number 374) return `ENOSYS`, because Proton probes them and Android's filter
    would SIGSYS.
  - `flock` falls back to `fcntl` on the SD-card filesystem.
  - `mprotect(EACCES, PROT_EXEC)` falls back to an anonymous copy.
  - A RakNet `sched_yield` spin becomes a 1 ms sleep.
- **Namespaces.** "Android denied Bubblewrap's user namespace with `EINVAL` and mount namespace
  with `EPERM`." This is why the Runtime still went through proot until the direct dispatcher. That
  matches GKI's `USER_NS` being off (§4).
- **The direct dispatcher.** Pressure-vessel builds its plan under a short proot. The plan and up
  to 64 file descriptors are then sent to a same-UID server, which runs it on a `/usr` tree built
  from the Runtime's mtree (`prepare-runtime-direct-root.py`). The game ran with `TracerPid: 0`
  ([TOMB_RAIDER_BENCHMARK.md](https://github.com/huntergdavis/steamclienttermux/blob/main/docs/TOMB_RAIDER_BENCHMARK.md),
  2026-08-18).
  - It is allow-listed per exact command and file hash: for example
    `TombRaider.exe -nolauncher -benchmark`, and pinned hashes for NMS and FEX.
- **Where the time went before:** enumerating 5,601 files took 0.129 s natively and 4.231 s under
  their patched proot (33×) ([PERFORMANCE.md](https://github.com/huntergdavis/steamclienttermux/blob/main/docs/PERFORMANCE.md)).
- **Caveat for DroidDeck.** Their baseline is a Termux proot without DroidDeck's patches 0004–0014,
  and their tracer was hot during gameplay. DroidDeck already moved futex, ioctl, fstat and sendmsg
  off the trap list. So the +20.8% FPS should not be expected here. The launch-time and metadata
  gains are the transferable part.

---

## 2. The Termux ecosystem

### 2.1 How termux-pacman/glibc-packages patches glibc

[`gpkg/glibc`](https://github.com/termux-pacman/glibc-packages/tree/main/gpkg/glibc) (glibc 2.44,
at `e289d1e`, 2026-09-13) [src]:

- **The prefix.**
  - `set-dirs.patch` hard-codes the prefix: `"@TERMUX_PREFIX@/etc/ld.so.preload"`, `/tmp` →
    `@TERMUX_PREFIX_CLASSICAL@/tmp`, and `_PATH_LOG` → `/dev/socket/logdw`.
  - The same patch makes `ldconfig` read `SYSCONFDIR "/" LD_SO ".conf"` instead of `ld.so.conf`.
  - The install prefix is `$PREFIX/glibc`. Changing the package name means rebuilding everything,
    which Termux's docs say for all packages.
- **The `fakesyscall` table.** `build.sh` moves each arch's `syscall.S` aside and **deletes the
  listed syscalls from `arch-syscall.h`**, so glibc can no longer make them. It then generates a
  `switch` in a C `syscall()`
  ([`syscall.c`](https://github.com/termux-pacman/glibc-packages/blob/main/gpkg/glibc/syscall.c)).
  Internal callers are routed through it ("set-fakesyscalls.patch"). From
  [`fakesyscall.json`](https://github.com/termux-pacman/glibc-packages/blob/main/gpkg/glibc/fakesyscall.json):
  - `faccessat2` → `faccessat`; `fchmodat2` → `fchmodat`; `epoll_pwait2` → an emulation;
    `statx` → `statx_generic`; `close_range` → a userspace loop;
  - `shm*` → Android shmem emulation;
  - the `set*id` family → `0` (pretend success);
  - `clone3`, `futex_waitv`, `landlock_create_ruleset`, `pidfd_send_signal`, `pidfd_getfd`, `rseq`,
    `set_robust_list`, `get_robust_list`, `io_uring_*`, `name_to_handle_at`, `open_by_handle_at`,
    `kcmp`, `mbind`, `get_mempolicy`, `set_mempolicy`, `mq_open`, all `sem*` and all `msg*` →
    `ENOSYS`.
- **NPTL.** `set-nptl-syscalls.patch` deletes the `set_robust_list` calls in `start_thread`,
  `_Fork` and `__tls_init_tp`, and the rseq registration. These are the calls glibc makes with all
  signals blocked, which is the case proot-performance.md describes.
- **Other patches.** `mprotect.c` is a `/proc/self/maps`-parsing fallback for `EACCES`.
  steamclienttermux found an invalid `free` in it and replaced it with a preload. `disable-clone3`
  and `disable-termios2` are also patched.
- **Blind spots.** The table is static, so it does not track Android 16 QPR1 allowing `faccessat2`
  and `fchmodat2`. These now get rewritten for nothing (harmless).
- **Launching with `glibc-runner` (`grun`)**
  ([glibc-runner.sh](https://github.com/termux-pacman/glibc-packages/blob/main/gpkg/glibc-runner/glibc-runner.sh)):
  - `grun -c bin` runs `patchelf --set-interpreter $PREFIX/glibc/lib/ld-linux-aarch64.so.1
    --set-rpath …`.
  - `grun -s` starts a shell with `$PREFIX/glibc/bin` first on `PATH`, optionally preloading
    `libtermux-exec.so` (the glibc build).
  - Neither works without direct exec (Termux is targetSdk 28).
- termux-pacman also packages **fakechroot 2.20.2** for glibc (`gpkg/fakechroot`).

### 2.2 termux-exec v2 and `system_linker_exec`

From [technical/index.md](https://github.com/termux/termux-exec-package/blob/master/site/pages/en/projects/docs/technical/index.md)
and [v2.0.0 notes](https://github.com/termux/termux-exec-package/blob/master/site/pages/en/projects/releases/2/v2.0.0.md)
[doc, src]:

- **How it works.**
  - An LD_PRELOAD intercepts the `exec()` family and runs `/system/bin/linker64 /path/elf args`.
  - "the kernel/SeLinux only sees the `linker` binary assigned the `system_linker_exec` file
    context type being executed by the app process and not the `*app_data_file` being executed by
    the linker".
  - The sepolicy agrees: `untrusted_app_all` has `system_linker_exec:file execute_no_trans` for all
    targetSdks ("Chrome Crashpad uses the dynamic linker to load native executables").
  - The linker has accepted a path argument since Android 10.
- **Documented limits.**
  1. `LD_PRELOAD` must be set at every entry point.
  2. `/proc/self/exe` and `comm` show `linker64`. termux-exec exports `TERMUX_EXEC__PROC_SELF_EXE`
     for the current process only.
  3. Statically linked binaries do not work.
  4. Raw `execve` syscalls bypass it.
  5. Not allowed on Play.
- **Not stated in their docs, but follows from the mechanism:** `linker64` is *bionic's* linker,
  so it links the target against bionic. This handles bionic ELFs only.
  - Whether `linker64 <glibc ld-linux-aarch64.so.1> <prog>` works (glibc's `ld.so` run "as a
    program" under a foreign auxv) is **untested; no prior art found** [unverified].
  - The robust alternative for glibc is a small static ELF loader in the native-library directory,
    which `mmap`s the program and its `PT_INTERP` and builds the auxv. That is exactly what proot's
    loader is.
- **Termux itself.**
  - The GitHub/F-Droid app is still **targetSdk 28**
    ([gradle.properties](https://github.com/termux/termux-app/blob/master/gradle.properties)).
  - The Play Store app is **targetSdk 37** and starts shells through
    `"/system/bin/linker" + (is64Bit ? "64" : "")`
    ([termux-play-store/termux-apps](https://github.com/termux-play-store/termux-apps),
    `TermuxShellUtils.java`). [src]
  - A relocated prefix also breaks for secondary users and work profiles (`/data/user/<id>/<pkg>`),
    as Termux documents.

### 2.3 proot-distro and other "proot alternatives"

- **proot-distro** is maintained. A 2026 security issue (CVE-2026-54574, a symlink traversal on
  install/reset) has been reported [sec].
- **Upstream proot.** `termux/proot` was last updated 2026-10-01. Its loader reads
  `sysconf(_SC_PAGE_SIZE)`, falling back to 0x1000 ([src/execve/enter.c](https://github.com/termux/proot/blob/master/src/execve/enter.c)).
  [src]
- **No maintained "proot-rs" or seccomp-unotify proot for Android was found** [sec]. The doc's
  measurements already show unotify buys nothing.
- **Single-app shims.** `opencode-termux` uses a preload that catches blocked syscalls for Bun. It
  reports `fstatat` at 2.75 µs native versus 304 µs under proot
  ([HanSoBored/opencode-termux](https://github.com/HanSoBored/opencode-termux)). [sec]
- **udocker and fakechroot** are the desktop analogue of "patchelf'd `PT_INTERP` plus LD_PRELOAD
  path rewriting". The udocker maintainers say runc, crun and Singularity cannot work on Android
  (no cgroups or namespaces) ([x11docker#224](https://github.com/mviereck/x11docker/issues/224)).
  [sec]

---

## 3. Constraints for non-root apps, Android 9 to 17

"src" rows were read in AOSP via the mirrors named at the top. The arm64 app seccomp policy was
computed from `SYSCALLS.TXT`, the allowlists and the blocklists for each release, using bionic's
`genseccomp.py` rule (base − blocklists ∪ allowlists).

| Constraint | Versions | Effect on DroidDeck | Source |
|---|---|---|---|
| **Exec of `app_data_file`** (`execute_no_trans`) | Allowed only in `untrusted_app_27`/`_25` (targetSdk ≤ 28); denied for targetSdk ≥ 29 on Android 10+. Gated by targetSdk, not OS version | targetSdk 28 lets rootfs ELFs be exec'd directly. Every design without proot depends on it | [sepolicy untrusted_app_27.te](https://android.googlesource.com/platform/system/sepolicy/+/main/private/untrusted_app_27.te) [src] |
| **`execmod`** (making a written, file-backed private mapping executable) | `untrusted_app_27`/`_25` only ("Text relocation support … disallowed for targetSdkVersion>=Q") | At 28: Wine PE relocations and in-place `svc` rewriting of file-backed code work. At ≥ 29 they need anonymous copies (GameNative's "rebake") | same [src] |
| **`execmem`** (anonymous `PROT_EXEC`, JIT) | Every `appdomain`, every version ("WebView and other application-specific JIT compilers") | FEX, V8 and the fast-path trampoline page are allowed. **Closes the open item in proot-performance.md** | [private/app.te](https://android.googlesource.com/platform/system/sepolicy/+/main/private/app.te) [src] |
| **`mmap(PROT_EXEC)` of app data** | `untrusted_app_all`: `app_data_file:file { r_file_perms execute }`, every targetSdk | proot's loader and any userland ELF loader work at targetSdk ≥ 29 | [untrusted_app_all.te](https://android.googlesource.com/platform/system/sepolicy/+/main/private/untrusted_app_all.te) [src] |
| **Exec from the native-library directory** (`apk_data_file`) | `appdomain: apk_data_file:file { … x_file_perms }`, every targetSdk | Shipping proot, the loader or a launcher as `lib*.so` (extractNativeLibs / `useLegacyPackaging`) keeps exec possible at any targetSdk | app.te [src]; UserLAnd comment [src] |
| **`/system/bin/linker64 <elf>`** | `system_linker_exec:file execute_no_trans` for all untrusted apps; linker path argument since Android 10 | For bionic ELFs only (§2.2) | untrusted_app_all.te [src]; termux-exec docs [doc] |
| **Exec of a memfd** (`fexecve`) | No app domain has `execute_no_trans` on `appdomain_tmpfs` or, in Android 17, the new `memfd_file` class. `app_domain()` grants only `execute` (mmap `PROT_EXEC`) | `memfd` exec is **not** a plan B at any targetSdk, whatever `vm.memfd_noexec` says. Mapping a memfd executable is fine | [public/te_macros `app_domain`](https://android.googlesource.com/platform/system/sepolicy/+/main/public/te_macros), sepolicy commit `0671c4d` (memfd_file class, 2025-10-24) [src] |
| **Minimum installable targetSdk** | 14: 23; 15 and 16: 24; **17: 24** (`MIN_INSTALLABLE_TARGET_SDK = Flags.minTargetSdk24() ? N : M`). Bypass: `adb install --bypass-low-target-sdk-block` | targetSdk 28 installs on 17 with headroom of 4 API levels | `PackageManagerService.java` and `InstallPackageHelper.java` (lineage-24.0) [src]; [bayton matrix](https://bayton.org/android/android-minimum-targetsdk-matrix/) [sec] |
| **"Built for an older version" warning** | `RELEASE_PLATFORM_MIN_SUPPORTED_TARGET_SDK_VERSION` = **28** in 15, 16 and 17 | No warning at 28. If 18 raises it to 29, users get a dialog (warning only) | build/release flag declaration (lineage-23.x; GrapheneOS `17`) [src] |
| **App seccomp filter** (identical for every targetSdk; installed by zygote via `set_app_seccomp_filter()`; arm64 policy is syscall-number only) | **Blocked throughout 10–17:** `rseq`, `set/get_robust_list`, `futex_waitv`, `io_uring_*`, SysV `shm*/sem*/msg*`, `setuid/setgid/setre*` (but `setresuid/setresgid` are allowed), `mount`, `umount2`, `chroot`, `open_tree`, `move_mount`, `fsopen`, `mount_setattr`, `fanotify_*`, `name_to_handle_at`, `open_by_handle_at`, `kcmp`, `memfd_secret`, `cachestat`, `map_shadow_stack`, `process_mrelease`, `keyctl`, `mbind`, `set_mempolicy`. **Allowed:** `ptrace`, `process_vm_*`, `seccomp`, `prctl`, `unshare`, `setns`, `pivot_root` (no capability), `perf_event_open`, `execveat`, `sched_setattr`, `personality`, `membarrier`, `memfd_create` | The blocked list is why proot (or a patched glibc) is needed. **No syscall was removed from the allowlist between Android 10 and 17** | `libc/SECCOMP_*.TXT` across lineage-17.0…24.0 [src]; [Zygote](https://android.googlesource.com/platform/frameworks/base/+/main/core/jni/com_android_internal_os_Zygote.cpp) [src] |
| **Syscalls newly allowed** | 11: `statx`, `pidfd_open`. 12: `pidfd_getfd`, `pidfd_send_signal`, `process_madvise`, `userfaultfd`. 13: `close_range`, `bpf`. 14 QPR / 15: `clone3` (AOSP `b65dec5`, 2023-09), `epoll_pwait2`. 15 QPR1: `mseal`. **16: `landlock_*`** (`ba03012`). **16 QPR1 and 17: `openat2`, `faccessat2`, `fchmodat2`, `*xattrat`** (`1d1e54b`) | See §0 item 1. On those devices the faccessat2 SIGSYS disappears, and `RESOLVE_IN_ROOT` and Landlock become usable | per-release diff of the policy [src] |
| **User namespaces** | GKI `gki_defconfig` 6.1, 6.6, 6.12 and 6.18 leave `CONFIG_USER_NS` unset; `PID_NS` is unset. `unshare(CLONE_NEWUSER)` → `EINVAL`; mount namespace → `EPERM` | No bwrap or pressure-vessel; no "real" container, even though seccomp allows `unshare` | GrapheneOS `kernel_common-6.x@17` defconfigs [src]; tgcompat observation [src] |
| **SysV IPC** | `CONFIG_SYSVIPC` unset in GKI **and** blocked by seccomp | Must be emulated (Steam needs semaphores; Wine and X need shm) | defconfigs [src] |
| **`ptrace` of own processes** | `untrusted_app_all self:process ptrace` | proot works. Note that a blocked SIGSYS is survivable *only* because the tracer sees the signal first (§5.3) | untrusted_app_all.te [src]; [kernel/signal.c](https://github.com/torvalds/linux/blob/master/kernel/signal.c) [src] |
| **Phantom process killer** | 12+. At most **32** phantom (non-zygote) processes in total (`max_phantom_processes`, DeviceConfig); excess are trimmed. If the app's process state is `HOME` or less important, excessive CPU kills the whole group, parent included. Disabled by `settings_enable_monitor_phantom_procs=false` (developer option "Disable child process restrictions" from 14; adb before) | A Steam, CEF, Proton and Wine tree can exceed 32. A foreground service keeps the app above `HOME`, which avoids the CPU kill | [PhantomProcessList.java](https://android.googlesource.com/platform/frameworks/base/+/main/services/core/java/com/android/server/am/PhantomProcessList.java), ActivityManagerConstants, ActivityManagerService (lineage-24.0) [src] |
| **MemoryLimiter** | **17**, only where the vendor ships `/vendor/etc/memory-limiter-config.xml`. cgroup v2 `memory.high` and `memory.swap.max` per app process. TOP = Visible; FOREGROUND_SERVICE = Not-visible. Over the limit: an atom, then a kill 30 s later. `am memory-limiter ignore <uid>` (adb) | The single proot tree probably shares one budget [unverified]. Example: 8 GB + 4 GB swap visible on a ≥14 GB phone, 4 GB + 4 GB as a foreground service | [MemoryLimiter.md](https://android.googlesource.com/platform/frameworks/base/+/main/services/core/java/com/android/server/am/MemoryLimiter.md) [src]; [Android 17 all-apps changes](https://developer.android.com/about/versions/17/behavior-changes-all) [doc] |
| **16 KB pages** | 15+ can boot 16 KB. Pixel 8/8a/9/9a have a developer toggle. Play blocks updates without 16 KB support from 2027-02-01. Android 17 can make 4 KB-aligned app .so files abort (`bionic.linker.16kb.app_compat.enabled fatal`) | §6.2 | [page sizes](https://developer.android.com/guide/practices/page-sizes) [doc] |
| **GPU ioctl filtering** | 16 QPR2+: `set_xperm_filter(target, allowed_target, unpriv, restricted, instrumentation)` "Gate by targetSdkVersion". Deployed on Pixel (Mali) | A vendor could restrict KGSL ioctls for apps above a targetSdk. A low targetSdk is likely to be exempt [unverified] | `public/te_macros` [src]; [GPU syscall filtering](https://source.android.com/docs/security/features/gpu-syscall-filtering) [doc] |
| **`/proc` restrictions** | `/proc/net` denied; `/proc/stat` denied; `/proc/self/root` cannot be read-opened (works as `O_PATH`) | Fake `/proc` files are needed with or without proot | tgcompat README [src] |
| **targetSdk-gated extras** (only if the target is raised) | ≥ 29: ashmem `open` denied (memfd instead). ≥ 30: netlink `RTM_GETLINK` restricted, scoped storage. ≥ 32: `RTM_GETNEIGH` denied. ≥ 34: foreground-service type required. ≥ 35: dataSync time limit. ≥ 37: new `untrusted_app` domain with no ashmem ioctls on memfd | Raising the target is more than an exec question: Wine and Steam network detection (`getifaddrs`) and storage access also change | `untrusted_app_{27,29,30,32,34}.te`, `untrusted_app.te`, seapp_contexts (`minTargetSdkVersion=37`) [src] |
| **Background audio hardening** | 17, all apps: audio APIs fail unless the app is visible or has a foreground service | DroidDeck's foreground service covers it | Android 17 all-apps changes [doc] |

---

## 4. Kernel facilities for path redirection, for `untrusted_app`

| Facility | arm64 kernel | GKI | App seccomp | SELinux / caps | Verdict |
|---|---|---|---|---|---|
| `openat2` `RESOLVE_IN_ROOT` / `BENEATH` | 5.6+ | every current GKI | **allowed 16 QPR1+**; blocked ≤ 16 | none | **Usable on new devices.** One root only, so binds must be handled first in userspace. 1.0 µs (proot-performance.md) |
| Landlock | 5.13+ | `SECURITY_LANDLOCK=y` in 6.12 and 6.18 only | allowed 16+ | none | Allowed but deny-only: it restricts, it cannot redirect. Not a path translator |
| User and mount namespaces, `chroot`, `pivot_root` | yes | `USER_NS` off | `unshare` and `pivot_root` allowed; `chroot`/`mount` blocked | needs `CAP_SYS_ADMIN` | **Closed** |
| `fanotify` | yes | not in GKI defconfig | blocked | n/a | **Closed** (and it never redirects) |
| `name_to_handle_at` / `open_by_handle_at` | yes | `FHANDLE` off | blocked | `CAP_DAC_READ_SEARCH` | **Closed** |
| FUSE | yes | — | `mount` blocked | — | **Closed** for apps |
| binfmt_misc | yes | — | — | root | **Closed** |
| `PR_SET_MM` (would fix proot's brk emulation) | yes | yes | prctl allowed | `CAP_SYS_RESOURCE` | **Closed** |
| inotify | yes | yes | allowed | — | Usable, for invalidating a userspace path cache |

---

## 5. Syscall interception technology for aarch64 Android

### 5.1 Summary table

Costs marked "measured" come from proot-performance.md on the SM8850. Everything else is an
estimate and says so.

| Technique | arm64 | On Android GKI | Allowed by app seccomp | Per-call cost | Robustness concerns |
|---|---|---|---|---|---|
| ptrace + seccomp `RET_TRACE` (proot) | yes | all | yes | 15–60 µs measured; a single tracer tops out around 75–95k traps/s | Robust: sees raw `svc`, JIT and static code. Survives a blocked SIGSYS (§5.3) |
| seccomp user notification | yes | all | yes (`seccomp` allowed) | 34–58 µs measured across cores | Cannot rewrite arguments. No gain |
| In-process SIGSYS (own `RET_TRAP` filter) | yes | all | yes | 0.83 µs measured | A blocked SIGSYS kills. **Filters survive `execve`, so the new image runs under the filter before any handler exists** (ld.so's own `openat`/`mmap` would die). Needs signal virtualisation (§5.3) |
| gVisor **systrap** | yes; "syscall patching is not supported on arm64" | all | uses `ptrace` (stub setup) and `seccomp` | Not measured on Android. Each event wakes the out-of-process Sentry, so it is like unotify unless it spins [estimate] | It is a whole userspace kernel (Sentry, gofer). Shows the *method*, not a drop-in |
| Syscall User Dispatch | **no**: mainline 7.3-rc6 selects `ARCH_SUPPORTS_SYSCALL_USER_DISPATCH` only on x86 | **none** (5.10 to 6.18) | prctl is not argument-filtered, so it would pass | n/a | The arm64 generic-entry conversion (Ruan, v7 to v16, 2025-11 to 2026-06) adds `arch_syscall_is_vdso_sigreturn()` but is **not merged**. Even merged, it would reach Android only through a future GKI |
| `svc` binary rewriting: **svc-hook** (Middleware '25) | yes; lists "ARM64 Linux, Android" | all | n/a | "1,000 times faster than ptrace", so roughly tens of ns of overhead [estimate] | Rewrites at start-up only (`/proc/self/maps` scan); misses later `dlopen` and **JIT code (FEX, V8, Wine)** unless re-scanned. At targetSdk ≥ 29, patching file-backed text needs `execmod`, so anonymous copies are required. Mis-decoding data as `svc` is possible |
| ASC-Hook (LCTES '25) | Arm | — | — | — | Research prototype (hybrid replacement plus trampolines); source not checked |
| zpoline / lazypoline / E9Patch / SaBRe | x86-64 only (SaBRe: x86-64 and RISC-V) | — | — | — | Not applicable. lazypoline also needs SUD. "K23" not located [unverified] |
| LD_PRELOAD libc wrappers (fast path 0014, fakechroot, tgcompat, termux-exec) | yes | all | yes | 0.6–1.9 µs for path calls, measured (0014) | Misses glibc-internal calls (ld.so, fts, nss, `posix_spawn`'s `__execve`), raw `svc` (FEX's `syscall()`, Go, static binaries) and `vfork` children |
| Patched glibc (Termux `fakesyscall`, prefix, internal entry points) | yes | all | n/a | ~0 for rewritten calls | Covers internal callers. Tied to the glibc version and to one prefix per package name. Does not cover non-glibc code |
| `LD_AUDIT` `la_objsearch` | yes | all | n/a | ~0 | Library lookup only, not general file paths |
| `openat2 RESOLVE_IN_ROOT` | yes | all | **16 QPR1+ only** | 1.0 µs measured | Kernel-correct symlink confinement for the rootfs, with no 2 s TTL staleness. Binds still need prefix logic; fall back to the current method on older releases |

### 5.2 How the binary rewriters handle `svc` in JIT code

They don't, or only lazily.
- zpoline and E9Patch rewrite once.
- lazypoline uses SUD as the always-on slow path and rewrites lazily: that is its whole point, and
  it is x86-only.
- svc-hook scans once at initialisation and leaves `[vdso]` alone
  ([main.c](https://github.com/retrage/svc-hook/blob/main/main.c)).

For DroidDeck this matters because:
- FEX translates x86 Linux syscalls through glibc's `syscall()`, which is already hookable in libc.
- Wine's unix side uses glibc wrappers.
- V8 and Chromium JIT rarely emit `svc`.
- The real gap is glibc *internal* syscalls, which a proot-aware glibc closes more cheaply than
  binary rewriting.

### 5.3 What gVisor systrap and lazypoline teach about "SIGSYS blocked = killed"

- **The kernel rule** ([kernel/signal.c](https://github.com/torvalds/linux/blob/master/kernel/signal.c),
  `force_sig_info_to_task`; `force_sig_seccomp` uses `HANDLER_CURRENT`):
  - If SIGSYS is blocked or ignored, the handler is reset to `SIG_DFL` and the signal is unblocked.
  - `SIGNAL_UNKILLABLE` is cleared unless the task is ptraced.
  - Under ptrace the tracer gets a signal-delivery stop *before* the default action, so proot can
    suppress it. **That is the only reason today's design survives glibc's all-signals-blocked
    `set_robust_list` and `rseq` calls.**
  - The handler is still reset to `SIG_DFL`. So a hybrid of "in-process SIGSYS fast path plus proot
    safety net" would lose its handler the first time a SIGSYS arrives while blocked.
- **gVisor systrap**
  ([README](https://github.com/google/gvisor/blob/master/pkg/sentry/platform/systrap/README.md)):
  - It installs `RET_TRAP` for *all* user syscalls, with an allow window only for its own stub
    region (and SUD on x86).
  - The application's `rt_sigprocmask`, `rt_sigaction` and `sigaltstack` are themselves trapped
    and emulated, so the *host* mask never blocks SIGSYS.
  - The stub's handler runs on an alternate stack shared with the Sentry. The stub's own
    `rt_sigreturn` is allowed by the filter.
  - The new image after `execve` is built by the Sentry, so the persistence of filters across exec
    is harmless.
- **lazypoline**
  ([`vIrTuAlIzE_sIgNaLs.cpp`](https://github.com/lazypoline/lazypoline/blob/main/vIrTuAlIzE_sIgNaLs.cpp)):
  - It wraps every application handler so it can intercept the `sigreturn`.
  - It refuses application registration of SIGSYS handlers.
  - It notes that nested SUD SIGSYS during a SIGSYS handler terminates the program.
- **What DroidDeck can borrow**, if it ever replaces the tracer in-process:
  1. Trap and emulate `rt_sigprocmask`/`pthread_sigmask`, `rt_sigaction` (strip SIGSYS from
     `sa_mask`, chain any application SIGSYS handler), the `sigsuspend`/`ppoll`/`pselect6`/`epoll_pwait`
     mask arguments, and `uc_sigmask` on `sigreturn`. Then SIGSYS is never really blocked.
  2. Use `SA_NODEFER` and a dedicated `sigaltstack`.
  3. **Keep something outside the process that owns exec** (proot, or a loader in the native-library
     directory that installs the handler before `ld.so` runs), because filters survive exec.

  This is a large, fragile project. Patching glibc so it never makes those calls (§2.1, option 6 in
  proot-performance.md) removes the same SIGSYS round trips for a fraction of the risk.

---

## 6. Recent Android developments

### 6.1 AVF and the Linux Terminal

- **Permissions.** In the Virtualization APEX manifest
  ([`android.system.virtualmachine.res/AndroidManifest.xml`](https://github.com/GrapheneOS/platform_packages_modules_Virtualization/blob/17/android/android.system.virtualmachine.res/AndroidManifest.xml),
  GrapheneOS `17`) [src]:
  - `MANAGE_VIRTUAL_MACHINE` is `signature|preinstalled|development`;
  - `USE_CUSTOM_VIRTUAL_MACHINE` is `signature|development` ("Not for use by third-party
    applications").

  The `development` flag means `adb shell pm grant` could grant them to a sideloaded app
  [unverified on user builds]. There is no install-time path.
- **Hardware.** Snapdragon phones mostly lack non-protected VMs. Users report "Non-protected VMs
  are not supported on this device" and "Only protected VMs are supported" on Snapdragon Galaxy
  phones [sec](https://us.community.samsung.com/t5/Discussions/Android-AVF-Linux-Terminal-app/td-p/3519972).
  Qualcomm's Gunyah supports AVF *protected* VMs
  ([Qualcomm blog](https://www.qualcomm.com/developer/blog/2024/01/gunyah-hypervisor-software-supporting-protected-vms-android-virtualization-framework)).
- **GPU.** The guest has no KGSL; graphics are forwarded through gfxstream over virtio-gpu.
  - It went live on Pixel 10 in Android 16 QPR2 betas.
  - Only "47 of the Pixel 10's 142 Vulkan extensions are exposed", and some apps run slower than
    on the CPU fallback
    ([Gadget Hacks](https://android.gadgethacks.com/news/android-linux-terminal-gpu-acceleration-lands-on-pixel-10-with-major-limits/),
    [Android Authority](https://www.androidauthority.com/pixel-10-linux-apps-gpu-acceleration-3608754/)).
    [sec]
- **Verdict for gaming: dead end today.** It needs an adb grant, mostly doesn't exist on Adreno
  phones, gives up direct KGSL/Turnip, and gfxstream is immature. Worth watching for one reason: a
  VM is the only way to give FEX and Wine a 4 KB-page kernel on a 16 KB device (Asahi's muvm
  precedent).

### 6.2 16 KB pages

- **Devices.** Google documents a boot-time toggle on Pixel 8/8 Pro/8a (15 QPR1+), Pixel 9 family
  (15 QPR2+) and Pixel 9a (16+). It warns that "without recompiling, apps won't work on 16 KB
  devices in future Android releases".
  - No shipping phone that *defaults* to 16 KB could be confirmed [unverified]. Samsung's Remote
    Test Lab lists 16 KB devices.
  - Bionic's backcompat mode only fixes 4 KB-aligned **app .so files loaded by bionic**
    ([page sizes](https://developer.android.com/guide/practices/page-sizes)). It does nothing for a
    glibc rootfs.
- **Effect on each layer:**
  - **proot:** page-size agnostic. It reads `sysconf(_SC_PAGE_SIZE)` ([enter.c](https://github.com/termux/proot/blob/master/src/execve/enter.c)).
    [src]
  - **glibc rootfs:** fine if ELF `LOAD` segments are aligned to at least 16 KB. GNU ld and lld
    default to 64 KB max-page-size on aarch64. Verify with `readelf -l` over the rootfs and the
    Steam runtime [unverified].
  - **FEX:** "FEX only supports AArch64 host systems running a 4k page-size kernel". Fedora Asahi
    runs it in a muvm micro-VM with a 4 KB guest kernel
    ([Fedora change](https://www.fedoraproject.org/wiki/Changes/FEX)). [sec]
  - **Wine ARM64EC / Proton ARM64:** ARM64EC needs a 4 KB system page size (the Windows ABI). Wine
    11 adds a partial 4 KB simulation on larger pages and warns that demanding apps may fail
    ([search summary of the Wine 11 notes](https://www.techspot.com/downloads/4638-wine.html);
    [wine-proton-arm64ec](https://github.com/fathonix/wine-proton-arm64ec) "Not working yet on 16K").
    [sec]
  - **box64:** box64 0.4.6 (preview, 2026-08) "makes the emulated program believe it is on a 4k
    system", including Wine and Proton
    ([box86.org](https://box86.org/2026/08/preview-of-next-box64-improved-non-4k-pagesize-handling/)).
    [sec]
- **Verdict.** On a 16 KB phone, DroidDeck's arm64ec FEX and Proton path is at risk *regardless*
  of proot versus native. The environment choice does not fix or break it. Box64 (x86-64 Wine) is
  the only translator with a 16 KB story today.

### 6.3 How long targetSdk 28 lasts, and plan B

- **The floor.** Android 17 still installs targetSdk ≥ 24 and warns below 28 (§3). Nothing found
  indicates a raise in 17. The last bumps were 14 (23) and 15 (24) [src/sec]. Google moves this
  floor about one API level every Android version or two, so 28 likely has several releases left.
  That is an extrapolation [unverified].
- **Plan B:**
  1. **Keep proot (or any tracer) as the exec owner.** It works at any targetSdk if `libproot.so`
     and its loader stay in the native-library directory (UserLAnd at 37). Costs: `execmod` loss,
     so Wine's relocated PE images need anonymous copies (as GameNative does); the targetSdk-gated
     network and storage changes in §3.
  2. **For native (no-tracer) designs:** an `execve`/`posix_spawn` preload that rewrites to
     `<nativeLibDir>/libelfloader.so <prog> args`, a static loader that maps the program and its
     `PT_INTERP`.
     - `/proc/self/exe` then points at the loader. Chromium, CEF, steamwebhelper and Wine re-exec
       themselves through it, so it needs emulation, which a no-ptrace design can only do with a
       preload over `readlink`.
     - Raw-`svc` `execve` (Go, static binaries) escapes.
  3. `linker64` works only for bionic builds (GameNative modern).
  4. **memfd exec is denied by SELinux** for every targetSdk (§3).
  5. `vm.memfd_noexec` is irrelevant.

### 6.4 App seccomp and SELinux changes, Android 9 to 17

Covered row by row in §3. The direction is one-way: **the app seccomp filter only ever gained
syscalls.** From the diff across lineage-17.0…24.0:
- nothing was removed;
- additions arrived in 11, 12, 13, 14 QPR/15, 15 QPR1, 16 and 16 QPR1.

The SELinux direction is the opposite: each new targetSdk domain is a little stricter. The most
recent are:
- `untrusted_app_34` and `untrusted_app` (37+) lose ashmem ioctls on memfds
  (sepolicy commits `0cf9cec`, `de15264`, 2026);
- the targetSdk-gated GPU ioctl filter macro (16 QPR2).

`execmem`, `ptrace` of self, `process_vm_*` and `mmap(PROT_EXEC)` of app data are unchanged for
every targetSdk.

---

## 7. The app itself as the container

| Facility | What Android gives a third-party app | Helps the Linux environment? |
|---|---|---|
| Multiple processes (`android:process`) | Each is a zygote-forked ART process with its own pid cgroup and its own MemoryLimiter budget and process state | **Yes, structurally.** Start the game-tree proot from a separate `:game` process: a second tracer (2.5× trap throughput measured), its own memory budget [inference], and a crash in the UI process doesn't take the game down. The 32-process phantom limit is global, so this does not raise it. A process that `exec`s loses its binder connection and AMS kills it, so a Linux program cannot "become" an Android process |
| `isolatedProcess` | Separate isolated UID; no app data; **`neverallow isolated_app_all gpu_device`** (except `isolated_compute_app`) | No |
| App zygote (`useAppZygote`) | Preloads for isolated services only; `neverallow app_zygote gpu_device` | No |
| Foreground service | Keeps the process at perceptible priority; avoids the freezer and the phantom excessive-CPU kill (which applies only at `HOME` and below). Type rules apply from targetSdk 34 | Yes, already used |
| Phantom killer (12+) | 32 total; adb or a developer option turns it off | Must be detected and explained to the user, as WinNative does |
| MemoryLimiter (17) | Per-process cgroup limits by state; adb override | Detect it (`am memory-limiter status` needs adb; the app can only see `ApplicationExitInfo` "MemoryLimiter:AnonSwap" afterwards). Splitting the tree across processes helps [inference] |
| CPU affinity | `sched_setaffinity` (seccomp allowed; SELinux `self:process setsched`) within the cgroup cpuset (top-app = all cores) | Already used. proot-performance.md: never pin the tracer away from its tracees |
| **uclamp** | `sched_setattr` with `SCHED_FLAG_UTIL_CLAMP` needs no capability in mainline (`uclamp_validate` has no permission check). It is bounded by the cgroup's `cpu.uclamp.max` and `sysctl sched_util_clamp_min` | **Worth testing:** set `uclamp_min` on the game's and gamescope's render threads [unverified on device; vendors may cap the sysctl] |
| **ADPF hint sessions** | `PerformanceHintManager.createHintSession(tids, target)` / NDK `APerformanceHint_*`. **TIDs may be in other processes of the same UID**. `reportActualWorkDuration(WorkDuration)` with GPU time, `setPreferPowerEfficiency`. Android 16: `SystemHealthManager.getCpuHeadroom`/`getGpuHeadroom` | **Yes, high value, low cost.** gamescope knows frame times; relay them to the app over a socket and report them for the game, gamescope and Wine threads |
| Thermal | `PowerManager.getThermalHeadroom`, thermal status listener | Yes: drive FEX and DXVK presets or frame caps before the OEM throttles |
| Game Mode / Game Dashboard | `GameManager.getGameMode()`, `setGameState(isLoading)` (API 33), `android.game_mode_config` | `setGameState(loading)` during Steam start-up and game load is a cheap hint. DroidDeck already declares `appCategory="game"` and `isGame` |

---

## 8. GPU and driver access

- **Who gets `/dev/kgsl-3d0` at all.** AOSP's `system/sepolicy` grants `appdomain` nothing on
  `gpu_device`. Only `gpu_sphal_domain` and a few system domains are granted it there. The app
  grant and the `/dev/kgsl-3d0 → gpu_device` label are device and vendor policy (LineageOS
  `device_qcom_sepolicy_vndr` `lineage-24.0-caf-sm8850` labels the node). [src]
  - In practice every Adreno phone lets apps open it, because the GLES and Vulkan drivers run
    in-process.
- **Domains that cannot reach the GPU.** Policy *denies* it to `isolated_app_all` (except
  `isolated_compute_app`), `app_zygote`, `sdk_sandbox_all` and `ephemeral_app` (execute only).
- **Architectures that keep direct KGSL and Turnip:** any native process in the app's UID and
  domain. That covers proot tracees, glibc-native processes, bionic processes and processes
  started from extra `android:process` services.
  - Not: isolated processes, app-zygote children, AVF guests (gfxstream only), or anything in
    another UID.
- **What can vary.**
  1. The 16 QPR2 targetSdk-gated ioctl filter, which a vendor may apply to KGSL. Pixel/Mali is the
     only documented deployment [doc].
  2. OEM `sysfs` and `devfreq` nodes for GPU clocks, which are vendor-labelled and often unreadable.
     tgcompat reads KGSL frequency and thermal level on Samsung.
  3. Turnip needs `TU_DEBUG=noconform` on Android (steamclienttermux).
- No evidence was found of KGSL *open* being restricted by Android version on Snapdragon.

---

## 9. What this means for DroidDeck

### 9.1 Ranked candidate architectures

The ranking is on expected gain, risk, effort and future-proofing. Gains are relative to today's
patched proot with the 0014 fast path.

| # | Architecture | Expected gain | Risk | Effort | targetSdk > 28 | 16 KB | Android 17 |
|---|---|---|---|---|---|---|---|
| **1** | **"proot as safety net, native everywhere it can be":** ship 0014 (write family, sockets, FEX's `syscall()`); a **proot-aware Arch glibc** (§2.1 patches plus internal path entry points into the fast path); **`openat2(RESOLVE_IN_ROOT)` fast path on 16 QPR1+**, probed at session start; game tree in a **second proot started from a `:game` Android process**; ADPF hint sessions, `setGameState` and a uclamp trial | **Medium-high** for start-up, loading and asset streaming. Small in steady state, which is already untraced | **Low**: every miss falls back to proot | Medium | **Works** (proot loader in the native-library directory; only `execmod` emulation for Wine to add) | proot is agnostic; the FEX/Wine risk is separate | Handles the phantom and memory limits best (two processes, two budgets) |
| **2** | **Native Steam host** (steamclienttermux model): Steam and CEF run outside proot on a DroidDeck-prefixed glibc built per package (4 builds, or a fixed-length placeholder prefix patched at install), with an exec preload, `/tmp`/`/dev/shm`/`/proc` shims and a SysV-semaphore broker. Games stay in proot | **High for the Steam UI and CEF bursts**: the tracer stops seeing CEF. Their 6.99× launch figure is against a much slower proot | **Medium-high**: Steam self-update re-execs, `/proc/self/exe`, `get_robust_list`, semaphores; the failure mode is a crash | High | Only with the loader-based exec of §6.3 (2) plus `/proc/self/exe` emulation | OK | Neutral |
| **3** | **Direct game dispatch** (their 2026-08 dispatcher): Proton, Wine, FEX and the game run natively from a prebuilt `/usr` tree replacing pressure-vessel | Load-time gain. The FPS gain is mostly already taken by 0004–0013; expect far less than their +20.8% | **High**: per-game allow-lists in their implementation; Wine's `/tmp/.wine-uid`, fsync and `shm_open` paths | High | As #2 | Same FEX/Wine risk | Neutral |
| **4** | **In-process interception runtime** (systrap-style SIGSYS with signal virtualisation, or svc-hook rewriting) with a native-library loader owning exec, replacing proot entirely | Highest in theory (no tracer at all) | **Very high**: signal virtualisation across glibc, Wine and Chromium; filters persisting across exec; JIT `svc`; `execmod` at ≥ 29 | Very high | Possible | Agnostic | Neutral |
| 5 | **Bionic-native Wine** (GameNative modern, Winlator Bionic): targetSdk 36 via `linker64` | Large for Wine-only titles | Medium | High | Yes | box64 path only | — |
| 6 | **AVF Debian VM** | Negative for GPU work | — | — | — | Only escape from 16 KB | — |

**Dead ends, stated plainly:**
- **Kernel namespaces and containers, bwrap, pressure-vessel as-is, chroot, FUSE, fanotify**:
  closed in GKI and seccomp (§3, §4).
- **Syscall User Dispatch**: no arm64 kernel has it.
- **seccomp unotify**: no faster than ptrace (doc).
- **memfd exec as a W^X workaround**: SELinux denies it.
- **AVF**: needs adb, mostly absent on Snapdragon, no KGSL.
- **#5 bionic Wine**: incompatible with the goal of running Valve's Linux client and Proton
  unchanged. It is a separate product.

**What would change the ranking:**
- A vendor shipping 16 KB by default on a flagship Snapdragon would move FEX/Proton 4 KB emulation
  to the top of the risk list.
- An Android release that raises the install floor past 28 would make §6.3 mandatory. proot (#1)
  is already compatible; #2 and #3 are not.
- A merged arm64 generic-entry conversion would change nothing until a GKI ships it, and the
  blocked-SIGSYS rule would still apply.

### 9.2 Cheap things worth doing whatever the architecture

1. **Probe the seccomp filter inside the app at session start.**
   - What to test: `openat2`, `faccessat2`, `fchmodat2`, `landlock_create_ruleset`, `clone3` and
     `close_range`.
   - How: in a forked child, so a SIGSYS kills the child, not the session.
   - What it enables: the `RESOLVE_IN_ROOT` fast path, and dropping the `faccessat2` emulation.
   - Why it is needed: adb does not run under the app filter.
2. **Detect and explain the 32-process phantom limit.** It is 32 child processes across all apps,
   and a single tree of Steam, CEF, Proton and Wine can pass it. Offer the developer-option or adb
   fix, as WinNative does.
3. **Detect the Android 17 MemoryLimiter afterwards.** Read `ApplicationExitInfo` and look for
   `MemoryLimiter:AnonSwap`. Document `adb shell am memory-limiter ignore <uid>`.
4. **ADPF.** Create one hint session covering gamescope's and the game's render threads (same UID,
   different processes). Feed it frame times from gamescope.
5. **Keep proot and its loader in the native-library directory**, and avoid any direct `execve`
   from the app into `files/`, so a future targetSdk bump stays possible.
6. **Audit rootfs ELF alignment for 16 KB.** Track box64 0.4.6's 4 KB emulation and Wine 11's
   partial simulation as the only options for 16 KB phones.

---

## 10. Where `proot-performance.md` is wrong or out of date

1. **`openat2`, `faccessat2` and `fchmodat2` "blocked".** This is true only up to the initial
   Android 16 release.
   - AOSP `1d1e54b` (2025-03-31) allows all three, plus `*xattrat`. It is in 16 QPR1 and 16 QPR2
     (lineage-23.1/23.2, GrapheneOS `16-qpr1`) and in 17.
   - The doc's check against `android16-release` predates it.
   - The test phone's QPR level is unknown, so re-test **inside the app**.
   - Consequences on those devices:
     - glibc's `faccessat` no longer costs a SIGSYS round trip;
     - the fast path can use `RESOLVE_IN_ROOT` (1.0 µs, kernel-correct) instead of the
       verify-parent and 2 s TTL scheme;
     - `libblsession.so`'s `openat2` → `openat` rewrite becomes optional.
2. **`landlock_*` "blocked".** Allowed since Android 16 (AOSP `ba03012`, 2025-03-24). GKI 6.12+
   builds Landlock in. It is useless for redirection.
3. **`clone3` "blocked before Android 15".** AOSP allowed it in September 2023 (`b65dec5`). It is
   in Android 15 and already in LineageOS 21 (Android 14 QPR builds). Early Android 14 builds may
   still block it.
4. **steamclienttermux "still run Proton and the games under proot".** Out of date since
   2026-08-18.
   - Their direct dispatcher runs the Runtime's Python, Proton, Wine, FEX and allow-listed games
     with `TracerPid: 0`, with a matched +20.8% average FPS (25.167 → 30.400) in Tomb Raider.
   - The doc's other figures are misattributed:
     - "Steam's first window 7× faster" is really *Runtime request to first game window*,
       407.2 s → 58.3 s;
     - "about 5% more FPS" is the native-*host*-only step (22.2 → 23.4).
5. **"Winlator-style padded path rewriting".** Winlator does not pad or patch paths. It compiles
   glibc, box64 and Wine with the absolute prefix `/data/data/com.winlator/files/rootfs`.
   - Forks rebuild for their own package: GameNative's box64 has
     `PT_INTERP /data/data/app.gamenative/files/imagefs/…`.
   - Bionic forks redirect foreign prefixes with a preload.
   - For four package names, the real options are four builds or a conda-style fixed-length
     placeholder patched at install.
6. **Open item "validate that the app domain allows an anonymous `PROT_EXEC` page".** sepolicy
   grants `execmem` to every `appdomain` (`private/app.te`, "WebView and other application-specific
   JIT compilers"). `mmap(PROT_EXEC)` of `app_data_file` is allowed for all untrusted apps. Both
   trampoline variants are permitted by AOSP policy. A quick in-app check is still worthwhile in
   case an OEM deviates.
7. **Option 4 (in-process SIGSYS) understates the obstacles.** Two need adding:
   - The kill on a blocked SIGSYS is avoided *only* under ptrace, and even then the kernel resets
     the handler to `SIG_DFL` (`force_sig_info_to_task`).
   - seccomp filters persist across `execve`, so a freshly exec'd image runs `ld.so` under the trap
     filter before any handler exists.
8. **Missing constraints, not errors:**
   - the 32-process phantom limit;
   - the Android 17 MemoryLimiter, which makes the single tree under one app process a memory
     budget question as well as a tracer-throughput one;
   - the targetSdk-gated GPU ioctl filter (16 QPR2).
9. **Minor.** "`pidfd_*` allowed": `pidfd_open` from Android 11; `pidfd_getfd` and
   `pidfd_send_signal` from 12. `close_range` "blocked on Android 12" is correct (allowed from 13).

---

## References

**DroidDeck**
- `docs/development/proot-performance.md`; `app/build.gradle` (targetSdk 28 rationale).

**Prior-art projects** (commit or date read)
- brunodev85/winlator, [releases](https://github.com/brunodev85/winlator/releases) and
  [glibc_patches](https://github.com/brunodev85/winlator/tree/main/glibc_patches) (`b6b2259`).
- brunodev85/winlator-app (`3981d86`):
  [GuestProgramLauncherComponent.java](https://github.com/brunodev85/winlator-app/blob/main/app/src/main/java/com/winlator/xenvironment/components/GuestProgramLauncherComponent.java),
  [RootFS.java](https://github.com/brunodev85/winlator-app/blob/main/app/src/main/java/com/winlator/xenvironment/RootFS.java),
  `app/src/main/assets/rootfs.tzst`, `assets/box64/box64-0.4.4.tzst` (strings and `readelf`).
- utkarshdalal/GameNative (`c78519c`):
  [build.gradle.kts](https://github.com/utkarshdalal/GameNative/blob/master/app/build.gradle.kts),
  [ProcessHelper.java](https://github.com/utkarshdalal/GameNative/blob/master/app/src/main/java/com/winlator/core/ProcessHelper.java),
  [BionicProgramLauncherComponent.java](https://github.com/utkarshdalal/GameNative/blob/master/app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java),
  [ImageFsInstaller.java](https://github.com/utkarshdalal/GameNative/blob/master/app/src/main/java/com/winlator/xenvironment/ImageFsInstaller.java),
  [ContainerConfigDialog.kt](https://github.com/utkarshdalal/GameNative/blob/master/app/src/main/java/app/gamenative/ui/component/dialog/ContainerConfigDialog.kt),
  `app/src/modern/assets/libredirect-bionic-wx.so`, `assets/box86_64/*`.
- [oxters168/Pluvia](https://github.com/oxters168/Pluvia) (2026-02-23).
- [maxjivi05/WinNative](https://github.com/maxjivi05/WinNative) (`098227e`):
  [LINUX-PROTONS.md](https://github.com/maxjivi05/WinNative/blob/main/docs/LINUX-PROTONS.md),
  [ChildProcessRestrictions.kt](https://github.com/maxjivi05/WinNative/blob/main/app/src/main/runtime/linux/ChildProcessRestrictions.kt).
- [The412Banner/Bannerlator](https://github.com/The412Banner/Bannerlator) (`c53c060`);
  [Pipetto-crypto/winlator](https://github.com/Pipetto-crypto/winlator);
  [coffincolors/winlator](https://github.com/coffincolors/winlator);
  [star-emu/star](https://github.com/star-emu/star);
  [olegos2/mobox](https://github.com/olegos2/mobox);
  [Ilya114/Box64Droid](https://github.com/Ilya114/Box64Droid).
- [huntergdavis/steamclienttermux](https://github.com/huntergdavis/steamclienttermux) (`dee92b8`):
  README,
  [ARCHITECTURE](https://github.com/huntergdavis/steamclienttermux/blob/main/docs/ARCHITECTURE.md),
  [PERFORMANCE](https://github.com/huntergdavis/steamclienttermux/blob/main/docs/PERFORMANCE.md),
  [TOMB_RAIDER_OPTIMIZATION_PLAN](https://github.com/huntergdavis/steamclienttermux/blob/main/docs/TOMB_RAIDER_OPTIMIZATION_PLAN.md),
  [TOMB_RAIDER_BENCHMARK](https://github.com/huntergdavis/steamclienttermux/blob/main/docs/TOMB_RAIDER_BENCHMARK.md),
  [TECHNICAL_LOG](https://github.com/huntergdavis/steamclienttermux/blob/main/docs/TECHNICAL_LOG.md),
  `scripts/pressure-vessel-direct-dispatch.py`, `scripts/prepare-runtime-direct-root.py`,
  `config/*-lock.json`.
- [huntergdavis/termux-glibc-compat](https://github.com/huntergdavis/termux-glibc-compat) (`8d63206`).
- [CypherpunkArmory/UserLAnd](https://github.com/CypherpunkArmory/UserLAnd/blob/master/app/build.gradle) (`73bac3a`).
- [grycap/udocker](https://github.com/grycap/udocker);
  [rootlesscontaine.rs udocker](https://rootlesscontaine.rs/getting-started/udocker/);
  [x11docker#224](https://github.com/mviereck/x11docker/issues/224);
  [HanSoBored/opencode-termux](https://github.com/HanSoBored/opencode-termux).

**Termux**
- [termux-pacman/glibc-packages gpkg/glibc](https://github.com/termux-pacman/glibc-packages/tree/main/gpkg/glibc)
  (`e289d1e`): build.sh,
  [fakesyscall.json](https://github.com/termux-pacman/glibc-packages/blob/main/gpkg/glibc/fakesyscall.json),
  syscall.c, set-nptl-syscalls.patch, set-dirs.patch,
  [mprotect.c](https://github.com/termux-pacman/glibc-packages/blob/main/gpkg/glibc/mprotect.c),
  [glibc-runner](https://github.com/termux-pacman/glibc-packages/tree/main/gpkg/glibc-runner),
  `gpkg/fakechroot`.
- [termux-exec technical docs](https://github.com/termux/termux-exec-package/blob/master/site/pages/en/projects/docs/technical/index.md),
  [v2.0.0](https://github.com/termux/termux-exec-package/blob/master/site/pages/en/projects/releases/2/v2.0.0.md).
- [termux-app gradle.properties](https://github.com/termux/termux-app/blob/master/gradle.properties);
  [termux-play-store/termux-apps](https://github.com/termux-play-store/termux-apps) (targetSdk 37,
  `TermuxShellUtils.java`).
- [termux/proot src/execve/enter.c](https://github.com/termux/proot/blob/master/src/execve/enter.c) (`a179d3e`).

**AOSP** (read via LineageOS/GrapheneOS mirrors; canonical paths given)
- bionic `libc/SYSCALLS.TXT`, `SECCOMP_ALLOWLIST_{COMMON,APP}.TXT`,
  `SECCOMP_BLOCKLIST_{COMMON,APP}.TXT`, `tools/genseccomp.py`, `Android.bp` —
  <https://android.googlesource.com/platform/bionic/+/main/libc/>;
  mirrors [LineageOS/android_bionic](https://github.com/LineageOS/android_bionic) (lineage-16.0…24.0),
  [GrapheneOS/platform_bionic](https://github.com/GrapheneOS/platform_bionic) (16, 16-qpr1, 17).
- bionic commits:
  [1d1e54b](https://github.com/LineageOS/android_bionic/commit/1d1e54b2f63916f329512da4f67b00d62a52e7ae)
  (openat2, faccessat2, fchmodat2, *xattrat),
  [ba03012](https://github.com/LineageOS/android_bionic/commit/ba0301243404cd599583a908077ebb39dd237a27)
  (Landlock),
  [b65dec5](https://github.com/LineageOS/android_bionic/commit/b65dec56295c6bc9d2e12a0df308577f6f70a4eb)
  (clone3),
  [b65e105](https://github.com/LineageOS/android_bionic/commit/b65e1050476ec37330129a7de480dc4fb662a05c)
  and [c9c0ebd](https://github.com/LineageOS/android_bionic/commit/c9c0ebde3c4d22592c2b868ee29cd0fe54c75e52)
  (close_range),
  [b3904ee](https://github.com/LineageOS/android_bionic/commit/b3904ee5b99a5cf12d4a19d190a3584ec5fcafde)
  (mseal),
  [7839c17](https://github.com/LineageOS/android_bionic/commit/7839c173f1d720e5c2d23b7d32de51675e4ef8cc)
  (userfaultfd).
- system/sepolicy `private/untrusted_app_all.te`, `untrusted_app_{25,27,29,30,32,34}.te`,
  `untrusted_app.te`, `app.te`, `domain.te`, `isolated_app_all.te`, `app_zygote.te`,
  `seapp_contexts`, `public/te_macros` (`app_domain`, `set_xperm_filter`) —
  <https://android.googlesource.com/platform/system/sepolicy/+/main/>;
  mirror [LineageOS/android_system_sepolicy](https://github.com/LineageOS/android_system_sepolicy)
  (lineage-24.0). Commits `0671c4d3071034ac81eef2cccac214fd3b88942a` (memfd_file class),
  `0cf9cecef951c6d96f9d179e566b09137560b951` and `de15264b10f852b1e1b59064aa3f316f2e29c625`
  (targetSdk-36/37 domains).
- frameworks/base (lineage-24.0, mirror [LineageOS/android_frameworks_base](https://github.com/LineageOS/android_frameworks_base)):
  `services/core/java/com/android/server/pm/PackageManagerService.java` (`MIN_INSTALLABLE_TARGET_SDK`),
  `InstallPackageHelper.java`,
  `services/core/java/com/android/server/power/hint/HintManagerService.java` (`checkTidValid`),
  `services/core/java/com/android/server/am/{PhantomProcessList,ActivityManagerConstants,ActivityManagerService,MemoryLimiter}.java`,
  `MemoryLimiter.md`, `core/java/android/os/PerformanceHintManager.java`,
  `core/java/android/os/health/SystemHealthManager.java`,
  `core/jni/com_android_internal_os_Zygote.cpp` (`SetUpSeccompFilter`).
- build/release `flag_declarations/RELEASE_PLATFORM_MIN_SUPPORTED_TARGET_SDK_VERSION.textproto`
  ([LineageOS/android_build_release](https://github.com/LineageOS/android_build_release),
  [GrapheneOS/platform_build_release](https://github.com/GrapheneOS/platform_build_release) `17`).
- packages/modules/Virtualization
  [`android/android.system.virtualmachine.res/AndroidManifest.xml`](https://github.com/GrapheneOS/platform_packages_modules_Virtualization/blob/17/android/android.system.virtualmachine.res/AndroidManifest.xml).
- GKI `arch/arm64/configs/gki_defconfig` for 6.1.177, 6.6.144, 6.12.94 and 6.18.33 (GrapheneOS
  `kernel_common-6.x`, branch `17`); [android17-6.18 release builds](https://source.android.com/docs/core/architecture/kernel/gki-android17-6_18-release-builds).
- Qualcomm vendor policy: [LineageOS/android_device_qcom_sepolicy_vndr](https://github.com/LineageOS/android_device_qcom_sepolicy_vndr) `lineage-24.0-caf-sm8850`.
- [GPU syscall filtering](https://source.android.com/docs/security/features/gpu-syscall-filtering);
  [16 KB page size](https://source.android.com/docs/core/architecture/16kb-page-size/16kb);
  [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes);
  [Android 17 behaviour changes, all apps](https://developer.android.com/about/versions/17/behavior-changes-all).

**Kernel and interception**
- torvalds/linux at `d6d3455` (v7.3-rc6): `arch/Kconfig` (`SYSCALL_USER_DISPATCH`), `arch/*/Kconfig`,
  `kernel/entry/syscall_user_dispatch.c`, `kernel/signal.c`, `kernel/sched/syscalls.c` —
  <https://github.com/torvalds/linux>.
- arm64 generic-entry series:
  [v7](https://lkml.iu.edu/hypermail/linux/kernel/2511.2/01436.html),
  [v14](https://lkml.iu.edu/hypermail/linux/kernel/2603.2/10637.html),
  [v15](https://lkml.rescloud.iu.edu/hypermail/linux/kernel/2605.1/04285.html),
  [v16](https://ratatoskr.run/linux-alpha/2026/06/17188145/t);
  [SUD docs](https://docs.kernel.org/6.1/admin-guide/syscall-user-dispatch.html).
- gVisor systrap: [README](https://github.com/google/gvisor/blob/master/pkg/sentry/platform/systrap/README.md),
  [usertrap_arm64.go](https://github.com/google/gvisor/blob/master/pkg/sentry/platform/systrap/usertrap/usertrap_arm64.go),
  [subprocess_linux.go](https://github.com/google/gvisor/blob/master/pkg/sentry/platform/systrap/subprocess_linux.go).
- [retrage/svc-hook](https://github.com/retrage/svc-hook) (Middleware '25,
  [paper](https://dl.acm.org/doi/10.1145/3721462.3770771));
  [ASC-Hook (LCTES 2025)](https://pldi25.sigplan.org/details/LCTES-2025-main/4/ASC-Hook-Efficient-System-Call-Interception-for-ARM);
  [zpoline](https://github.com/yasukata/zpoline);
  [lazypoline](https://github.com/lazypoline/lazypoline)
  ([DSN '24](https://research.tudelft.nl/en/publications/system-call-interposition-without-compromise/));
  [SaBRe](https://github.com/srg-imperial/SaBRe);
  [E9Patch](https://github.com/GJDuck/e9patch).

**16 KB, AVF and translators**
- [Fedora FEX change](https://www.fedoraproject.org/wiki/Changes/FEX);
  [box64 non-4K preview](https://box86.org/2026/08/preview-of-next-box64-improved-non-4k-pagesize-handling/);
  [box64 COMPILE.md](https://github.com/ptitSeb/box64/blob/main/docs/COMPILE.md);
  [fathonix/wine-proton-arm64ec](https://github.com/fathonix/wine-proton-arm64ec);
  [lacamar/wine-arm64ec-rpm](https://github.com/lacamar/wine-arm64ec-rpm);
  [Wine 11 notes as summarised by TechSpot](https://www.techspot.com/downloads/4638-wine.html).
- [Android Authority: Pixel 10 Linux GPU](https://www.androidauthority.com/pixel-10-linux-apps-gpu-acceleration-3608754/);
  [Gadget Hacks](https://android.gadgethacks.com/news/android-linux-terminal-gpu-acceleration-lands-on-pixel-10-with-major-limits/);
  [Samsung community AVF thread](https://us.community.samsung.com/t5/Discussions/Android-AVF-Linux-Terminal-app/td-p/3519972);
  [Qualcomm Gunyah AVF](https://www.qualcomm.com/developer/blog/2024/01/gunyah-hypervisor-software-supporting-protected-vms-android-virtualization-framework).
- [bayton.org minimum targetSdk matrix](https://bayton.org/android/android-minimum-targetsdk-matrix/).
