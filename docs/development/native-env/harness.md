# Measurement harness: producing a baseline

How to measure a session on the device, before and after each native-environment change, with
nothing but the app. Everything here is off until **Performance → Measurement → Performance
report** is turned on; off, proot, the fast path and the app count nothing.

## What it measures

With the toggle on, each session writes `perf-report.json` and a short `perf-report.txt` into its
log folder, rewritten at every milestone (so a session that dies still leaves the last one):

- **Milestones:** session created, proot started, first frame (gamescope up), READY, Big Picture
  focused (app 769), and for each game its launch, its focus and its exit, then the session's end.
  A launch is the app's own request, or the Steam client's `reaper SteamLaunch AppId=<id>` process,
  which a once-a-second `/proc` scan finds (so a Play from inside Big Picture counts).
- **Phases** between them: startup (created → READY), READY → Big Picture, idle in Big Picture
  (→ the first launch), launch → focus, and in game (→ the game's exit, or the end).
- **Per phase:**
  - proot stops per second, by kind and per syscall, and the time proot spent handling them
    (`PROOT_STATS`, patch 0016);
  - the tracer's CPU (`/proc/<proot>/stat`) and the whole session tree's CPU, as % of one core;
  - every tracer on its own (`tracers`: role, pid, stops/s, tracer %), and the tree under all of
    them;
  - execs, SIGSYS emulations;
  - fast-path hits and misses by program and call family (`PROOT_FP_STATS_DIR`);
  - frames: how many the screen showed, fps, the 50th, 95th and 99th percentile frame time, and
    stutters (frame times over twice the target);
  - battery power, mean and peak, plugged in or not, and thermal headroom at both ends;
  - memory (VmRSS) of the app and of the session's processes, mean and peak;
  - the app's processes by who traces them, and the peak count of phantom processes.
- **Per session:** the teardown time (stop → every session process gone), the game state hints
  Android was given, and the rootfs's glibc (stock, or a patched build's version).
- **Context:** device (model, SoC, Android, kernel, page size, CPUs), build label and commit, and
  the toggles in effect, named as `droiddeckctl prefs set session <key>` names them.

The summary is the numbers a comparison needs: time to READY and Big Picture, launch → focus and
teardown; traps/s, tracer %, tree %, execs, SIGSYS, fast-path hit rate, fps and p99 frame time per
phase; power and phantoms; the ten most-trapped syscalls, stops by kind, and the fast path's hit rate
per program group. Columns in the phase table:
- `traps/s` is the session proot's stops per second;
- `tracer%` and `tree%` are CPU as a percentage of one core (the tree can exceed 100);
- `fp hit%` is the share of fast-path-eligible calls answered in-process;
- `fps` and `p99 ms` are the frames the screen showed, and the frame time 99% of them beat;
- `game t/s`, only once a game has a proot of its own (`03-design.md` M10), is that proot's stops
  per second.

The `power` line has the mean battery power over the session, idle in Big Picture and in game, and
the thermal headroom at the first and last milestone (1.0 is where Android throttles hard). The
`session` line has the peak phantom count, how many processes each tracer traced at most and how
many ran untraced, peak memory, the glibc and the number of game state hints.

## Capabilities

At every session start, report on or off, the app asks the device's own seccomp filter and kernel
which of the calls Android's releases differ on it allows (`core/CapProbe.kt`,
`app/src/main/cpp/hostsys/capprobe.c`, `03-design.md` §3.3); adb does not run under the app's
filter, so only the app can ask. Each test runs in a child of its own, so a trapped call kills only
that child. The answer is kept in `files/caps.json` for the rest of the boot (keyed on the boot id,
the build fingerprint, the version code and the app's install time, since every test build has the
same version code), so only the first session after a boot or an install pays for the probe: one
fork of the app and a few milliseconds. `perf-report.txt` shows it under
`device`, for example on Android 15:

