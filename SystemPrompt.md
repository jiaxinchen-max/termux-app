# Project Agent System Prompt: idap / termux-app

## What This Project Is
`termux-app` is a fork of the Termux terminal emulator, extended beyond core terminal functionality with:
- **X11 desktop environment** (`termux-x11` module): runs a full Linux desktop on Android (Xserver + containerized Wine desktop management).
- **Local Games hub** (`games` module): runs Windows games on Android via Wine/Box64, with two runtime backends (see below).
- **Legacy TermuxBox feature** (in the `app` module's `com.termux.app.activities.termuxbox` package): an earlier wine/box64 integration entry point, still maintained.
- Core terminal capabilities (`terminal-emulator`, `terminal-view`, `termux-shared`, etc.).

Gradle modules: `app`, `termux-shared`, `terminal-emulator`, `terminal-view`, `shell-loader` (+ `shell-loader:stub`), `termux-x11`, `float-ball`, `termux-render`, `games`. Confirm which module a change belongs to before touching anything — don't assume everything is about `games`.

## Repository Structure
- Outer repo: `/home/user/workspace/idap-2031193509335416832`
  GitLab: `git.garena.com/shopee/seamoney-data/data-servers/apps/idap`
  Working branch: `feat/Idap-test` (the local clone only fetches `master` by default; run `git fetch origin feat/Idap-test` first to see the full history and the correct merge-base).
- Inner submodule: `submodule/` (the full termux-app fork)
  GitHub: `jiaxinchen-max/termux-app`
  Bound branch: `master-x11-submodule-tmp` (note: distinct from `master-x11-submodule` — the names are easy to confuse).

## Known Gotchas
- **The submodule working tree may be empty** (`submodule/` contains only an empty directory, no `.git`). This is a known side effect of checking out other branches in the outer repo — it does not mean data was lost. Fix:
  ```bash
  cd /home/user/workspace/idap-2031193509335416832
  git submodule update --init submodule
  ```
  Before touching any code, confirm `submodule/.git` exists and the branch is correct.
- **`submodule/local.properties` is frequently missing** (not preserved across submodule re-clones). Recreate when needed:
  ```bash
  printf 'sdk.dir=%s\n' "/home/user/.local/android-sdk" > submodule/local.properties
  ```
- Native builds require **bison + flex** (needed by `termux-x11`'s xkbcomp), not installed by default: `sudo apt-get install -y bison flex`.
- `termux-x11` has ~15 nested cpp submodules (libX11, pixman, xserver, etc.), which are large and not always needed — only run `git submodule update --init --recursive` when you need a full `assembleDebug` or are touching X11-related native code.
- After a fresh submodule clone, the first gradle run triggers a long sequence of Gradle wrapper re-download + on-demand SDK component (NDK/build-tools/platform) reinstallation logging (even if already installed on the machine). This is normal — be patient, it does not mean the build is stuck.

## Push Conventions (follow strictly)
- **Never push proactively** unless the user explicitly says "push" / "commit and push".
- **Never merge to master, never open/create an MR** — even if the git output helpfully includes a link to create one, don't click it.
- Pushing to termux-app (GitHub) requires a user-provided PAT, used as a **one-time URL** (`git push https://user:TOKEN@github.com/...`) — never written to git config, never persisted to disk, discarded after use. **Ask the user for a fresh token every time you need to push termux-app** — never assume a previously-provided token is still available or remembered.
- Pushing to idap (GitLab) uses credentials already configured on the system — no extra setup needed.
- Outer-repo commit messages follow the existing convention: `Bump termux-app submodule: <summary>`.
- The submodule is normally in a detached HEAD state; after committing, push with `git push <remote> HEAD:<branch-name>` — don't assume you're on a named branch.

## Build Environment
- Android SDK lives at `/home/user/.local/android-sdk`; `ANDROID_HOME`/`ANDROID_SDK_ROOT` are already set in the environment.
- Already installed: NDK 25.1.8937393, platform 34, build-tools 34.0.0, cmake 3.22.1. Gradle's configuration phase will auto-install additional NDK versions as needed by other modules (e.g. `termux-render` needs 26.x) — this is expected.

## Build / Test Commands
```bash
cd submodule
./gradlew :<module>:testDebugUnitTest --no-daemon -Dorg.gradle.jvmargs=-Xmx4g   # test a single module, no native involved — prefer this day-to-day
./gradlew assembleDebug --no-daemon -Dorg.gradle.jvmargs=-Xmx4g                 # full app build, requires native submodules to be in place
```
Run the unit tests for whichever module you changed (e.g. `:games:testDebugUnitTest`, `:termux-x11:testDebugUnitTest`); only run a full `assembleDebug` when the blast radius of a change is unclear.

To determine whether a test failure was caused by your change, **compare before/after with `git stash`** rather than guessing — and don't assume some failures are "always been there, safe to ignore." If a test has been failing long-term and is confirmed obsolete, delete it outright instead of keeping it around as a noise source — keep "tests pass = BUILD SUCCESSFUL" as the clean bar.

Running gradle in the background: use `nohup ... > /tmp/xxx.log 2>&1 &` plus the `Monitor` tool watching for `BUILD SUCCESSFUL|FAILED|tests completed`. A `run_in_background` bash call may immediately report "completed" if an extra `&` gets appended outside, even though the process is still running — cross-check with `ps aux | grep -i gradle` (CPU usage > 0 means it's still working normally, not stuck).

## Emulator Access
- adb address: **`120.24.191.156:8055`** (not the usual port 5555 — that port accepts TCP connections but isn't a real adb service, and will sit in `offline` forever).
- The connection will first show `unauthorized`; **the user must manually tap the authorization prompt on the emulator screen** before it becomes `device`. Don't assume reconnecting a few times will auto-approve it — when you see `unauthorized`, tell the user to confirm on the emulator, then retry `adb connect`.
- After `adb kill-server`, you'll need to `connect` again, and may need to go through manual authorization again.
- **Do not use `adb install` to install an APK onto the emulator by default**: the files are large (tens to hundreds of MB) and network conditions are variable — large transfers are often slow or can hang. Don't initiate this on your own; confirm with the user first if installation is genuinely needed.
- **Ordinary adb debugging operations (shell commands, `logcat`, `run-as` reads/writes of small files, small `push`/`pull`) have low network overhead and are fine to use freely** — they're not subject to the restriction above.
- Inspecting app-private data: `adb shell run-as com.termux ...` (the app is debuggable).
- For logs, prefer a **one-shot `logcat -d -t N` dump + grep** over an unbounded streaming `logcat`, which can hang or burn tokens for no reason. Only use the Monitor tool when you genuinely need to watch something live, and call `TaskStop` the moment you've captured what you need.
- Relevant package names: `com.termux` (main app), `com.termux.x11`.

## Subsystem Architecture Overview

### games module (Local Games hub)
Two runtime backends:
- `GameRuntimeBackendType.GLIBC_TERMUX_BOX`: runs box64+wine directly from the Termux glibc prefix. Componentization, on-demand downloads, UI, and preflight gating are all mature here — treat it as the reference implementation pattern.
- `GameRuntimeBackendType.ROOTFS_PROOT`: a Debian 13 + proot container. This architecture is newer and still evolving — confirm the current implementation state before changing anything; don't assume some intermediate design discussed historically is the final state.

Unified component install framework (`games/src/main/java/com/termux/localgames/components/`):
- `SafeTarXzExtractor`: auto-detects xz / gzip / plain tar by file magic bytes; anything else (e.g. a `.deb`, which is an ar archive, not a tar) goes through a **raw passthrough publish** path.
- `ComponentInstaller`: download → sha256 verify → extract/raw-publish → write to an immutable version directory + receipt → (optionally, depending on backend) activate.
- The component catalog lives at `games/src/main/assets/termux-box-packages/index-v1.json`. When adding a component, `url`/`size`/`sha256` **must be real, downloadable, verified values** — never fabricated. If a placeholder is genuinely needed, the `url` must be a syntactically valid but unreachable domain (e.g. `https://example.invalid/...`), not an arbitrary string — otherwise `ComponentDescriptor`'s URL validation throws `MalformedURLException`, which **breaks parsing of the entire component catalog**, taking down every other component with it.

### termux-x11 module (X11 desktop)
Provides a full Xserver plus containerized Wine desktop management (`Container`/`WineRegistryEditor`/`WineThemeManager` etc. under the `controller` package) — a separate "container" concept from the one in the `games` module. The two share similar naming but are different implementations; be careful not to conflate them when making changes.

### app module
The main Termux app, the legacy TermuxBox wine/box64 integration (`activities/termuxbox` package), and the host-capability bridge exposed to the `games` module via `TermuxLocalGamesHostFactory`.

## Verified Device Environment Constraints (hard prerequisites for any native/runtime design work)
- **The app's private storage does not support hard links** (`ln`/`cp -al` have been confirmed to return EPERM on-device). Any design relying on "hard-link-clone a large directory to save space" is not viable on this device — use a different approach (centralized storage + references, or accept the cost of a full copy).
- `apt` inside the Debian container cannot install a specific historical version (the repo only serves the current snapshot version). **Runtime components that need multiple versions to coexist (e.g. wine) cannot rely on apt** — they need to be shipped as prebuilt portable packages extracted to independent paths, with the active version switched via environment variables at runtime.
- `dpkg-deb -x` under proot logs warnings like `tar: Cannot change mode` because proot doesn't support `chmod` (the files still get extracted correctly and this doesn't block functionality) — this noise can be avoided by preprocessing offline (unpack ahead of time and repackage into a clean tar), so the device never needs to invoke `dpkg-deb` directly.

## How to Collaborate
- **For changes where the blast radius is uncertain or turns out larger than expected, enter plan mode first** and honestly relay the actually-discovered scope to the user — don't quietly touch extra files beyond what was agreed.
- **Leave key architectural trade-offs to the user — don't guess.** This especially applies to decisions like "what granularity should data/components be organized at" or "should a new runtime model be introduced" — guessing wrong on these usually means starting over.
- **When a technical premise can be verified on a real device/emulator in a few minutes, do that instead of continuing to reason about it in the abstract.** If you're unsure whether some mechanism works in the current environment, write a minimal repro script and run it — that's faster than designing further on an unverified assumption.
- When something looks like a git anomaly (empty submodule, branch mismatch, etc.), investigate with read-only commands first to understand the cause, and confirm it isn't in-progress work being lost before taking any action — don't reach for destructive operations out of anxiety.
- Before committing/pushing, confirm the target branch (especially in the outer repo, where more than one branch is in play) to avoid pushing to the wrong branch or accidentally pushing to master.
- For tests that have been failing long-term and are confirmed not worth fixing, delete them outright rather than maintaining a "known baseline failures" list — such lists go stale and get forgotten; a simple "all tests green" bar is easier to trust.
