# AGENTS.md — WatchWolf CLI

The `cli/` module: a Java 17, picocli-based application that replaces
[`../WatchWolfSetup.sh`](../WatchWolfSetup.sh). Builds, installs, runs, monitors and diagnoses a
WatchWolf environment, shipped as a Docker image so the host needs nothing but Docker.

Lives inside the WatchWolf standard repo (`watch-wolf/WatchWolf`), branch `dev`, alongside `API/`.

## What lives where

| Path | Role |
| --- | --- |
| `watchwolf` | The launcher script. Always builds the CLI's image itself from this checkout's `Dockerfile` -- deliberately no fallback to a published image, since there isn't one and defaulting to a guessable registry name would be a supply-chain risk. Must be run from a checkout, not fetched standalone. |
| `Dockerfile` | Multi-stage: `maven:3.9-eclipse-temurin-17` builder → `eclipse-temurin:17-jre` runtime with `docker` CLI + compose plugin (Docker's static binaries, not a distro apt repo — see the Dockerfile's own comment for why) and `git`. |
| `ci/{build,tests,validator}.sh` | Dockerized build/test verbs, same shape as every other WatchWolf repo's `ci/`. See [`ci/README.md`](ci/README.md). |
| `ci/tests-external.sh` | Builds the Docker image and runs only the `IT*` classes that check an *external* dependency still responds as expected (published versions/plugins, JDK images) -- never the slow per-version build/download itself. See `ci/README.md`'s own section on it. |
| `src/main/java/dev/watchwolf/cli/` | The application. See the package map below. |
| `src/test/java` | Unit tests (`*Should`), hermetic — no Docker, no network, no filesystem beyond a temp dir. Asserts correctness. |
| `src/integration-test/java` | System tests (`IT*`) — need a reachable Docker daemon. Asserts correctness. |
| `src/validation-test/java` | Code checks (`*Should`) — naming conventions, the pure-logic boundary, the Core drift check. Asserts repo conventions. |
| `src/nonfunctional-test/java` | Non-functional tests (`NF*`), hermetic — no Docker, no real terminal (driven over a Lanterna `DefaultVirtualTerminal`). Asserts **wall-clock timing**, not correctness — its own category, its own Maven profile (`-P nonfunctional-test`), its own report directory; never folded into the unit suite. |
| `docs/` | Architecture diagrams as **PlantUML** (`.puml`) source, not images — render with any PlantUML tool/plugin rather than committing a picture that silently goes stale next to the code it describes. One file per flow (e.g. `build-server-jars.puml`, `watchwolf-update.puml`, `install-ui.puml`); add a new one for a flow that's genuinely hard to follow from the code alone, not for every class. |

## Package map