```
caps     seccomp: openat2 blocked, faccessat2 blocked, fchmodat2 blocked, clone3 ok, close_range ok, landlock ok; uclamp ok · BL_NATIVE_BLOCK=6 · probed in 24 ms
         fp_stub ok, execmem ok, exec_data ok, userfaultfd ok, memfd_create ok, setresuid ok · blocked: resolve_in_root, rseq, set_robust_list, get_robust_list, futex_waitv, io_uring_setup, setuid
```

- `ok` is usable here; `blocked` is the filter's `SIGSYS`; an errno name is a call the filter let
  through and the kernel refused (`ENOSYS`: no such call; uclamp's `EOPNOTSUPP`: a kernel without
  it); `error` is a test that could not tell.
- The first line is the version-gated calls, then uclamp (`uclamp_min` 512 on the test's own
  thread, read back). The second has the rest:
  - `resolve_in_root`: an absolute symlink under `openat2(RESOLVE_IN_ROOT)` stays in the root;
  - `fp_stub`: the fast path's stub page at its fixed address, made executable, answers `getpid`;
  - `execmem`, `exec_data`: executable anonymous memory, and an executable map of an app file;
  - `setresuid`, allowed everywhere, is the probe's own check: when it is not `ok` the whole probe is
    distrusted; `rseq` and the other always-blocked calls are a sanity check.
- `BL_NATIVE_BLOCK`, in the guest, is the patched glibc's mask (`tools/native/glibc-patches/0001`;
  stock glibc ignores it): `0x1` clone3, `0x2` faccessat2, `0x4` fchmodat2, `0x8` close_range, set
  unless the call came back allowed. By `02` §3 that is `f` up to Android 12, `7` on 13 and 14, `6`
  on 14 QPR to 16, and `0` from 16 QPR1; device runs confirm it.
- `BL_CAPS`, also in the guest, lists which of the tests that differ by release or device came back
  `ok`: `openat2`, `resolve_in_root`, `faccessat2`, `fchmodat2`, `clone3`, `close_range`,
  `landlock`, `uclamp`, `fp_stub` (`CapProbe.EXTRAS`). The controls every Android allows are in the
  report only.
- `probe failed (…)`: the probe could not run, or had not finished 100 ms after its fork. Neither
  variable is set, which the glibc reads as every gated call blocked.
- `cached` means read from `files/caps.json`; `probed in N ms` (fork included) means it ran.

`perf-report.json` has the same as `device.caps`: `nativeBlock`, `extras` (`BL_CAPS`), each test's
`state`, `ret`, `errno`, `usable` and `step` (0 set-up, 1 the call under test, 2 on what follows),
and the page size, kernel, Android release and SDK, security patch and fingerprint it ran on. The
session's `app.log` has the first line; `droiddeckctl state` has the object as `caps`.

## On the device (no adb)

1. Install the `DroidDeck-Env-<sha>.apk` from the `droiddeck-env-apk` artifact of the
   `Build APK` run. It installs as **DroidDeck Env**, beside the released app.
2. In DroidDeck Env: **Performance → Measurement → Performance report: on**. Leave every other
   toggle as it is for the baseline; the report records them.
3. Keep conditions the same for every run you will compare:
   - the same game, the same power state (plugged in or not), a cool device;
   - nothing else running, and the screen on throughout (Doze makes proot about 7× slower).
4. One run:
   1. From the app's home, start **Steam** and wait for Big Picture.
   2. Leave it untouched for **60 seconds**.
   3. Launch **one game** from Big Picture and play for **2 minutes**.
   4. Quit the game from the Steam menu and wait about 10 seconds in Big Picture.
   5. Stop the session (session drawer → **Stop**), back to the app's home.
5. **Performance → Measurement → Last report → Copy**, and paste it into the chat. That copies the
   summary of the newest session that wrote one.
6. For the full report, use **Share logs** (session drawer, or Setup): the zip's session folder
   holds `perf-report.json` and `perf-report.txt`.
7. Do three runs, each a fresh session, so the spread is visible.

