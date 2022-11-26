#!/bin/bash

# TODO --install

branch="master"

while [[ "$#" -gt 0 ]]; do
    case $1 in
        #--install) branch="$2"; shift ;;
        --dev) branch="dev" ;;
        *) echo "[e] Unknown parameter passed: $1" >&2 ; exit 1 ;;
    esac
    shift
done

# target paths
servers_manager_path="$HOME/WatchWolf/ServersManager"
clients_manager_path="$HOME/WatchWolf/ClientsManager"

rm -rf "$servers_manager_path" 2>/dev/null
rm -rf "$clients_manager_path" 2>/dev/null

# get git files
git clone --branch "$branch" https://github.com/rogermiranda1000/WatchWolf-ServersManager.git "$servers_manager_path"
git clone --branch "$branch" https://github.com/rogermiranda1000/WatchWolf-Client.git "$clients_manager_path"

if [ `docker -v >/dev/null 2>&1 ; echo $?` -ne 0 ]; then
	echo "[e] Docker is not installed, or is currently stopped. Check https://docs.docker.com/get-docker/." >&2
	exit 1
fi

source "$servers_manager_path/SpigotBuilder.sh" # getAllVersions/buildVersion

# download all Spigot versions
getAllVersions |
while read version; do
	buildVersion "$servers_manager_path/server-types/Spigot" "$version" >/dev/null 2>&1 &
done