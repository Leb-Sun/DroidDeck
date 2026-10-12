# Native environment: plan and decision log

Working notes for the experiment on `Environment-work`. The question: can DroidDeck's Linux
environment act more natively on a non-rooted Android device and pay less for compatibility than
it does under proot today? Three routes are on the table: a new environment structure, a modular
proot that runs native where it can, or something new built on recent developments, possibly with
the app itself as the container.

## Finished looks like

- DroidDeck runs faster and more efficiently, measured on a device against today's proot baseline
  with the same harness:
  - time to Steam Big Picture;
  - game load time;
  - CPU spent outside the game, and stutter while assets stream;
  - power.
- The environment behaves more like a native one: fewer processes under a tracer, fewer traps per
  second, nothing on one thread serialising the whole session.
- Nothing that works today breaks: Steam self-update, Proton, FEX, Flatpak, the Desktop,
  AppImages, Decky, the stores.
- Each new mechanism ships behind a toggle, with proot as the fallback, until a device has
  validated it.

## Starting point

This is not a greenfield. `docs/development/proot-performance.md` already measures where proot's
time goes. The tree already carries:

- proot patches 0001–0014 (`tools/proot/PATCHES.md`), which take the hot gameplay syscalls (GPU
  `ioctl`, `futex`, `fstat`, `sendmsg`, the vDSO) off the trap list;
- an in-process fast path (`tools/proot/fastpath/`, `runtime/ProotFastPath.kt`) that answers
  common path calls without a trap.

What remains is mostly structural:

- every path syscall the fast path misses, plus every `execve`, `brk`, thread creation and
  Android-blocked syscall, costs 15–60 µs;
- all of those go through one tracer thread.

## Ground rules

- **Branch.** All work goes on `Environment-work` in `Leb-Sun/DroidDeck`. `main` is the anchor
  and sync point with upstream and is never pushed (a local pre-push hook refuses it).
- **Authorship.** Commits are authored as `Leb-Sun <266895696+Leb-Sun@users.noreply.github.com>`
  and committed and signed by Claude, so GitHub credits the owner and shows them as Verified.
- **Agents.** At most two work at once. The orchestrator reviews every deliverable before it is
  committed.
- **No device in the cloud container.** Agents read code, design, build and test on the host, and
  write device scripts. Device numbers come from runs on real hardware. Every claim is labelled
  *measured* (where, how) or *inferred*.

## Testing on the device

- **The build.** Every push to `Environment-work` that changes more than Markdown runs `Build APK`
  in GitHub Actions on `Leb-Sun/DroidDeck`.
- **The download.** The run's `droiddeck-env-apk` artifact holds `DroidDeck-Env-<sha>.apk`.
- **How it installs.** As **DroidDeck Env** (`com.droiddeck.env`), beside the released app:
  - it is signed with the public AOSP testkey (a fork has no release key), and the package is
    renamed the way `tools/release/sign-apk.sh` makes its variants;
  - it keeps its own data, runtime and Steam sign-in;
  - the updater offers it nothing, since no catalog lists the package.
- **A/B within one install.** New mechanisms sit behind toggles. The baseline is the same build
  with the toggles off, so a comparison never mixes packages, runtimes or signatures.
- **Over adb.** The owner runs adb from Termux on the phone itself (Wireless debugging,
  `adb connect localhost:<port>`), so scripted runs go through `tools/droiddeckctl` under Termux's
  Python. It hardcodes `com.droiddeck.launcher` until the harness adds a package option.

## Phases and gates

| phase | work | output | gate |
|---|---|---|---|
| 1. Discovery | two agents in parallel: an internal audit of how DroidDeck uses proot; prior art and Android non-root constraints | `01-current-architecture.md`, `02-landscape-and-constraints.md` | **Gate 1:** pick one or two candidate architectures |
| 2. Design | a target-architecture design; feasibility spikes on the riskiest mechanisms | `03-design.md`, `04-spikes.md` | **Gate 2:** fix the milestone list; check with the owner before any large structural change (for example, rebuilding the rootfs glibc) |
| 3. Build | one milestone at a time, implementer then independent reviewer, each behind a toggle | code, tests, notes in this folder | each milestone has to pass host tests and review |
| 4. Validate | device A/B harness on `droiddeckctl` and `tools/proot/bench`, runs on hardware | `05-results.md` | a mechanism's toggle defaults on only after it wins on a device |

## Direction (Gate 1, 2026-10-12)

**proot as the safety net, native wherever it can be, with the app as the container.** This is
rank 1 in `02` §9.1, and it matches `01` §8. Each layer is useful on its own. Each one leaves
proot's job smaller, until proot is attached only to the process trees that still need it.