To compare a mechanism, run the same steps with its toggle on and off in the same install, with the
report on in both. The report's own cost is small but not zero, so never compare a run with the
report against one without.

## From Termux (adb on the phone itself)

`tools/droiddeckctl` and `tools/droiddeck-scenario` need only Python's standard library and adb.

1. Install the tools: `pkg install android-tools python git`, then
   `git clone -b Environment-work https://github.com/Leb-Sun/DroidDeck` and `cd DroidDeck`.
2. Connect adb:
   1. **Developer options → Wireless debugging → Pair device with pairing code**, then
      `adb pair localhost:<pairing port> <code>`.
   2. `adb connect localhost:<port>`, with the port shown on the Wireless debugging screen.
3. Point the tools at the test app: `export DROIDDECK_PACKAGE=com.droiddeck.env`.
4. Turn the report on:
   - in the app; or
   - `tools/droiddeckctl prefs set session perfReport true --type bool`, which needs **Setup →
     Diagnostics → Debugging tools** on.
5. Fetch the newest report: `tools/droiddeckctl perf --out ~/baseline`. It writes
   `perf-report.txt` and `perf-report.json`, and prints both as JSON.
6. A scripted run, which does the device steps above and needs Debugging tools on:

   ```sh
   termux-wake-lock    # keep Termux alive while the game is in front
   tools/droiddeck-scenario tools/scenarios/perf-baseline.json --var appId=<steam app id> --out ~/runs
   ```

   - Optional: `--var idle=60 --var play=120`, and `--repeat 3` for three cold runs.
   - The last step's answer, in `~/runs/perf-baseline-*/run-01/steps.json`, holds the summary and
     the report.

## A/B: one toggle, on and off

`03-design.md` §6.3. The same scripted run, alternating a toggle, then a comparison:

```sh
termux-wake-lock
tools/droiddeck-scenario tools/scenarios/perf-baseline.json --var appId=<steam app id> \
    --ab gameStateHints=false,true --repeat 3 --out ~/runs
tools/perf-compare ~/runs/perf-baseline-<date>-<time>
```

- `--ab <key>=<A>,<B>` runs the scenario's steps in A B B A order, `--repeat` times over (3 gives
  six runs of each). Before each run, `prefs set session <key> <value>`; the type is `bool` for
  `true`/`false`, `int` for numbers, else `string` (`--ab-type` to say). The scenario must start a
  fresh session after that, as `perf-baseline.json` does: a toggle takes effect at session start.
- Between runs, a cool-down of `--cooldown` seconds (180) at the app's home, so heat from one run
  does not slow the next.
- After each run, the report is fetched into the run's folder (`run-NN/perf-report.json` and
  `.txt`), once the session's teardown time is in it (up to 30 s after the stop), and `ab.json`
  names the arm. A run that left no new report fails.
- The toggle is left at A's value at the end.
- `tools/perf-compare <folders or reports…>` groups the runs by the key (from `ab.json`, else
  `--key`, else the one toggle that differs) and prints each metric's median and spread
  (max − min) for A and B, and the delta. **win**: the delta beats the larger of 5% of A and A's
  spread, for the better. **REGRESSION**: the same, for the worse, on a guarded metric (READY, Big
  Picture, launch → focus, teardown, peak phantoms, and in game: fps, p99, power and tree %). It
  exits 1 on a regression. It reads `droiddeck.perf/1` reports too (their new metrics are left out)
  and older run folders' `steps.json`. `--json` for the rows as JSON.
- Keep conditions fixed across all runs, as above. perf-compare notes runs that mixed plugged in and
  unplugged, failed runs (left out) and arms of fewer than 3 runs.

## Where things are

