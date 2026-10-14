# 03: The layered design, and the milestones for Gate 2

Phase 2 design for the Gate 1 direction (`PLAN.md`, "Direction"): proot as the safety net, native
wherever it can be, with the app as the container. It turns the four layers into code changes,
toggles and milestones whose acceptance is stated in the Performance report's own numbers.

Inputs:

- `01-current-architecture.md` (the audit), `02-landscape-and-constraints.md` (Android limits);
- `harness.md` and the report it describes (`session/PerfReport.kt`, `session/SessionPerf.kt`);
- `04-spikes.md` and `tools/native/` at `9824475`: **layer 3 GO, layer 4 GO with conditions**.
  Sections 4 and 5 are designed against those results, not left as open frameworks:
  - the patch set and its `BL_NATIVE_BLOCK` gate are the spike's;
  - so are the exec form, the env table and the escape list.

  What remains open is the in-`ld.so`/libc translation patch, which gets its own milestone (M15),
  and the spike's device checks, which are folded into milestone acceptance (§7). Each table also
  says what changes if a device check reverses a result.

Conventions:

- Paths are relative to the repo root. App code under `app/src/main/java/com/droiddeck/launcher/`
  is shortened to `session/…`, `runtime/…`, `core/…`, `wayland/…`; native code under
  `app/src/main/cpp/` to `waylandcomp/…`, `hostsys/…`. Guest scripts under
  `tools/linuxfs/overlay/usr/local/bin/` are named alone (`steam-compatibility:130`). proot
  references such as `proot:src/tracee/event.c:312` are the pinned source
  (`tools/proot/source.env`).
- **[verified in code]** was read at `Environment-work` `030c1a7`. **[measured]** quotes a cited
  run: device numbers from `proot-performance.md`, host numbers from `04`. **[inferred]** is
  reasoning. **Device check** marks something only Phase 4 hardware can settle.
- **†** marks a report metric that does not exist yet; §6.1 specifies it, and milestone M0 adds
  it.

## 0. Summary

- **Layer 1** gives the game tree its own proot, T2, started inside a new `:game` Android process.
  The hand-off reuses the pattern `BwrapSpawner` already proves: a stand-in in the session, a
  socket to the app, a proot started by the app. It is generalised rather than copied. Same
  rootfs, same binds, same fast-path key; the stdio fds are passed, not relayed. Plus ADPF hint
  sessions fed by frames the compositor already sees, `setGameState(loading)`, a uclamp trial,
  and exit-reason detection for the phantom and MemoryLimiter kills.
- **Layer 2** folds the fast path into `libblsession`, which forces one load-order change: the
  fast path must stay the last interposer, so `libblsession` moves to the end of the chain. It also
  widens `libblaudit`'s scope, adds a forked-child capability probe, and adds an `openat2` path for
  the open family on Android 16 QPR1+.
- **Layer 3** ships the spike's six-patch glibc, rebased on Arch's exact `2.43+r22` commit, as an
  overlay in the APK. It is staged into the rootfs per session, hash-pinned, with an instant
  rollback. The missing piece, path translation inside `ld.so` and libc, is its own milestone.
- **Layer 4** runs the Steam client, then CEF, untraced. A per-exec allow-list in the exec shim
  decides traced or untraced. Untraced programs run through `ld.so --argv0`, or through the same
  `ld.so` shipped in the native-library directory. A small supervisor replaces `--kill-on-exit`.
  The Wine tree stays under T2.
- **Two findings from reading the code**, both fixed early (M3) and both relevant to a second
  tracer:
  - proot ignores `SIGTERM` (`proot:src/tracee/event.c:310-314`), so tearing down a live tree
    waits `GRACE_MS` and then `SIGKILL`s proot (`session/SessionService.kt:988-1007`).
  - proot does not set `PTRACE_O_EXITKILL`, so a proot killed by Android leaves its tracees
    running with no tracer.
- **Order:** 18 milestones. Nine of them are worth doing whatever the baseline shows: harness
  additions, the probe, detection, the proot exit fix, the fold, audit scope and the three hint
  mechanisms. The second tracer waits on the baseline. Layer 3 needs the owner's Gate 2 approval;
  the spike's device checks are part of the acceptance of M1 and M13–M16, and layer 4 follows
  layer 3.

## 1. Target picture

The session after all four layers, with every toggle on:

```
 Android: one uid, untrusted_app_27 (targetSdk 28), one app seccomp filter on every process below
 ┌─ main app process  <pkg> ─────────────────────────────────────────────────────────────────────┐
 │ UI · Wayland compositor (waylandcomp) · SessionService (foreground service) · SessionPerf     │
 │ stand-in writers: battery, hwmon, cpu/gpu stat, network · PulseAudio + DirectAudio relay      │
 │ SpawnServer \0<pkg>.spawn  kinds: sandbox (Flatpak) | game | native                           │
 │ PerfHints: ADPF session (TIDs from T1 and T2), setGameState, uclamp · CapProbe (forked child) │
 └──┬─────────────────────┬───────────────────────┬──────────────────────────┬───────────────────┘
    │ HostProcess         │ ProcessBuilder        │ HostSys.spawn (L4)       │ Messenger (Binder)
    ▼                     ▼                       ▼                          ▼
 proot T1 (session)    proot per sandbox       libddinit.so (L4)          :game process
 traced, fast path     traced, no fast path    untraced supervisor        GameHostService
  droiddeck-session ×2  (BwrapSpawner)          └ steam ─ exec shim         │ HostSys.spawn
  gamescope, Xwayland ×2                          └ steamwebhelper, CEF     ▼
  mangoapp, helpers                                 allow-listed → ld.so   proot T2 (game)
  steam + CEF (until L4)                            otherwise → libproot.so  traced, fast path
  reaper ─▶ droiddeck-proton                        (a net proot, T3…)      droiddeck-game-init
     ─▶ droiddeck-game-spawn ──── socket + SCM_RIGHTS(stdio) ───▶ SpawnServer ──▶ :game ──▶ T2
        (waits; relays signals                                                  └ proton ─▶ wine,
         and the exit status)                                                     wineserver, game
 Shared by every tree at the same host paths: the rootfs (with /tmp), files/, cache/
 (/dev/shm = cache/shm), /run/droiddeck (wayland-0), X11 sockets, PULSE_SERVER, the input rings,
 PROOT_FP_STATS_DIR.
 L3: every tree, traced or not, loads the rootfs glibc; patched or stock is chosen at session start.
```

| what | where it lives | why there |
|---|---|---|
| Bind list and fast-path key | Computed once in `SessionService.runSession` (`session/SessionService.kt:439-457`); T1, every T2 and the native trees get the identical list | One view, one key; `traced_by_match` accepts any tracer that carries it (`tools/proot/fastpath/fastpath.c:200-232`) |
| Stand-in files (`/proc`, `/sys`) | Written by the main process (`session/*Component.kt`); bound into every tracer; mapped in-process by the fast path in native mode | Already app-side (`01` §1.5) |
| Stand-in processes | `droiddeck-bwrap` (Flatpak), `droiddeck-game-spawn` (game), `droiddeck-native-run` (L4 roots) | Each keeps the guest's process semantics (parent, reaper, exit status) while the app owns the real process |
| Supervision | Main: T1 (`teardown`), sandboxes, native trees through `libddinit.so`. `:game`: T2 through `GameHostService`, with a cgroup sweep | Every tree has an Android-side owner that outlives it |
| Hints | Main process: frame times from the compositor, TIDs from both trees | ADPF accepts any TID of the app's uid (`02` §0 item 5); device check below API 34 |
| Measurement | Main: `SessionPerf` reads every tracer's `proot*.stats`, every counter file, and both process trees | §6.1 |

## 2. Layer 1: the app as the container

### 2.1 The game tree in `:game`, with its own proot

#### Routing rule

In `bl_run` (`steam-compatibility:130-138`, shared by `LAUNCHER_SH` and `EXTRA_WRAPPER_SH`), the
final `exec "$@"` becomes `exec /usr/local/bin/droiddeck-game-spawn -- "$@"` only when all of these
hold:

- `BL_GAME_PROOT=1`, which `SessionService` sets when the `gameProot` toggle is on and `:game` is
  bound;
- `$verb` is `waitforexitandrun`;
- `STEAM_COMPAT_DATA_PATH` is a real prefix, not `compatdata/0`. The same guard as
  `bl_components` (`steam-compatibility:151-152`) keeps Steam's post-login graphics probe through
  every tool (`steam-compatibility:87-92`) in T1.

Everything before `bl_run` (`bl_components`, `bl_directaudio`, `bl_game_cpus`, `bl_store_launch`)
still runs in T1, unchanged. `getcompatpath` and every other verb stay in T1. With the toggle off,
the script takes today's path byte for byte. The store-launch branch, which runs Proton as a child
for cloud saves (`steam-compatibility:131-136`), wraps `droiddeck-game-spawn` the same way.

#### Hand-off, end to end

1. Steam runs `steam-launch-wrapper` → `reaper SteamLaunch AppId=N -- …/droiddeck-proton
   waitforexitandrun <cmd>` (T1). The reaper is a subreaper. Steam treats the game as running
   while it lives, and `SessionPerf.scan` finds it (`session/SessionPerf.kt:209-229`).
2. `droiddeck-proton` runs its `bl_*` steps, then `exec`s the stand-in. The pid is kept, so the
   stand-in stays the reaper's child for the game's lifetime.
3. The stand-in connects to the abstract socket named by `BL_SPAWN_SOCKET` (`<pkg>.spawn`). It
   sends frame `R` with `{"kind": "game", "argv", "env": <its whole environ>, "cwd", "appId"}`,
   and fds 0, 1 and 2 as `SCM_RIGHTS`.
4. `SpawnServer` (main process) checks `SO_PEERCRED` against the app uid, as
   `runtime/BwrapSpawner.kt:67-71` does, and hands the request to `GameSpawner`. If a precondition
   fails (failure modes below), it answers `F`. The stand-in then `exec`s `"$@"` itself in T1,
   which is today's launch.
5. `GameSpawner` builds the argv:
   - `LinuxRuntime.prootPrefix(root, cwd)` (`runtime/LinuxRuntime.java:170-192`, with
     `--kill-on-exit`);
   - the exact `-b` list T1 got;
   - `/usr/local/bin/droiddeck-game-init -- /usr/bin/env -i <env> <argv>`.

   The host env holds `PROOT_LOADER`, `PROOT_TMP_DIR`, `PROOT_FASTPATH=<key>` (when T1 had one),
   `PROOT_NO_SECCOMP` (when set), `PROOT_EXIT_KILL=1` (M3) and, with the report on,
   `PROOT_STATS=cache/perf/proot-game-<seq>.stats`.
