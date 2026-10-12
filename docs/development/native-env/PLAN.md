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
- **Over adb.** `tools/droiddeckctl` hardcodes `com.droiddeck.launcher` and needs a package option
  before it can drive the test app.

## Phases and gates

| phase | work | output | gate |
|---|---|---|---|
| 1. Discovery | two agents in parallel: an internal audit of how DroidDeck uses proot; prior art and Android non-root constraints | `01-current-architecture.md`, `02-landscape-and-constraints.md` | **Gate 1:** pick one or two candidate architectures |
| 2. Design | a target-architecture design; feasibility spikes on the riskiest mechanisms | `03-design.md`, `04-spikes.md` | **Gate 2:** fix the milestone list; check with the owner before any large structural change (for example, rebuilding the rootfs glibc) |
| 3. Build | one milestone at a time, implementer then independent reviewer, each behind a toggle | code, tests, notes in this folder | each milestone has to pass host tests and review |
| 4. Validate | device A/B harness on `droiddeckctl` and `tools/proot/bench`, runs on hardware | `05-results.md` | a mechanism's toggle defaults on only after it wins on a device |

## Candidate directions (decided at Gate 1)

- **A. Widen the fast path.**
  - Cover the write family.
  - Hook `syscall()` for FEX.
  - Fold it into `libblsession.so`.
  - Set `GLIBC_TUNABLES`.
  - Give the game tree a second tracer.

  Incremental and low risk.
- **B. Proot-aware guest glibc.** Rebuild the rootfs's own glibc:
  - route its internal path entry points (ld.so, fts, realpath, nss) through in-process
    translation;
  - never issue Android-blocked syscalls, which ends the `SIGSYS` round trips.
- **C. Tracer-optional native mode.**
  - In-process translation covers enough that most processes run untraced.
  - `exec` goes through a userspace loader.
  - proot becomes a fallback, attached only to processes that need it.
- **D. New interception technology.** Systrap-style in-process `SIGSYS`, Syscall User Dispatch,
  binary rewriting: whatever Phase 1 shows is available to apps on arm64 Android.
- **E. The app as the container.** The Android app prepares binds and fake `/proc` and `/sys`
  files, and owns process lifetime and scheduling for the environment. It complements B and C.
- **Expected to be ruled out:** a VM through the Android Virtualization Framework. A third-party
  app is unlikely to get access, and a VM loses direct KGSL. Phase 1 confirms or overturns this.

## Decision log

| date | decision | why |
|---|---|---|
| 2026-10-11 | Build on the existing proot work rather than start over | The measured analysis and the fast path are the strongest base. The fastest route to "more native" is to keep pulling work out of the tracer until it becomes optional. |
| 2026-10-11 | One work branch, `Environment-work`; `main` untouched | The owner keeps `main` as the upstream sync point. |
| 2026-10-11 | The owner authors, Claude commits and signs | Commits are credited to the owner and show as Verified; this environment signs only Claude-committed commits. |
| 2026-10-12 | No AI `Co-authored-by` trailers from here on | Upstream's `Build APK` rejects them on pull requests ("Check co-authors"), and the owner wants commits as their own. |
| 2026-10-12 | Test builds install beside the released app, as `com.droiddeck.env` | The owner tests on their own device and keeps their daily install; a fork cannot sign with the release key, so a test build could never update it anyway. |
| 2026-10-11 | At most two agents at a time | Keeps usage even and keeps each step reviewable. |

## Documents

- `PLAN.md`: this file.
- `01-current-architecture.md`: how DroidDeck uses proot today, and which of proot's jobs are
  load-bearing (Phase 1).
- `02-landscape-and-constraints.md`: other projects, interception technology, and the limits
  Android puts on a non-root app (Phase 1).