| what | where |
|---|---|
| reports | `perf-report.json`, `perf-report.txt` in the session folder (`files/logs/<session>/`) |
| proot's stop counters | `cache/perf/proot.stats`, from `PROOT_STATS` in proot's own environment. The session's proot only: a terminal's does not get it. Another tracer's file is `proot-<role>-<n>.stats` (`game`, `net`; M10 and later): the report reads every `proot*.stats` by its name, with the pid from the file's header |
| fast-path counters | `cache/perf/fastpath/<program>.<pid>`, one per exec, from `PROOT_FP_STATS_DIR` in the guest's environment. The session's processes only |
| code | `session/SessionPerf.kt` (sampling), `PerfSamples.kt` (frames and the per-second samples), `PerfReport.kt` (phases, JSON, summary), `PerfCounters.kt` (readers); the frame tap is `WaylandCompositor.addFrameTap` |
| A/B | `tools/droiddeck-scenario --ab`, `tools/perf-compare` |
| capability probe | `files/caps.json` (this boot's); code in `core/CapProbe.kt` and `app/src/main/cpp/hostsys/` |

- `cache/perf` is emptied whenever a session starts, so no earlier counters reach a report. The raw
  files stay out of the logs; only the two reports go in.
- The proot file's layout is in `tools/proot/PATCHES.md` (0016).
- A fast-path file is:
  - an 8-byte magic `FPSTATS\0`, written last;
  - `u32` version (1), number of families (7), pid, reserved;
  - a 32-byte program name;
  - 7 `u64` hits, then 7 `u64` misses, in the order open, stat, access, path, write, socket, other
    (`live_counters` in `tools/proot/fastpath/fastpath.c`).
- `perf-report.json` (`schema: droiddeck.perf/2`) has `build`, `device`, `toggles`,
  `clockTicksPerSecond`, `milestones` (each with its raw totals), `times`, `phases` (deltas, top
  syscalls, fast path by group and program) and `session` (totals, every syscall row, fast path).
  New in `/2` (readers take `/1` as well):
  - top level: `frameTargetMs`; `powerUnits` (`current`: `uA` or `mA`, `voltage`: `mV`, `V` or
    `uV`, `dischargeSign`: the raw current's sign while unplugged, -1 by AOSP's convention);
    `teardownMs` (null without a `session.teardown_done` event); `hints.gameState` (each
    `hints.game_state` change with its `atMs`, or null); `glibc {state, pkgver}`;
  - each milestone: `tracers [{file, role, pid, stops, tracerTicks}]`, `thermalHeadroom`;
  - each phase, and `session` for the whole: `frames {count, fps, p50Ms, p95Ms, p99Ms, stutters}`,
    `power {meanMw, peakMw, plugged, samples, raw {currentNowMean, voltageMean}}`,
    `rssMb {main, sessionTree, game}` (each `{mean, peak}`; `game` null until M10) and
    `processes {app, tracers, traced {<role>: n}, untraced, peakPhantoms}` (peaks); each phase also
    `tracers [{role, pid, stops, stopsPerSecond, tracerPct}]` and `thermalHeadroom {start, end}`.
    A part with nothing read is null.
- `toggles` has today's keys always, as the app reads them (defaults included), then any of the
  native environment's (`03-design.md` §6.2: `prootExitKill`, `auditScope`, `fastPathFolded`,
  `fastPathOpenat2`, `fpSetid`, `fpFexSyscall`, `gameStateHints`, `adpfHints`, `uclampTrial`,
  `gameProot`, `patchedGlibc`, `nativeClient`, `nativeCef`) that is set, as stored. A key never set
  is left out. `gameStateHints` shows its effect: false below Android 13.

## Reading it: limits

- **Stops** are proot's own count of `waitpid` returns. `wait4` stops are the `wait4` row of the
  syscall table. Emulated means proot answered a syscall Android's policy refused: `SIGSYS`, or
  `ENOSYS` at the exit.
- **Tracer CPU** is proot's utime + stime. **Tree CPU** sums the live session tree plus what each
  process reaped from its children. An orphan reparented outside the tree and gone by the next
  milestone is lost, so tree CPU is a floor.
- **Fast-path counts:**
  - a process counts from its first path call;
  - a forked child counts into its parent's file until it execs;
  - a program run again under the same pid carries on its counts.

  Sums by program are exact up to those rules.
