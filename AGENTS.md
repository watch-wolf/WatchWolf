# AGENTS.md — WatchWolf (standard)

This repository is primarily the **specification**, not an implementation: the WatchWolf wire
protocol, the machine-readable API definitions generated from it, and (as of `cli/`) the Java
application that installs a working environment — the one thing here that does compile. Everything
else is either LaTeX, JSON, or a Bash script.

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
| `cli/` | The `watchwolf` CLI (Java 17): build / install / uninstall / run / monitor / diagnose the whole environment, shipped as a Docker image. See [`cli/AGENTS.md`](cli/AGENTS.md). |
| `WatchWolfSetup.sh` | **Deprecated shim.** Forwards `--build`/`--install`/`--uninstall`/`--run` and their flags to `cli/watchwolf`, kept for one release so the previously documented `wget … && bash WatchWolfSetup.sh --build` path keeps working. |
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
- **`WatchWolfSetup.sh` is now a thin shim; the real logic lives in `cli/`.** It still clones
  `WatchWolf-ServersManager` and `WatchWolf-Client` into
  `$HOME/WatchWolf/{ServersManager,ClientsManager}` and reaches out to `watchwolf.dev` for the
  "usual plugins" list and the latest WatchWolf-Server jar — same layout as before — but by
  forwarding to `cli/watchwolf`, which runs entirely in a Docker image (the host needs only
  Docker, not `jq`/`wget`/`curl`/`dos2unix`) and works the same on Ubuntu, WSL, and anywhere else
  Docker runs.
- **The two problems that motivated `cli/` are fixed there, not patched here.** The old script
  pulled `openjdk:{8,16,17}` while `DockerizedServerInstantiator` launches
  `eclipse-temurin:<v>-jdk` and needs **21** for MC 1.20.5+ — `cli/`'s `JavaImageCatalog` is the
  single place that list is derived from now, checked against WatchWolf-Core's own
  `DockerUtilities.getJavaVersion` by a code check
  (`MinecraftJavaVersionsMatchesCoreShould`). And `--build` no longer **deletes**
  `ServersManager`/`ClientsManager` before cloning (the old backup-then-restore dance): every
  install step is idempotent and verified, so a second `watchwolf build` updates in place and
  refuses to touch a directory it does not recognise as its own. See `cli/AGENTS.md`'s
  "Conventions and gotchas" for the current design.

## Git conventions

- **`dev` is the working branch.** Every WatchWolf repo integrates and releases from `dev`.
  `master` (`main` in the WatchWolf standard repo) is downstream of it — never commit there
  directly, and never open a PR against it.
- **One branch per change, named for its kind:** `fix/<topic>` for defects, `feature/<topic>` for
  new work. Branch from `dev`.
- **Always open a PR into `dev`.** Do not push straight to `dev`, even for a one-line change.
