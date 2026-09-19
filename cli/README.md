# WatchWolf CLI

Build, install, run, monitor and diagnose a WatchWolf environment — from one Docker image, with
nothing else required on the host.

Replaces [`WatchWolfSetup.sh`](../WatchWolfSetup.sh), which stays around for one release as a
[deprecated shim](../WatchWolfSetup.sh) that forwards to this.

## Quick start

```bash
git clone https://github.com/watch-wolf/WatchWolf
cd WatchWolf

./cli/watchwolf build      # menuconfig-style checkbox screen on a TTY; flags otherwise
./cli/watchwolf install    # optional: register at startup, then self-test
./cli/watchwolf run
./cli/watchwolf monitor    # the live dashboard
```

There is no published `watchwolf` image, and `cli/watchwolf` never defaults to pulling one —
defaulting to a guessable registry name would let anyone who registers it first have this launcher
pull and run their image, on every user's machine, with a mounted Docker socket. It always builds
the image itself from this checkout's own `Dockerfile` (cached after the first run), so beyond
`git` and Docker, the host needs nothing else: no `jq`, `wget`, `dos2unix`, Maven or JDK.

## Commands

| Command | Does |
| --- | --- |
| `watchwolf` | Prints help. The dashboard is never opened by surprise — see `monitor`. |
| `watchwolf build` | Clone/update the repos, pull JDK images, build Spigot/Paper jars, fetch plugins, build the two Docker images. Opens a menuconfig-style checkbox screen on a TTY with no flags; fully flag-driven otherwise (`--branch`, `--path`, `--threads`, `--skip-spigot-build`, ...). |
| `watchwolf install` | Registers the startup service and the `/usr/local/bin/watchwolf` symlink, then runs the self-diagnosis unless `--skip-self-test`. |
| `watchwolf uninstall` | Undoes `install`. |
| `watchwolf run` / `stop` | Lifecycle. `run` checks the environment first unless `--skip-checks`; `stop` also sweeps up leftover `MC_Server-*` containers. |
| `watchwolf status` | One-shot plain-text picture of what's running. Safe to pipe. |
| `watchwolf monitor` | The live dashboard — a btop-style view of the managers, their servers and their bots, with per-entity logs. |
| `watchwolf logs` | Exports one `tar.gz` diagnostics bundle to `<install base>/logs/` by default (`--session`, `--last`, `--since`, `--out` to override). Includes the CLI's own run logs — see below. |
| `watchwolf doctor` | Self-tests: fast static checks (tier 1), then the real WatchWolf-Tester integration suites against a live environment (tier 2, `--quick` to skip). |
| `watchwolf update` | Fast-forwards this checkout (rebuilding the image if it moved) and, if an install exists, its ServersManager/ClientsManager clones. Never merges, rebases, or discards local work — a checkout with commits the remote lacks is reported, not touched. |

Every command accepts `--path <dir>` (default `$HOME/WatchWolf`) to point at a different install.

## Downloading a single server version

The menu (`watchwolf build` on a TTY with no flags) lets you pick individual Spigot/Paper versions
under `Server jars --->`. From flags, name the version directly:

```bash
./cli/watchwolf build --paper 1.20.4     # downloaded from fill.papermc.io -- seconds
./cli/watchwolf build --spigot 1.20.4    # built locally with BuildTools -- about an hour
```

Either accepts a comma list, `all`, or `newest:<n>` (e.g. `--paper newest:3`) in place of one
version. Naming only `--paper` (or only `--spigot`) leaves the other untouched — nothing is built
or downloaded for it, since neither defaults to any version without being asked. The jar lands in
`<install base>/ServersManager/ci/release/server-types/{Spigot,Paper}/<version>.jar`.

This still runs the rest of `build` (cloning the managers, pulling JDK images, plugins, the two
Docker images) unless you skip those too, e.g. `--skip-tester --skip-self-test`. `--dry-run` lists
every step a given set of flags would actually perform, so nothing runs by surprise.

## Installing from the menu

`watchwolf build` on a TTY with no flags opens the checkbox screen. Arrows move, space toggles,
Enter descends into a submenu, `?` explains the highlighted row, and the last row is
`< Start build >` (`s` still works from anywhere).

`F8`/`F9` tick and untick everything under the menu you are standing in, **submenus included** — so
F8 at the top level selects every server jar and plugin two levels down, and F8 on `Server jars`
covers both Spigot and Paper. Rows locked by a self-test suite (below) are left alone.

