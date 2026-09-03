# WatchWolf CLI
## CI scripts

Every script runs Maven inside Docker, so the host needs nothing but Docker itself. This module is
Java 17 (`maven:3.9-eclipse-temurin-17`), unlike WatchWolf-Core and WatchWolf-Tester which are
Java 8 (`maven:3.8.4-openjdk-8`).

| Script | Does |
| --- | --- |
| `./ci/build.sh [--preclean] [--image]` | Build `target/watchwolf-cli.jar`; `--image` also builds the `watchwolf-cli:local` Docker image |
| `./ci/tests.sh --unit [--tests <pattern>]` | Run the unit tests — hermetic, no Docker socket, no network, no `$HOME/WatchWolf` |
| `./ci/tests.sh --validation` | Run the code checks (naming conventions, the pure-logic boundary, the Core drift check) |
| `./ci/tests.sh --integration [--tests <pattern>]` | Run the system tests (**needs a reachable Docker daemon**, see below) |
| `./ci/tests.sh --integration --skip-preflight` | ...without first checking the daemon is reachable |
| `./ci/validator.sh` | Alias for `./ci/tests.sh --validation` |

## The three suites

| | Unit | System / integration | Code checks |
| --- | --- | --- | --- |
| Source root | `src/test/java` | `src/integration-test/java` | `src/validation-test/java` |
| Naming | `*Should` | `IT*` | `*Should` |
| Runner | Surefire | Failsafe | Surefire (separate execution) |
| Maven profile | `default` | `-P integration-test` | `-P validation-test` |
| Reports | `target/surefire-reports` | `target/failsafe-reports` | `target/validation-reports` |
| Needs a Docker daemon | no | **yes** | no (one check optionally reads a sibling checkout — see below) |

Each suite writes to its own reports directory, so a failing code check never mixes into the
unit-test report. `target/site` holds the HTML summary. This is deliberately the same shape as
WatchWolf-Tester’s own `ci/` (a separate repository, so not linkable from here) — same verbs,
same profile names, same reports layout — just applied to a Java 17 module instead of a Java 8
one.

### Why the unit suite matters more here than in the other repos

This is the one WatchWolf component whose bugs are invisible until someone's install is already
half broken, so its production code is deliberately built backwards from testability: every side
effect — Docker, subprocesses, the filesystem, HTTP — sits behind an interface
(`DockerFacade`, `CommandRunner`, `FileGateway`, `HttpFetcher`), with a fake for each in
`src/test/java/dev/watchwolf/cli/fake/`. `FakeDockerFacade` is the important one: it is a mutable
in-memory Docker daemon, so a test can construct any environment — three servers and two bots, a
dead ServersManager, a container with a malformed name — in milliseconds and assert what the
install engine, `status` or the dashboard's model make of it.

`src/validation-test/java/dev/watchwolf/cli/validation/KeepPureLogicPureShould` polices this
mechanically: it fails the build if any class under `model/`, `parse/`, or the two TUI models
imports `com.github.dockerjava`, `ProcessBuilder`, `Socket`, or `java.nio.file.Files` directly. That
is the check that keeps this true a year from now, not just on the day it was written.

### The code checks

These assert things about *the repository* rather than about its runtime behaviour — naming
conventions, that every install `Step` declares a title and a verification, that the pure-logic
packages stay pure — as ordinary JUnit `@TestFactory` dynamic tests, one per file or per step, so a
violation reports individually and names the offending one:

```
nameEverySystemTestWithTheItPrefix() docker/SomeBadlyNamedTest.java
  -> SomeBadlyNamedTest.java is under src/integration-test/java but does not start with 'IT',
     so Failsafe will never run it
```

A file that breaks the naming convention is **silently never executed** by Maven, which is how
WatchWolf-Tester ended up with a suite nobody had run in years. Run `./ci/validator.sh` before
opening a PR.

One check, `MinecraftJavaVersionsMatchesCoreShould`, is a plain `@Test` rather than a dynamic
factory: it looks for a sibling `WatchWolf-Core` checkout (two directories up from this module, or
one — it checks both layouts) and, when found, parses `DockerUtilities.getJavaVersion` and fails on
drift from this module's own `JavaImageCatalog`. `JavaImageCatalog` deliberately duplicates that
logic rather than depending on WatchWolf-Core (which is not published to any resolvable repository
— see its own Javadoc for why), and this is the check that stops the duplicate from rotting
silently. It **skips**, not fails, when run through `./ci/tests.sh` — that mounts only this module's
own directory, so the sibling checkout is never reachable from inside the container. It is a real,
passing check for a developer running the full monorepo checkout on the host.

### Running the system tests

They start real, throwaway Docker containers through the mounted socket — nothing from a live
WatchWolf install, and nothing is left behind. **The script checks the daemon is reachable for
you first**, from inside the same image, network mode and socket mount the tests will use (the
maven image has no `docker` CLI, so this probes the daemon's HTTP API directly over the socket with
`curl`, which is what `docker-java` itself does):

```
[e] The Docker daemon is not reachable from inside the test container, so every
    system test would fail with 'Cannot connect to the Docker daemon'.
    Not running them.

      socket: unix:///var/run/docker.sock
      user:   you (uid 1000, groups: ...)

    Is the daemon running, and is you in the 'docker' group?
      sudo usermod -aG docker you   # then log out and back in
```

If the daemon is reachable only in some other way, pass `--skip-preflight`.

### A note on this filesystem

`ww_needs_isolated_build` in `ci/common.sh` detects when the checkout lives on a network filesystem
(CIFS/SMB, NFS) — where the thousands of small concurrent writes a Maven build makes into a bind
mount can intermittently land as zero-length files, producing a `ClassFormatError: Truncated class
file` that is a miserable thing to debug and different every time. On such a filesystem, the build
runs on the container's own disk and only the finished jar and reports are copied back: a few large
sequential writes, which these mounts handle fine. This machine's checkout is on exactly such a
mount, which is how the isolated path got exercised and fixed during this module's own development.

### Ownership

Every `ci/*.sh` script runs Maven **as the invoking user** (`--user "$(id -u):$(id -g)"`), unlike
the other WatchWolf repos' CI scripts, which run as root and leave `$HOME/.m2` root-owned as a side
effect. If `$HOME/.m2` is not writable, these scripts fall back to
`${XDG_CACHE_HOME:-$HOME/.cache}/watchwolf-cli/m2` and say so, rather than failing or requiring
`sudo`.
