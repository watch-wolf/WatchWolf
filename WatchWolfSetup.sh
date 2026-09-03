#!/bin/bash
#
# DEPRECATED. WatchWolfSetup.sh has been replaced by the `watchwolf` CLI in cli/ -- a Java 17
# application shipped as a Docker image, so the host needs only Docker (not `jq`, `wget`, `curl`
# or `dos2unix`), works the same on Ubuntu/WSL/macOS/Windows, verifies every install step, and adds
# `watchwolf monitor` (a live dashboard) and `watchwolf doctor`/`watchwolf logs` for diagnosing a
# broken environment.
#
# This script is kept for one release so the previously documented
#     wget https://raw.githubusercontent.com/watch-wolf/WatchWolf/main/WatchWolfSetup.sh
#     bash WatchWolfSetup.sh --build
# path keeps working. It does nothing itself: it downloads cli/watchwolf (the CLI's own launcher)
# next to itself if not already present, translates this script's flags into the CLI's, and execs
# it. See cli/README.md for the full command reference; see cli/AGENTS.md for the design.
#
# Two behaviours worth knowing about if you were relying on the old script specifically:
#   - `--build` no longer deletes ServersManager/ClientsManager before cloning. Every step is
#     idempotent and verified instead, so a second `--build` updates in place.
#   - The JDK images pulled now actually match what MC servers run on, eclipse-temurin:{8,16,17,21}
#     -- the old script pulled openjdk:{8,16,17}, which were both the wrong image names and missing
#     the one 1.20.5+ servers need.

set -euo pipefail

script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
launcher="$script_dir/cli/watchwolf"

echo "[w] WatchWolfSetup.sh is deprecated. Forwarding to cli/watchwolf -- see cli/README.md." >&2
echo "[w] Update any scripts or bookmarks to use that directly." >&2

# A bare download of this file (the documented `wget .../WatchWolfSetup.sh` path) won't have
# cli/watchwolf sitting next to it, since that only exists inside a full checkout. Fetch it.
if [ ! -f "$launcher" ]; then
    echo "[v] Fetching cli/watchwolf next to this script..." >&2
    if command -v wget >/dev/null 2>&1; then
        wget -q -O "$script_dir/watchwolf" \
            https://raw.githubusercontent.com/watch-wolf/WatchWolf/main/cli/watchwolf
    elif command -v curl >/dev/null 2>&1; then
        curl -fsSL -o "$script_dir/watchwolf" \
            https://raw.githubusercontent.com/watch-wolf/WatchWolf/main/cli/watchwolf
    else
        echo "[e] Neither wget nor curl is available to fetch cli/watchwolf." >&2
        echo "[e] Download it yourself: https://raw.githubusercontent.com/watch-wolf/WatchWolf/main/cli/watchwolf" >&2
        exit 1
    fi
    chmod +x "$script_dir/watchwolf"
    launcher="$script_dir/watchwolf"
fi

# translate this script's flags into the CLI's -- almost all of them are already the same
opt=""
declare -a cli_args=()

while [[ "$#" -gt 0 ]]; do
    case $1 in
        --build)               opt="build" ;;
        --install)              opt="install" ;;
        --uninstall)             opt="uninstall" ;;
        --run)                  opt="run" ;;

        # identical on both sides
        --dev)                   cli_args+=(--dev) ;;
        --threads)               cli_args+=(--threads "$2"); shift ;;
        --path)                  cli_args+=(--path "$2"); shift ;;
        --skip-spigot-build)     cli_args+=(--skip-spigot-build) ;;
        --disable-startup)       cli_args+=(--disable-startup) ;;

        *) echo "[e] Unknown parameter passed: $1" >&2 ; exit 1 ;;
    esac
    shift
done

if [ -z "$opt" ]; then
    echo "[e] No operation. Run 'bash WatchWolfSetup.sh --build', 'bash WatchWolfSetup.sh --install', 'bash WatchWolfSetup.sh --uninstall' or 'bash WatchWolfSetup.sh --run'" >&2
    echo "[e] (or, directly: watchwolf build / install / uninstall / run)" >&2
    exit 1
fi

# non-interactive by default here, matching the old script's behaviour: it never opened a TUI.
# Explicitly ask for the new menuconfig screen with `watchwolf build` (no --build/flags) instead.
if [ "$opt" = "build" ] && [ -t 1 ]; then
    cli_args+=(--no-tui)
fi

exec bash "$launcher" "$opt" "${cli_args[@]}"