The install that follows is drawn, not printed: an overall bar, the step list with what each one is
waiting on, and -- while BuildTools runs -- **a row per selected Spigot version**: the ones being
compiled spin, the ones past the `--threads` limit sit there as `waiting`, so the whole of what you
asked for is visible from the first frame. BuildTools reports no percentage, so nothing pretends to
know one. Two keys matter while it runs:

| Key | Does |
| --- | --- |
| `q` | Abort, after a confirmation. Nothing already finished is undone; re-running carries on from where it stopped. Spigot builders already started keep going in their own containers either way. |
| `b` | Keep installing without you. You get your prompt back, the install continues in a detached container (`docker logs -f WatchWolf_install` to watch it), and the **next** `watchwolf build` opens with how it ended and an `< OK >` before the menu. |

Flag-driven runs (`--spigot`, `--skip-tester`, a non-TTY, ...) keep the plain one-line-per-step
output, unchanged.

### Self-test suites hold the jars they need

Every self-diagnosis suite starts specific servers — `ITWorldLoaderShould` runs against Spigot
1.8.8, `ITServerStarterShould` against four Spigot and four Paper versions, and so on. Tick a suite
and those versions are ticked for you under `Server jars` and **locked**: greyed, with the suite
that is holding them named on the row.

```
  [*] 1.8.8    -- locked: ITWorldLoaderShould needs it (untick that suite to release)
  [*] 1.20.4
```

Untick the suite and each row goes back to exactly what it was before. Versions already on disk are
left alone — the suite will find their jars, and re-ticking them would mean rebuilding, for an hour,
something that is already there. If a suite needs a version the remote index does not offer at all,
the suite's own row says so (`NOT OFFERED: Spigot 1.8.8`) rather than leaving you to find out an
hour later.

## What the CLI writes about itself

Every run leaves a log in `<install base>/.watchwolf/run-logs/`, one file per run, named
`<timestamp>-<command>.log`:

```
watchwolf build
started      2026-09-04T16:07:16Z
install base /home/me/WatchWolf
branch       dev
------------------------------------------------------------------------------

plan
    branch                 dev
    spigot versions        [1.8.8, 1.20.4]
    ...

16:07:17  [v] (3/13) Clone WatchWolf-ServersManager
16:07:17  [v]   -> ok
16:07:19  [v]   Spigot 1.20.4: queued
16:08:02  [e]   Spigot 1.8.8: no jar was produced
16:08:02  [e]      remedy: BuildTools needs network access and about 1.5GB free. ...
```

It matters most where the terminal cannot help: the drawn install prints nothing while it runs (a
full-screen UI owns the terminal), and an install sent to the background finishes in a container
nobody is watching. Detail is written whether or not you passed `--verbose`; the per-second
heartbeats are not, so the file stays readable. The newest 20 runs are kept.

**A Spigot version that fails to build leaves its whole BuildTools console** next to that run's log,
as `<timestamp>-spigot-<version>.log`, with the last dozen lines quoted inline in the failure. The
builder's container is kept as well (it is removed only when its jar comes out good), so
`docker logs Spigot_build_<version>` still works — but the file outlives it.

`watchwolf logs` puts the newest 10 run logs in the bundle under `cli-runs/`, and any leftover
`Spigot_build_*` container's log under `containers/`, so a bug report says what the installer did
as well as what the containers did.

## The dashboard

`watchwolf monitor` is two levels, deliberately:

- **Overview** — a tree of the two managers and their children (Minecraft servers, client bots),
  with ports and state. No log lines here; the whole height goes to the inventory.
- **Entity view** (press Enter on a row) — that one entity's facts, and its log, live. `Esc` goes
  back. `e` exports a full diagnostics bundle from either level.

Client bots are not Docker containers — they are threads inside the single `ClientsManager`
container — so their rows are assembled from the container's own listening sockets (authoritative)
and its stdout (which names them, when it can). A row you can't be sure about is marked accordingly
rather than guessed at.

## Building this module yourself

Dockerized, same as every other WatchWolf repo — the host needs only Docker:

```bash
./ci/build.sh [--preclean] [--image]   # -> target/watchwolf-cli.jar, optionally the Docker image
./ci/tests.sh --unit                   # hermetic: no Docker socket, no network
./ci/tests.sh --validation             # naming conventions + code checks
./ci/tests.sh --nonfunctional          # timing budgets (e.g. dashboard input latency); own suite
./ci/tests.sh --integration            # needs a reachable Docker daemon
./ci/validator.sh                      # alias for --validation
```

See [`ci/README.md`](ci/README.md) for the three-suite layout, and [`AGENTS.md`](AGENTS.md) for
the module's internal design (the seams, the step/verification framework, the two TUI models).
