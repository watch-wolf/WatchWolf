# AGENTS.md — WatchWolf (standard)

This repository is the **specification**, not an implementation. It holds the WatchWolf wire
protocol, the machine-readable API definitions generated from it, and the script that installs a
working environment. Nothing here compiles; almost everything here is either LaTeX, JSON, or a
Bash script.

Repo: `https://github.com/watch-wolf/WatchWolf` · default branch `main`, development on `dev`.

## What lives where

| Path | Role |
| --- | --- |
| `API/API.tex` | **Source of truth for the protocol.** Every operation, argument and data type. |
| `API/API.pdf` | Rendered spec, committed on purpose (people link to it). |
| `API/A-Blocks.tex` | Appendix: every block type and its properties (~2 300 lines). |
| `API/diagram.tex`, `API/glossaries.tex`, `API/sample.bib` | Included by `API.tex`. |
| `API/definitions/*.json` | Machine-readable subset of the spec, one file per module. |
| `API/definitions/*.md`, `API/definitions/<module>/*.svg` | **Generated.** Never edit. |
| `api-docs.py` + `api-docs.Dockerfile` | Renders the JSON into those `.md`/`.svg` files. |
| `WatchWolfSetup.sh` | build / install / uninstall / run the whole environment. |
| `Diagram.mdj` | StarUML model. |

## Packet format (needed to read anything here)

16-bit header, sent LSB first, then the arguments:

```
bits 15..4  operation      bit 3  r (response)      bits 2..0  DST
```

`DST`: `0b000` Servers Manager · `0b001` Server · `0b010` Clients Manager · `0b011` Client.
`r = 1` marks a reply or an async notification, and `DST` then means *origin*.
Operation `0` is always a NOP keep-alive.

You will see these as literals in the implementations, e.g. `0b000000000100_0_011` in
WatchWolf-Client is "operation 4, request, to the Client".

## Common tasks

### Regenerate the definition docs

```bash
sudo docker build -f api-docs.Dockerfile --tag api-docs-builder .
sudo docker run -i --rm --name API-docs-builder \
    -v ./API/definitions:/app/API/definitions api-docs-builder:latest
```

`api-docs.py` walks every `.json` in `API/definitions/`, emits one SVG per petition / return /
async-return via `bytefield-svg`, and one `.md` per module. Edit the JSON, re-run this — do not
touch the outputs.

### Build the PDF

```bash
cd API
pdflatex -synctex=1 -interaction=nonstopmode API.tex
bibtex API && makeglossaries API
pdflatex -synctex=1 -interaction=nonstopmode API.tex
pdflatex -synctex=1 -interaction=nonstopmode API.tex
```

Four passes are required (glossaries + apacite). `.gitignore` keeps every intermediate out; only
`API.tex`, `API.pdf` and the other `.tex`/`.bib` sources are tracked.

## Conventions and gotchas

- **`API.tex` first.** A protocol change lands in the LaTeX spec, then in the JSON definition,
  then in the implementations. The JSON currently covers *only* the Servers Manager; every other
  module is described in the PDF alone.
- **JSON schema.** Each file is `{"WatchWolfComponent": {...}}` with `name`, `description`,
  `version`, `DestinyId` (the DST value), `petitions[]` and `AsyncReturns[]`. Every petition's
  `contents[0]` must be `{"type": "_operation", "value": N}` — `api-docs.py` asserts this.
  A petition may carry a `return`; an async return may carry `RelatesTo`.
- **`api-docs.py` needs Python 3.12+.** It uses f-strings with the same quote character nested
  inside the expression (`f"{petition["name"]}"`), which is a syntax error on older interpreters.
  Run it through `api-docs.Dockerfile` rather than the system Python.
- **`api-docs.py` only knows a few types.** `_contentToEntry` handles `String`, `ServerType`,
  arrays (`Foo[]`) and `WorldType`; anything else raises `Unrecognised type`. Adding a new
  argument type to a definition means teaching that function about it.
- **Downstream pin.** WatchWolf-Core generates its RPC stubs from a *pinned commit* of
  `API/definitions/servers_manager.json` — see `LATEST_DEFINITIONS_LIST` in
  `src/scripts/java/dev/watchwolf/rpc/DefinitionDataFactory.java` in that repo. Changing the JSON
  here has no effect downstream until that URL's SHA is bumped.
- **`WatchWolfSetup.sh` is Ubuntu/WSL-only** and assumes `docker`, `jq`, `wget`, `curl` and
  `dos2unix`. It clones `WatchWolf-ServersManager` and `WatchWolf-Client` into
  `$HOME/WatchWolf/{ServersManager,ClientsManager}` and reaches out to `watchwolf.dev` for the
  "usual plugins" list and the latest WatchWolf-Server jar. The `--install` path writes a systemd
  unit (non-WSL) or a Startup `.bat` (WSL) — treat it as destructive.
- **`WatchWolfSetup.sh` has drifted from the ServersManager.** It pulls `openjdk:{8,16,17}`, but
  `DockerizedServerInstantiator.getDockerImageForJavaVersion` now launches servers on
  `eclipse-temurin:<v>-jdk` and `DockerUtilities.getJavaVersion` returns **21** for MC 1.20.5+.
  The images the script pre-pulls are the wrong names and miss 21, so those pulls happen lazily
  (or fail) at server-start time.
- The setup script's `--build` mode **deletes** `$HOME/WatchWolf/ServersManager` and
  `.../ClientsManager` before cloning (it backs up `server-types/` and `usual-plugins/` to a
  temp dir first). Do not run it against a path holding anything else.