- **Milestones:**
  - a launch from inside Steam is seen within a second;
  - focus comes from the guest agent's `focus.changed` events;
  - a game that never takes focus has no launch → focus phase.
- **Frames** are those of the window the HUD counts: in fullscreen the game's own buffers, which
  gamescope forwards; with the Steam menu up, gamescope's composition (`03-design.md` §2.2). The
  target is `1e9 / BL_FPS` with a frame limit, else `1e9 / BL_REFRESH`. No frame time spans a
  milestone, a change of that window, or a stretch with the screen off or the session suspended
  (checked each second, so up to two seconds of frames around one are dropped). fps is frames
  over the frame times counted. Percentiles come from 0.1 ms bins up to 100 ms and 10 ms bins up
  to 2 s.
- **Power is a device check.** `BatteryManager`'s `CURRENT_NOW` times the sticky battery
  broadcast's voltage, each second. The documentation says µA and mV, negative while discharging;
  OEMs report mA, µV or V, and either sign. The report reads a current under 10,000 as mA and
  otherwise µA, a voltage under 100 as V and over 100,000 as µV, and drops the sign; `powerUnits`
  and each phase's `raw` keep what it read, so a wrong guess can be redone. Plugged in, the
  battery shows its net flow, not the device's draw: compare unplugged runs. Some devices update
  `CURRENT_NOW` only every few seconds or average it. Thermal headroom (Android 11+) is read at
  milestones only; Android answers NaN when asked too often, which the report leaves out.
- **Processes** are every process of the app's uid, once a second (`/proc/<pid>/status`: `Uid`,
  `PPid`, `TracerPid`, `VmRSS`). The app's own are those zygote started: named after the package or
  `<package>:<name>`, with a parent that is not the uid's. Every other is a phantom, which is what
  Android's phantom process limit counts. A tracer is known by its counter file's pid; a process
  traced by another (a terminal's proot) counts as `other`. Peaks between two samples are missed.
- **Memory:** `main` is the app's main process; `sessionTree` every tracer, what it traces and their
  children, so a guest process orphaned to init still counts.
- **Teardown and game state hints** come from the session's own events (`session.teardown_done`,
  after the stop; `hints.game_state`, with Performance → Game state hints on). The teardown event
  comes after the end milestone, so the report is written once more when it does, up to a minute
  after the stop; a fetch straight after the stop may not have it yet.
- **Cost with the report on:**
  - per stop, a pair of `clock_gettime` reads and a few stores;
  - per exec that makes a path call, about five untraced syscalls to make its counter file;
  - per presented frame, on the compositor thread, one store into a ring (no allocation, no lock);
  - in the app, a `/proc` scan each second (now with each uid process's `status`, and the battery)
    and a read of every counter file at each milestone.

  With the report off, nothing is sampled and no frame tap is registered.
- **Host tests:**
  - `tools/proot/bench/host-proot.sh <dir>` builds this repo's proot for the host;
  - `DROIDDECK_HOST_PROOT=<dir> python3 -m unittest discover -s tools/tests -p 'test_proot_stats.py'`
    runs workloads under it and checks both counter files;
  - `python3 -m unittest discover -s tools/tests -p 'test_native_capprobe.py'` runs the capability
    probe under the spike's simulated app filter (`tools/native/launcher/seccomp-launch`), once per
    profile, and checks that each call comes back blocked or allowed as that profile says.

## Later

What `03-design.md` §6.1 adds and this harness does not have yet:

- `killed9`, tracee deaths by `SIGKILL` (proot's counter file v2, M2);
- `nativeExecs`, processes that exec'd untraced (fast-path counter file v2, M16);
- `fastPath.openat2`, the `openat2` sub-count (counter file v2, M11);
- `spawn {handoffMs, fallbacks[]}` and `:game`'s cgroups (M10), and the `game` memory slot;
- `device.memoryLimiter`, Android 17's per-app memory budget (M2);
- `hints {adpf, tids, reports}`, ADPF hint sessions (M7).
