#!/bin/bash

echo "Starting WatchWolfSetup..."

opt=""
branch="master"
no_startup=0

while [[ "$#" -gt 0 ]]; do
    case $1 in
        #-i|--install) branch="$2"; shift ;;
        --dev) branch="dev" ;;
		
		--build) opt="build" ;;
		--install) opt="install" ;;
		--disable-startup) no_startup=1 ;;
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
		dots=""
		while [ "$current_downloading_containers" -gt 0 ]; do
			echo -ne "Waiting all Spigot containers to finish$dots ($((num_downloading_containers-current_downloading_containers))/$num_downloading_containers)      \r"
			
			dots="$dots."
			if [ ${#dots} -gt 3 ]; then
				dots=""
			fi
			
			sleep 15
			current_downloading_containers=`docker container ls -a | grep 'Spigot_build_' -c`
		done
		
		echo -ne '\nWatchWolf built.\n'
		;;
		
	"install" )
		if [ "$0" == "/usr/bin/WatchWolf" ]; then
			echo "[e] 'bash WatchWolfSetup.sh --install' can only be executed from the original path. Check that location with 'stat /usr/bin/WatchWolf'." >&2
			exit 1
		fi
		
		wsl=`cat /proc/version | grep -i -c 'microsoft'`
		script_path="$(pwd)/$0"
		
		# accessible from everywhere
		chmod +x "$script_path"
		sudo ln -sf "$script_path" /bin/WatchWolf # run WatchWolf from any place
		
		if [ $no_startup -eq 0 ]; then
			# run at startup
			if [ $wsl -eq 0 ]; then
				echo "[w] Install has only been tested with WSL. Report any problem in https://github.com/watch-wolf/WatchWolf/issues" >&2
			else
				# WSL
				# enable systemd
				if [ `sudo cat /etc/wsl.conf 2>/dev/null | grep -c 'systemd=true'` -eq 0 ]; then
					echo "[e] systemd not enabled in WSL. Add 'systemd=true' under '[boot]' in '/etc/wsl.conf'." >&2
					exit 1
					# TODO auto-add
				fi
			fi
			
			# create service
			service_contents=$(cat <<-END
				[Unit]
				Description=Launches WatchWolf ServersManager and WatchWolf ClientsManager
				
				[Service]
				ExecStart=bash "$script_path" --run
				
				[Install]
				WantedBy=multi-user.target
			END
			)
			sudo bash -c "echo '$service_contents' > /etc/systemd/system/watchwolf.service" # create service
			sudo systemctl enable watchwolf # init service
		fi
		;;
	
	"uninstall" )
		wsl=`cat /proc/version | grep -i -c 'microsoft'`
		sudo rm /bin/WatchWolf
		if [ $wsl -eq 0 ]; then
			echo "[w] Uninstall has only been tested with WSL. Report any problem in https://github.com/watch-wolf/WatchWolf/issues" >&2
			sudo rm /etc/systemd/system/watchwolf.service
		else
			base=`/mnt/c/Windows/System32/cmd.exe /c 'echo %USERPROFILE%' | sed 's/\r$//'`
			base=`echo "$base" | sed 's_\\\\_/_g' | sed 's_C:/_/mnt/c/_g'`
			windows_start_folder="$base/AppData/Roaming/Microsoft/Windows/Start Menu/Programs/Startup"
			sudo rm "$windows_start_folder/WatchWolf.bat"
		fi
		;;
		
	"run" )
		dots=""
		while [ `docker -v >/dev/null 2>&1 ; echo $?` -ne 0 ]; do
			echo -ne "Waiting Docker to start$dots    \r"
			
			dots="$dots."
			if [ ${#dots} -gt 3 ]; then
				dots=""
			fi
			
			sleep 15
		done
		echo ""
		
		# run ServersManager
		sudo docker run --privileged=true -i --rm --name ServersManager -p 8000:8000 -v /var/run/docker.sock:/var/run/docker.sock -v "$servers_manager_path":"$servers_manager_path" ubuntu:latest sh -c "cd $servers_manager_path ; chmod +x ServersManager.sh ServersManagerConnector.sh SpigotBuilder.sh ; echo '[*] Preparing ServersManager...' ; apt-get -qq update ; DEBIAN_FRONTEND=noninteractive apt-get install -y socat docker.io gawk procmail >/dev/null ; echo '[*] ServersManager ready.' ; socat -d -d tcp-l:8000,pktinfo,keepalive,keepidle=10,keepintvl=10,keepcnt=100,ignoreeof,fork system:./ServersManagerConnector.sh" >/dev/null 2>&1 & disown

		# run ClientsManager
		sudo docker run -i --rm --name ClientsManager -p 7000-7199:7000-7199 clients-manager:latest >/dev/null 2>&1 & disown
		
		dots=""
		while [ `docker container ls -a | grep -c -E 'ClientsManager|ServersManager'` -lt 2 ]; do
			echo -ne "Waiting Docker containers to start$dots    \r"
			
			dots="$dots."
			if [ ${#dots} -gt 3 ]; then
				dots=""
			fi
			
			sleep 1 # wait
		done
		
		echo -ne "\nWatchWolf started.\n" # TODO wait for containers to install
		;;
	
	* )
		echo "[e] No operation. Run 'bash WatchWolfSetup.sh --build', 'bash WatchWolfSetup.sh --install', 'bash WatchWolfSetup.sh --uninstall' or 'bash WatchWolfSetup.sh --run'" >&2
		exit 1
		;;
esac