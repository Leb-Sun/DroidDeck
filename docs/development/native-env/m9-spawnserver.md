# M9: `SpawnServer`

`03-design.md` §2.1 and §7 (M9). A refactor with no performance change: `BwrapSpawner`'s socket
becomes the app's one service for starting a process tree that the guest asks for, so that M10's
game tree and layer 4's native trees can use it as later kinds.

## What moved

- **`runtime/SpawnServer.kt` (new)** holds what used to be `BwrapSpawner.start`, `serve` and
  `frame`: the abstract socket, the accept loop with one thread per stand-in, the `SO_PEERCRED`
  check against the app's uid, the frame codec, and dispatch on the request's `kind`. It also
  receives the stdio fds a stand-in may send as `SCM_RIGHTS` (`getAncillaryFileDescriptors()`).
  They reach the handler in `Request.fds` and are closed when the request ends; no kind uses them
  yet.
- **`runtime/BwrapSpawner.kt`** keeps `plan`, `bindsFor`, `hostPath`, `gpu`, `preload`,
  `browserSandboxes` and `run`, and is registered as the `sandbox` handler in `App.onCreate`. The
  sandbox gets the same proot options, binds, environment, exit status and `bwrap-<id>-watch`
  thread as before. Its `cache/bwrap` cleanup also runs at the same point: once the socket is
  bound.
- **The socket is `\0<packageName>.spawn`**, not the fixed `\0com.droiddeck.launcher.bwrap`, so
  the release and `com.droiddeck.env` installed side by side no longer collide (`01` §7).
  `SessionService` (the session and its terminal) and `GuestCommand` (the store's Flatpak work)
  put `BL_SPAWN_SOCKET=<name>` into the guest environment.
- **`droiddeck-bwrap`** sends `"kind": "sandbox"`. It reads `BL_SPAWN_SOCKET` from its own
  environment. Failing that, it walks up the process tree and reads each ancestor's
  `/proc/<pid>/environ`. It uses the old fixed name only when no ancestor has the variable.

  The walk is needed because Flatpak starts bwrap with an empty environment and passes the
  sandbox's environment as `--setenv` arguments. Its ld.so cache and extra-data sandboxes get
  only a minimal environment, without `BL_*` (read in Flatpak's current source,
  `common/flatpak-run.c` and `flatpak-dir.c`). The parent, Flatpak or whatever started it,
  still has the variable.

## Wire format

Every frame is one kind byte, a big-endian 32-bit length, and the payload. The comment at the
top of `SpawnServer.kt` is the reference.

| frame | direction | payload |
|---|---|---|
| `R` | stand-in → app | the request as JSON: `{"kind": "sandbox" \| "game" \| "native", …}`. A request with no kind is a sandbox. Stdio may come with it as `SCM_RIGHTS` |
| `K` | stand-in → app | a signal the stand-in received, in decimal |
| `P` | app → stand-in | the tree's pid, in decimal |
| `O` | app → stand-in | output bytes: a sandbox's stdout and stderr, merged |
| `E` | app → stand-in | an error message, UTF-8 |
| `X` | app → stand-in | the exit status, in decimal; the last frame |
| `F` | app → stand-in | fall back: run the command locally; the reason, UTF-8. Legal only before `P`, and the last frame |

What the app answers:

- **A sandbox:** `P`, then `O` as the sandbox writes, then `X`. If the sandbox cannot start, `E`
  then `X 1`. All of this is unchanged.
- **An unknown kind, a first frame that is not `R`, or bad JSON:** `E` then `X 1`.
- **`game` or `native`, which nothing serves yet:** `F`.
- **A negative length, or one over 64 MiB:** treated as garbage, and the connection ends.

On `SIGTERM`, `SIGINT`, `SIGHUP`, `SIGQUIT` or `SIGUSR1`, `droiddeck-bwrap` sends `K` without
blocking, then exits exactly as before: status 143 for the first three, death by the signal for
the other two. The sandbox's watch thread skips `K`; the socket closing still stops the sandbox.
On `F`, the stand-in prints the reason and exits 1, because a sandbox has no local way to run.

## Device check

1. Run a Flatpak app from the front end and from the desktop, and install an app that uses
   extra data from the store. Each works as it did before M9.
2. Install the release beside `com.droiddeck.env`. Until the release has M9, it still listens on
   `@com.droiddeck.launcher.bwrap`.
   1. Open the release first, then the test build. Run a Flatpak app in each. Before M9 the
      second app could not bind the shared name, and its sandboxes reached the first app, which
      refused them because of the uid check.
   2. Force-stop both apps, open them in the opposite order, and run a Flatpak app in each again.
   3. Both apps start Flatpak apps in either order, and logcat has no `SpawnServer: cannot listen
      on` line.
   4. If adb can read `/proc/net/unix`, `adb shell grep -E 'spawn|bwrap' /proc/net/unix` lists
      `@com.droiddeck.env.spawn`.