| layer | what | why it pays |
|---|---|---|
| **1. App as the container** | The Android app owns the environment: binds and stand-in files (already done), process supervision, and the game tree's proot started from a separate `:game` Android process. Also ADPF hint sessions for gamescope and game threads, `setGameState(loading)`, and a uclamp trial. It detects the phantom-process limit and Android 17's MemoryLimiter | A second tracer measured 2.5× the trap throughput, so Steam/CEF bursts stop stalling a game load. It gives the game its own process state and memory budget, and Android's supported CPU boosts |
| **2. In-process first** | Fold the fast path into `libblsession.so`. Apply `libblaudit` session-wide. Probe the app seccomp filter at session start, in a forked child, and use `openat2(RESOLVE_IN_ROOT)` where Android 16 QPR1+ allows it | Fewer libraries per exec, fewer `ld.so` misses, and kernel-exact path resolution on new devices |
| **3. proot-aware glibc** | Rebuild the rootfs's glibc (same version as Arch's): it never issues Android-blocked syscalls, and its internal path entry points (`ld.so`, fts, realpath, nss, `posix_spawn`) translate in-process | Removes the `ld.so` and libc-internal traps that dominate exec, and makes an untraced process *possible*: stock glibc dies on `set_robust_list`/`rseq` before any preload runs |
| **4. Native mode** | Processes run with no tracer. `exec` goes through an exec shim to the rootfs's `ld.so`, or a loader in the native-library directory so it survives a targetSdk bump. `/proc/self/exe` is emulated in-process. Start with the Steam client and CEF tree, the largest exec and path load at startup; proot stays the net for the game tree and anything unusual (static binaries, raw `svc`) | Startup and the Steam UI leave the tracer entirely. steamclienttermux measured 7× faster Runtime-to-first-window natively, against a slower proot than ours |

**Ruled out at Gate 1:**
- **Kernel containers** (user and mount namespaces, chroot, FUSE, fanotify): closed in GKI and the
  app seccomp filter.
- **Syscall User Dispatch:** no arm64 kernel has it.
- **seccomp user notification:** no faster than ptrace.
- **memfd exec:** SELinux denies it at every targetSdk.
- **A VM through AVF:** needs adb, mostly absent on Snapdragon, no KGSL.
- **A full in-process interception runtime** (systrap-style `SIGSYS` everywhere): signal
  virtualisation across glibc, Wine and CEF is fragile, and filters persist across exec.
- **Bionic-native Wine:** a different product, not Valve's Linux client and Proton.

**Held constant:** targetSdk 28. Every new exec path must also work from the native-library
directory, so a targetSdk bump stays possible.

**Out of scope:** 16 KB-page devices threaten FEX and ARM64EC whatever the environment, and are
tracked apart.

**Data before Gate 2:** a baseline from the owner's device through the harness (`harness.md`)
decides which layer goes first, and what "faster" means in numbers.

## Decision log

| date | decision | why |
|---|---|---|
| 2026-10-11 | Build on the existing proot work rather than start over | The measured analysis and the fast path are the strongest base. The fastest route to "more native" is to keep pulling work out of the tracer until it becomes optional. |
| 2026-10-11 | One work branch, `Environment-work`; `main` untouched | The owner keeps `main` as the upstream sync point. |
| 2026-10-11 | The owner authors, Claude commits and signs | Commits are credited to the owner and show as Verified; this environment signs only Claude-committed commits. |
| 2026-10-12 | No AI `Co-authored-by` trailers from here on | Upstream's `Build APK` rejects them on pull requests ("Check co-authors"), and the owner wants commits as their own. |
| 2026-10-12 | Test builds install beside the released app, as `com.droiddeck.env` | The owner tests on their own device and keeps their daily install; a fork cannot sign with the release key, so a test build could never update it anyway. |
| 2026-10-11 | At most two agents at a time | Keeps usage even and keeps each step reviewable. |
| 2026-10-12 | Build a measurement harness before any change | `01` found no device numbers for a real session (traps per second, tracer CPU, hit rates, load times); nothing could be shown to help without them. |
| 2026-10-12 | **Gate 1:** proot as the safety net, native wherever it can be, with the app as the container (four layers, above) | Both Phase 1 reports converge on it. It is the only direction that is low-risk at every step (each miss falls back to proot), works at any targetSdk, and still ends with untraced processes. |

## Documents

- `PLAN.md`: this file.
- `01-current-architecture.md`: how DroidDeck uses proot today, and which of proot's jobs are
  load-bearing (Phase 1).
- `02-landscape-and-constraints.md`: other projects, interception technology, and the limits
  Android puts on a non-root app (Phase 1).
- `harness.md`: how to take a baseline on the device (Phase 1, in progress).
- `03-design.md`, `04-spikes.md`: the layered design and its feasibility spikes (Phase 2).
