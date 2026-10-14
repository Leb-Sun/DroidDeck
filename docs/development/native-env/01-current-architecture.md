# 01: Current architecture, and what proot does for DroidDeck

Phase 1 internal audit for the native-environment work. This is a read-only code audit of branch
`Environment-work`, at `main`'s `0cafa83` (2026-10-11). No device was used.
`docs/development/proot-performance.md` (written 2026-09-30) is the measured baseline. This audit
builds on it and does not repeat its measurements.

Conventions:

- Every load-bearing claim cites `path:line`. Paths are relative to the repo root.
- **[measured]** means a number from a device run, quoted with its source: proot-performance.md, a
  commit message or a code comment. **[inferred]** means reasoning from the code.
- The proot source was read at the pinned commit (`tools/proot/source.env:1`, termux/proot
  `4dba3afb`) with `tools/proot/patches/*` applied. A reference such as `proot:src/execve/enter.c:709`
  means that patched tree. glibc behaviour was checked against glibc master of 2026-01-20, which is
  what 2.43 was released from. The rootfs ships Arch Linux ARM's `glibc 2.43+r22`.
- The git history is shallow, grafted at `b213ae7` (2026-10-09). Anything older can only be dated
  from comments and docs.

## 0. Summary

- **One session uses one proot.** The app process starts it with `ProcessBuilder`
  (`app/src/main/java/com/droiddeck/launcher/core/HostProcess.kt:29-60`). It runs about 50–100
  binds, a guest started with `env -i`, kompat (utsname only) and fake_id0 (identity only). Other
  proots also run beside it: one per Flatpak sandbox, per one-off store or package command, per
  Proton-extra install and per terminal.
- **The fast path has shipped and is on by default.** `SessionPrefs.kt:394` defaults it to `true`.
  It is no longer the prototype that proot-performance.md describes. It walks paths component by
  component the way proot's `canonicalize()` does, and covers the write family, Unix sockets,
  `/proc` links, `memfd_create`, `uname`, `isatty`, `passwd` and `get_nprocs`. An LD_AUDIT module
  (`tools/proot/fastpath/audit.c`) skips library lookups that cannot succeed. It is still a
  **separate** preload, loaded last (`SessionFiles.kt:208-210`), and has no `syscall()` hook.