6. Main sends `MSG_SPAWN {argv, env, dir, stdio ParcelFileDescriptors}` over a `Messenger` to
   `GameHostService`, a bound service in `:game`. It calls `HostSys.spawn`: `posix_spawn` with
   `setsid` and `dup2` of the passed fds. It answers `MSG_STARTED {pid}` once
   `droiddeck-game-init` exists as T2's child (`/proc/<T2>/task/<T2>/children`, or a ppid scan),
   within 2 s. Main then:
   - sends `P` to the stand-in;
   - records `game.proot_started {pid, appId, handoffMs}`;
   - raises T2 to nice −6, as `raiseTracer` does for T1 (`session/SessionService.kt:1524-1540`);
   - adds T2 to the suspend roots and to the sampler.
7. While the game runs, the stand-in catches `SIGTERM`, `SIGINT`, `SIGHUP`, `SIGQUIT`, `SIGUSR1`
   and `SIGUSR2` and sends `K {sig}`. Main signals `droiddeck-game-init`, which forwards to its
   child's process group.
8. On exit, `droiddeck-game-init` (a subreaper) has waited for every descendant, including
   orphans. It exits with its main child's status, and T2 exits with it. `:game` sends
   `MSG_EXITED {status}`, main sends `X {exit|signal, n}`, and the stand-in exits the same way: it
   re-raises a signal with `SIG_DFL`. The reaper has no descendants left, so Steam marks the game
   stopped and `SessionPerf` records `exit`.
9. If the stand-in disappears (the agent's `quit` kills by `SteamAppId`, `droiddeck-agent:486-502`,
   or T1 is torn down), the socket closes. Main then stops that T2: `SIGTERM` to the init's group,
   `GRACE_MS`, `SIGQUIT` to T2, a sweep. This is the `bwrap-<id>-watch` thread's job today
   (`runtime/BwrapSpawner.kt:391-398`).

`SIGQUIT`, not `SIGTERM`: proot ignores every terminating signal except `SIGQUIT`, `SIGILL`,
`SIGABRT`, `SIGFPE` and `SIGSEGV`. On `SIGQUIT` it `SIGKILL`s all tracees and leaves through its
event loop (`proot:src/tracee/event.c:119-130,283-314`) **[verified in code]**.

#### Process semantics carried across

| concern | how |
|---|---|
| Reaper and subreaper | The stand-in is the reaper's child from `exec` to `X`. `droiddeck-game-init` reproduces subreaper semantics inside T2: `PR_SET_CHILD_SUBREAPER`, a `waitpid(-1)` loop until `ECHILD`, and `PR_SET_PDEATHSIG(SIGKILL)` against T2 |
| stdio | The fds themselves pass through `LocalSocket.getAncillaryFileDescriptors()` → `ParcelFileDescriptor` → `dup2`. No bytes are relayed, `isatty` and pipe semantics are unchanged, and stdout and stderr stay separate. The `O`-frame relay remains only for Flatpak sandboxes |
| Exit status | Exit code or terminating signal, preserved exactly (step 8) |
| Signals | Forwarded (step 7). Signals sent straight to game pids work across tracers: pids are global, since Android has no pid namespace (`02` §3), and T2 re-injects them |
| Environment | The stand-in's whole `environ`: `SteamAppId`, `SteamGameId`, `SteamOverlayGameId`, `STEAM_COMPAT_*`, `ENABLE_VK_LAYER_VALVE_steam_overlay_*`, `LD_PRELOAD` (interposers plus Steam's overlay), `LD_AUDIT`, `PROOT_FP_*`, `PROOT_FP_STATS_DIR`, `BL_*`, `DISPLAY`, `WAYLAND_DISPLAY`, `XDG_RUNTIME_DIR`, `PULSE_SERVER`, `FAKE_EVDEV_*`. `env -i` keeps proot's host variables out, as in T1 and the sandboxes |
| cwd, affinity, nice | `-w <cwd>`. `taskset -c $BL_GAME_CPUS` is part of `"$@"` (`steam-compatibility:240-248`), so it runs inside T2. T2 itself gets nice −6 |

#### Shared state across two tracers

| resource | guest path | host path | why it works | caveat |
|---|---|---|---|---|
| `/tmp`: X11 sockets, Wine's server dir, CEF singleton | `/tmp` | `<rootfs>/tmp` | Both proots get `-r <rootfs>` | A `sun_path` of 108 bytes or more is rebound by each proot to its own short name (`01` P9), invisible to the other tracer. GameSpawner refuses routing when `<rootfs>/tmp/.wine-<uid>/server-<dev>-<ino>/socket` reaches 108 bytes, which on adopted storage it can **[inferred]** |
| `/dev/shm`: `sysv.c` objects, esync/fsync, Steam's shm | `/dev/shm` | `cache/shm` | Same bind in both | Emptied only at session start (`session/SessionService.kt:437`), never at a game launch |
| SysV semaphores and message queues | `/dev/shm/wnsysv` | `cache/shm/wnsysv` | Files plus futexes on shared mappings (`tools/linuxfs/preload/sysv.c:1-11,404-443`). Dead lock holders are found with `kill(pid, 0)`, valid across tracers | — |
| Wayland, X11, PulseAudio, input rings | `/run/droiddeck`, `/tmp/.X11-unix`, `PULSE_SERVER`, `FAKE_EVDEV_*` | `files/.wayland-rt`, rootfs, `files/…` | Real sockets and shared files at host paths | — |
| wineserver | `/tmp/.wine-<uid>/server-*` | rootfs | The whole Wine tree of the launch lives in T2 | A wineserver left by an earlier verb in T1 is reached through a plain socket, if the path is under 108 bytes |
| steamclient IPC with the client | `/dev/shm`, SysV via `sysv.c`, sockets | as above | Kernel objects at host paths **[inferred]** | **Device check:** overlay, achievements and the client's "running" state for a game launched through T2 |
| Fast-path counters | `PROOT_FP_STATS_DIR` | `cache/perf/fastpath` | One file per process; T2's land beside T1's | — |

#### Fast-path key for the second proot

No new handshake. The key is a hash of the rootfs and the bind list
(`runtime/ProotFastPath.kt:38-43`). T2 gets the same list, so it gets the same key:

- the library's `traced_by_match` reads `TracerPid` (now T2) and finds `PROOT_FASTPATH=<key>` in
  `/proc/<T2>/environ` (`fastpath.c:200-232`);
- `in_tracer` resolves the `/dev/std*` binds through T2's own fds (`fastpath.c:234-245`). Those
  are the stand-in's stdio, so in the game tree `/dev/stdout` names Steam's pipe rather than
  `/dev/null`, which is more correct than today.

The one rule: `SessionService` hands `GameSpawner` the list and key it computed
(`session/SessionService.kt:441-457`), rather than letting it recompute them. A unit test asserts
the two keys are equal.

#### Teardown, suspend, crash isolation

- **Session stop:**
  1. `GameSpawner.stopAll()`: for each live T2, `SIGTERM` to the init's group, `GRACE_MS`,
     `SIGQUIT` to T2, then a sweep of its snapshotted descendants by start time, as
     `teardown`/`descendants` do (`session/SessionService.kt:988-1030`).
  2. The existing order: `askSteamToExit`, `teardown(T1)`, `killGuestLeftovers`
     (`session/SessionService.kt:1593-1607`).
  3. Unbind `:game`.

  Games go first because they are clients of T1's gamescope, Xwayland and PulseAudio.
- **Suspend:** `SessionSuspendController` already stops `helperRoots` descendants first and the
  roots from pass 2 on, and resumes roots first (`session/SessionSuspendController.kt:8-10,62,
  193-215`). T2 pids join `helperRoots` (`session/SessionService.kt:557`) through
  `GameSpawner.livePids()`. No new ordering logic.
- **Crash isolation:**

| failure | today (one proot) | with `:game` |
|---|---|---|
| A game or Wine process crashes | contained | contained |
| The tracer of the game tree dies | it is T1: the session is over, and tracees survive untraced, spinning on `ENOSYS` (`session/SessionService.kt:1560-1566` comment) | T2 dies alone. With `PROOT_EXIT_KILL` (M3) the kernel kills its tracees. `:game` reports the exit and Steam sees the game end; the session continues |
| `:game` killed (LMK, MemoryLimiter, phantom CPU kill) | — | Android kills `:game`'s process group. Main's `linkToDeath` sends `X signal:9` to every stand-in |
| The main process dies | the session is over | the session is over (the compositor is gone). `GameHostService` links to main's `Messenger`, tears T2 down and stops itself |
| T2 dies, `:game` survives | — | `GameHostService` kills every process in its own cgroup other than itself (`/proc/<pid>/cgroup` equal to its own). **Device check:** the per-process v2 path is readable |

#### Phantom processes

The 32-process limit is global across apps (`02` §3). `:game` is an app process, so it is not a
phantom. Each running game adds two phantoms (T2 and `droiddeck-game-init`). The stand-in replaces
`droiddeck-proton`'s bash by `exec`, so it adds nothing. Steam sessions are already refused while
the limit is on (`MainActivity.kt:1697-1701`). As a backstop, GameSpawner refuses routing when the
live count† is 28 or more and `PhantomProcessLimit.read` is not `DISABLED`.

#### Android process state of `:game`, the main risk of this layer

Children inherit their parent's cgroups (cpuset, cpuctl and uclamp, and on Android 17 memory) at
fork, and Android sets those per process from its process state **[inferred]**:

- T1 is forked from the main process while its activity is on top, so it starts in `top-app`.
- T2 is forked from `:game`, which has no activity. If `:game` sits in `foreground` or
  `background`, the game gets fewer cores or a lower uclamp ceiling than today. On an Android 17
  device whose vendor ships the MemoryLimiter config, it would also get the not-visible memory
  budget (`02` §3).

Design:

- `bindService(BIND_AUTO_CREATE | BIND_IMPORTANT)` from the foreground-service process;
- `GameHostService.startForeground` while a game runs (targetSdk 28 needs no service type);
- `:game` bound at `READY`, so its zygote start is off the launch path;
- at each spawn, `GameSpawner` compares `/proc/<:game>/cpuset` and `/proc/<:game>/cgroup` with the
  main process's. It answers `F` when `:game` is in `background` or `restricted`, and records both
  in the report†.

**Device check:** the process state, cpuset and cpuctl group `:game` actually gets, and
`memory.high` under the limiter.

#### Code: generalising `BwrapSpawner`

