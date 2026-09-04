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
| `watchwolf logs` | Exports one `tar.gz` diagnostics bundle to `<install base>/logs/` by default (`--session`, `--last`, `--since`, `--out` to override). |
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
Enter descends into a submenu, `F8`/`F9` tick and untick a whole list, `?` explains the highlighted
row, and the last row is `< Start build >` (`s` still works from anywhere).

The install that follows is drawn, not printed: an overall bar, the step list with what each one is
waiting on, and -- while BuildTools runs -- **one bar per Spigot version**, the way `docker pull`
gives one per layer. Two keys matter while it runs:

| Key | Does |
| --- | --- |
| `q` | Abort, after a confirmation. Nothing already finished is undone; re-running carries on from where it stopped. Spigot builders already started keep going in their own containers either way. |
| `b` | Keep installing without you. You get your prompt back, the install continues in a detached container (`docker logs -f WatchWolf_install` to watch it), and the **next** `watchwolf build` opens with how it ended and an `< OK >` before the menu. |

Flag-driven runs (`--spigot`, `--skip-tester`, a non-TTY, ...) keep the plain one-line-per-step
output, unchanged.

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