- **What still goes through the single tracer:**
  - every `execve`, with its loader, `PT_INTERP`, the ld.so opens and the `brk` emulation;
  - every glibc-internal or raw-`svc` path call (ld.so, locale/gconv, fts, most of nss, the static
    `wine-preloader`, FEXInterpreter's `syscall()`);
  - clone/exit ptrace events, `wait4`, `chdir`, `getcwd` misses and `readlink /proc/self/exe` once
    per process;
  - every Android-blocked syscall glibc issues (`SIGSYS` round trips).
- **A tracer-free mode needs a patched guest glibc/ld.so, or something that runs before ld.so.**
  Stock glibc 2.43 calls `set_robust_list` and `rseq` from ld.so's TLS setup
  (`glibc:sysdeps/nptl/dl-tls_init_tp.c:95,111-112`), before any preload exists. Android's app
  seccomp policy answers both with `SIGSYS` (proot-performance.md:186-188). Without a tracer, that
  `SIGSYS` takes its default action and kills the process **[inferred]**.

## 1. Launch path end to end

### 1.1 Android side

```
MainActivity / SessionActivity            (the Wayland compositor runs in the app process: app/src/main/cpp/waylandcomp)
  └─ SessionService.runSession()          session/SessionService.kt:320-556
       ├─ LinuxRuntime.writeAccounts()    passwd/group: "root" = the app uid       runtime/LinuxRuntime.java:503-510
       ├─ killStragglers()                                                         SessionService.kt:333, 1163
       ├─ SessionFiles.stage()            APK assets → rootfs, ld.so.preload       session/SessionFiles.kt:45-234
       ├─ guest env: env -i … (addClientEnvironment, startAudio, controllers)      SessionService.kt:579-795
       ├─ sessionBinds() + LinuxRuntime.binds()                                    SessionService.kt:798-974, LinuxRuntime.java:195-289
       ├─ fast path key and env                                                    SessionService.kt:437-452
       ├─ LinuxRuntime.command() → proot argv                                      LinuxRuntime.java:136-192
       ├─ host env for proot (PROOT_LOADER, PROOT_TMP_DIR, PROOT_FASTPATH, …)      SessionService.kt:458-471
       ├─ SessionParts start: PulseAudio (Android-side, bionic), DirectAudio relay,
       │    battery/hwmon/cpu-stat/gpu-stat writers, network link, rumble          SessionService.kt:489-498, 814-850
       └─ HostProcess.start(proot …) → pid; raiseTracer(pid) to nice −6 after 2 s  SessionService.kt:510-534, 1505-1527
```

The proot process is a direct child of the app. It counts against Android 12+'s phantom-process
limit, which the app asks the user to lift (`core/PhantomProcessLimit.kt:4-31`). Stopping a session
sends SIGTERM to proot, waits 1.2 s, kills it and then sweeps its tree (`SessionService.kt:976-995`,
`GRACE_MS` at `:1833`). `--kill-on-exit` takes the tracees down with proot.

### 1.2 The proot command line

**Binaries.** The app runs `nativeLibraryDir/libproot.so` and points `PROOT_LOADER` at
`nativeLibraryDir/libproot-loader.so` (`LinuxRuntime.java:83-93`, `SessionService.kt:459`). The
rootfs's own Termux proot at `opt/android-host/` is only a fallback (`LinuxRuntime.java:80-99`), and
the fast path refuses to run under it (`runtime/ProotFastPath.kt:28-35`).

proot is built with NDK API 26 and talloc linked statically (`tools/proot/build.sh:5,31-48`). The
loader is not bundled (`PROOT_UNBUNDLE_LOADER=/nonexistent`, `build.sh:48`). Patches 0001–0014 are
applied in glob order; two files share the number 0012 (`tools/proot/patches/`).

**Why `extractNativeLibs`/targetSdk 28 matter, precisely.** proot rewrites every guest `execve` to
exec its loader instead (`proot:src/execve/enter.c:709-713`). The loader then maps the program and its
`PT_INTERP` with `mmap(PROT_EXEC)`. So the kernel never `execve`s a guest ELF. It execs the loader
from `nativeLibraryDir`. AppImageManager confirms the loader "maps a program executable" and that
noexec storage refuses it (`runtime/AppImageManager.kt:143-144`).

Two things in the app do depend on running files from `files/`:

- The PulseAudio daemon is exec'd directly from `files/pulseaudio/libpulseaudio.so`
  (`audio/PulseAudioComponent.java:53,159-169`). This contradicts the comment at
  `app/build.gradle:172-174`.
- The loader `mmap`s app_data_file pages executable.

Whether a targetSdk 29+ domain still allows that mmap is a platform question for
`02-landscape-and-constraints.md`.

**Options** (`LinuxRuntime.java:170-192`):

| option | why (source) |
|---|---|
| `--kill-on-exit` | Stopping the session means killing exactly the proot pid; the tree goes with it (`core/HostProcess.kt:10-12`, `LinuxRuntime.java:173`). |
| `--kernel-release=\Linux\<hostname>\<release>\<version>\<machine>\localdomain\-1\` | The guest hostname (`DroidDeck`, configurable) while keeping the host's kernel identity (`LinuxRuntime.java:174-175,495-500`). This loads kompat. With patch 0011 it traces only `uname`, `sethostname` and `setdomainname`, and leaves the vDSO in place (`tools/proot/PATCHES.md:60-67`). |
| `-i <uid>:<uid>` | Xwayland's `Popen()` calls `setgid()`/`setuid()` before it execs xkbcomp, and dies without them. Android traps the whole `set*id` family. fake_id0 answers it (`LinuxRuntime.java:176-186`). Patch 0012 cuts it to identity mode (`PATCHES.md:68-75`). |
| `-0` (instead of `-i`) | Only for `GuestCommand(fakeRoot=true)`, because pacman and pacman-key refuse any uid but 0 (`LinuxRuntime.java:166-169`, `runtime/GuestCommand.kt:46`, `docs/development/flatpak.md:14`). |
| `-r <files>/linuxfs`, `-w /root` | The rootfs and start directory (`LinuxRuntime.java:187-190`). |
| `--link2symlink` | Only for AppImage import, through `GuestCommand(linkDir=…)` with `PROOT_L2S_DIR`: sharun images use hard links, which Android denies (`GuestCommand.kt:47,61`, `AppImageManager.kt:151-158`). |
| `-v N` | Flatpak sandboxes only, with `DROIDDECK_PROOT_VERBOSE` (`runtime/BwrapSpawner.kt:365-368`). |

**proot's own environment** (`SessionService.kt:458-471`):

| variable | why |
|---|---|
| `PROOT_LOADER` | The loader sits beside `libproot.so` in `nativeLibraryDir`. |
| `PROOT_TMP_DIR=<cache>` | Glue directories and short socket names (`SessionService.kt:460`). |
| `PROOT_NO_SECCOMP=1` | Only with the "Run proot without seccomp" toggle, for vendor kernels whose filter misbehaves (`session/SessionPrefs.kt:368-375,383`). |
| `PROOT_FASTPATH=<key>` | The fast path is on (section 5). |
| `LD_LIBRARY_PATH` | Only when the rootfs fallback proot (which needs libtalloc) is used (`LinuxRuntime.java:95-99`). |

**The guest command:** `/usr/bin/env -i HOME=/root USER=root PATH=/usr/local/bin:/usr/bin:/bin …
/usr/local/bin/droiddeck-session <mode> [args]` (`SessionService.kt:579-675, 407-430`). It carries
about 60 variables, including:

- `XDG_RUNTIME_DIR=/run/droiddeck` and `WAYLAND_DISPLAY=wayland-0` (`:596-598`);
- Zink for CEF (`:602-604`) and the Vulkan ICD as a *host* path (`:605`);
- `MESA_DISK_CACHE_DATABASE=1` (`:631-636`), which makes the shader cache one file read with
  `pread`/`pwrite` instead of an open and a rename per entry;
- `GLIBC_TUNABLES=glibc.pthread.rseq=0:glibc.malloc.top_pad=16777216` (`:637-640`);
- `PULSE_SERVER=unix:<files>/…` (`:702`), the `FAKE_EVDEV_*` controller rings (`:733-793`), the
  `BL_*` switches, and the `PROOT_FP_*` view (`:445`).

The guest starts with an empty environment, so nothing from Android leaks in.

**Binds.** Each is `-b host[:guest]`, in this order (`LinuxRuntime.java:195-289`, then
`SessionService.sessionBinds` at `:798-974`):

| guest path | host | why (source) |
|---|---|---|
| `/dev`, `/proc`, `/sys` | same | The real kernel views (`LinuxRuntime.java:200-202`). |
| `/dev/random` | `/dev/urandom` | `LinuxRuntime.java:203`. |
| `/dev/fd`, `/dev/stdin`, `/dev/stdout`, `/dev/stderr` | `/proc/self/fd[/N]` | The usual links (`:204-207`). proot resolves these host paths with `realpath()` **in its own process** (`proot:src/path/binding.c:480`), so in a session `/dev/stdout` names proot's own fd 1, which is `/dev/null` (`tools/proot/fastpath/fastpath.c:194-209`). |
| `/sys/fs/selinux` | `etc/droiddeck/empty` | Hides SELinux from libselinux users (`:208`). |
| `<files>`, `<cache>` | same path | Compositor socket, audio socket, logs, fake input rings and staged drivers. The same string is valid on both sides (`:124-129,209-210`, `SessionService.kt:690-691`). |
| `<files>/.wayland-rt`, also at `/run/droiddeck` | runtimeDir | libwayland checks `sun_path` (108 bytes) against the *guest* path before proot sees it. On adopted storage `…/files/.wayland-rt/gamescope-0` was one byte too long (`LinuxRuntime.java:37-44,211-214`). |
| `<files>/session` | same | Fake evdev nodes and rings (`:71-78,215`). |
| `/storage/emulated/0` | same | The user's games and files (`:216-218`). |
| `/dev/shm` | `<cache>/shm` | Android has no `/dev/shm`. glibc `shm_open` and Chromium accept a plain directory (`:127-128,219-221`). It is emptied before every session (`SessionService.kt:432-434`). |
| `/usr/local/share/fonts/android` | `/system/fonts` | CJK and Thai fonts (`:222,481-488`). |
| `/proc/bus/pci/devices`, `/proc/{stat,version,loadavg,uptime,vmstat}`, `/proc/sys/kernel/{cap_last_cap,overflowuid,overflowgid}`, `/proc/sys/fs/file-max` | `etc/droiddeck/proc/*` | Bound only when the real file is unreadable (`:224-279`). "Android denies apps these; glibc, Steam and libcap read them at startup." The PCI list stops CEF's GPU process `exit(1)`ing in libpci, which had sent the UI to SwiftShader: 44 → 85 fps scrolling, per the comment **[measured, cited]** (`:226-236`). `file-max` is for protonfixes (`:248-250`). |
| `/sys/dev/char`, `/dev/dri`, `/dev/dri/renderD<N>` | `<cache>/drm/*`, `/dev/kgsl-3d0` | libdrm and gamescope need a render node. Apps cannot open `/dev/dri`, so KGSL stands in (`:291-340`). |
| `/sys/class/drm`, `/sys/class/powercap` | `<cache>/drm/class`, an empty dir | MangoHud dies when the listing is refused (`:342-372`). |
| `/sys/kernel/debug/dri/0/perf_now`, the Adreno devfreq `cur_freq`, `thermal_zone{28,26,22}/temp` | readable KGSL/thermal files | Valve's mangoapp reads Deck paths (`:394-423`). |
| `/sys/class/thermal/thermal_zoneN/type` (per CPU zone) | `etc/droiddeck/cpu-thermal-type` | mangoapp averages zones named `cpuN-thermal` (`:429-448`). |
| `/dev/input` (+ Deck sysfs: `/sys/devices/droiddeck`, `/sys/class/hidraw`) | `<files>/session/dev/input`, … | Fake pads (`SessionService.kt:807-808`, `session/SteamDeckPad.kt:40,324`). |
| `/sys/class/power_supply`, `/sys/class/hwmon`, `/proc/stat`, `/sys/class/kgsl/kgsl-3d0` (+2) | Files the app writes every few seconds | Battery, temperatures, CPU load and GPU load where the kernel refuses them (`SessionService.kt:809-847`, `session/GpuStatsComponent.kt:37-42`). |
| `/root/Storage`, `/root/ROMs` | shared storage, the ROMs folder | File dialogs list "Computer" from `/proc/mounts`, where a proot bind never shows (`SessionService.kt:851-859,926-933`). |
| `/mnt/droiddeck-sd` and `/mnt/bannerlator-sd`, each plus ~20 nested binds | the SD library plus `files/steam-libraries/<sha>/…` | Shared storage cannot hold the symlinks of Wine prefixes or native Steam depots. `compatdata`, `shadercache` and every Proton/runtime/FEX depot dir (in both cases) are overlaid with app-private directories: "parent binds come first; the more specific private directories override them" (`session/SecondaryLibrary.kt:4,22-55`). |
| Games folders, store install roots, folders of added scripts | the user's choices | `SessionService.kt:904-925,934-962`. Games-folder libraries are budgeted against the fast path's 256-bind limit (`:938-953`). |

A typical session has 80-odd binds (`fastpath.c:71-73`), not the "about 25" in
proot-performance.md:56-58.

### 1.3 Into the guest

**`droiddeck-session`, outer pass** (`tools/linuxfs/overlay/usr/local/bin/droiddeck-session`):

1. Redirects its output to the session log (`:16-30`).
2. Builds a locale archive once per glibc package (`:32-51`). Without it, about 30 opens per process
   hit proot.
3. Clears stale `/tmp` entries with `pgrep` and `find` (`:53-82`).
4. Sets up fonts with `droiddeck-fonts` and sometimes `fc-cache` (`:198-221`).
5. Waits for the compositor with up to 100 `wayland-info` runs (`:224-227`).
6. `exec`s `gamescope --backend wayland … [-e] [--xwayland-count 2] [--mangoapp] -- droiddeck-session
   <mode>` (`:244-313`). The desktop mode (`lxqt`) instead execs `droiddeck-desktop`, with no
   gamescope (`:230-243`).

**Inner pass (under gamescope, `BL_INSIDE=1`), Steam mode** (`:402-1090`):

1. Starts the clipboard helper and `droiddeck-agent`, a python X-focus publisher (`:356-362`).
2. Runs the one-off helpers: `droiddeck-steam-install` (bash), `droiddeck-steam-library`,
   `steam-compatibility` and `droiddeck-steam-ui-scale` (python), `droiddeck-steam-shortcuts`
   (python), and `droiddeck-seed-redists` (bash, "some forty programs", `:640-641`) (`:403-492`).
3. Starts the background loops:
   - the client-cores beat (`:451-468`);
   - the launcher-cores python, polling every 2 s (`:472-475`);
   - first-run Proton watchers (`:495-558`);
   - the stretch loop (`:579-588`);
   - two `xprop -spy` loops (`:596-639`);
   - the library beat, every 15 s and gated (`:647-700`).
4. Starts the system bus `dbus-daemon`, `droiddeck-netmanager` and `droiddeck-login1` (python)
   (`:750-801`), the optional Decky supervisor (`:809-841`), `droiddeck-steam-compat` (python, CDP)
   (`:857`), `droiddeck-steam-games watch`, the Lossless probe and the launch watcher (`:908-1001`).
5. Runs the client loop: `$steam_root/steamrtarm64/steam -gamepadui -steamdeck -steamos3
   -clientbeta steamdeck_publicbeta -no-cef-sandbox -cef-force-gpu -cef-use-gl=angle
   -cef-use-angle=vulkan …`, restarting on rc 42 (`:1002-1057`).

The session avoids exec-heavy polling deliberately: `nap` is `read -t` on a pipe (`:149-163`).

**Game launch chain.** proot-performance.md:51 has the order wrong; this is the order in the code:

```
steam ─▶ linuxarm64/steam-launch-wrapper ─▶ reaper (SteamLaunch AppId=…)        droiddeck-steam-install:84-90
   ─▶ droiddeck-proton  (bash; the compat tool's commandline)                    steam-compatibility:77-84, 380-445
        bl_components (awk, one sha256sum, cp), bl_directaudio (`wine --version`, grep, ln, awk, date, stat),
        bl_game_cpus, bl_idtech3, bl_libpath, bl_store_launch (droiddeck-store-launch)
        LD_PRELOAD=libblsession:libfakeinput:<overlay>, LD_AUDIT=libblaudit (fast path only)    :384-411
   ─▶ droiddeck-game-env (python3; runpy droiddeck-wincomponents + droiddeck-esync)   droiddeck-game-env:278-321
   ─▶ [optional sync-pack copy of Proton] ─▶ <depot>/proton (python3)
   ─▶ wine (arm64ec; FEX loaded in-process as a PE DLL for x86), wine-preloader, wineserver, services,
      winedevice, explorer, plugplay, … game.exe
```

Steam runs the tool "several times per launch (getcompatpath, run, waitforexitandrun)"
(`steam-compatibility:355`). About 40 s after login it also runs "its own graphics probe through
EVERY registered tool at once" (`:87-92`): a burst of concurrent Proton starts. x86 Linux games
and an x86_64 Decky run through `droiddeck-fex`, which starts FEXInterpreter and FEXServer over
SteamLinuxRuntime_sniper (`steam-compatibility:539-555`, `droiddeck-fex:16-49,413-419`).

**Long-lived vs exec-heavy** **[inferred from the scripts]**:

| long-lived (per session) | exec-heavy phases |
|---|---|
| proot (one thread); outer and inner `droiddeck-session` bash; gamescope + 2 Xwayland + mangoapp + gamescopereaper; Steam client + steamwebhelper/CEF tree; dbus-daemon; python helpers (agent, netmanager, login1, steam-compat, launcher-cores, steam-games watch); bash loops; during a game: reaper, the proton python, wineserver and each Wine process | Session start (≈10 helper programs, several python3, `seed-redists` ≈40 programs, `wayland-info` polling); the Steam client's own start and self-update (rc 42 restarts), CEF zygote and renderer forks; the post-login probe through every tool; each game launch (3 tool runs × ~10 programs, 2 python3, then Wine's process fan-out); first launch of a game (prefix creation); the library beat when due (only when nothing is playing: `pgrep -x wineserver`, `:690`) |

### 1.4 Other modes and every other proot

| instance | started by | options | fast path | notes |
|---|---|---|---|---|
| Desktop (KDE) session | same `runSession`, mode `lxqt` (`SessionService.kt:1848`) | session options | yes | `droiddeck-desktop` runs `dbus-run-session startplasma-wayland`, with KWin nested in the app's compositor (`tools/linuxfs/desktop/droiddeck-desktop`). Games run in their own nested gamescope through `droiddeck-gpu`. `BL_DESKTOP=1` switches on `binfmt.c` (x86, Windows and noexec programs go to `droiddeck-open --exec`). |
| Run mode / file manager | same, `run` / `desktop` | session options | yes | `droiddeck-session:368-401` (AppImage with `APPIMAGE_EXTRACT_AND_RUN=1`, scripts, ELF; x86 through `droiddeck-fex run`). |
| Terminal shell | `SessionTerminal` (termux PTY), the same argv and host env as the session | session options + binds | yes (same key, `SessionService.kt:473-483`) | One proot per terminal (`SessionService.kt:78`). |
| Flatpak sandbox | guest `droiddeck-bwrap` → abstract socket → `BwrapSpawner` | `prootPrefix` + sandbox binds, `env -i` + Flatpak's env | no (no `PROOT_FASTPATH`, `BwrapSpawner.kt:376-382`) | The sandbox is built under `cache/bwrap/<n>`. Session binds are translated app-side (`BwrapSpawner.hostPath`, `:113-118`). Preloads go in by `LD_PRELOAD` from `/run/droiddeck-preload` (`:267-281`). A nested proot would be "about thirty times slower at a program's start" (`flatpak.md:46-47`). Sandboxes that remap nothing run inside the session proot (`droiddeck-bwrap:12-14`). |
| Store and package commands (Flatpak setup and installs, AppImage import, MSI unpack) | `GuestCommand.run` | `prootPrefix` (`-0` for pacman), `--link2symlink` for AppImages | no | `GuestCommand.kt:39-74`; callers `FlatpakManager.kt:93`, `AppImageManager.kt:154`, `WinComponents.kt:550,589`. |
| Proton extra install | `ProtonExtras` | `LinuxRuntime.command` without a session root | no | `session/ProtonExtras.kt:238-256`. It does not pass `PROOT_NO_SECCOMP` (inconsistent with the other spawners). |
| Decky | inside the Steam session | — | yes | arm64 directly, x86_64 through `droiddeck-fex` (`droiddeck-session:806-841`). |
| AppImages (imported) | inside a session | — | yes | `droiddeck-appimage-run` re-exports `ld.so.preload` as `LD_PRELOAD` for sharun's own ld.so (`:30-35`). |

### 1.5 What already runs outside proot

These parts are already "the app as the container":

- the Wayland compositor (`app/src/main/cpp/waylandcomp`);
- PulseAudio and the DirectAudio relay (`PulseAudioComponent.java:153-169`);
- the input ring writer (`input/FakeInputWriter.java`);
- the sysfs and proc stand-in writers (`session/{Battery,Hwmon,CpuStat,GpuStats,GpuMem}Component.kt`);
- the network description (`runtime/LinuxNetworkLinkComponent.kt:256-291`, which writes
  `/etc/droiddeck-network.json`, `resolv.conf` and `hosts` into the rootfs);
- suspend and resume by SIGSTOP of the whole tree (`session/SessionSuspendController.kt:193-214`).

## 2. Rootfs

- **Where it comes from.** The rootfs tarball is **not built in this repo**. The app fetches a
  catalog row (`version`, `url`, `sha256`, `size`) from
  `Droid-Deck/DroidDeck-Components/main/linuxfs.json` (`runtime/LinuxRuntimeInstaller.java:43-44,
  107-121`). The runtime came from WinNative's gamescope runtime (`LinuxRuntime.java:26`).
  Releases are `linuxfs-rN`. The last one named in the repo is `linuxfs-r9`, a 790 MB download
  (`docs/progress-log.md:866`; "~790 MB re-download" in `SessionFiles.kt:42`). The README says to
  allow about 3 GB installed (`README.md:18`).
- **Distro and glibc.** It is Arch Linux ARM, aarch64. The pacman database lists only the base
  image; gamescope, Mesa, Python and the rest "were unpacked over it without registering"
  (`tools/linuxfs/overlay/usr/local/bin/droiddeck-flatpak-setup:5-9`,
  `tools/desktop-kde/README.md:7-10`). glibc is **`2.43+r22+g8362e8ce10b2-2`**, Arch's package
  version (`tools/desktop-kde/runtime-packages.txt:35`). Nothing in the repo builds or patches
  glibc, and `droiddeck-session:39-40` keys the locale archive on the pacman glibc entry. Stock
  glibc **[inferred]**. The x86 preloads for FEX guests are compiled in Debian 11 (glibc 2.31) to
  match Steam's sniper runtime (`tools/linuxfs/debian-image.env:1-8`,
  `tools/linuxfs/build-x86-preloads.sh:16-22`).
- **Install and update on device.** The installer:
  1. downloads `linuxfs.tar.zst` to the cache and checks its sha256;
  2. extracts to `files/linuxfs.new`, keeping symlinks and hard links (a hard link falls back to a
     copy) and skipping device nodes;
  3. swaps it with `linuxfs`, carrying `/root` across (Steam, sign-in, games) by `rename`;
  4. writes `.version` (`LinuxRuntimeInstaller.java:271-354,493-554`).
- **The overlay is refreshed from the APK at every launch, not baked into the tarball.**
  `SessionFiles.stage` copies the `droiddeck-*` scripts, `libblsession.so`, `libfakeinput.so`,
  `libblfastpath.so`, `libblaudit.so`, `libssbs.so`, the patched gamescope, mangoapp, msitools and
  the FEX x86 preloads into the rootfs. Each lands through a rename. It then rewrites
  `/etc/ld.so.preload` (`SessionFiles.kt:36-217`). CI builds these from `tools/linuxfs/{preload,
  overlay}` and `tools/proot/fastpath` (`.github/workflows/build.yml:186-260`). The KDE desktop is a
  separate package (`tools/desktop-kde/README.md:13-17`).
- **The four package variants** (`tools/release/variants.txt`) differ only in the manifest package
  name (`tools/release/axml_rename.py:1-3`). The names are `com.droiddeck.launcher`,
  `com.tencent.ig`, `com.antutu.benchmark.full` and `com.ludashi.benchmark`. Every host path is
  derived at run time from `getFilesDir()`/`getCacheDir()`:
  - `/data/user/<u>/<pkg>/files/linuxfs`;
  - `/mnt/expand/<uuid>/user/<u>/<pkg>/…` on adopted storage (`LinuxRuntime.java:38-43`).

  Today **one tarball serves all four**: the rootfs holds only guest paths, and proot's `-r`
  supplies the prefix. Host paths do appear in the guest environment (`VK_ICD_FILENAMES`,
  `PULSE_SERVER`, `BL_LOG`, `FAKE_EVDEV_*`, `PROOT_FP_*`, imported driver manifests). They work only
  because `files/` and `cache/` are bound at their own paths. A relocated-prefix glibc (the Termux
  approach) would have to bake one of at least four prefixes, plus the user-id and adopted-storage
  variants, into `PT_INTERP` and ld.so's search paths (proot-performance.md:230-231).

## 3. The proot responsibility matrix

Classes:

- (a) the app does it before launch;
- (b) in-process, by the preload or the fast path;
- (c) a patched guest glibc/ld.so;
- (d) a userspace ELF loader;
- (e) only a tracer.

Costs are the patched-proot figures from proot-performance.md:97-109 unless stated. Frequency is
**[inferred]** unless a source is given.

| # | job | depends on it (evidence) | how often | cost today | could move to |
|---|---|---|---|---|---|
| P1 | **Path translation of libc path wrappers** (guest → rootfs/bind host path; readlink and getcwd results back) | Every program. Hot callers named in the code: wineserver `realpath`s every file it opens (`fastpath.c:1069-1071`); the Steam client stats each file a download creates through `__xstat` (`:929-935`) and `faccessat(AT_SYMLINK_NOFOLLOW)`s each reserved file (`:982-983`); Python imports (proot-performance.md:172) | hot at startup, load and streaming | 22.9–26.8 µs traced, 0.6–1.9 µs fast path; realpath 240 → 1.0 µs **[measured, fb8361b message]** | **(b), done** for the wrappers in §5 |
| P2 | **Path translation for glibc-internal and raw-`svc` callers** | ld.so (search path, preloads, `ld.so.cache`, `DT_NEEDED`, `dlopen`); locale/gconv; fts (`find`, `rm -r`); nss other than the passwd case; `posix_spawn`'s internal `chdir`; static `wine-preloader` (`files/lib/wine/aarch64-unix/wine-preloader`, `droiddeck-esync:22`); FEXInterpreter's x86 syscall passthrough | **every exec** (≥6 + N opens) and every `dlopen` | same per trap; "72% of ld.so's opens in an Among Us launch failed" **[measured, fb8361b]** | (c) for ld.so and libc internals; (b) partly with `libblaudit` (only in Proton launches) and a `syscall()` hook for FEX (not done); (e) for static binaries, or patch Wine's preloader |
| P3 | **Bind semantics**: remapped binds, nested override binds (SD library private dirs, plus lowercase twins), binds over single files, glue dirs above binds whose parent is missing, `/proc/self/fd` binds resolved in proot's process | SD/Games libraries (`SecondaryLibrary.kt:22-55`), `/run/droiddeck`, `/dev/shm`, every stand-in in §1.2 | resolved on every translated path | included in P1 | (b) with a bind table in-process (the fast path already mirrors proot's: `fastpath.c:301-332,341-397`); (a) the app creates mount points (`SessionService.kt:868-870,908,919`) and could replace some binds by real symlinks in the rootfs |
| P4 | **Fake `/proc` and `/sys` files** | glibc, Steam and libcap at startup; protonfixes (`file-max`); CEF's GPU process (libpci); MangoHud (asks for `/sys/class/drm` twice a second, `LinuxRuntime.java:366-367`); Steam battery and Deck controller sysfs | startup, plus periodic overlay reads | P1 cost | (a) the content is already written by the app; (b) open redirection, a pattern that already exists in-process (`tracer.c`, `tracefs.c`, `inputudev.c`) |
| P5 | **`/proc/self/exe`, `/proc/<pid>/{exe,cwd,root}`**: readlink, open and *exec* through them; `/proc/self` = tgid (0007) | The kernel's exe is proot's loader (`tools/linuxfs/preload/perms.c:32-34`), so anything asking needs proot: `perms.c`, `noexec.c:18-29`, the fast path's `own_exe` (`fastpath.c:517-538`), Chromium/CEF zygote re-exec and Wine's bin-dir lookup **[inferred]** | a few per process | 38–43 µs per readlink; the fast path asks proot once per process, then caches | (b) for readlink and open; exec through `/proc/self/exe` needs an exec shim (P7). If the kernel execs the real ELF, exe is correct and only needs detranslating (b) |
| P6 | **cwd**: `chdir` emulated, `getcwd` answered on exit | everything relative | `getcwd` moderate, `chdir` rare | 30–33 µs; `getcwd` now in-process (`fastpath.c:1096-1118`); patch 0014 keeps the kernel cwd at the host dir | (b): natively the kernel cwd *is* the host dir; `chdir` translate plus `getcwd` detranslate |
| P7 | **execve/execveat**: run the loader, map `PT_INTERP` from the rootfs, expand shebangs (`#!/bin/bash` does not exist on Android), set `AT_EXECFN`/comm, record exe, ignore setuid bits (`proot:src/execve/enter.c:595-735`) | every program start: scripts, python helpers, Steam self-update, CEF children, Wine re-exec, `taskset` | thousands at startup; dozens to hundreds per game launch | fork+exec+wait 0.70–0.78 ms, +0.2–0.4 ms with the fast path's second library; 200 execs ≈ 500 ms vs 230 ms direct | (c) relocated `PT_INTERP` (4+ prefixes) or (d) a userspace loader/exec shim (b) on the libc exec family. `posix_spawn`/`system`/`popen` use glibc's internal `__execve` and need their own wrappers (net.c and binfmt.c already wrap them). Raw-`svc` exec: (e) |
| P8 | **brk/heap emulation** (the kernel's brk belongs to proot's loader) | every glibc main arena, bash, python | at load; fewer with `top_pad` 16 MiB (`SessionService.kt:637-640`) | 30–33 µs, entry and exit | needed only because of a loader. A kernel-exec'd ELF needs nothing; under (d), (c) make malloc avoid brk (glibc already falls back to mmap when brk fails **[inferred]**) |
| P9 | **Unix-socket `sun_path`**: translate, and rebind host paths over 108 bytes to a short temp name | Wayland (`/run/droiddeck/wayland-0`), X11 (`/tmp/.X11-unix`), wineserver (`/tmp/.wine-<uid>/server-*/socket`), the system dbus, FEXServer (its socket moved for this reason, `droiddeck-fex:413-419`) | each connect; Wine per process | ≈25 µs; in-process when the host path fits (`fastpath.c:1382-1501`) | **(b), done** up to 108 bytes; (a) keep socket dirs short; >108 needs (e) or app-chosen short paths |
| P10 | **Android-blocked syscalls via `SIGSYS`** (`proot:src/tracee/seccomp.c:117-567`; extensions get them first) | see the next table | per exec and per thread | a trap plus a signal round trip | mostly (c); some (b); see below |
| P11 | **kompat**: `uname`/`gethostname` = `DroidDeck` | Steam, libX11 per display connection (`fastpath.c:1600-1602`), KDE, shells | once per process now | 28 → 0.006 µs **[measured, fb8361b]** | **(b), done** for libc callers; a raw `uname` would see Android's |
| P12 | **fake_id0**: `-i` identity `set*id`; `-0` fake root | Xwayland's keymap compile (`LinuxRuntime.java:176-186`); pacman (`GuestCommand.kt:46`) | rare | `SIGSYS` path | (b): `setuid`/`setgid`/`setgroups` wrappers that succeed when unchanged; a fakeroot-style preload for `-0` (or keep a tracer for that one tool path) |
| P13 | **ptrace emulation** (`ptrace`, `wait4`/`waitpid` traced) | gdb/strace in the terminal; crash handlers **[inferred, no evidence in repo]**. `wait4` traps on every wait (exit stop skipped by 0005) | `wait4` per child; ptrace rare | one stop per wait | not needed without a tracer (the kernel's ptrace works); (e) only to nest debuggers under proot |
| P14 | **`memfd_create` name filter** (refuses `JITCode:`, `opcache_lock`, `lib/apk/exec/`, `proot:src/syscall/enter.c:705-731`) | `wl_shm` pools, Chromium shm, wineserver, the session's `/proc/net` and status copies | hot | 18.6 µs → native | **(b), done** (`fastpath.c:1654-1665`, eadebc4) |
| P15 | **`statfs` exit fixup**: `/dev/shm` reported as tmpfs | `/dev/shm` probes **[inferred]** | rare | one exit stop | (b), trivial (the fast path leaves `/dev/shm` to proot, `fastpath.c:1276-1283`) |
| P16 | **`/tmp` and `/dev/shm`**: `/tmp` is the rootfs's own directory; `/dev/shm` → `<cache>/shm` | Wine's server dir, X11 sockets, CEF singleton, MangoHud config, esync/fsync/sysv objects in `/dev/shm` (`sysv.c:1-11`) | constant | P1 cost | (a) the directories, plus (b) path mapping. No tracer needed |
| P17 | **ioctl fixups**: `TCSETSF`→`TCSETS`, termios2 → termios, `FICLONE` `EACCES`→`EOPNOTSUPP` (0013 traces only these) | glibc ≥2.42 termios2 (`fastpath.c:1642-1652`), coreutils `cp` | rare (isatty now in-process) | one stop | (b) `tcgetattr`/`tcsetattr`/`ioctl` wrappers or (c) |
| P18 | **Android hard links and the new mount API**: `O_TMPFILE`→`EOPNOTSUPP`, denied link → `EPERM` (0012), `open_tree`/`move_mount`/`fspick`/`mount_setattr`/`openat2` → `ENOSYS` (0008, 0010) | ostree/libglnx (Flatpak), localedef, libICE locks (`links.c:1-23`) | rare | one stop | (b): `links.c` already covers the lock and rename cases; (c) for libglnx's raw calls |
| P19 | **Tracer self-protection**: `prctl(PR_SET_DUMPABLE)` ignored (`proot:src/syscall/enter.c:670-677`), `RLIMIT_STACK` fixups | — | rare | one stop | not needed without a tracer |
| P20 | **Process lifecycle and visibility**: one root pid, `--kill-on-exit`, TracerPid visible, SIGSTOP of a tracee undone by proot | teardown (`SessionService.kt:976-995`), suspend order (`SessionSuspendController.kt:193-202`), stragglers by name (`:1830-1831`); `tracer.c` hides TracerPid because the Steam client closes fd 1 on asserts (`tracer.c:5-18`); launcher-cores cannot SIGSTOP helpers "proot … resumes a stopped tracee" (`droiddeck-launcher-cores:12-13`) | session lifetime | — | (a) a supervisor (process group, subreaper, pidfd) in the app or a guest init |

**P10 in detail: what Android blocks, who calls it, and where it could go.** Policy is from
proot-performance.md:186-195.

| syscall | caller | frequency on glibc 2.43 | today | could move to |
|---|---|---|---|---|
| `rseq` | glibc TLS init | **once per exec**. New threads only try when the creating thread registered (`glibc:nptl/pthread_create.c:717-720,382-386`); proot-performance.md:188 and `SessionService.kt:637-639` say "every thread" | avoided by `GLIBC_TUNABLES=…rseq=0` | (a)/(c) |
| `set_robust_list` | glibc TLS init in ld.so | **once per exec**. After the first `ENOSYS`, `__nptl_set_robust_list_avail` stays false and threads skip it (`glibc:sysdeps/nptl/dl-tls_init_tp.c:95-101`, `pthread_create.c:388-397`) | proot `ENOSYS` | (c), or (d) with a `SIGSYS` handler installed before ld.so runs |
| `get_robust_list` | Steam's cross-process mutex | rare | **in-process** (`robust.c:40-72,111-120`) | done (b) |
| `faccessat2` | glibc `faccessat()` tries it on every call (`glibc:…/faccessat.c:29-35`) | per uncaught access | fast-path misses with flags 0 call plain `faccessat` (`fastpath.c:991-993`) | (b) wrappers, done; (c) for internal callers |
| `fchmodat2` | glibc `fchmodat()` with flags (`glibc:…/fchmodat.c:91-102`) | rare | proot | (c) |
| `openat2` | libglnx, FEX rootfs lookups | first call per process | `robust.c` turns it into `openat` after the first `ENOSYS` (`robust.c:128-137`) | (b) done; (c) for the first call |
| `setuid`/`setgid`/… | Xwayland | rare | fake_id0 | (b). The setxid broadcast in a threaded process runs with signals blocked, so it needs (c) or (e) |
| `futex_waitv` | Proton fsync | hot in games | **in-process** userspace implementation through the `syscall()` hook (`fsync.c:1-3`, `robust.c:121-124`) | done (b) |
| `clone3` | glibc `pthread_create` and `posix_spawn`; retried every time (`glibc:…/clone-internal.c:__clone_internal`) | **per thread, before Android 15** | proot `ENOSYS` → `clone` | (c). It is issued with every signal blocked (`glibc:nptl/pthread_create.c:804-805,298,868`), so an in-process handler cannot catch it |
| `close_range` | glibc spawn `closefrom`, Wine (Android 12) | rare | proot | (c); `ntsync.c:1292-1304` only does bookkeeping |
| `io_uring_*`, `landlock_*` | some engines | rare | proot `ENOSYS` | (c) or per-app |

**Hardest to move out of a tracer:**

- **P7 exec.** It needs all of these together: `PT_INTERP`, shebangs, a correct
  `/proc/self/exe` for CEF re-exec, glibc's internal `__execve` in `posix_spawn`, and the raw
  syscalls of the static `wine-preloader`.
- **P2 for raw-`svc` code and ld.so.**
- **P10 for calls glibc makes with every signal blocked** (`clone3` before Android 15, setxid
  broadcasts). These need (c); the doc's option 4 (proot-performance.md:240-242) explains why
  in-process `SIGSYS` is unsafe.
- **P9 above 108 bytes.**

Everything else is either done in-process already or is easy (b)/(a) work.

## 4. What already runs in-process

**Load order.** `/etc/ld.so.preload` = `libblsession.so`, `libfakeinput.so` (unless the no-pad
switch is set), `libblfastpath.so` (when enabled, "last, so the others' opens and stats reach it
through RTLD_NEXT") (`SessionFiles.kt:201-217`). Game launches also prepend `libblsession` and
`libfakeinput` to `LD_PRELOAD`, ahead of Steam's overlay (`steam-compatibility:384-401`), and add
`LD_AUDIT=libblaudit.so` (`:403-411`).

The result is that **the fast path is the only path translator**. Every libblsession and
libfakeinput hook is a policy shim in front of it, whose "real" call is the fast path's.
Translation is not duplicated. Policy shims plus one translator is how much of a "native"
environment already exists: the shims hold no proot assumptions apart from those listed in
section 7.

The exported hooks below come from building the sources on the host with `nm -D`; `binfmt.c` and
`noexec.c` add more on aarch64.

| shim | intercepts | why | relation to proot |
|---|---|---|---|
| `robust.c` | `syscall()`: `get_robust_list`, `futex_waitv` (through fsync), `openat2`, `execveat` → `execve` | blocked syscalls Steam/FEX/Flatpak use | **replaces proot's `SIGSYS` answers in-process.** This is also the natural `syscall()` hook for FEX path calls (not wired) |
| `fsync.c` | `syscall(SYS_futex_waitv)`, `shm_open`, `mmap`/`munmap` tracking (`BL_FSYNC`) | Proton fsync without the kernel call | emulates a blocked syscall entirely in userspace |
| `ntsync.c` | `/dev/ntsync` open/ioctl, `close`/`dup2`/`dup3`/`close_range`/`closefrom`, `recvmsg` (`BL_SYNC`) | userspace ntsync | independent |
| `opens.c` | `open`/`openat` (+64) | refuses the trace marker; serves `/proc/*/status` without TracerPid; ntsync open; dircache creates; `O_NOATIME` retry on FUSE; root-style write retry | policy in front of the fast path |
| `pathcache.c` (+`inputudev.c`'s `realpath`) | `access`, `faccessat(AT_FDCWD)`, `eaccess`, `realpath` — **Steam client only**, 2 s TTL, successes only | the post-logon compat-tool loop "was 23 of the client's 31 seconds of startup" on a Thor **[measured, cited]** (`pathcache.c:1-14`) | a cache in front of the fast path. Partly redundant now that realpath costs ~1 µs in-process |
| `dircache.c` | `opendir`/`readdir*`/…, `mkdir`, `rmdir`, `unlink`, `rename`, `link`, `symlink` — Steam client, `steamapps` on FUSE | listing a directory ~10× per file made reservation quadratic | independent (FUSE cost, not proot) |
| `binfmt.c` | `execvpe`, `posix_spawnp`, plus helpers for `execve`/`execv`/`execvp`/`posix_spawn`/`access` — desktop only | binfmt_misc for x86, Windows and noexec programs. proot "loads a static x86 program as a native one" (`binfmt.c:1-8`) | an **exec shim that already exists**: precedent for P7 |
| `noexec.c` | `mprotect`, mmap retry — FEX only | FEX maps x86 code `PROT_EXEC` on noexec storage | independent |
| `tracer.c` | used by `opens.c` and `netif.c` | hides TracerPid from everything but steamwebhelper | **exists only because of the tracer** |
| `links.c` | `link`, `linkat` | lock files by symlink; `DROIDDECK_LINK_RENAME` for localedef | complements patch 0012 |
| `perms.c` | write retry for the Steam client | root's override of read-only files | uses `/proc/self/exe`, which proot emulates |
| `storage.c` | `fallocate`, `posix_fallocate`, `pwrite`, `ftruncate`, `fstat`, `fsync` | opt-in storage diagnostics and the allocation fallback | independent |
| `lock.c` | `flock`, `fcntl` locks | FUSE has no locks | independent |
| `net.c` | `bind`, `listen`, `connect`, `accept(4)`, `execve`/`execv`/`execvp`/`posix_spawn` | answers Steam's `lsof` peer check (no `/proc/net/tcp`) | calls through to the fast path's socket translation |
| `netif.c` | `getifaddrs`, `if_*`, `ioctl` (dispatches to kgsl/ntsync/drm), `fopen` | adapters and routes from `/etc/droiddeck-net`; `/proc/net` stand-ins | independent |
| `sysv.c` | `sem*`, `shm*`, `msg*` | no SysV IPC in Android kernels | files under `/dev/shm` |
| `space.c` | `statfs`/`statvfs` | free space of card libraries | in front of the fast path |
| `drm.c`, `kgslid.c`, `kgslpoll.c` | `drmPrime*`, `ioctl` | GEM emulation on KGSL, A840v2 chip id, zero-timeout wait | independent |
| `inputudev.c`, `udevmon.c` | `udev_device_*`, `realpath`; `socket`/`getsockname`/`setsockopt` | pads for Wine's HID bus; a stand-in netlink monitor | independent |
| `tracefs.c`, `xmode.c` | `tracefs_*`, `XChangeProperty` | mangoapp's GPU memory; Follow screen | independent |
| `libfakeinput.so` (`app/src/main/cpp/fakeinput_steam.cpp`) | `open`/`openat`/`stat`/`statx`/`statfs`/`access`/`opendir`/`scandir`/`inotify_add_watch`/`read`/`write`/`ioctl`/`poll`/`select`/`close` | fake evdev, uinput, hidraw and sysfs | path-keyed but not a translator |
| `libblfastpath.so` | §5 | path translation | **the proot replacement for libc wrappers** |
| `libblaudit.so` | `la_objsearch` | skips ld.so candidates that cannot exist | **partially covers P2** (Proton launches only) |
| x86_64/i386 `libblsession`/`libfakeinput`/`libfaultreport`/`libthunkaudit` | the same, inside FEX guests | `SessionFiles.kt:136-138`, `droiddeck-fex:238-260` | — |

## 5. Fast path status

**On by default, and how it is gated:**

- The pref `prootFastPath` defaults to `true` (`SessionPrefs.kt:390-394`). It is shown as "proot
  fast path" under Session fixes and greyed out when "Run proot without seccomp" is on
  (`ui/PerformanceDialog.kt:151-160`).
- `ProotFastPath.enabled()` also requires seccomp on, and `libproot.so` from `nativeLibraryDir`
  rather than the rootfs's Termux copy (`ProotFastPath.kt:33-35`).
- It is turned off when there are more than 256 binds or a path contains `|`
  (`ProotFastPath.kt:38-43`, `SessionService.kt:448-450`).
- When enabled, `libblfastpath.so` goes into `ld.so.preload` (`SessionFiles.kt:208-210`).

**Handshake** (not in proot-performance.md):

1. The app derives a key from SHA-256 over the rootfs path and the bind list.
2. proot gets `PROOT_FASTPATH=<key>`; the guest gets `PROOT_FP_ROOT`, `PROOT_FP_BINDS` and
   `PROOT_FP_KEY` (`ProotFastPath.kt:45-53`).
3. At first use the library reads its `TracerPid` and the **tracer's `/proc/<pid>/environ`**. It
   answers nothing unless that proot carries the same key and no `PROOT_NO_SECCOMP`
   (`fastpath.c:158-192`).

So the library is idle in Flatpak sandboxes, one-off commands and Proton-extra installs, whose
proots have no key.

**Mechanism:**

- The trampoline is an anonymous page at `0xffff00000`, mapped RW then `mprotect`ed RX
  (`fastpath.c:69,215-229`). Patch 0014 lets syscalls from that page through every
  `SECCOMP_RET_TRACE`, with the check placed after the syscall-number dispatch
  (`tools/proot/patches/0014-fastpath-trampoline.patch:65-162`).
- With the fast path on, `chdir` also moves the kernel's cwd, and the first tracee starts at the
  host directory of `-w` (`0014-fastpath-trampoline.patch:17-63,188-205`).
- Resolution walks each component as proot's `canonicalize()` does:
  - follows symlinks with detranslation (`fastpath.c:404-422,620-706`);
  - handles `..`, `/proc/self`, `/proc/thread-self`, the `exe`/`cwd`/`fd` links and glue above
    binds;
  - checks other guest names when a directory is bound twice (`:774-812`).
- Directories are cached for 2 s in 512 slots, for paths up to 256 bytes (`:88,424-468`).
- Anything doubtful returns `FP_SLOW`, which calls the real libc function, which proot then traps.

**Covered now** (`fastpath.c:838-1815`):

- open, openat, creat, the `__open*_2` variants;
- the stat family, `__xstat*`, `statx`, fstat (as plain `SYS_fstat`);
- access, faccessat, eaccess;
- readlink, readlinkat, the `_chk` variants, realpath, `canonicalize_file_name`, getcwd;
- mkdir/mkdirat, unlink/unlinkat/rmdir, rename/renameat/renameat2 (files, `RENAME_NOREPLACE`),
  symlink/symlinkat, chmod/fchmodat (flags 0), utimensat, utimes, utime, futimens, truncate;
- statfs/statvfs (not `/dev/shm`), getxattr/lgetxattr/listxattr/llistxattr, inotify_add_watch;
- connect, bind, accept/accept4, getsockname, getpeername;
- shm_open, shm_unlink, opendir, fopen;
- get_nprocs, sysconf(`_SC_NPROCESSORS_ONLN`), uname, gethostname, isatty, memfd_create;
- getpwnam/getpwuid (and the `_r` variants) when nsswitch sends passwd to files first.

**Left to proot:**

- execve, chdir/fchdir, link/linkat, chown/fchownat, the setxattr family, mknod, rename of
  directories, `O_TMPFILE`;
- `/dev/fd` and `/dev/std*`;
- host socket paths of 108 bytes or more;
- every glibc-internal and raw-`svc` call, and anything raised by `syscall()`.

**Tuning knobs:** `PROOT_FP_OFF`, `PROOT_FP_TTL_MS`, `PROOT_FP_STATS` (one line per process **at
exit**) and `PROOT_FP_LOG=<file>` (every miss) (`fastpath.c:25-34`).

**`audit.c`** (`tools/proot/fastpath/audit.c:1-17,148-195,336-386`) is an LD_AUDIT module with no
libc:

1. It maps the same trampoline itself and performs the same tracer-key check.
2. In `la_objsearch` it returns `NULL` for a candidate library whose directory canonicalises
   exactly as proot would (no symlink inside a bind, no glue) and whose name is missing there.
3. Each lookup's first candidate, and the default directories, are always tried, so `dlerror()`
   and errno stay identical.

Only the Proton launchers set it, and only when `PROOT_FP_KEY` is present
(`steam-compatibility:403-411,492-499`).

**Status of what proot-performance.md listed as open:**

| item (proot-performance.md) | status | evidence |
|---|---|---|
| Validate inside the app: execmem, the address free, the stub's calls allowed (`:298-302`) | **Done in practice**: shipped default-on. No written validation record. A failed mmap silently disables the fast path | `SessionPrefs.kt:394`; `fastpath.c:215-231` |
| Integrate into `libblsession.so` (`:303-306`) | **Not done**: a separate library, loaded last; built separately | `SessionFiles.kt:57-60,208-210`; `build.yml:207-213` |
| Wire the launcher and add a toggle that is off without seccomp (`:307-311`) | **Done**, plus the keyed handshake | `SessionService.kt:437-452,469`; `ProotFastPath.kt`; `PerformanceDialog.kt:156-160` |
| Write family (`:312`) | **Mostly done**: no link, chown, setxattr, directory rename or mknod | `fastpath.c:1120-1274` |
| Unix sockets `connect`/`bind` (`:312-313`) | **Done**, including accept and getsockname/getpeername; ≥108 bytes left to proot | `fastpath.c:1380-1501` |
| Measure Steam start, a game load and `PROOT_FP_STATS` hit rates (`:313-314`) | **Partly**: per-call device numbers and "up to 57%" in the commit message. Its bullet "docs: … measured launch times" changed no doc. No hit-rate table in the repo | `git show fb8361b` |
| `syscall()` hook for FEX (`:315-316`) | **Not done**. The hook point exists (`robust.c:103-142`). In arm64ec Proton, FEX is a PE DLL whose x86 code makes NT calls into Wine's unix side (glibc wrappers), so this matters mainly for standalone FEXInterpreter (`droiddeck-fex`, Decky x86_64, x86 AppImages) **[inferred]** | — |
| `GLIBC_TUNABLES` `rseq=0`/`top_pad` (`:257-258`) | **Done** | `SessionService.kt:637-640` |
| A second proot for the game tree (`:253-255`) | **Not done** | no code |
| `memfd_create` (`:216`) | **Done** in the fast path | `fastpath.c:1654-1665` (eadebc4) |
| Leave the tracer unpinned (`:256`) | **Done**: niced to −6, not pinned | `SessionService.kt:1505-1527` |
| Option 6, a proot-aware glibc (`:244-251`) | **Not started** | — |
| Shipped beyond the doc's list | the LD_AUDIT module; uname/isatty/passwd/nprocs/realpath/getcwd/statfs/xattr/inotify/`__xstat`; `MESA_DISK_CACHE_DATABASE`; locale-archive; `nap`; library-beat gating | `steam-compatibility:403-411`; `SessionService.kt:631-636`; `droiddeck-session:32-51,149-163,640-700` |

## 6. Existing perf tooling and toggles

**`tools/proot/bench`:**

- `sysbench` (per-call latency), `mechbench` (mechanism cost: ptrace, SIGSYS, user-notif, filter
  length), `parstat` (N-thread `stat` throughput) and `fpbench` (the calls the fast path took over).
- `equiv.py`, `equiv2.py` and `equiv3.py`: equivalence suites, diffed line by line with and without
  the fast path. The fb8361b message gives 222, 507 and 924 lines.
- `workloads.sh` (python imports, find, 200×exec, tar).
- `run-device.sh` runs variants `patched`, `vanilla`, `noseccomp`, `bare`, `fastpath`, `fpoff` and
  `direct` from `/data/local/tmp/dd` **as the adb shell user, which is not under the app seccomp
  policy** (`run-device.sh:1-25`).

There is **no host test for `fastpath.c`/`audit.c`**. `tools/tests` covers the other preloads
(binfmt, dircache, fsync, noexec, storage) but nothing that runs proot.

**`tools/droiddeckctl`** drives an installed build over adb (`docs/agent-control.md:100-175`):

- `start`/`stop`/`wait`, with phases `PREPARING…STARTING_STEAM`/`READY`, and `wait focused <appId>`;
- `launch` and `quit` a Steam game;
- `prefs get|set session <key>`, which can flip `prootFastPath` and `prootNoSeccomp`;
- `env set` (guest `KEY=VALUE` for the next session, e.g. `PROOT_FP_STATS=1`, `BL_NO_PATH_CACHE=1`);
- `guest -- <cmd>`, which runs inside the session (and can read `/proc/<proot pid>/stat` through
  the `/proc` bind);
- `logs` (the session folder: `session.log` with `== STEP hh:mm:ss` milestones, and `events.jsonl`
  with lifecycle and `focus.changed`), `screenshot`/`record`, `wake`.

**`tools/droiddeck-scenario`** repeats JSON scenarios with setup, before, steps, after and
teardown, and collects artifacts for failures (`tools/scenarios/*.json`).

**Toggles in the app:**

- Performance → Session fixes: "Run proot without seccomp" (`perf_seccomp`) and "proot fast path".
- Client and game core masks, Zink, glthread, sysmem, gamescope realtime, GPU clock pin, and the
  esync/fsync/ntsync choices (`PerformanceDialog.kt:83-160`).

**Environment knobs:**

- fast path: `PROOT_FP_STATS`, `PROOT_FP_LOG`, `PROOT_FP_TTL_MS`, `PROOT_FP_OFF`;
- preload caches: `BL_NO_PATH_CACHE`, `BL_NO_DIR_CACHE`;
- other preloads: `BL_NO_KGSL_POLL_FIX`;
- Flatpak sandboxes: `DROIDDECK_PROOT_VERBOSE`;
- `droiddeck-env` lines (`SessionService.kt:656-661`).

**What a device A/B harness still needs:**

1. **Traps per second and per syscall inside the app domain.** proot has no stop counter. Patching
   one in, dumped on a signal or at exit, would give the baseline and show what each change
   removes. `run-device.sh` numbers miss the app policy's `SIGSYS` paths.
2. **Live fast-path counters.** `PROOT_FP_STATS` prints only at process exit and only to that
   process's stderr, so steam and steamwebhelper report only at teardown, and nothing aggregates
   the lines. A periodic dump or a shared counter file would fix both.
3. **Tracer CPU accounting.** Sample the proot pid's utime/stime (state's `guestPid`) and the tree's
   total around each phase.
4. **Machine-readable milestones:** time to `READY` and to Big Picture (focus on app 769),
   launch → focus of the game, game-specific "loaded" markers, and frame-time capture (MangoHud
   logging could serve).
5. **A way to run the bench binaries inside a real session** (`droiddeckctl guest`), under the app's
   seccomp policy.
6. **Thermal and Doze control and repetition statistics** (Doze is 7× slower,
   proot-performance.md:89-90), and power readings.
7. **Toggles for each new mechanism** (second tracer, native mode, …) so a scenario can A/B them
   through `prefs set`.

## 7. Coupling and risk map

**Android side (Kotlin/Java):**

- `runtime/LinuxRuntime.java`:
  - `command`, `prootPrefix`, `binds`;
  - `prootBinary`/`prootLoader`/`prootLibraryPath`;
  - `isInstalled()`, which requires the proot binaries (`:101-110`);
  - `guestUtsname`;
  - `writeAccounts()`: the app uid is named root, and X auth and Steam look users up by uid;
  - `lastBinds()`, the guest view used to translate guest paths app-side.
- `session/SessionService.kt`:
  - `runSession`: the `env -i` guest, host `PROOT_*`, `HostProcess.start`;
  - `raiseTracer`;
  - `teardown`/`descendants` (one root pid);
  - `askSteamToExit` (waits on the proot pid);
  - `killStragglers` with `STRAGGLERS` containing `/libproot.so` (`:1830-1831`);
  - `killGuestLeftovers`. It matches `/proc/<pid>/exe` under the rootfs (`:1146-1162`). Under proot
    the kernel exe is the loader (`perms.c:32-34`), so this sweep likely matches only processes
    that escaped the loader **[inferred]**. In a kernel-exec native mode it would start matching
    everything.
  - `importForDesktop` (guest → host through binds, `:1281-1283`);
  - the terminal proot (`:473-483`).
- `session/SessionSuspendController.kt`: the SIGSTOP order assumes a tracer root (`:193-202`).
- `runtime/BwrapSpawner.kt` (`plan`, `run`, `hostPath`, `bindsFor`), `runtime/GuestCommand.kt`,
  `session/ProtonExtras.kt:238-256`, `runtime/AppImageManager.kt` (link2symlink copy-back),
  `runtime/FlatpakManager.kt`.
- `runtime/ProotFastPath.kt`, `session/SessionFiles.kt` (the preload list), `session/SessionPrefs.kt`,
  `ui/PerformanceDialog.kt`, `MainActivity.kt:155-156,1417-1430,1580-1581`, `core/DeviceReport.kt:164-165`.
- `session/SecondaryLibrary.kt`: the override-bind design depends on longest-prefix bind semantics.
- `core/HostProcess.kt` and `session/OrphanReaper` (keep and release proot pids).

**Guest side:**

- `droiddeck-bwrap`: the app spawns the sandbox proot. Its abstract socket name is fixed, so two
  variants installed side by side would collide **[inferred]** (`droiddeck-bwrap:23`,
  `BwrapSpawner.kt:36`).
- `droiddeck-fex`: the `sun_path` workaround (`:413-419`).
- `droiddeck-launcher-cores`: nice and affinity instead of SIGSTOP.
- `steam-compatibility`: LD_AUDIT gated on `PROOT_FP_KEY`.
- `droiddeck-session`: exec-avoidance and the locale archive, which only matter for cost.
- `tracer.c`, `perms.c`, `noexec.c`, `fastpath.c`, `audit.c` (`traced_by_match`).
- Guest paths that exist only through binds: `/run/droiddeck`, `/mnt/droiddeck-sd` (+ legacy),
  `/mnt/droiddeck`, `/root/Storage`, `/root/ROMs`, `/root/Games/*`, `/dev/input`, `/dev/dri`,
  `/dev/shm`, `/sys/class/{power_supply,hwmon,drm,hidraw,powercap}`, the `/proc/*` stand-ins,
  `/usr/local/share/fonts/android`.

**Build, release and tests:**

- `tools/proot/*`, the `build.yml:391-404` proot cache and build, and `tools/build_local.sh:266-280`.
- `tools/release/runtime_inputs.py:43-75`: `libproot.so` and `libproot-loader.so` are required
  outputs.
- `tools/tests/test_runtime_inputs.py:42-60`.
- App unit tests: `app/src/test/.../ProotFastPathTest.kt`, `BwrapSpawnerTest.kt` (bind planning),
  `GameFileSyncTest.kt:19` (fakes `opt/android-host/proot` to satisfy `isInstalled`) and
  `GuestHostnameTest.kt`.
- No test in `tools/tests` exercises proot itself.

**What breaks if the tracer is removed:**

- *For a process that keeps the preloads but loses proot:*
  - Every absolute guest path that is not an identity bind (`files/`, `cache/`,
    `/storage/emulated/0`) resolves on the host and fails: `/usr/lib/…` in ld.so before any preload
    exists, `/bin/bash` shebangs, and `PT_INTERP`.
  - Startup dies on `set_robust_list`/`rseq` `SIGSYS` (P10).
  - `/proc/self/exe` names the real binary (good) but as a host path (needs detranslating).
  - Xwayland's `setgid`, pacman's uid checks, `uname`, `TracerPid` and SIGSTOP semantics all change.
  - Binds over `/proc` and `/sys` files vanish unless redirected in-process.
  - `--kill-on-exit` teardown and the suspend order need a replacement.
- *For a game tree only* (the "second proot" or "native game" idea):
  - Wine re-execs itself and wineserver (P7).
  - `wine-preloader` opens ld.so with raw syscalls (P2).
  - The server socket lives in `/tmp/.wine-<uid>` (P9/P16).
  - fsync/esync need `libblsession` (already in-process).
  - DXVK, vkd3d and Turnip load through ld.so and `dlopen` (P2).

**What an alternative environment would have to touch:**

- `LinuxRuntime.command`/`prootPrefix`/`binds` (or a parallel builder);
- `SessionService.runSession`, `teardown`, `raiseTracer`, `killGuestLeftovers`, `killStragglers`;
- `SessionSuspendController.stop`;
- `BwrapSpawner.run` and `GuestCommand.runLogged`;
- `SessionFiles.stage` (the preload list);
- `ProotFastPath` (gating, and the key handshake in `fastpath.c:traced_by_match`/`audit.c`);
- the guest's `fastpath.c` `fp_init` (it assumes a tracer for `own_exe` and for `/proc/self` binds,
  `fastpath.c:517-538,199-209`);
- `tracer.c`, `perms.c`, `noexec.c` (`/proc/self/exe` assumptions);
- `steam-compatibility`'s launcher templates;
- `runtime_inputs.py` and `isInstalled()`, if proot becomes optional.

## 8. Assessment

1. **[measured]** Steady-state gameplay already runs essentially untraced. GPU `ioctl`s, `futex`,
   `read`/`write`, `mmap`, poll and epoll, `sendmsg` and `clock_gettime` cost 0.2–0.4 µs under
   patches 0004–0013 (proot-performance.md:97-111). `futex_waitv` is answered in userspace
   (`fsync.c`). A proot-free design should not expect a large steady-state FPS gain from the
   syscall path alone.
2. **[measured]** Steam's own start fell from about 31 s to about 6 s (`docs/releases/0.3.0.md:20`).
   The cause was mostly one realpath/access loop, 23 of the 31 s (`pathcache.c:6-9`). Per-call
   realpath is now about 1 µs in-process (fb8361b). No post-fast-path time to Big Picture is
   recorded in the repo.
3. **[inferred]** What remains of startup is exec-dominated: the outer script, ≈10 helper programs
   with several Python interpreters, `seed-redists`' ≈40 programs, CEF's zygote and renderers, and
   then the post-login probe that starts every registered Proton at once. Under proot a
   fork+exec+wait costs 0.70–0.78 ms in `sysbench`, and 200 execs of `true` take ~500 ms against
   230 ms direct (proot-performance.md:109,174). Each exec includes ≥6 trapped ld.so opens, the brk
   emulation, the clone and exit events, and one `set_robust_list` `SIGSYS`. All of it is
   serialised on one tracer thread (68–95k traps/s, proot-performance.md:142).
4. **[inferred]** A game launch is the most exec-heavy phase:
   - Steam runs the tool 3 times;
   - each run starts bash plus about 10 programs, `droiddeck-game-env` and `proton`, which are two
     Python 3 interpreters;
   - Wine's process fan-out follows, each process through the loader. `wine-preloader`'s opens are
     raw syscalls the fast path cannot see.

   Asset loading afterwards mostly hits the fast path, but every `dlopen` miss and every
   glibc-internal open still queues behind a tracer that Steam/CEF may also be using.
5. **Biggest remaining wins, cheapest first [inferred]:**
   - fold `libblfastpath` into `libblsession` (one less library per exec; doc estimate 0.2–0.4 ms);
   - apply `libblaudit` session-wide, not only in Proton launches;
   - route FEXInterpreter's `syscall()` path calls through `robust.c`'s hook;
   - give the game tree its own tracer (measured 2.5× trap throughput, proot-performance.md:143);
   - a patched glibc/ld.so for internal opens and blocked syscalls.
6. **[inferred]** The SIGSYS cost is smaller than the doc implies on glibc 2.43. `rseq` and
   `set_robust_list` happen once per exec, not per thread (section 3, P10). `clone3` is still once
   per thread before Android 15, and `faccessat2` once per uncaught `faccessat()`. This matters for
   ranking option 6: its main value is ld.so/libc internal paths and making a tracer-free process
   *possible*, not removing a per-thread tax.
7. **Hardest responsibilities to replace [inferred]:**
   - execve as a whole (loader/`PT_INTERP`, shebangs, `/proc/self/exe` consistency for CEF
     re-exec, glibc's internal `__execve` in `posix_spawn`, raw-`svc` execs);
   - path translation for raw-syscall and ld.so code;
   - blocked syscalls issued with every signal masked (`clone3`, setxid);
   - socket paths over 108 bytes.

   The SD-library override binds (`SecondaryLibrary.kt`) are the most intricate bind semantics any
   in-process mapper must keep bit-identical.
8. **[inferred]** The repo already has most of the in-process pieces a "tracer-optional" mode needs:
   - a complete-looking libc-level translator with an equivalence suite;
   - userspace emulation of two blocked syscalls;
   - an exec shim pattern (`binfmt.c`/`net.c`);
   - an LD_AUDIT module;
   - the app-side bind table and stand-in writers.

   What is missing is everything that runs before or beneath libc: ld.so, static binaries, the
   loader and `SIGSYS` at startup.
9. **Measured data gap.** There are no device numbers in the repo for traps per second during a
   real session, fast-path hit rates per process, or game-load times before and after the fast
   path. Section 6 lists what the harness needs to produce them before Gate 2.

## Appendix: where the code disagrees with proot-performance.md

| doc says | code says |
|---|---|
| The fast path is a prototype, opt-in (`:36-43,260-263`); PATCHES.md calls 0014 "Prototype (inert unless PROOT_FASTPATH is set)" (`tools/proot/PATCHES.md:92`) | Default **on** (`SessionPrefs.kt:335`), wired with a keyed handshake |
| `..`, a final symlink and `/proc` always go to proot; parents are verified with one `O_PATH` plus readlink (`:268-279`) | Full component walk with symlink and `/proc` emulation (`fastpath.c:10-21,620-706`) |
| The write family and sockets are "next" (`:206,312`) | Done (§5) |
| Integration into `libblsession` is planned (`:303-306`) | Not done: a separate library, loaded last |
| `rseq` and `set_robust_list` are paid "at the start of every thread" (`:188`, also `SessionService.kt:637-639`) | glibc 2.43 attempts both once per process when they fail; only `clone3` (before Android 15) is per thread |
| The session tree has `reaper ─▶ droiddeck-game-env ─▶ droiddeck-proton` (`:51`) | `reaper ─▶ droiddeck-proton ─▶ droiddeck-game-env ─▶ proton` (`steam-compatibility:440`) |
| "About 25 binds" (`:57`) | 80-odd typical, with a 256 cap (`fastpath.c:71-74`) |
| FEX inside Proton translates x86 path syscalls through `syscall()` (`:315-316`) | In arm64ec, FEX is a PE DLL and makes no Linux syscalls of its own **[inferred]**. The `syscall()` route applies to standalone FEXInterpreter |
| — (not mentioned) | `libblaudit.so` (LD_AUDIT), `uname`/`isatty`/`passwd`/`get_nprocs` answers, and `memfd_create` all ship |
| `app/build.gradle:172-174`: PulseAudio "is executed from the native library directory" | It is exec'd from `files/pulseaudio/` (`PulseAudioComponent.java:53,159`) |
