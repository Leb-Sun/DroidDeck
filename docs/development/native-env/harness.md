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
    (`PROOT_STATS`, patch 0015);
  - the tracer's CPU (`/proc/<proot>/stat`) and the whole session tree's CPU, as % of one core;
  - execs, SIGSYS emulations;
  - fast-path hits and misses by program and call family (`PROOT_FP_STATS_DIR`).
- **Context:** device (model, SoC, Android, kernel, page size, CPUs), build label and commit, and
  the toggles in effect, named as `droiddeckctl prefs set session <key>` names them.

The summary is the numbers a comparison needs: time to READY and Big Picture, launch → focus,
traps/s, tracer %, tree %, execs, SIGSYS and fast-path hit rate per phase, the ten most-trapped
syscalls, stops by kind, and the fast path's hit rate per program group. Columns in the phase table:
`traps/s` is proot stops per second; `tracer%` and `tree%` are CPU as a percentage of one core
(the tree can exceed 100); `fp hit%` is the share of fast-path-eligible calls answered in-process.

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

## Where things are

| what | where |
|---|---|
| reports | `perf-report.json`, `perf-report.txt` in the session folder (`files/logs/<session>/`) |
| proot's stop counters | `cache/perf/proot.stats`, from `PROOT_STATS` in proot's own environment. The session's proot only: a terminal's does not get it |
| fast-path counters | `cache/perf/fastpath/<program>.<pid>`, one per exec, from `PROOT_FP_STATS_DIR` in the guest's environment. The session's processes only |
| code | `session/SessionPerf.kt` (sampling), `PerfReport.kt` (phases, JSON, summary), `PerfCounters.kt` (readers) |

- `cache/perf` is emptied whenever a session starts, so no earlier counters reach a report. The raw
  files stay out of the logs; only the two reports go in.
- The proot file's layout is in `tools/proot/PATCHES.md` (0015).
- A fast-path file is:
  - an 8-byte magic `FPSTATS\0`, written last;
  - `u32` version (1), number of families (7), pid, reserved;
  - a 32-byte program name;
  - 7 `u64` hits, then 7 `u64` misses, in the order open, stat, access, path, write, socket, other
    (`live_counters` in `tools/proot/fastpath/fastpath.c`).
- `perf-report.json` (`schema: droiddeck.perf/1`) has `build`, `device`, `toggles`,
  `clockTicksPerSecond`, `milestones` (each with its raw totals), `times`, `phases` (deltas, top
  syscalls, fast path by group and program) and `session` (totals, every syscall row, fast path).

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
- **Cost with the report on:**
  - per stop, a pair of `clock_gettime` reads and a few stores;
  - per exec that makes a path call, about five untraced syscalls to make its counter file;
  - in the app, a `/proc` scan each second and a read of every counter file at each milestone.
- **Host tests:**
  - `tools/proot/bench/host-proot.sh <dir>` builds this repo's proot for the host;
  - `DROIDDECK_HOST_PROOT=<dir> python3 -m unittest discover -s tools/tests -p 'test_proot_stats.py'`
    runs workloads under it and checks both counter files.