| file | change |
|---|---|
| `runtime/SpawnServer.kt` (new) | From `BwrapSpawner.start/serve/frame` (`runtime/BwrapSpawner.kt:41-89,421-426`). Socket `"${packageName}.spawn"`, which fixes the variant collision `01` §7 notes (`BwrapSpawner.kt:36`, `droiddeck-bwrap:23`). Request `kind` dispatch, `SCM_RIGHTS` receipt, frames `R P O E X` plus new `K` (signal) and `F` (fall back) |
| `runtime/BwrapSpawner.kt` | Keeps `plan`, `bindsFor`, `hostPath`, `gpu`, `preload` and `browserSandboxes` as the `sandbox` kind. `run` (`:363-419`) moves to `runtime/ProotRun.kt`, shared by sandboxes (output relay) and games (passed fds) |
| `session/GameSpawner.kt` (new, main) | `prepare(binds, key, hostEnv, perf)` at session start; `handle(request, fds)`; `stopAll()`; `livePids()`; the routing preconditions; `game.proot_*` events |
| `session/GameHostService.kt` (new, `:game`) | `Messenger` handler for `SPAWN`, `SIGNAL` and `STOP_ALL`; a `waitpid` thread; the cgroup sweep; `linkToDeath` on main. It reads no preferences: `SharedPreferences` are not coherent across processes, so every decision travels in the message |
| `core/HostSys.kt` + `app/src/main/cpp/hostsys/hostsys.c` (new) | `spawn(argv, envp, dir, fd0, fd1, fd2, setsid)`, `waitPid`, `kill(pid or -pgid)`. M1 adds `probe()`, M8 adds `setUclamp()`. `HostProcess` (`core/HostProcess.kt:29-65`) cannot place arbitrary fds |
| `tools/linuxfs/spawn/droiddeck-game-spawn.c`, `droiddeck-game-init.c` (new) | The stand-in and the init, in C: one exec each, and about 1 MB resident for the game's whole life against a Python interpreter's ~10 MB **[inferred]**. Built in `build.yml` beside `droiddeck-clipboard` (`.github/workflows/build.yml:220`) |
| `droiddeck-bwrap` | Socket from `BL_SPAWN_SOCKET`, falling back to the old name for one release; request gets `"kind": "sandbox"` |
| `steam-compatibility` | The routing rule in `bl_run` |
| `session/SessionService.kt` | `GameSpawner.prepare` after the fast-path block (`:441-457`); guest env `BL_SPAWN_SOCKET`, `BL_GAME_PROOT`; `helperRoots` (`:557`); `GameSpawner.stopAll()` first in the teardown (`:1593-1599`) |
| `session/OrphanReaper.kt` | `reap` kills every uid process but itself (`:30-50`), so it would kill a live `:game` at session start. It now spares app processes (cmdline `<pkg>` or `<pkg>:*`) but still reaps their children |
| `App.kt` | `onCreate` runs in every process (`App.kt:22-35`). In `:game` it must skip `GpuClockPin.clearLeftover` (it would clear a running session's pin), `BwrapSpawner.start` (a race for the socket), `GameFileSync`, the stores and updates. Process name from `Application.getProcessName()` (API 28+), or `/proc/self/cmdline` on 26–27 |
| `AndroidManifest.xml` | `<service android:name=".session.GameHostService" android:process=":game" android:exported="false"/>` |
| `session/SessionPerf.kt` | Several tracers (§6.1) |

#### Failure modes and fallback to one proot

| # | failure | detected by | behaviour |
|---|---|---|---|
| F1 | toggle off | `SessionPrefs` | `BL_GAME_PROOT` unset: today's launch |
| F2 | the app does not answer | stand-in `connect` fails | The stand-in `exec`s `"$@"` in T1 |
| F3 | `:game` not bound, or the spawn fails | `GameSpawner` | `F`, so the launch runs in T1 |
| F4 | a precondition fails: phantoms ≥ 28 with the limit not off; wine socket path ≥ 108 bytes; `:game` cpuset `background` or `restricted` | `GameSpawner` | `F`, with the reason in `session.log` and a `game.proot_fallback {reason}` event |
| F5 | T2 exits before `droiddeck-game-init` appears (a proot error, such as a refused bind) | no `MSG_STARTED` | `F`. Nothing has run yet |
| F6 | T2 dies mid-game | its exit status | `X`. Two such deaths within 60 s of a start turn routing off for the rest of the session (`game.proot_disabled`) |
| F7 | `:game` dies | `linkToDeath` | `X signal:9` to each stand-in |
| F8 | the stand-in dies | socket EOF | Graceful stop of its T2 |

`F` is legal only before `P`. After the first guest process exists, the launch never moves to T1.

### 2.2 ADPF hint sessions, `setGameState`, and a uclamp trial

All three live in `session/PerfHints.kt` (new), a `SessionPart` in the main process, each behind
its own toggle.

**Threads:**

- **gamescope:** the pid comes from `WaylandCompositor.onGameProgram`
  (`wayland/WaylandCompositor.java:98-103`). It reports the Wayland client's credentials
  (`waylandcomp/src/compositor.c:864-866`), and in a Steam session that client is gamescope.
  Every gamescope thread is taken; it has few.
- **The game:** the pid of the focused window, from the agent's focus snapshot:
  `focusableWindows[].pid` for the `focusedApp` window (`droiddeck-agent:203,243-245`,
  `_NET_WM_PID`). Under Wine that is the game's Windows process, a host pid.
- **Render threads:** the game's `/proc/<pid>/task/*` ranked by `utime+stime` over a 2 s window.
  The top 4 are taken, re-ranked every 10 s. Names like `dxvk-submit`, `dxvk-cs` and `vkd3d*` add
  rank. **Device check:** Wine forwards thread names to the kernel.

**Frame times:**

- **v1, all modes:** the compositor already makes one JNI upcall per presented frame of the HUD's
  window (`waylandcomp/src/compositor.c:872` → `wayland/WaylandCompositor.java:83-96`).
  In fullscreen that window carries the game's own buffers, which gamescope forwards
  (`session/PerfHud.kt:49-52`). Its listener is a single slot that `PerfHud` holds
  (`wayland/WaylandCompositor.java:57`, `session/PerfHud.kt:81`), so M0 adds a list of frame
  taps with `System.nanoTime()` stamps.
- **v2, when mangoapp is on:** gamescope writes each frame's timings into the `sysv.c` message
  queue in `cache/shm/wnsysv` (`tools/linuxfs/preload/sysv.c:404-415`). The app can read the
  newest slot without dequeuing it, for `app_frametime_ns` and the focused pid **[inferred from
  gamescope's `mangoapp_msg_v1`]**. Device check.
- The target is `1e9 / BL_FPS` when a limit is set, else `1e9 / BL_REFRESH`. The app already
  computes both for the guest env (`session/SessionService.kt:378-379`).

**ADPF by API level** (a hint session is created only in the `in_game` phase and closed on exit):

| API | what is used |
|---|---|
| < 31 | nothing. The report says `adpf: unsupported`† |
| 31–33 | `PerformanceHintManager.createHintSession(tids, target)`, `reportActualWorkDuration(long)`, `updateTargetWorkDuration`. A TID change recreates the session. `getPreferredUpdateRateNanos() < 0` means unsupported |
| 34 | `setThreads` |
| 35 | `reportActualWorkDuration(WorkDuration)` with the total only (CPU and GPU split unknown); `setPreferPowerEfficiency(true)` outside games |
| 36+ | `SystemHealthManager.getCpuHeadroom`/`getGpuHeadroom`, logged into the report |

The TIDs live in other processes of the same uid. `02` read this as allowed in the Android 17
source. **Device check** on 31–33. If `createHintSession` refuses them, the session covers only
the compositor's thread and the report says so.

**`setGameState`** (API 33+): `GameManager.setGameState(GameState(isLoading, mode))`, driven by the
events `SessionPerf` already consumes (`session/SessionPerf.kt:143-166`):

- `loading=true` from session creation to Big Picture focus;
- `loading=true` from a launch to that game's focus;
- mode `GAMEPLAY_INTERRUPTIBLE` in a game, `CONTENT` in Big Picture.

The app already declares `appCategory="game"` and `isGame` (`AndroidManifest.xml:50-51`).

**uclamp trial:** `HostSys.setUclamp(tid, min, max)` →
`sched_setattr(SCHED_FLAG_KEEP_ALL | SCHED_FLAG_UTIL_CLAMP_MIN)`, with `uclamp_min = 512` on the
same TIDs. It is reset on game exit and skipped when the probe (§3.3) reports uclamp refused. The
off switch is the `uclampTrial` toggle, default off. It is A/B'd apart from ADPF, and combined only
after each has been measured. Bounded by the cgroup's `cpu.uclamp.max` and a vendor
`sched_util_clamp_min` (`02` §7). Device check.

### 2.3 Detecting the phantom-process limit and Android 17's MemoryLimiter

**Already there [verified in code]:**

- `PhantomProcessLimit.read`/`status` (`core/PhantomProcessLimit.kt:57-85`);
- the Steam gate (`MainActivity.kt:1697-1701`), its page (`ui/PhantomProcessGatePage.kt`) and the
  Setup row (`ui/FrontEndSetup.kt:166-276`);
- the Performance warning (`ui/PerformanceDialog.kt:195-203`, fed by `phantomWarning`,
  `MainActivity.kt:1708-1716`);
- the device-report line (`core/DeviceReport.kt:181`);
- adb commands for both setting styles.

Nothing reads exit reasons or the MemoryLimiter.

**Added:**

1. **A live phantom count:** uid processes that are not app processes, sampled each second in
   `SessionPerf.scan`. It gives `peakPhantoms`† in the report, and a `session.log` line when the
   count reaches 28 while the limit is not `DISABLED`. Desktop and run modes are not gated today.
2. **`core/ExitReasons.kt`:** `ActivityManager.getHistoricalProcessExitReasons(pkg, 0, 10)` (API
   30+), read at app start. Each new record for `<pkg>` or `<pkg>:game` is classified:

   | classification | source |
   |---|---|
   | low memory | `REASON_LOW_MEMORY` |
   | phantom CPU kill | `REASON_EXCESSIVE_RESOURCE_USAGE` |
   | MemoryLimiter | `REASON_OTHER` with a description containing `MemoryLimiter` (`02` §3) |
   | killed without a reason | `REASON_SIGNALED` with signal 9 |

   Each becomes an `app.exit_reason` event in the next session folder, a one-time explanation in
   Setup → Diagnostics, and a `DeviceReport` line. Phantom trimming of *children* produces no
   exit record, so it shows as a tracee death by `SIGKILL` that the app did not send. proot sees
   it; M0 adds a `killed9`† counter to the stop file.
3. **MemoryLimiter readout** (API 37): `/proc/self/cgroup` → `memory.high`, `memory.swap.max` and
   `memory.current` for main and `:game`, in `device.memoryLimiter`†. The explanation carries
   `adb shell am memory-limiter ignore <uid>`, and `droiddeckctl memory-limiter ignore` runs it.
   **Device check:** whether an app can read its own v2 cgroup files.

## 3. Layer 2: in-process first

### 3.1 Folding `libblfastpath` into `libblsession`

**Today's chain [verified in code]:**

- `/etc/ld.so.preload` is `libblsession`, then `libfakeinput`, then `libblfastpath`
  (`session/SessionFiles.kt:204-210`).
- A game launch prepends `libblsession:libfakeinput:<overlay>` to `LD_PRELOAD`
  (`steam-compatibility:395-402`).
- Since glibc reads `LD_PRELOAD` first and skips duplicates, the effective order is
  `libblsession → libfakeinput → overlay → libblfastpath → libc`. Every hook's `RTLD_NEXT`
  reaches the fast path last.

**The constraint:** the fast path must stay the last interposer. Any interposer after it reaches
libc directly, which means a trap. Steam's overlay hooks `open` (`steam-compatibility:384-394`),
so a fast path inside a `libblsession` that stays first would be bypassed by every `open` in a game
process.

**The fold therefore moves `libblsession` to the end:**

- `ld.so.preload` = `libfakeinput`, `libblsession`;
- game `LD_PRELOAD` = `libfakeinput:<overlay>:libblsession`. The interposer stays ahead of the
  overlay, which is the ordering `steam-compatibility:384-394` exists for;
- inside `libblsession`, the policy shims (`opens.c`, `dircache.c`, `pathcache.c`, `space.c`,
  `net.c` and others) call the fast path's internals (`fp_openat`, `fp_stat`, …) instead of
  `dlsym(RTLD_NEXT)`. Its exported wrappers stay the definitions for calls no shim covers.

**What changes order, to audit hook by hook** (each was `libblsession`-first and is now
`libfakeinput`-first):

- `open`, `openat`, `stat`, `statx`, `access`;
- `opendir`, `scandir`, `realpath`, `inotify_add_watch`;
- `ioctl` (`netif.c` dispatches KGSL, ntsync and drm);
- `close`, `read`, `write`, `poll`, `select`.

The only hot one is `ioctl`: every GPU ioctl now passes `libfakeinput`'s fd check first **[inferred:
one table lookup]**. The `in_game` fps is in the acceptance.

**Unchanged:**

- The handshake. `traced_by_match` is the same code. `audit.c` still maps the trampoline first,
  and the library finds it already mapped (`fastpath.c:252-256`).
- The x86 FEX preloads.
- Sandboxes. `BwrapSpawner.preload` re-exports `ld.so.preload` in its own order
  (`runtime/BwrapSpawner.kt:267-281`), and the fast path idles there without a key.

**Trial build:** `build.yml` also builds `libblsession-fp.so` from `tools/linuxfs/preload/*.c` plus
`tools/proot/fastpath/fastpath.c` (`.github/workflows/build.yml:205-212`). `SessionFiles.stage`
stages one layout or the other per `fastPathFolded`, and the launcher templates read
`BL_FP_FOLDED=1` for the `LD_PRELOAD` order. The classic build goes once the folded one wins.

**Gain:** one fewer library open, map and relocation per exec. proot-performance.md measured the
second library at +0.2–0.4 ms per fork+exec **[measured, `01` P7]**.

### 3.2 `libblaudit` beyond Proton

**Today:** it is gated in the launcher templates on `PROOT_FP_KEY`, for Proton launches only
(`steam-compatibility:406-411,494-499`).

**Proposal:** one toggle, `auditScope` (string): `off`, `proton` (today, the default), `client`
or `session`.

- `client` adds `LD_AUDIT` to the Steam client's environment in `droiddeck-session`'s client loop
  (`droiddeck-session:1002-1057`). That covers the client, CEF and everything Steam starts, all of
  which carry the client's long `LD_LIBRARY_PATH`.
- `session` adds it to the guest env beside the fast-path variables
  (`session/SessionService.kt:441-457`).

**What could break:**

| risk | why | handling |
|---|---|---|
| x86 FEX guests inherit an aarch64 audit module | x86 `ld.so` prints "wrong ELF class; ignored" per process | `droiddeck-fex` strips `/usr/local/lib/libblaudit.so` where it builds the guest's `LD_AUDIT` (`droiddeck-fex:513`) |
| Link namespaces | An audit module takes one of glibc's 16 | Proton launches already run with it **[verified in code]**. **Device check** for the Steam overlay and CEF |
| A net loss for simple programs | Loading the module is itself one trapped open per exec, and `bash` and `python` miss few libraries through `ld.so.cache` | This is why `client` is the expected winner, not `session` **[inferred]** |
| Does Steam pass `LD_AUDIT` on? | The client rebuilds `LD_PRELOAD` for its children (`session/SessionFiles.kt:201-203`) | **Device check:** read `steamwebhelper`'s environ |

### 3.3 The capability probe

`core/CapProbe.kt` plus `HostSys.probe()`. In the main process, a JNI `fork()` per test. The child
makes one raw `syscall()` and calls `_exit(errno)`, both async-signal-safe, so forking from ART is
safe. The parent's `waitpid` reads `WTERMSIG == SIGSYS` as **blocked**.

| test | call (harmless arguments) | allowed looks like |
|---|---|---|
| `openat2` | `openat2(AT_FDCWD, "/", {O_PATH, RESOLVE_IN_ROOT}, 24)` | fd ≥ 0. Then a second test, in a `cache/capprobe/` tree, checks that an absolute symlink resolves inside the root |
| `faccessat2` | `faccessat2(AT_FDCWD, "/", F_OK, AT_EACCESS)` | 0 |
| `fchmodat2` | on a probe file, `AT_SYMLINK_NOFOLLOW` | 0 or `EOPNOTSUPP` |
| `clone3` | `clone3(NULL, 0)` | `EINVAL`/`EFAULT` |
| `close_range` | `close_range(~0U, ~0U, 0)` | 0 or `EINVAL` |
| `landlock_create_ruleset` | `(NULL, 0, LANDLOCK_CREATE_RULESET_VERSION)` | ABI version ≥ 1 |
| uclamp | `sched_setattr(0, {UTIL_CLAMP_MIN=512, KEEP_ALL})`, read back with `sched_getattr` | 0 and the value reads back |
| fast-path stub | `mmap(0xffff00000, MAP_FIXED_NOREPLACE)`, then RX, then `getpid` through it | the pid. Closes `01` §5's "validate inside the app" item |
| `rseq`, `set_robust_list` | `(NULL, 0, 0, 0)`, `(NULL, 0)` | expected blocked: a probe sanity check |
| page size, kernel release | `sysconf`, `uname` | — |

- **When it runs:** once per boot and app version, cached in `files/caps.json`, keyed on
  `/proc/sys/kernel/random/boot_id`, `Build.FINGERPRINT` and `versionCode`. A session start reads
  the cache.
- **Cost:** about 10 forks of a large process, ≈10–30 ms **[inferred]**.
- **Outputs:**
  - the report gets `device.caps`†;
  - the guest env gets `BL_NATIVE_BLOCK=<hex>`, the spike's gate mask (`04` S1:
    `0x1 clone3, 0x2 faccessat2, 0x4 fchmodat2`). It is extended with `0x8 close_range` (patch
    `0007`, §4.1), which is blocked on Android 12;
  - the guest env gets `BL_CAPS=openat2,landlock,…`, the allowed extras.
