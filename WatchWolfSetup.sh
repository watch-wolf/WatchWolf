#!/bin/bash

opt=""
branch="master"

while [[ "$#" -gt 0 ]]; do
    case $1 in
        #-i|--install) branch="$2"; shift ;;
        --dev) branch="dev" ;;
		
		--build) opt="build" ;;
		--install) opt="install" ;;
		--uninstall) opt="uninstall" ;;
		--run) opt="run" ;;
		
        *) echo "[e] Unknown parameter passed: $1" >&2 ; exit 1 ;;
    esac
    shift
done

# target paths
servers_manager_path="$HOME/WatchWolf/ServersManager"
clients_manager_path="$HOME/WatchWolf/ClientsManager"

# ask for sudo
sudo echo "" # this will prompt the sudo password input (if not sudo)

# run the desider operation
case "$opt" in
	"build" )
		rm -rf "$servers_manager_path" 2>/dev/null
		rm -rf "$clients_manager_path" 2>/dev/null

		# get git files
		git clone --branch "$branch" https://github.com/rogermiranda1000/WatchWolf-ServersManager.git "$servers_manager_path"
		git clone --branch "$branch" https://github.com/rogermiranda1000/WatchWolf-Client.git "$clients_manager_path"

		if [ `docker -v >/dev/null 2>&1 ; echo $?` -ne 0 ]; then
			echo "[e] Docker is not installed, or is currently stopped. Check https://docs.docker.com/get-docker/." >&2
			exit 1
		fi

		# ServersManager dependencies
		docker pull openjdk:8
		docker pull openjdk:16
		docker pull openjdk:17
		docker pull ubuntu

		source "$servers_manager_path/SpigotBuilder.sh" # getAllVersions/buildVersion

		# download all Spigot versions
		num_downloading_containers=0
		while read version; do
			buildVersion "$servers_manager_path/server-types/Spigot" "$version" >/dev/null 2>&1 &
			((num_downloading_containers++))
		done <<< "$(getAllVersions)"
		
		# WatchWolf Server as usual-plugins
		watchwolf_server_versions_base_path="https://watchwolf.dev/versions"
		higher_version=`curl -s "$watchwolf_server_versions_base_path" | grep -o -P '(?<=WatchWolf-)[\d.]+(?=-)' | sort -r | head -1` # get the current higher version
		wget "$watchwolf_server_versions_base_path/WatchWolf-$higher_version-1.8-1.19.jar" -P "$servers_manager_path/usual-plugins"

		# ClientsManager dependencies
		docker pull nikolaik/python-nodejs
		docker build --tag clients-manager "$clients_manager_path"
		
		# all ended; wait for the Spigot versions to finish
		current_downloading_containers=`docker container ls -a | grep 'Spigot_build_' -c`
		while [ "$current_downloading_containers" -gt 0 ]; do
			echo -ne "Waiting all Spigot containers to finish... ($((num_downloading_containers-current_downloading_containers))/$num_downloading_containers)  \r"
			
			sleep 15
			current_downloading_containers=`docker container ls -a | grep 'Spigot_build_' -c`
		done
		
		echo -ne '\nWatchWolf built.\n'
		;;
		
	"install" )
		;;
	
	"uninstall" )
		;;
		
	"run" )
		# run ServersManager
		sudo docker run --privileged=true -i --rm --name ServersManager -p 8000:8000 -v /var/run/docker.sock:/var/run/docker.sock -v "$servers_manager_path":"$servers_manager_path" ubuntu:latest sh -c "cd $servers_manager_path ; chmod +x ServersManager.sh ServersManagerConnector.sh SpigotBuilder.sh ; echo '[*] Preparing ServersManager...' ; apt-get -qq update ; DEBIAN_FRONTEND=noninteractive apt-get install -y socat docker.io gawk procmail >/dev/null ; echo '[*] ServersManager ready.' ; socat -d -d tcp-l:8000,pktinfo,keepalive,keepidle=10,keepintvl=10,keepcnt=100,ignoreeof,fork system:./ServersManagerConnector.sh"

		# run ClientsManager
		sudo docker run -i --rm --name ClientsManager -p 7000-7199:7000-7199 clients-manager:latest
		;;
	
	* )
		echo "[e] No operation. Run 'bash WatchWolfSetup.sh --build', 'bash WatchWolfSetup.sh --install', 'bash WatchWolfSetup.sh --uninstall' or 'bash WatchWolfSetup.sh --run'" >&2
		exit 1
		;;
esac
