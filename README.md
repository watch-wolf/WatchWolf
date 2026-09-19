# WatchWolf

[![Website](https://img.shields.io/badge/website-watchwolf.dev-blue)](https://watchwolf.dev/)
[![License: MIT](https://img.shields.io/badge/license-MIT-green)](LICENSE)

**WatchWolf is an integration-testing environment for Minecraft plugins.** You describe the
servers you want (type, version, plugins, world, config files) and the players that should join
them; WatchWolf starts *real* Minecraft servers and *real* clients, drives them from your JUnit
tests, and tears everything down afterwards.

```java
@ParameterizedTest
@ArgumentsSource(WorldInteractionPetitionsShould.class)
public void breakBlock(TesterConnector connector) throws Exception {
    String username = connector.getClients()[0];
    ExtendedClientPetition client = connector.getClientPetition(username);

    Position target = client.getPosition().add(0, -1, 0);
    client.breakBlock(target);

    assertEquals(Blocks.AIR, connector.server.getBlock(target));
}
```

This repository holds the **standard**: the wire protocol every WatchWolf module speaks, the
machine-readable API definitions, and the setup script that installs a working environment.
The modules themselves live in their own repositories (see [Implementations](#implementations)).

---

## Table of contents

- [Architecture](#architecture)
- [Quick start](#quick-start)
- [The API](#the-api)
- [Working on the standard](#working-on-the-standard)
- [Implementations](#implementations)
- [Repository layout](#repository-layout)
- [Contributing](#contributing)

---

## Architecture

WatchWolf splits into five programs, each with a single responsibility. Every arrow below is a
TCP socket speaking the [WatchWolf API](#the-api).

```mermaid
flowchart LR
    T["<b>Tester</b><br/>your JUnit tests"]
    SM["<b>Servers Manager</b><br/>:8000"]
    S["<b>Server</b><br/>Spigot plugin"]
    CM["<b>Clients Manager</b><br/>:7000"]
    C["<b>Client</b><br/>mineflayer bot"]

    T -->|"start server (DST 0b000)"| SM
    SM -->|"docker run"| S
    T -->|"world / player petitions (DST 0b001)"| S
    T -->|"start client (DST 0b010)"| CM
    CM -->|"spawn bot"| C
    T -->|"player actions (DST 0b011)"| C
    C -->|"minecraft protocol"| S
```

| Module | Responsibility | Language | Repository |
| --- | --- | --- | --- |
| **Tester** | Entry point. Orchestrates setup/teardown and runs your tests. | Java 8 | [WatchWolf-Tester](https://github.com/miranda1000/WatchWolf-Tester) |
| **Servers Manager** | Provides Minecraft servers on demand (one Docker container each) and frees them afterwards. | Java 17 | [WatchWolf-ServersManager](https://github.com/miranda1000/WatchWolf-ServersManager) |
| **Server** | The Minecraft server itself. A Spigot plugin that executes the Tester's commands in-game. | Java 8 | [WatchWolf-Server](https://github.com/miranda1000/WatchWolf-Server) |
| **Clients Manager** | Same as the Servers Manager, but for players. Starts bots and connects them to a server. | Python 3 | [WatchWolf-Client](https://github.com/miranda1000/WatchWolf-Client) |
| **Client** | A headless Minecraft client that can move, mine, chat and record video. | Python 3 + Node | [WatchWolf-Client](https://github.com/miranda1000/WatchWolf-Client) |

Two supporting repositories complete the picture:

| Module | Responsibility | Repository |
| --- | --- | --- |
| **Core** | Shared entities (blocks, items, entities, plugins) and the RPC runtime + code generator. | [WatchWolf-Core](https://github.com/watch-wolf/WatchWolf-Core) |
| **Material Getter** | Spigot plugin that extracts every block/item of a Minecraft version and generates the `blocks.special` classes consumed by Core and Tester. | [WatchWolf-MaterialGetter](https://github.com/miranda1000/WatchWolf-MaterialGetter) |

### Ports

| Port | Used by |
| --- | --- |
| `8000` | Servers Manager (accepts Tester connections) |
| `8001+` | One Minecraft server per **pair** of ports: `N` is the Minecraft port, `N+1` is the WatchWolf Server socket |
| `7000` | Clients Manager (accepts Tester connections) |
| `7001+` | One client per **pair** of ports: `N` is the client socket, `N+1` streams the recorded images |

---

## Quick start

The [`WatchWolfSetup.sh`](WatchWolfSetup.sh) script installs and runs everything the Tester needs
(the Servers Manager and the Clients Manager). It requires **Ubuntu** and **Docker**; on Windows,
see [how to install Linux on Windows with WSL](https://learn.microsoft.com/en-us/windows/wsl/install).

1. **Download the script**

   ```bash
   wget https://raw.githubusercontent.com/watch-wolf/WatchWolf/main/WatchWolfSetup.sh
   ```

2. **Build**

   ```bash
   bash WatchWolfSetup.sh --build
   ```

   This clones the Servers Manager and Clients Manager, pulls the JDK images, downloads the
   "usual plugins" and builds the Spigot/Paper jars. Have at least **1.5 GB** free; building the
   Spigot servers can take up to an hour.

3. **Install** *(optional — makes `watchwolf` available everywhere and starts it at boot)*

   ```bash
   bash WatchWolfSetup.sh --install
   ```

4. **Run**

   ```bash
   bash WatchWolfSetup.sh --run
   ```

Once it is up, point your tests at the machine running it (see the Tester's
[configuration file](https://github.com/miranda1000/WatchWolf-Tester)) and you are ready to go.

### Script options

| Flag | Applies to | Meaning |
| --- | --- | --- |
| `--build` | — | Clone, compile and prepare the environment |
| `--install` | — | Symlink to `/bin/watchwolf` and register the startup service |
| `--uninstall` | — | Undo `--install` |
| `--run` | — | Start the Servers Manager and Clients Manager containers |
| `--path <dir>` | all | Base directory (default `$HOME/WatchWolf`) |
| `--dev` | `--build` | Clone the `dev` branches instead of `master` |
| `--threads <n>` | `--build` | Parallel Spigot build containers (default `1`) |
| `--skip-spigot-build` | `--build` | Skip building Spigot; drop the jars in `<path>/ServersManager/ci/release/server-types/Spigot` yourself |
| `--disable-startup` | `--install` | Do not launch WatchWolf at boot |

---

## The API

Every WatchWolf module talks over TCP using the same framing. A packet starts with a 16-bit
header (sent LSB first) followed by the operation's arguments:

```
 15                                4   3   2       0
+------------------------------------+---+---------+
|             operation              | r |   DST   |
+------------------------------------+---+---------+
|                  arguments ...                   |
+--------------------------------------------------+
```

- **DST** — which module the packet is for. When **r** is set, DST means *origin* instead.

  | DST | Destination |
  | --- | --- |
  | `0b000` | Servers Manager petition |
  | `0b001` | Server petition |
  | `0b010` | Clients Manager petition |
  | `0b011` | Client petition |
  | `0b1XX` | *Reserved* |

- **r** (response) — `0` for a request, `1` for a reply or an asynchronous notification.
- **operation** — the request itself; the set of operations depends on the DST. Operation
  `0b000000000000` is always a NOP (a keep-alive, so a long test is not dropped for inactivity).

Arguments use language-independent encodings (characters, booleans, doubles, strings, arrays,
files, positions, blocks, items, entities, containers…). **The full specification lives in
[`API/API.pdf`](API/API.pdf)** — that document is the source of truth for the protocol.

### Machine-readable definitions

[`API/definitions/`](API/definitions) mirrors part of that specification as JSON, one file per
module. These are consumed by tooling rather than read by hand:

- `api-docs.py` renders them into byte-field SVGs and a Markdown page for the website.
- WatchWolf-Core's `ci/rpc-gen.sh` generates the Java RPC stubs from them.

Today only [`servers_manager.json`](API/definitions/servers_manager.json) exists; the other
modules are still described only in the PDF.

---

## Working on the standard

### Rendering the definitions to docs

```bash
sudo docker build -f api-docs.Dockerfile --tag api-docs-builder .
sudo docker run -i --rm --name API-docs-builder \
    -v ./API/definitions:/app/API/definitions api-docs-builder:latest
```

This regenerates the `.md` and `.svg` files next to each definition. **Do not edit those by
hand** — edit the `.json` and re-run the container.

### Building the PDF

`API/API.tex` uses `glossaries` and `apacite`, so it needs the full four-pass build:

```bash
cd API
pdflatex -synctex=1 -interaction=nonstopmode API.tex
bibtex API
makeglossaries API
pdflatex -synctex=1 -interaction=nonstopmode API.tex
pdflatex -synctex=1 -interaction=nonstopmode API.tex
```

`API/A-Blocks.tex` (the block-type appendix) and `API/diagram.tex` are included from `API.tex`.

### The UML model

`Diagram.mdj` is a [StarUML](https://staruml.io/) model of the framework.

---

## Implementations

A full reference implementation of every module is available:

- [WatchWolf-Tester](https://github.com/miranda1000/WatchWolf-Tester) — JUnit 5 test harness
- [WatchWolf-ServersManager](https://github.com/miranda1000/WatchWolf-ServersManager) — Docker-backed server provider
- [WatchWolf-Server](https://github.com/miranda1000/WatchWolf-Server) — Spigot plugin ([pre-compiled builds](https://watchwolf.dev/versions/))
- [WatchWolf-Client](https://github.com/miranda1000/WatchWolf-Client) — Clients Manager & mineflayer client
- [WatchWolf-Core](https://github.com/watch-wolf/WatchWolf-Core) — shared entities and RPC runtime
- [WatchWolf-MaterialGetter](https://github.com/miranda1000/WatchWolf-MaterialGetter) — block/item class generator

---

## Repository layout

```
.
├── API/
│   ├── API.tex             # the specification (source of truth)
│   ├── API.pdf             # rendered specification
│   ├── A-Blocks.tex        # appendix: every block type and its properties
│   ├── diagram.tex         # the architecture figure included by API.tex
│   ├── glossaries.tex      # acronyms and glossary entries
│   ├── sample.bib          # bibliography
│   └── definitions/        # machine-readable API (JSON) + generated .md/.svg
├── api-docs.py             # definitions -> SVG/Markdown renderer
├── api-docs.Dockerfile     # container that runs api-docs.py
├── WatchWolfSetup.sh       # build / install / run the environment
└── Diagram.mdj             # StarUML model
```

Generated artefacts (`API/API-*.aux`, `API/definitions/*/`, …) are covered by `.gitignore`;
only the `.tex`, `.json`, the rendered `API.pdf` and the checked-in SVG/Markdown are tracked.

---

## Contributing

Found a problem or want a new operation in the protocol? Open an issue at
[watch-wolf/WatchWolf](https://github.com/watch-wolf/WatchWolf/issues). Changes to the protocol
should land in `API/API.tex` first, then in the JSON definitions, then in the implementations.

WatchWolf is MIT licensed (see [LICENSE](LICENSE)) and accepts sponsorship through
[GitHub Sponsors](https://github.com/sponsors/miranda1000).