- **Consumers:** the fast path (§3.4), the patched glibc (L3), `robust.c` (it skips its first
  `openat2` attempt, `tools/linuxfs/preload/robust.c:127-137`), and the uclamp trial.

### 3.4 `openat2(RESOLVE_IN_ROOT)` in the fast path (Android 16 QPR1+)

**Reachability:** under proot, a tracee's `openat2` gets `ENOSYS` from patch 0008. A call from the
trampoline passes every `SECCOMP_RET_TRACE` (0014). On QPR1+ Android's filter allows it, so the
fast path can make the call **[verified in code: `tools/proot/PATCHES.md`, 0008 and 0014]**.

**Which calls:** the open family only (`open`, `openat`, `creat`, `fopen`, `opendir`). There
`openat2` replaces a walk plus the open, which is at least two syscalls, with one.

- For `stat` and `access` the cached walk is already a single `newfstatat`.
- `openat2(O_PATH)` + `fstat` + `close` would be slower.
- `fpbench` (`tools/proot/bench`) settles the edge.

**Composition, bind first:**

1. **Eligibility.** The guest path must be absolute, contain no `..`, not be `reserved()`, and not
   lie strictly above a bind (`binding_below()`, `fastpath.c:344,524-531`, the glue case).
   Anything else goes to today's walk.
2. **Pick the base.** The longest-prefix bind (`bind_of`, `fastpath.c:351`) gives the bind's host
   directory and the remainder. With no bind, the base is the rootfs. A file bind (empty
   remainder) opens the bind's host path directly.
3. **Open.** `openat2(basefd, rest, {flags, mode, resolve})`, where `resolve` is:
   - `RESOLVE_IN_ROOT | RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS` for the rootfs;
   - `RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS` for a bind.

   With no symlink followed and the bind chosen lexically, a success, `ENOENT` or `ENOTDIR` is
   exactly proot's answer. `ELOOP` (a symlink on the way) or `EXDEV` moves on to step 4.
   This keeps the SD library's nested override binds (`session/SecondaryLibrary.kt:22-55`)
   correct: no symlink is followed blindly inside them.
4. **Symlinked rootfs paths** (`/lib` → `usr/lib` on Arch) get a second attempt with
   `RESOLVE_IN_ROOT | RESOLVE_NO_MAGICLINKS`. The kernel then resolves absolute symlinks against
   the rootfs, as proot does. The result is accepted only after a `readlink` of
   `/proc/self/fd/<n>`: it must lie under the rootfs host path and outside every bind's mount
   point inside it. Any other answer, and any error on this attempt, goes to today's walk.
5. **Base fds.** The rootfs and each used bind get a lazily opened `O_PATH|O_DIRECTORY|O_CLOEXEC`
   fd, duplicated above 512. Each fd is checked by `(st_dev, st_ino)` whenever the close-family
   generation has moved. After the fold (M5), `ntsync.c`'s `close`, `dup2`, `dup3`, `close_range`
   and `closefrom` hooks live in the same library and bump that generation.

**The TTL cache:** `openat2` answers bypass it, so the open family gets kernel-exact results with
no 2 s staleness. The walk and its cache stay for `stat`, `access` and fallbacks, with
`PROOT_FP_TTL_MS` unchanged.

**Gate:** `PROOT_FP_OPENAT2=1`, which the app sets only when `fastPathOpenat2` is on and the probe
found `openat2` allowed.

**Counting:** hits count under their call families as today. M0 adds an `openat2`† sub-count, to
read the share.

### 3.5 Open items from `01` §5 closed here

