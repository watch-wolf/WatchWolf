# WatchWolf CLI

Build, install, run, monitor and diagnose a WatchWolf environment — from one Docker image, with
nothing else required on the host.

Replaces [`WatchWolfSetup.sh`](../WatchWolfSetup.sh), which stays around for one release as a
[deprecated shim](../WatchWolfSetup.sh) that forwards to this.

## Quick start

```bash
wget https://raw.githubusercontent.com/watch-wolf/WatchWolf/main/cli/watchwolf
chmod +x watchwolf

./watchwolf build      # menuconfig-style checkbox screen on a TTY; flags otherwise
./watchwolf install    # optional: register at startup, then self-test
./watchwolf run
./watchwolf monitor    # the live dashboard
```

That `watchwolf` file is the **only** thing that has to exist on the host. It pulls (or builds, if
not yet published) the CLI's own image and runs everything inside it — the host needs Docker and
nothing else: no `git`, `wget`, `jq`, `dos2unix`, Maven or JDK.

## Commands

| Command | Does |
| --- | --- |
| `watchwolf` | Prints help. The dashboard is never opened by surprise — see `monitor`. |
| `watchwolf build` | Clone/update the repos, pull JDK images, build Spigot/Paper jars, fetch plugins, build the two Docker images. Opens a menuconfig-style checkbox screen on a TTY with no flags; fully flag-driven otherwise (`--dev`, `--path`, `--threads`, `--skip-spigot-build`, ...). |
| `watchwolf install` | Registers the startup service and the `/usr/local/bin/watchwolf` symlink, then runs the self-diagnosis unless `--skip-self-test`. |
| `watchwolf uninstall` | Undoes `install`. |
| `watchwolf run` / `stop` | Lifecycle. `run` checks the environment first unless `--skip-checks`; `stop` also sweeps up leftover `MC_Server-*` containers. |
| `watchwolf status` | One-shot plain-text picture of what's running. Safe to pipe. |
| `watchwolf monitor` | The live dashboard — a btop-style view of the managers, their servers and their bots, with per-entity logs. |
| `watchwolf logs` | Exports one `tar.gz` diagnostics bundle (`--session`, `--last`, `--since`, `--out`). |
| `watchwolf doctor` | Self-tests: fast static checks (tier 1), then the real WatchWolf-Tester integration suites against a live environment (tier 2, `--quick` to skip). |

Every command accepts `--path <dir>` (default `$HOME/WatchWolf`) to point at a different install.

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
./ci/tests.sh --integration            # needs a reachable Docker daemon
./ci/validator.sh                      # alias for --validation
```

See [`ci/README.md`](ci/README.md) for the three-suite layout, and [`AGENTS.md`](AGENTS.md) for
the module's internal design (the seams, the step/verification framework, the two TUI models).