| Package | Responsibility |
| --- | --- |
| `command` | picocli `@Command` classes: one per subcommand, plus `CliContext` (wires every seam's real implementation together — the only place in the app that names a concrete class) and `GlobalOptions`. |
| `layout` | `InstallLayout` / `RuntimeFlavor` — pure `Path` algebra over an install base. Zero I/O. |
| `docker` | `DockerFacade` (the seam) + `DockerJavaFacade` (real, over `docker-java` 3.3.6 — the same client and version WatchWolf-ServersManager already drives the daemon with) + value types (`ContainerSnapshot`, `PortBindingInfo`, `DaemonInfo`, `RunSpec`) + `ComposeProject` (shells out to `docker compose` for the ServersManager, the one thing `docker-java` can't do). |
| `proc` | `CommandRunner` (the seam) + `ProcessCommandRunner` (real) + `GitRepository` (clone/update/verify a checkout) + `CommandResult`. |
| `io` | `FileGateway` (the seam) + `NioFileGateway` (real) + `JarInspector` (is this actually a runnable jar / a Bukkit plugin, not just a same-shaped file). |
| `remote` | `HttpFetcher` (the seam) + `JdkHttpFetcher` (real, with retry/backoff and a stage-then-rename download) + the API clients (`SpigotHubClient`, `PaperApiClient`, `WatchWolfWebClient`). |
| `model` | Pure value types and policy: `McVersion`, `JavaImageCatalog` (deliberately duplicates WatchWolf-Core's `DockerUtilities.getJavaVersion` — see its Javadoc), `UsualPluginJar`, `ServerTypeVersion`, `BuildPlan`, `TesterSuiteCatalog`, `Confidence`, `SessionInfo`, `ClientLogEvent`. |
| `parse` | Pure `String -> model` parsers: `InfoTxtParser`, `UsualPluginNameParser`'s logic (in `UsualPluginJar`), `SpigotVersionListParser`, `PaperVersionListParser`, `ClientsManagerLogParser` + `ClientsManagerLogReader`, `ProcNetTcpParser`, `ContainerNames`. |
| `inventory` | `EnvironmentScanner` builds an `EnvironmentSnapshot` from `DockerFacade` + `InstallLayout`; `ManagerStatus`/`McServerStatus`/`ClientStatus`; `ClientDiscovery` + `SocketAndLogClientDiscovery` (see below); `ServerJarInventory`. |
| `net` | `HostInterfaces` (candidate addresses, ranked — the whole point of this effort), `AddressCandidate`/`AddressClassifier`, `PortProbe`. |
| `step` | The install engine: `Step`, `Verification`, `StepContext`, `StepGraph` (topological sort, cycle/duplicate detection), `StepRunner` (verify-before-and-after, "performed but unverified" as a distinct outcome), `HostAction` (see below). `step.build` and `step.install` hold the concrete steps and `StepCatalog` assembles the graphs. |
| `log` | `RunLog` — every run writes itself to `<base>/.watchwolf/run-logs/<timestamp>-<command>.log`, plus the `RunLogProgressSink`/`RunLogStepReporter` decorators that tee the existing events into it. Appends line by line (never buffers to the end), records state changes but not heartbeats, and degrades to a no-op rather than ever failing a command. |
| `progress` | `ProgressSink` (the seam) — every slow operation announces itself, names the host it's waiting on, and reports a heartbeat. Nothing calls `System.out` directly outside this package. |
| `doctor` | `Check`/`CheckResult`/`DoctorReport`, `Tier1Suite` (fast static checks), `Tier2Runner` (shells out to `WatchWolf-Tester/ci/tests.sh`), `CompatibilityMatrixSource` (currently always `AbsentMatrixSource` — see below). |
| `bundle` | `BundleWriter` + `ManifestBuilder` — the diagnostics `tar.gz`, reused by `logs`, `doctor` on failure, and the dashboard's `e` key. |
| `tui` | `Async` (the four states of a value being fetched, so a menu never freezes on network I/O), `Theme`/`Painter`/`TerminalCapability`. `tui.menu`, `tui.monitor` and `tui.install` each split model (pure, unit-tested) from screen (Lanterna, paints the model and turns keys into calls). |

## Conventions and gotchas

- **No side effect outside a seam.** `DockerFacade`, `CommandRunner`, `FileGateway`, `HttpFetcher`
  are the only doors to Docker, subprocesses, the filesystem and the network respectively.
  `KeepPureLogicPureShould` (in `src/validation-test`) enforces this for `model/`, `parse/`, and
  the two TUI models by scanning for forbidden imports — it is not a convention you have to
  remember, it is a red build. `FakeDockerFacade` in `src/test/java/.../fake/` is an in-memory
  Docker daemon; construct whatever environment a test needs with its builder rather than reaching
  for a real socket.
- **Every step declares a `Verification`, and it is checked twice** — before `perform()` (already
  satisfied → the step is skipped, which is what makes `build` idempotent instead of deleting and
  re-cloning the way `WatchWolfSetup.sh --build` did) and after. A step whose work succeeded but
  whose verification then fails is reported as `VERIFY_FAILED`, distinctly from `FAILED` — that
  distinction is the entire reason this framework exists over a plain script. See
  `src/main/java/dev/watchwolf/cli/step/StepRunner.java` for the exact sequencing.
- **Every failure carries a remedy, and it's enforced at construction, not by convention.**
  `StepFailedException`/`VerificationFailedException` throw `IllegalArgumentException` if
  constructed with a blank `remedy`. A step that cannot tell the user what to do about its failure
  does not compile a working failure path.
- **Client bots are not Docker containers.** They are Python threads inside the single
  `ClientsManager` container. `SocketAndLogClientDiscovery` merges two signals of unequal quality —
  the container's own `/proc/net/tcp` (authoritative: which ports are actually listening) and its
  stdout (inferential: which username goes with which port, only when the log's `Starting client` /
  `Client started` lines are unambiguously adjacent). The listening-port set is the truth; a row is
  never synthesised from the log alone. See that class's Javadoc before changing it — the two
  gotchas it documents (why host-side port probing is useless, and why adjacent-line pairing can
  lie under concurrent Tester connections) are both things a previous version of this got wrong.
- **The dashboard is two levels on purpose.** The overview (`MonitorModel`) carries no log lines at
  all; logs exist only on the level-2 `EntityView`, reached with Enter. Don't add a log pane to the
  overview — that was an explicit design decision, not an oversight.
- **`JavaImageCatalog` duplicates WatchWolf-Core's `DockerUtilities.getJavaVersion` on purpose** —
  see the class's own Javadoc. `MinecraftJavaVersionsMatchesCoreShould` is the safety net; it skips
  (not fails) inside `ci/tests.sh`, since that only mounts this module's own directory, and is a
  real check when run against the full monorepo checkout.
- **There is no compatibility matrix yet.** `doctor`'s version check always reports `SKIP` via
  `CompatibilityMatrixSource.AbsentMatrixSource`, and `DoctorReport` exits non-zero only on `FAIL`
  — a check that never ran must not silently block an install. `--strict` promotes `WARN`/`SKIP` to
  failures; that is the flag to flip once a real matrix (`plan 2` Stage 4) ships as a
  `CompatibilityMatrixSource` implementation.
- **The launcher's identity-mount scheme is load-bearing.** The CLI runs in a container but hands
  the daemon bind-mount sources like `-v <base>/...:/Versions`; the daemon resolves those on the
  *host*. The launcher mounts the install base, `$HOME/.m2` and the cwd at their own identical
  absolute paths, so a `Path` computed in Java is valid both for reading (container view) and for
  `docker run -v` (host view). `PreflightDockerStep` proves this holds with a sentinel file before
  any real work starts — read its Javadoc before touching the mount scheme.
- **Root-owned host state goes through `HostAction`, never `sudo` from inside the container.**
  Writing `/etc/systemd/system/watchwolf.service` or the `/usr/local/bin/watchwolf` symlink is
  rendered into a script and the process exits `ExitCodes.HOST_ACTION_REQUIRED` (10); the launcher
  prints the script in full and asks before running it. Reading a root-owned `logs/` (written by
  the ServersManager container, which runs as root — most images still leave it world-readable,
  but nothing guarantees it) goes through `InternalCopyCommand`, a short-lived `--user 0` helper
  container from this CLI's own image (`BundleWriter.readableLogsRoot()`, wired via
  `RootHelperConfig`) — still no `sudo`. Deliberately scoped to `logs/` only, never `tmp/`: `logs/`
  structurally only ever holds `info.txt`/`latest.log`, so copying the whole tree can never sweep up a jar,
  whereas `tmp/<id>/` holds `server.jar` and the plugin jars, so its
  four named config files keep their plain per-file graceful skip instead.
- **The CLI keeps its own logs, and the bundle collects them.** `CliContext` opens a `RunLog` per
  run and wraps the progress sink and step reporter in it, so nothing at a call site has to
  remember to log. Three cases made this necessary: the drawn install cannot print while it runs, a
  backgrounded install has no terminal at all, and a bundle that describes everything except the
  run that produced it is missing half of what "it installed wrong" needs. `BundleWriter` adds them
  under `cli-runs/`. Anything genuinely per-second (`update`, `taskUpdate`) is deliberately *not*
  logged — see `RunLogProgressSink`'s Javadoc. Output too long to inline but too valuable to lose
  goes through `RunLog.attachment`, which writes it beside the run's own log under the *same*
  timestamp: `BuildSpigotJarsStep` uses it for a failed BuildTools console, which previously
  reached the user as a single line and otherwise existed only inside a container the verification
  then told them to delete.
- **A menu session gets a drawn install; flags get printed output.** `BuildCommand` decides on
  `usedMenu`: the menuconfig path runs `StepRunner` on a worker thread with `TuiProgressSink`/
  `TuiStepReporter` writing `InstallProgressModel`, while `InstallProgressScreen` only paints
  snapshots of it (every model method is `synchronized`; nothing else in the class is concurrent).
  Steps report concurrent sub-operations through `ProgressSink.taskStarted/taskUpdate/taskFinished`
  — a row per Spigot jar (queued ones announced up front, so the list never grows over the
  afternoon) rather than an aggregate that cannot say which version is stuck. Those default to
  silence or a plain detail line, so a stream sink needs no changes. **Nothing under a
  Lanterna screen may print**: `TuiProgressSink.detail` is a deliberate no-op, and the summary and
  remedies are printed only after the screen has closed.
- **Stopping an install is cooperative and coarse, and that is deliberate.** `CancelSignal` is
  polled by `StepRunner` between steps and by `BuildSpigotJarsStep` once a second inside its poll
  loop; nothing is interrupted mid-operation, because every step is idempotent and verified, so
  stopping at a boundary and re-running resumes rather than repeats. Aborting therefore leaves
  running `Spigot_build_<version>` containers alone — and `BuildSpigotJarsStep` adopts a live one
  and removes a dead one instead of colliding with Docker's "name already in use".
- **"Send it to the background" is an exit code, not a fork.** A foreground `docker run -it` cannot
  detach itself, so the CLI writes the resolved plan to `.watchwolf/install.yaml`
  (`BuildPlanFile`) and exits `ExitCodes.BACKGROUND_REQUESTED` (11); the launcher then re-runs the
  same image detached as `WatchWolf_install` with the hidden `build --resume-background`. That run
  leaves an `InstallRunRecord` in `.watchwolf/last-run.txt`, which the next `watchwolf build` shows
  in an `AcknowledgeScreen` before the menu and then deletes. Same shape as exit 10 — a thing the
  process cannot do to itself, handed to the launcher through an exit code and a file.
- **This filesystem needs the isolated-build path.** `ci/common.sh`'s `ww_needs_isolated_build`
  detects a network filesystem (this checkout's own SMB mount included) and builds on the
  container's own disk, copying back only the finished jar and reports — see
  [`ci/README.md`](ci/README.md#a-note-on-this-filesystem) for why.
- **Both TUI loops drain every buffered key before drawing, and draw only after handling input.**
  `MonitorScreen.runOn`/`MenuConfigScreen.runOn` loop `pollInput()` until it returns `null`,
  *then* draw once. A single `pollInput()` per sleep looked fine for an isolated keypress but
  meant a burst of taps — completely normal when moving a selection — drained at one key per
  sleep, taking most of a second to visibly catch up; drawing before consuming the key added a
  further frame of lag on top. Both are guarded by
  `NFMonitorScreenResponsivenessShould`/`NFMenuConfigScreenResponsivenessShould`
  (`src/nonfunctional-test/java`) — its burst test is what actually catches a regression here; a
  single-keypress test alone would not have. If you touch either loop, keep the drain-before-draw
  order and re-run `./ci/tests.sh --nonfunctional`.
- **Loop-iteration counters must not drive animation or periodic-task timing.** Both screens used
  to gate the spinner, the periodic file-log reload, and the status-message auto-clear on a
  `frame` counter incremented once per loop iteration; when the idle-poll interval dropped (see
  above) those all sped up by the same factor. They are wall-clock-based now
  (`System.currentTimeMillis()`), independent of however fast the loop actually polls.
- **A file log's periodic re-read must use `LogRing.replaceAll()`, never `clear()`+`addAll()`.**
  `MonitorScreen.reloadFileLog` runs on every ~1s re-read of `logs/<id>/latest.log` (no push
  notification exists for a file), and `clear()` resets `scrollBack` to 0 — which used to snap the
  view back to "following" and jump to the tail on that same ~1s cadence even while someone was
  mid-scroll reading history. `replaceAll()` swaps the buffered lines in place and leaves
  `scrollBack` untouched. `clear()` is still correct (and still used) when switching to a
  *different* entity, where starting at the live edge is exactly what's wanted — the two calls are
  not interchangeable. `LogRingShould` only tests `LogRing`'s own contract and would pass either
  way; `MonitorScreenLogViewingShould` (`src/test/java`, drives the real loop over a
  `DefaultVirtualTerminal`) is what actually catches a caller regressing to `clear()`+`addAll()`
  here. Relatedly, `logIsLive()` on `EntityView` gates both this periodic reload and the `f` key —
  a finished server's log file will never grow again, so "following" it is offered nowhere.
- **`BuildPlan.selectedUsualPlugins()` distinguishes "unresolved" from "explicitly empty".**
  Internally the field is `null` until the menu's async fetch to watchwolf.dev actually loads
  (`MenuModel.usualPluginsLoaded`); the getter turns that into `Set.of()` for callers, but
  `usualPluginsSelectionResolved()` is what `DownloadUsualPluginsStep.isApplicable`/`perform`
  actually branch on. Collapsing the two (e.g. treating an empty set as "not resolved") would make
  the flags-only path's default of "download every usual plugin" indistinguishable from someone
  deselecting all of them with F9 in the menu — one must download everything, the other nothing.
  See `MenuModel.selectedUsualPluginsOrNullIfUnresolved` and `BuildPlan.selectedUsualPlugins`'s
  Javadoc before changing either side of this.
- **`watchwolf update` needs the checkout root identity-mounted, which no other command does.**
  `cli/watchwolf` mounts the repo root (the directory containing `cli/`, detected by `.git` sitting
  next to `cli/`) at its own path and passes it as `WW_REPO_ROOT`, the same identity-mount trick
  used for `WW_BASE`/`$HOME/.m2`/`$PWD` -- see the launcher's own header comment. Without this the
  container has no filesystem access to the checkout that built its own image, which is why every
  other command never needed it. `UpdateCommand` rebuilds the image via
  `DockerFacade.buildImage(repoRoot/cli, WW_IMAGE, ...)`, reusing the exact mechanism
  `BuildClientsManagerImageStep` uses -- it only works because `repoRoot` is identity-mounted, so
  the daemon resolves that build context on the host.
  **The rebuild is unconditional**, not gated on the pull having fast-forwarded: the point of
  running this on the CLI's own checkout is "make the image match what's on disk right now", and
  the working tree can carry local edits (committed or not, pushed or not) that `git fetch` would
  never see -- gating the rebuild on a fast-forward would silently skip exactly that case, which is
  what actually happened the first time this was tried (see the commit that fixed it). Not free
  either way: `buildImageCmd` goes through the classic Docker builder, not BuildKit, so it does not
  reuse a `docker build` CLI run's cache and takes about a minute even with nothing changed.
- **`GitRepository.pullFastForwardOnly` must never call `git merge` without first proving the
  fast-forward is clean.** It exists specifically so `watchwolf update` is safe to run on a
  checkout with active, uncommitted-or-not development on it: commits local to the checkout that
  origin does not have are reported (`Diverged`, or `UpToDate` if origin simply hasn't moved past
  where they branched) and never touched -- there is no code path in this method that runs a real
  merge, a rebase, or a branch switch. If you touch this, re-run
  `ITGitRepositoryPullFastForwardOnlyShould`, which proves all three outcomes against a real local
  `git`, not just a scripted fake.
- **Spigot and Paper are submenus of individually selectable versions, the same shape as Usual
  plugins** -- `ID_SPIGOT`/`ID_PAPER` are `MenuNode.submenu(...)`, not a flat on/off checkbox the
  way they used to be. `BuildPlan.buildSpigot()`/`buildPaper()` are derived from
  `!selectedVersions(ID_X).isEmpty()`, not a separate flag, for the same reason server jars needed
  fixing in the first place: a `MenuNode.check(...)` never descends on Enter (only `SUBMENU` does),
  so the per-version children `populateVersions` was already writing into it were completely
  unreachable in the actual TUI -- selectable in the model, invisible on screen. **Whatever
  fetch-failure handling you add to Spigot/Paper must also run on `spigotLoading`/`paperLoading`,
  not only `spigotFailed`/`paperFailed`.** `populateInstalledOnly` (called from both) is what makes
  the failure remedy's own claim -- "versions already on disk are still selectable" -- actually
  true instead of the submenu going empty the moment hub.spigotmc.org is unreachable; it has to run
  at `*Loading` too so that promise already holds while still waiting, not only once the fetch has
  failed. `MenuConfigScreenFailureHandlingShould` drives the real screen loop with a fetcher that
  fails immediately and proves the submenu stays populated, selectable and navigable -- reverting
  either `populateInstalledOnly` call independently makes it fail.
- **Every fetched Spigot/Paper version (and every usual plugin) starts selected, except one
  already built/downloaded.** `populateVersions` no longer takes a "preselect the newest N"
  count -- it never had a real caller passing anything but 0 -- and unconditionally checks
  everything not in `withInstalled`'s set. The install default is "get everything you don't
  already have" with one keypress, not "you must go pick each version by hand".
- **Spigot's and Paper's live version lists changed API/shape once already; the parsers now guard
  against it happening silently again.** `api.papermc.io/v2` stopped receiving builds at the end
  of 2025, replaced by `fill.papermc.io/v3` (see `PaperApiClient`/`PaperVersionListParser`'s own
  Javadoc for the exact shape and why family keys are never read as versions). Separately, Spigot
  changed its own numbering after 1.21 (`26.1`, `26.2`, ...), which broke the old parser two ways
  at once: it never matched anything not starting with `"1."`, **and** its unanchored regex matched
  the phantom substring `"1.1.json"`/`"1.2.json"` embedded inside `26.1.1.json`/`26.1.2.json`,
  inventing versions that were never really listed. `SpigotVersionListParser` now anchors to
  `href="..."` so a match always consumes the whole filename via backtracking, never an inner
  substring. `ITSpigotHubClientShould`/`ITPaperApiClientShould` are deliberate canaries: they
  hardcode the exact live version list and fail loudly the moment it drifts, rather than silently
  adapting -- when one fails because the list legitimately changed, update the hardcoded list, do
  not loosen the assertion. `McVersion.MIN_SUPPORTED` (1.8) is applied in both parsers, since both
  services still list versions WatchWolf was never meant to run.
- **A submenu's `[ ]`/`[o]`/`[*]` marker is computed live, never stored.**
  `MenuNode.aggregateState()` walks every `CHECK` descendant at any depth (so `Server jars`, which
  holds no checkboxes of its own -- Spigot and Paper are themselves submenus -- still rolls up
  correctly) and `marker()` reads it on every draw. This replaced a hand-maintained "nothing
  selected" annotation that had to be refreshed by remembering to call `applyConstraints()` after
  every mutation to the right list; the live version cannot go stale, so don't reintroduce a stored
  copy. The `--->` suffix that marks a row as a submenu is unrelated and still appended separately
  in `MenuConfigScreen.drawRows`.
- **A `TEXT` field's `Enter` is rejected, not silently patched over.** `MenuNode.withValidator(...)`
  attaches a `String -> Optional<String>` check (empty = valid); `MenuConfigScreen.handleTextInput`
  refuses to call `MenuModel.setValue` and leaves edit mode open when it returns an error, so
  "Parallel Spigot builders" can no longer take an empty box or letters and have them quietly
  become 1 the moment the plan is built -- the user sees the rejection and stays in the field to
  fix it. `MenuModel.toBuildPlan()`'s `parseIntOr` fallback stays as a second line of defence for
  any caller that sets a value directly, bypassing the screen (as tests do) -- the two are not
  redundant, they guard different paths. See `MenuConfigScreenTextValidationShould` for the
  screen-level proof; a `MenuModel`-only test would not have caught a regression in the rejection,
  since that logic lives in the screen.

## Git conventions

Same as every other WatchWolf repo:

- **`dev` is the working branch.** `master`/`main` is downstream of it — never commit there
  directly, never open a PR against it.
- **One branch per change, named for its kind:** `fix/<topic>` for defects, `feature/<topic>` for
  new work. Branch from `dev`.
- **Always open a PR into `dev`.**