| item | where it closes |
|---|---|
| Validate execmem, the free address, the stub's calls | the probe's stub test (M1) |
| Integrate into `libblsession` | M5 |
| Measure Steam start, game load, hit rates | the harness (done), then the Gate 2 baseline |
| `syscall()` hook for FEX | M12. `robust.c`'s hook (`robust.c:103-142`) routes path syscalls into the fast path, for standalone FEXInterpreter, Decky x86_64 and x86 AppImages |
| P12 `set*id` in-process | M12. Wrappers that succeed when the ids are unchanged and map to `setresuid`/`setresgid`, as the spike's glibc 0005 does. A prerequisite for native mode, where `fake_id0` is gone |
| P15 `statfs` on `/dev/shm` | M12 (trivial) |
| Write family leftovers (`link`, `chown`, xattr set, directory rename, `mknod`) | only if the baseline puts them in the top trapped syscalls |
| A second proot for the game tree | Layer 1 (M10) |
| Option 6, a proot-aware glibc | Layer 3 |

## 4. Layer 3: the proot-aware glibc

### 4.1 What the spike settled, and what this layer adds

**The spike's result (`04` S0–S4):**

- Stock glibc 2.43 dies untraced at `set_robust_list`, then `rseq`, both in `ld.so`'s TLS setup
  (`04` S0).
- With six patches (`tools/native/glibc-patches/0001-0006`), a dynamic userland trips the filter
  **0 times** under all three profiles. The userland: bash, coreutils, Python with threads and
  imports, `posix_spawn`, `system`, `popen`, pthreads, `faccessat` with flags, and the `set*id`
  family (`04` S1).
- Host costs, relative only, untraced against proot:
  - `stat` ≈60× and `tar` ≈34× cheaper;
  - fork+exec+wait ≈1.6× and Python imports ≈1.5× cheaper;
  - in-process translation adds 0.3–0.7 µs per path call (`04` S3–S4).

  **[measured, x86_64 host]**

The patch set, adopted as is:

| patch | what it does | design here |
|---|---|---|
| `0001` | `BL_NATIVE_BLOCK` gate: a hex mask, unset = the Android 14 worst case; `setfsuid`/`setfsgid` sources | The app fills the mask from the probe (§3.3) |
| `0002` | `rseq` and `set_robust_list` never issued (ld.so TLS init, `pthread_create`, `_Fork`) | as is |
| `0003` | `clone3` → `clone` when gated; covers `__clone3_internal` and `posix_spawn` | as is |
| `0004` | `faccessat2`/`fchmodat2` take the portable fallback when gated | as is |
| `0005` | `set*id` → `setresuid`/`setresgid`; `setgroups` succeeds; `setfs*id` return the current id | as is. It replaces `fake_id0` for untraced processes |
| `0006` | SysV `shmget`/`semget`/`msgget` → `ENOSYS` | as is. `sysv.c` still interposes first (§4.3) |
| `0007` (new) | `close_range` gated on bit `0x8`, falling back to glibc's `close` loop | Android 12 blocks `close_range` (`02` §3); the spike's profiles start at 14 |
| translation patches (new, M15) | `ld.so` and libc internal path entry points translate in-process | the open piece (below) |
| exec hook (new, M16) | `__execve`/`__spawni` call a hook `libblsession` registers | `04` condition 2 (§5) |

**What this layer still has to settle:**

