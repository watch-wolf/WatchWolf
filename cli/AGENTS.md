# AGENTS.md — WatchWolf CLI

The `cli/` module: a Java 17, picocli-based application that replaces
[`../WatchWolfSetup.sh`](../WatchWolfSetup.sh). Builds, installs, runs, monitors and diagnoses a
WatchWolf environment, shipped as a Docker image so the host needs nothing but Docker.

Lives inside the WatchWolf standard repo (`watch-wolf/WatchWolf`), branch `dev`, alongside `API/`.

## What lives where

| Path | Role |
| --- | --- |
| `watchwolf` | The launcher script. The only file that has to exist on the host. |
| `Dockerfile` | Multi-stage: `maven:3.9-eclipse-temurin-17` builder → `eclipse-temurin:17-jre` runtime with `docker` CLI + compose plugin (Docker's static binaries, not a distro apt repo — see the Dockerfile's own comment for why) and `git`. |
| `ci/{build,tests,validator}.sh` | Dockerized build/test verbs, same shape as every other WatchWolf repo's `ci/`. See [`ci/README.md`](ci/README.md). |
| `src/main/java/dev/watchwolf/cli/` | The application. See the package map below. |
| `src/test/java` | Unit tests (`*Should`), hermetic — no Docker, no network, no filesystem beyond a temp dir. |
| `src/integration-test/java` | System tests (`IT*`) — need a reachable Docker daemon. |
| `src/validation-test/java` | Code checks (`*Should`) — naming conventions, the pure-logic boundary, the Core drift check. |

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
| `progress` | `ProgressSink` (the seam) — every slow operation announces itself, names the host it's waiting on, and reports a heartbeat. Nothing calls `System.out` directly outside this package. |
| `doctor` | `Check`/`CheckResult`/`DoctorReport`, `Tier1Suite` (fast static checks), `Tier2Runner` (shells out to `WatchWolf-Tester/ci/tests.sh`), `CompatibilityMatrixSource` (currently always `AbsentMatrixSource` — see below). |
| `bundle` | `BundleWriter` + `ManifestBuilder` — the diagnostics `tar.gz`, reused by `logs`, `doctor` on failure, and the dashboard's `e` key. |
| `tui` | `Async` (the four states of a value being fetched, so a menu never freezes on network I/O), `Theme`/`Painter`/`TerminalCapability`. `tui.menu` and `tui.monitor` each split model (pure, unit-tested) from screen (Lanterna, paints the model and turns keys into calls). |

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
- **This filesystem needs the isolated-build path.** `ci/common.sh`'s `ww_needs_isolated_build`
  detects a network filesystem (this checkout's own SMB mount included) and builds on the
  container's own disk, copying back only the finished jar and reports — see
  [`ci/README.md`](ci/README.md#a-note-on-this-filesystem) for why.

## Git conventions

Same as every other WatchWolf repo:

- **`dev` is the working branch.** `master`/`main` is downstream of it — never commit there
  directly, never open a PR against it.
- **One branch per change, named for its kind:** `fix/<topic>` for defects, `feature/<topic>` for
  new work. Branch from `dev`.
- **Always open a PR into `dev`.**