| item | state after `04` | design | verified in | if the check fails |
|---|---|---|---|---|
| Path translation inside `ld.so` and libc | **open:** the mechanism, the env table and the ≈80 ns core are settled; the entry-point patch is not written (S3 stubbed it with `--library-path`) | M15. The `pathxlat_core.h` core goes into `ld.so`'s and libc's open, stat and readlink chokepoints (library search, `ld.so.cache`, preloads, nss, `realpath`, fts, `posix_spawn`). Translation sits at the syscall boundary only, so `link_map` names and `dladdr` stay guest paths. Under proot an unprovable path still traps | M15 acceptance (`04` device check 6) | M15 runs only under proot, with prefix translation; native mode limited to programs whose paths avoid the divergences (`pathxlat_core.h` header) |
| Arch's exact build | the spike used the upstream 2.43 tarball | M13 rebases onto Arch's commit and flags (§4.2) | M13 acceptance (`04` device check 2: `__ASSUME_*` under Arch's `--enable-kernel`) | The patch sites move; patches 0002 and 0004 are revised before M14 |
| Syscall numbers and the per-version policy | x86_64 tested; aarch64 column emitted by `seccomp-launch --dump aarch64` | The probe uses the aarch64 numbers and fills `BL_NATIVE_BLOCK` | M1 and M14 acceptance (`04` device checks 1, 3) | Unset mask (the safe default) until the probe agrees with the policy |
| One translation table | the spike used `BL_XLAT_ROOT`/`BL_XLAT_BINDS` | **Use `PROOT_FP_ROOT`/`PROOT_FP_BINDS`/`PROOT_FP_KEY`**, already in the guest env (`runtime/ProotFastPath.kt:49-53`): one view, one key, one authorisation, and no second copy of 80 binds (≈8 KB) per environment | M15 | — |

### 4.2 Where it comes from, and how CI builds it

- **Source:**
  - glibc git at the commit in Arch's version string, `2.43+r22+g8362e8ce10b2-2`
    (`tools/desktop-kde/runtime-packages.txt:35`): `8362e8ce10b2` on `release/2.43/master`;
  - Arch Linux ARM's PKGBUILD patches and configure flags at that pkgrel;
  - `tools/glibc/patches/`: the spike's 0001–0006 rebased, plus 0007, M15's translation patches
    and M16's exec hook.

  `tools/glibc/source.env` pins the commit, the pkgver, and the sha256 of every ELF file in the
  stock package.
- **Build:** `tools/glibc/build.sh`, inside an Arch Linux ARM aarch64 rootfs on a GitHub arm64
  runner, so the toolchain and flags are Arch's. It is cached by the hash of `source.env` plus the
  patches, the way the proot build is (`.github/workflows/build.yml:391-404`). An `abidiff` against
  the stock package, built with the patches disabled, must show no ABI change.
- **Output:** every ELF file the `glibc` package owns, not just `libc.so.6` and `ld.so`. `libm`,
  `libresolv`, `libnss_*`, the `gconv` modules and others share `GLIBC_PRIVATE` interfaces with
  `libc.so.6` and `ld.so`, so the set must come from one build. That is roughly 10 MB in the APK as
  `assets/linuxfs/glibc/<pkgver>/`, plus the same `ld.so` as `jniLibs/arm64-v8a/libddld.so` for
  layer 4.

**Overlay in the APK, or a rebuilt rootfs:**

| | APK overlay, staged per session (**recommended**) | rebuilt `linuxfs-rN` in the catalog |
|---|---|---|
| A/B inside one install | yes: the toggle chooses per session | no: every user of that rootfs gets it |
| Rollback | instant: restore the stock files kept beside it | another ~790 MB download |
| Coupling | the APK carries a build per supported rootfs glibc | the rootfs carries one glibc; the app must check it |
| Owner and catalog change | none (a Gate 2 check, per `PLAN.md`) | a new `linuxfs` release on `DroidDeck-Components` |
| Later | becomes the rootfs's own glibc once default-on everywhere | — |

### 4.3 Pinning, pacman, and the four package names

- **Staging (`session/NativeGlibc.kt`, new, called from `SessionFiles.stage`):**
  1. Read the rootfs's glibc version from `/var/lib/pacman/local/glibc-*/desc`. The locale
     archive is already keyed on that entry (`droiddeck-session:32-51`).
  2. Hash each file and compare with `source.env`.
  3. Stock and matching: rename each patched file into place, keeping the stock file as
     `<name>.droiddeck-stock`, and write `/etc/droiddeck/glibc-state` = `patched <pkgver>`.
     Renaming keeps processes that still map the old file safe, the same rule `SessionFiles`
     follows (`session/SessionFiles.kt:37-44`).
  4. Toggle off: restore the stock files and write `stock <pkgver>`.
  5. Neither stock nor ours matches: install nothing, write `mismatch <found>`, and the report
     shows `glibc: mismatch`†.
- **pacman:** the app never runs `pacman -S` on the real database. `droiddeck-flatpak-setup` uses
  `-Sw -dd` and `bsdtar` (`droiddeck-flatpak-setup:66-87`). So glibc changes only when the user
  runs `pacman -Syu` in a terminal, or a new `linuxfs` release ships. Defences:
  1. The session-start check above.
  2. A pacman hook, `/usr/share/libalpm/hooks/zz-droiddeck-glibc.hook` (Upgrade/Install of
     `glibc`), which runs `droiddeck-glibc-state` and rewrites the state file mid-session.
  3. Native mode (§5) and the exec shim read the state file once per process and refuse to exec
     natively unless it says `patched`.

  **No `IgnorePkg` by default.** Holding glibc back while `-Syu` upgrades its dependants is a
  partial upgrade, which Arch does not support. That breaks more than losing the patch does. An
  owner decision (§8).
- **Four package names:** the build has no compiled-in prefix. Translation reads the table from the
  environment at run time (`04` S3), so one build serves `com.droiddeck.launcher`, the three
  variants (`tools/release/variants.txt`), secondary users and adopted storage. That is the
  difference from Winlator's and Termux's relocated prefixes (`02` §2.1, §10 item 5).
- **`0006`** (SysV `*get` returns `ENOSYS`) does not shadow `sysv.c`: the preload's `semget` and
  `shmget` interpose before libc's **[inferred]**. M14 tests Steam's semaphore and mangoapp's
  queue.

### 4.4 Toggle and rollback

- **Toggle:** `patchedGlibc`, default off.
  - Off at session start: the stock files are back before T1 starts.
  - On: staging as above.
  - Outside a session (terminals, one-off `GuestCommand`s) the state is whatever the last session
    staged. With no key and no tracer that matches, the patched build behaves like stock under
    proot **[inferred from `04` S1]**.
- **Automatic rollback:** two consecutive sessions with `patchedGlibc` on that fail before
  `READY` restore stock and turn the toggle off (`glibc.reverted` event).
- **Last resort:** reinstall the runtime (`runtime/LinuxRuntimeInstaller.java`).

## 5. Layer 4: native mode

### 5.1 What the spike settled, and its conditions

**Settled untraced on the host (`04` S2) [measured]:**

- exec through `ld.so --argv0`, shebangs resolved in the rootfs, the whole exec family, and
  `posix_spawn` (CPython's `subprocess` needs that hook);
- `/proc/self/exe` emulated for `readlink`, `open` and re-`execve`;
- `comm` fixed with `prctl(PR_SET_NAME)`;
- `AT_EXECFN` already correct through `ld.so`'s own auxv fix-up;
- a copy of `ld.so` outside the rootfs acting as the loader.

The spike's names are kept: `BL_LOADER`, `BL_EXEC_REAL`.

**The three conditions, and how this design meets them:**

| `04` condition | design |
|---|---|
| 1. Steam and CEF go native first; the Wine and game tree stays on proot (static `wine-preloader`, raw `svc` in FEX's passthrough) | M16 (client), then M17 (CEF). The allow-list (§5.2) never names `reaper`, `droiddeck-proton`, Wine or FEX, so games still reach T2 through layer 1 |
| 2. The exec shim and translator go into the patched glibc and `libblsession`, not a separate preload, so raw `execve` and `ld.so`'s own opens are covered | The dispatcher is `tools/linuxfs/preload/execshim.c`, inside `libblsession`, absorbing `binfmt.c`'s and `net.c`'s exec wrappers. glibc's `__execve` and `__spawni` call a hook it registers (the M16 exec-hook patch). The translator is M15's, in `ld.so` and libc, and the fast path is in `libblsession` (M5) |
| 3. Device validation against the harness baseline before any toggle defaults on | `nativeClient` and `nativeCef` are off by default. M16 and M17 acceptance is stated in report metrics; M16's includes `04` device check 4, and M14 records check 5 |

**Left to verify:**

| item | verified in | if it fails |
|---|---|---|
| `ld.so` from the native-library directory executes on the device (`04` device check 4) | M16 acceptance | Only the rootfs-`ld.so` form remains, valid at targetSdk 28 only (§5.3) |
| Whether Wine 10/11's arm64 `wine-preloader` issues `rseq`, `set_robust_list` or `clone3` (`04` device check 5) | recorded in M14 | If it does not, a later milestone could take the Wine tree native. Not in this list |
| Valve binaries making raw `svc` calls of their own | before each allow-list entry: `trap-trace --continue` under `seccomp-launch`, and on device the supervisor's `SIGSYS` death count† | Allow only the clean subset; Steam stays traced if the client itself escapes |

### 5.2 What runs untraced, and the per-exec decision

**Roots:** `droiddeck-session`'s client loop (`droiddeck-session:1002-1057`) runs
`droiddeck-native-run -- steam …` instead of `steam`. This stand-in uses the same `SpawnServer`
(kind `native`). The app starts `libddinit.so`, which starts the client untraced as a child of the
main process, with the guest env and the passed stdio. The loop's `rc 42` restart is unchanged.

**Per-exec decision in the exec shim** (`tools/linuxfs/preload/execshim.c`, new, absorbing
`binfmt.c`'s and `net.c`'s exec wrappers into one dispatcher):

1. Resolve the target in the guest view: `PATH` search, then the interpreter of a shebang.
   `/proc/self/exe` resolves to this process's guest program.
2. Classify it.
   - **Candidate:** an aarch64 dynamic ELF whose `PT_INTERP` is `/lib/ld-linux-aarch64.so.1` or
     `/usr/lib/ld-linux-aarch64.so.1`.
   - **Never native:** static, x86, another interpreter, or a script whose interpreter is not a
     candidate.
3. **Native** if the target matches the allow-list (`BL_NATIVE_ALLOW` = a file the app writes:
   exact guest paths plus prefixes) and `glibc-state` says `patched`. Exec
   `$BL_LOADER --argv0 <argv0> <host prog> <args>` with `BL_EXEC_REAL=<guest prog>` (the spike's
   names, `04` S2).
4. **Otherwise, the safety net:** exec `<nativeLibraryDir>/libproot.so` with the session's prefix,
   binds and host env from `cache/native/proot.args`, then the program. The result is a proot of
   its own (T3…), a child of this process, with `--kill-on-exit`. It is rare by construction.
5. glibc-internal execs (`posix_spawn`, `system`, `popen`) reach the dispatcher through a hook in
   the patched `__execve`/`__spawni` (the M16 exec-hook patch, §4.1).
6. A raw-`svc` `execve` of a guest path gets `ENOENT`, because `/lib/ld-linux-aarch64.so.1` does
   not exist on the host. It fails visibly, never silently wrong.

**The first allow-list (M16):** `steamrtarm64/steam` and its helper binaries in `steamrtarm64/`.
M17 adds `steamwebhelper` and its `/proc/self/exe` re-execs. `reaper`, `droiddeck-proton` and
everything Wine stay traced, so game launches still reach T2 through layer 1. A launch from a
native client therefore runs `steam-launch-wrapper`, `reaper` and `droiddeck-proton` in a net
proot (T3), and the game in T2.

Each exec that falls to the net costs a proot start and a phantom process. The report counts net
tracers† per phase, and M16's acceptance bounds them. Programs the client runs often (its shell
scripts, `lsof`) join the allow-list once `trap-trace` shows them clean.

### 5.3 The exec shim's two forms

| form | exec | kernel `exe` | `cmdline` | targetSdk |
|---|---|---|---|---|
| rootfs `ld.so` | `<rootfs>/usr/lib/ld-linux-aarch64.so.1 --argv0 a0 prog …` | rootfs `ld.so` | shows `ld.so --argv0 …` | 28 only (`app_data_file` exec) |
| `libddld.so` (the same patched `ld.so`, shipped in the native-library dir) | `<nativeLibraryDir>/libddld.so --argv0 a0 prog …` | `libddld.so` | same | any (`apk_data_file`, `02` §3) |

- Both need M15. A guest-path `ld.so` search only works with in-`ld.so` translation, because the
  rootfs is not at `/`.
- `libddld.so` must match the rootfs's libc exactly, which is why native mode requires
  `glibc-state = patched`. Both come from the same build.
- **Default:** `libddld.so`, which survives a targetSdk bump (`PLAN.md` "Held constant").
- `cmdline` keeps the program's own arguments after the loader's. `SessionPerf.scan` matches
  `reaper SteamLaunch AppId=` by argument (`session/PerfCounters.kt:106-110`), and the reaper stays
  traced anyway.

### 5.4 `/proc/self/exe`, `comm`, and the fast path without a tracer

- **`/proc/self/exe`:** `readlink`, `open` and `execve` on it are answered from `BL_EXEC_REAL`,
  read once at `libblsession` init (`04` S2). A forked child inherits the value (correct), and an
  exec gets a fresh one from the shim.
  - The fast path's `own_exe` (`fastpath.c:568-592`) takes it from there instead of asking proot.
  - `tools/linuxfs/preload/perms.c:32-34` and `noexec.c:18-29` then see the guest program.
- **`comm`:** `prctl(PR_SET_NAME, basename)` at init. `pgrep -x`, `droiddeck-launcher-cores` and
  `STRAGGLERS` (`session/SessionService.kt:1842-1843`) keep working.
- **Fast-path authorisation without a tracer:** today `traced_by_match` refuses `TracerPid == 0`.
  Native mode accepts it only when `PROOT_FP_NATIVE=1` and the file named by `PROOT_FP_KEYFILE`
  (written by the app, mode 0600, under `cache/native/`) holds `PROOT_FP_KEY`. Stale environment
  from another session or package therefore never translates.
- **`/proc/self/fd` binds:** `/dev/stdout` resolves against the process itself, the natural
  meaning, instead of the tracer (`fastpath.c:234-245`).
- **Host paths seen by programs:** `/proc/self/maps` shows host paths. `files/` is an identity
  bind, so a program that opens a path read from there still lands on the right file.
  `dladdr`/`link_map` must stay guest paths (M15, §4.1).

### 5.5 Supervision without `--kill-on-exit`

`libddinit.so` (new): a static bionic executable in the native-library directory, built with the
NDK the way `libproot.so` is (`tools/proot/build.sh`). It is the root of every native tree:

- `PR_SET_CHILD_SUBREAPER`, so orphans stay under it;
- it watches the app through `pidfd_open` (Android 11+) or `getppid` polling.
  `PR_SET_PDEATHSIG` would fire when the spawning *thread* exits, which is wrong for a JVM thread;
- it forwards signals to the root child;
- on `SIGTERM` or the app's death: `SIGTERM` to every descendant, a grace period, `SIGKILL`, then
  exit with the root child's status.

The app keeps one supervisor pid per tree. `teardown` (`session/SessionService.kt:988-1007`) and
the suspend roots handle it like a proot pid. Without a tracer, `SIGSTOP` simply works, which
`droiddeck-launcher-cores` could not rely on before (`01` P20).

### 5.6 What still forces the safety-net proot

| what | why | where it runs |
|---|---|---|
| The Wine tree | the static `wine-preloader` carries its own glibc startup; a static test binary dies on `rseq` and `set_robust_list` untraced (`04` S5). M14 records the real preloader on the device (`04` device check 5) | T2 |
| Static binaries; non-glibc code with raw `svc` (Go) | no libc patch reaches them | shim → net proot |
| Standalone FEXInterpreter | x86 path syscalls through `syscall()`. M12's hook covers only libc callers | `droiddeck-fex` stays traced |
| `pacman`/`pacman-key` (`-0`), AppImage import (`--link2symlink`) | fake root, hard links | `GuestCommand` proots, unchanged |
| Flatpak sandboxes | a different root view | `BwrapSpawner` proots, unchanged |
| `gdb`, `strace` in a terminal | ptrace inside ptrace | terminal proot, unchanged |
| Unix sockets with a host `sun_path` of 108 bytes or more | only proot rebinds them (`01` P9) | net proot, or app-chosen short paths |
| Xwayland's `setgid` before xkbcomp | `fake_id0` today; M12's wrappers or glibc 0005 natively | T1, as part of gamescope |
| Anything off the allow-list | default-deny | net proot |

### 5.7 Coupling-map changes (`01` §7)

| `01` §7 item | change |
|---|---|
| `LinuxRuntime.command`/`prootPrefix`/`binds` | unchanged. A `prootArgs(root, cwd, binds)` helper is shared by T1, T2, sandboxes and `cache/native/proot.args` |
| `SessionService.runSession`, `teardown`, `raiseTracer`, `killGuestLeftovers`, `killStragglers` | several roots (T1, T2s, supervisors). `killGuestLeftovers` (`:1159-1174`) matches exe under the rootfs, which in the rootfs-`ld.so` form is every native process. It is run only after the native trees are down, and the `libddld.so` form does not match it |
| `SessionSuspendController.stop` | supervisors join the roots |
| `BwrapSpawner.run`, `GuestCommand.runLogged` | `ProotRun`. `GuestCommand` is unchanged |
| `SessionFiles.stage` | the preload order (M5), glibc staging (M13), `execshim`, the allow-list, the key file |
| `ProotFastPath`, `fastpath.c` `fp_init`/`traced_by_match`/`own_exe`/`in_tracer`, `audit.c` | native authorisation and `BL_EXEC_REAL` (§5.4). `audit.c` mirrors `traced_by_match` |
| `tracer.c`, `perms.c`, `noexec.c` | `tracer.c` is a no-op untraced (`TracerPid` is already 0). The other two get the emulated `exe` |
| `steam-compatibility` | `LD_AUDIT` gate also accepts the native key; the routing rule (L1) |
| `droiddeck-session` | the client loop through `droiddeck-native-run` |
| `runtime_inputs.py`, `isInstalled()` | `libddld.so` and `libddinit.so` become required outputs (`tools/release/runtime_inputs.py:43-75`, `tools/tests/test_runtime_inputs.py:42-60`). proot stays required |

## 6. Cross-cutting

### 6.1 Harness additions (M0)

What the report produces today **[verified in code]**:

- milestones and phases (`session/PerfReport.kt:83-102`);
- per phase: stops/s, kinds, the top 10 trapped syscalls, tracer % and tree %, execs, `SIGSYS`, and
  fast-path hits by family and by program group (`PerfReport.kt:75,214-265`);
- time to READY and Big Picture, launch → focus (`PerfReport.kt:267-277`);
- one tracer only: `prootPid` and `proot.stats` (`session/SessionPerf.kt:85,242-260`).

Missing, and added (†):

| metric | source | needed by |
|---|---|---|
| `tracers[]` per phase `{role, pid, stopsPerSecond, tracerPct}` (role: session, game or net); tree % over all roots | every `cache/perf/proot*.stats`; roots from `game.proot_started` and supervisor events | M3, M10, M16 |
| `processes {traced: {session, game, net}, untraced}`; `peakPhantoms` | `/proc/<pid>/status` `TracerPid` and `Uid`, sampled in `scan()` | M2, M10, M16 |
| `nativeExecs` | fast-path counter file v2 sets a flag bit in the reserved `u32` when `TracerPid` is 0 at exec | M16, M17 |
| `frames {count, fps, p50Ms, p95Ms, p99Ms, stutters}` per phase; `stutters` = intervals over 2× target | frame tap (§2.2) | M5, M7, M8, M10 |
| `powerMw`, plugged flag; `thermalHeadroom` | `BatteryManager` `CURRENT_NOW` × voltage each second; `PowerManager.getThermalHeadroom(10)` (API 30+) at milestones. **Device check:** µA vs mA by OEM | M7, M8 |
| `rssMb {main, game, t1Tree, t2Tree}`; `device.memoryLimiter` | `/proc/<pid>/status` `VmRSS`; cgroup v2 files | M2, M10 |
| `teardownMs` (stop → every session process gone) | `session.teardown_done` event | M3 |
| `spawn {handoffMs, fallbacks[]}`; `:game` cpuset and cgroup | `GameSpawner` events | M10 |
| `killed9` stop count | patch 0016 file v2: tracee deaths by `SIGKILL` | M2 |
| `device.caps` | probe cache | M1, M11, M14 |
| `glibc {state, pkgver}` | `/etc/droiddeck/glibc-state` | M13–M17 |
| `hints {adpf, tids, reports, gameState}` | `PerfHints` | M6–M8 |
| `fastPath.openat2` sub-count | counter file v2 | M11 |
| the new toggle keys in `toggles` | `SessionPerf.Sampler` toggle map (`session/SessionPerf.kt:126-134`) | all |

The summary keeps its columns and gains `game t/s` and `p99 ms`. The JSON schema becomes
`droiddeck.perf/2`. Readers accept `/1`.

### 6.2 Toggle matrix

Every toggle is a `session` preference that `droiddeckctl prefs set session <key>` can set, shown
under a new **Performance → Native environment (experimental)** group, and recorded in the
report's `toggles` with its *effective* value. All default off, or to today's value, until a device
run shows a win (`PLAN.md` "Phase 4").

| key | mechanism | type, default | effective only if | guest or proot effect |
|---|---|---|---|---|
| `prootExitKill` | `PTRACE_O_EXITKILL` and `SIGQUIT` teardown | bool, off | — | `PROOT_EXIT_KILL=1` in proot's env (patch 0017, inert without it) |
| `auditScope` | `libblaudit` scope | `off` / `proton` / `client` / `session`, **`proton`** | `prootFastPath` | `LD_AUDIT` placement |
| `fastPathFolded` | fast path inside `libblsession` | bool, off | `prootFastPath` | preload list and order; `BL_FP_FOLDED=1` |
| `fastPathOpenat2` | `openat2` open family | bool, off | `prootFastPath` and probe `openat2` | `PROOT_FP_OPENAT2=1` |
| `fpSetid`, `fpFexSyscall` | M12 items | bool, off | — | `BL_FP_SETID=1`, `BL_FP_FEXSYSCALL=1` |
| `gameStateHints` | `setGameState` | bool, off | API 33+ | — |
| `adpfHints` | ADPF hint session | bool, off | API 31+ | — |
| `uclampTrial` | uclamp on the hint TIDs | bool, off | probe uclamp ok | — |
| `gameProot` | game tree in `:game` with T2 | bool, off | — | `BL_GAME_PROOT=1`, `BL_SPAWN_SOCKET` |
| `patchedGlibc` | layer 3 | bool, off | `glibc-state` stock and matching | rootfs files; `BL_NATIVE_BLOCK` |
| `nativeClient` | Steam client untraced | bool, off | `patchedGlibc`, `prootFastPath` | client loop via `droiddeck-native-run` |
| `nativeCef` | CEF untraced | bool, off | `nativeClient` | allow-list entry |

`perfReport` (existing) must be on in every compared run (`harness.md`).

### 6.3 A/B on the device

- **`tools/droiddeck-scenario --ab <key>=<off>,<on>`** (new option) runs the
  `perf-baseline.json` steps (`tools/scenarios/perf-baseline.json`) in A B B A order, `--repeat`
  times. A `prefs set session` step goes before each run, and a 3-minute cool-down between runs
  (`harness.md` conditions: same game, screen on, unplugged or plugged throughout).
- **`tools/perf-compare <runs…>`** (new, stdlib Python) reads every run's `perf-report.json` and
  prints, per metric, the median and spread for A and B and the delta.
  - **A win:** the delta exceeds the larger of 5% and A's spread (max − min).
  - **A regression:** a guarded metric gets worse by the same rule.
- **Stacking:** each mechanism is first measured alone against all-off. The next one is then
  measured with the earlier winners on, so a toggle's default reflects the stack it will ship in.

### 6.4 Test strategy

| level | what | where |
|---|---|---|
| Host, simulated Android filter | `tools/native/launcher/seccomp-launch` (the spike's), with profiles `android14`, `android15` and `android16q`, runs: the probe's host build (expected blocked or allowed matrix per profile); the S1 battery against the production glibc build (M14); exec-shim chains and `/proc/self/exe` re-exec (M16); `trap-trace --continue` over a Steam-client-shaped tree, to list escapes before any allow-list entry | `tools/tests/test_native_*.py` |
| Host, proot | `bench/host-proot.sh` proot, optionally under `seccomp-launch`. Equivalence suites (`tools/proot/bench/equiv*.py`) byte-identical for the fold (M5) and for `openat2` (M11, android16q vs android14 fallback); `PTRACE_O_EXITKILL` (SIGKILL proot, no tracee survives); counter files v2 | `tools/tests/test_proot_stats.py` and new `test_proot_exitkill.py`, `test_fastpath_fold.py` (`libfakeinput` → fake overlay hooking `open` → `libblsession` still reaches the fast path) |
| Host, protocol | `droiddeck-game-spawn` and `-init` against a Python fake `SpawnServer`: fd passing, `K` signals, exit codes and signal deaths, `F` fallback before and after `P`, subreaper waits for orphans | `tools/tests/test_game_spawn.py` |
| App unit | `GameSpawnerTest` (routing, preconditions, equal key for equal binds), `SpawnServerFramingTest`, `PerfReportTest` (several tracers, frames, phantoms, schema 1 vs 2), `CapProbeTest` (parsing, cache key), `ExitReasonsTest`, `OrphanReaperTest` (spares `<pkg>:game`), `NativeGlibcTest` (match, mismatch, rollback), the existing `BwrapSpawnerTest` and `ProotFastPathTest` | `app/src/test/…` |
| CI | `build.yml` builds the new C programs and `hostsys`, the glibc job (arm64, cached, only when `tools/glibc` changes), and runs `tools/tests` (already wired, `.github/workflows/build.yml:501`) plus `tools/native/tests` | Actions |
| Device (Phase 4) | `--ab` runs per milestone; the device checks named in this document and in `04` "Verify on device" | `05-results.md` |

### 6.5 Upstream mergeability

- **One milestone, one reviewable change.** New code goes in new files (`SpawnServer`,
  `GameSpawner`, `GameHostService`, `PerfHints`, `CapProbe`, `ExitReasons`, `NativeGlibc`,
  `hostsys.c`, `execshim.c`). `SessionService.kt` (1,954 lines) gets hooks of a few lines per
  milestone.
- **House style:**
  - Kotlin `object`s and `SessionPart`s;
  - comments that explain why, in prose;
  - proot patches that are inert unless an env var is set, as 0014, 0016 and 0017 are;
  - the `BL_*`, `PROOT_FP_*` and `PROOT_*` env namespaces;
  - commit subjects in `feat:`, `fix:` and `docs:` form, with no AI co-author trailers
    (`PLAN.md` decision log).
- **Separable layers:**
  - Layer 1 touches the app, the launcher template and two C programs.
  - Layer 2 touches preloads, the build and the fast path.
  - Layer 3 adds `tools/glibc` and staging, behind owner approval.
  - Layer 4 depends on 3, and on 1's `SpawnServer`.
  - With every toggle off, behaviour is today's, so upstream can take any layer alone.

### 6.6 Risk register

| # | risk | likelihood, impact | mitigation |
|---|---|---|---|
| 1 | **`:game`'s process state gives T2 a worse cpuset, cpuctl/uclamp ceiling or MemoryLimiter budget than T1**, so the game runs slower or is killed | medium, high | `BIND_IMPORTANT` plus `startForeground`; bind at READY; compare cgroups at spawn and fall back (F4); report both; device check before M10 can win |
| 2 | **Cross-tracer state breaks a launch:** an earlier verb's wineserver, sockets of 108 bytes or more rebound per tracer, overlay or steamclient IPC | medium, high | Route only `waitforexitandrun` with a real prefix; socket-length precondition; `F` fallback before `P`; device check of the overlay and achievements |
| 3 | **The fold's order change** (`libblsession` last) alters which shim sees a call first: input, GPU `ioctl` dispatch, dircache | medium, medium | Both layouts ship behind `fastPathFolded`; hook-by-hook audit (§3.1); equivalence and input tests; `in_game` fps in the acceptance |
| 4 | **A glibc mismatch breaks the whole rootfs**: pacman upgrade, a new `linuxfs`, an ABI slip | low, very high | Full-package overlay from Arch's exact commit; `abidiff`; hash-pinned staging; stock copies kept; auto-revert after two failed starts; mismatch disables layers 3 and 4 |
| 5 | **A `SIGSYS` kill in an untraced process** from a path the patches do not cover: Valve binaries, vendor libraries, a missing gate bit such as `close_range` on Android 12 | medium, high | `trap-trace` under the simulated filter before each allow-list entry; the probe-driven `BL_NATIVE_BLOCK`; the supervisor reports signal deaths†; two `SIGSYS` deaths turn `nativeClient` off for the session |
| 6 | **Native exec escapes** (raw `svc` exec, an internal glibc exec path) make Steam self-update or CEF fail | medium, medium | `ENOENT`, never wrong; the `__execve` hook; default-deny allow-list; Steam's `rc 42` loop retries; the toggle is off by default |
| 7 | **ADPF refuses cross-process TIDs before API 34, or boosts a GPU-bound game for nothing** | medium, low | Fall back to the compositor thread; `setPreferPowerEfficiency` outside games; `powerMw`† guards the acceptance; the uclamp trial as an alternative |
| 8 | **`openat2` diverges from proot's view** (symlinks across binds, glue, nested override binds) and opens a wrong file silently | low, high | `RESOLVE_NO_SYMLINKS` plus lexical bind-first; a symlinked path gets a `RESOLVE_IN_ROOT` retry accepted only after `readlink` verification, else the walk; glue excluded; equivalence suites under both profiles |
| 9 | **Phantom count rises**: +2 per game (T2, init), +1 per net proot and per supervisor | low (Steam gated), high | `peakPhantoms`†; refuse routing at 28 or more unless the limit is off; net proots are rare by construction |
| 10 | **Noise or thermal drift** makes A/B inconclusive or misleading | high, medium | A B B A order, n ≥ 3 pairs, cool-downs, spread-aware win rule (§6.3), the same game and power state |

## 7. Milestones for Gate 2

Ordered. "Blocked on" names what must exist before the milestone can *start*: earlier
milestones, the device baseline, a device capability, or the owner. The spike no longer blocks
anything: its verdict is in, and its device checks (`04` "Verify on device", items 1–6) appear
in the acceptance of M1 and M13–M16. Item 7, 16 KB pages, is tracked apart (`PLAN.md`).
**Whatever** = worth doing whatever the baseline shows.

| id | title | layer | size | blocked on | acceptance metric (median of n ≥ 3 A/B pairs, §6.3) | whatever |
|---|---|---|---|---|---|---|
| M0 | Harness: several tracers, process accounting, frames, power, memory, teardown, `--ab`, `perf-compare` | — | M | none | Every † field present; 3 baseline runs with spread ≤ 10% for READY, Big Picture and launch → focus | yes |
| M1 | Capability probe in a forked child; caps in the report and in `BL_NATIVE_BLOCK`/`BL_CAPS` | 2 | S | none | `device.caps`† matches `02` §3 for the device's release; the probe's aarch64 syscall numbers equal the device headers and `seccomp-launch --dump aarch64` (`04` device check 1); the mask matches the real policy per Android version (`04` device check 3); READY within ±2% (cached) | yes |
| M2 | Phantom count, exit reasons, MemoryLimiter readout | 1 | S | none | `peakPhantoms`†, `killed9`†, `app.exit_reason` for a forced kill of `:game` | yes |
| M3 | proot 0017: `PTRACE_O_EXITKILL` with `--kill-on-exit`; `SIGQUIT` teardown | 1 | S | none | Host: 0 tracees survive a `SIGKILL`ed proot. Device: `teardownMs`† down ≈1.2 s when the tree is alive at stop | yes |
| M4 | `libblaudit` scope (`auditScope`) | 2 | S | none | `client`: `openat` stops in startup and READY → Big Picture ≥ 20% lower; fp hit % per group within ±1 pt; READY and Big Picture no worse; no "wrong ELF class" in `session.log` | yes |
| M5 | Fold `libblfastpath` into `libblsession` (load order moved) | 2 | M | none | startup stops per exec (stops ÷ `exec` kind) lower by ≥ 1; READY no worse; equivalence suites identical; `in_game` fps ±2% and p99† no worse | yes |
| M6 | `setGameState(loading)` | 1 | S | none | launch → focus and Big Picture time no worse; `hints.gameState`† transitions logged. On if either time improves ≥ 5% | yes |
| M7 | ADPF hint sessions (compositor frames; gamescope and game TIDs) | 1 | M | none | `in_game` p95/p99 frame interval† ≥ 10% lower, or fps higher, at `powerMw`† within +5%; `hints.adpf=created` with TIDs from ≥ 2 processes | yes |
| M8 | uclamp trial | 1 | S | M1, M7 | as M7, run alone and then with M7 | yes |
| M9 | `SpawnServer`: generalise `BwrapSpawner`, variant-safe socket, fd passing, `K`/`F` frames | 1 | M | none | Unit and protocol tests; device: a Flatpak app launches; two variants installed side by side do not share the socket. No perf metric (refactor) | yes, as a prerequisite |
| M10 | Game tree in `:game` with T2 | 1 | L | M0, M3, M9; **baseline**; device check of `:game`'s cgroups | launch → focus ≥ 15% lower, *or* T1 traps/s during launch → focus ≥ 50% lower with T2 carrying the Wine tree; `in_game` fps ±2%, p99† no worse; tree % up < 5%; 0 `F` in 10 launches; T2's cpuset equals T1's† | no |
| M11 | `openat2(RESOLVE_IN_ROOT)` open family | 2 | M | M1, M5; a **16 QPR1+ device** | equivalence identical (android16q and android14 fallback); fp hit % up or equal; `openat` stops not up; READY and Big Picture no worse; `fastPath.openat2`† > 0 | no |
| M12 | In-process items: `set*id` wrappers, the FEX `syscall()` hook, `statfs` on `/dev/shm` | 2 | S–M | **baseline** (which ones appear in the top trapped syscalls) | per item: `setgid`/`setuid` emulated stops → 0 with Xwayland's keymap working; the `fex` group's fp hit % from ≈0 to ≥ 80% in an x86 Linux game, with that phase's traps/s down | partly |
| M13 | glibc pipeline: Arch commit rebase, arm64 CI build, staging, state file, pacman hook, rollback | 3 | M | **owner (Gate 2)** | `abidiff` clean; `__ASSUME_FACCESSAT2`/`FCHMODAT2` = 0 and `__ASSUME_SET_ROBUST_LIST` = 1 under Arch's `--enable-kernel` (`04` device check 2, read from the build); READY no worse with the patch-free rebuild staged; on → off → on leaves the stock files byte-identical | no |
| M14 | Patched glibc: no blocked syscalls (spike 0001–0006 plus 0007, `close_range`) | 3 | M | M1, M13 | `SIGSYS` column = 0 in startup and the Big Picture phases (in game, only the static `wine-preloader`'s pair per Wine process may remain); `set_robust_list`, `rseq`, `clone3`, `faccessat2` gone from startup's top trapped syscalls; READY, Big Picture and launch → focus no worse; S1 battery clean under all three profiles on the host and untraced on the device with the probe's mask (`04` device checks 1, 3); Steam's semaphore and mangoapp's queue still work through `sysv.c`; recorded: whether the depot's arm64 `wine-preloader` dies untraced (`04` device check 5) | no |
| M15 | Patched glibc: `ld.so` and libc internal paths in-process (the spike's open piece) | 3 | L | M5, M13 | on device, library search, `ld.so.cache`, preloads, nss, `realpath`, fts and `posix_spawn` translate without `--library-path` (`04` device check 6); startup `openat`+`newfstatat`+`readlinkat` stops per exec ≥ 50% lower; READY ≥ 10% lower; equivalence identical | no |
| M16 | Native Steam client: exec shim, `/proc/self/exe` and `comm`, `libddinit`, `libddld` | 4 | L | M9, M14, M15 | `libddld.so` execs from the native-library directory on the device (`04` device check 4; otherwise the rootfs form, targetSdk 28 only); `processes.untraced`† ⊇ the steam group; startup traps/s ≥ 30% lower; READY → Big Picture ≥ 15% lower; idle Big Picture tracer % down; Steam self-update and `rc 42` work; net tracers† ≤ game launches + 2 per session; 0 `SIGSYS` deaths† in 10 sessions | no |
| M17 | Native CEF tree | 4 | L | M16 | `steamwebhelper` group untraced; READY → Big Picture ≥ 20% lower than M16; idle Big Picture tracer % ≤ 1%; `nativeExecs`† covers CEF children; Big Picture scrolling fps no worse | no |

The proposed thresholds are **[inferred]** and for the orchestrator to fix at Gate 2, once the
baseline gives the spread.

**How the baseline orders the middle** (proposals):

| baseline reading | promotes |
|---|---|
| launch → focus tracer % ≥ 50%, or its traps/s within 30% of the single-tracer ceiling (68–95k/s, proot-performance.md) | M10 next after M9 |
| `in_game` traps/s < 1k and tracer % < 5% | judge M10 on launch → focus alone; its `in_game` effect is noise |
| steam/steamwebhelper groups ≥ 40% of startup stops | M15 and M16 ahead of M10; consider sending CEF's tree to its own tracer through `SpawnServer` as an interim |
| `SIGSYS` ≥ 5% of startup stops | M14 promoted (it pays under proot) |
| ld.so-shaped misses (`openat`/`newfstatat` failing in library dirs) dominate the top syscalls | M4, M5, then M15 |
| a group's fp hit % < 90% | M11 or M12 on that family |

## 8. Decisions needed

| # | decision | from | recommendation |
|---|---|---|---|
| 1 | Patched glibc as an **APK overlay staged in place** (with stock copies kept) or as a **rebuilt `linuxfs` release** | owner (Gate 2, `PLAN.md`) | overlay (§4.2) |
| 2 | `IgnorePkg = glibc` in the rootfs's `pacman.conf` | owner | no: state file plus hook (§4.3) |
| 3 | A second Android process (`:game`): manifest service, `App.onCreate` branch, a second foreground notification while a game runs | owner | yes, behind `gameProot` |
| 4 | The baseline thresholds that promote or skip M10 (§7) | orchestrator | as proposed, fixed after the spread is known |
| 5 | Whether safety fixes may default on after host tests (M3's `PTRACE_O_EXITKILL`), or follow the device-win rule like every other toggle | orchestrator | device-win rule, but M3 first in the queue |
| 6 | An arm64 GitHub runner and an Arch Linux ARM build container for glibc (about 15 min, cached) | owner | yes |
| 7 | Devices: one on Android 16 QPR1+ (M11), one on 31–33 (ADPF cross-process TIDs), and an Android 17 device with the MemoryLimiter config, if any is at hand | owner | M1's probe tells which device is which |
| 8 | One translation table for glibc, the fast path and native mode: `PROOT_FP_*`, not the spike's `BL_XLAT_*` | orchestrator (with the spike agent) | `PROOT_FP_*` |
