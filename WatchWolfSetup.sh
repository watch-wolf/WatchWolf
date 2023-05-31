#!/bin/bash

echo "Starting WatchWolfSetup..."

opt=""
branch="master"
no_startup=0
no_spigot=0
num_processes=$((`nproc --all` - 2))
base_path="$HOME/WatchWolf"

while [[ "$#" -gt 0 ]]; do
    case $1 in
        --dev) branch="dev" ;;
		--threads) num_processes="$2"; shift ;;
		--path) base_path="$2"; shift ;;
		--disable-startup) no_startup=1 ;;
		--skip-spigot-build) no_spigot=1 ;;
		
		--build) opt="build" ;;
		--install) opt="install" ;;
		--uninstall) opt="uninstall" ;;
		--run) opt="run" ;;
		
        *) echo "[e] Unknown parameter passed: $1" >&2 ; exit 1 ;;
    esac
    shift
done

if [ $num_processes -lt 1 ]; then
	num_processes=1 # at least 1 process
fi

# target paths
servers_manager_path="$base_path/ServersManager"
clients_manager_path="$base_path/ClientsManager"

# ask for sudo
sudo echo "" # this will prompt the sudo password input (if not sudo)

# run the desider operation
case "$opt" in
	"build" )
		# keep previous servers/plugins
		tmpdir=`mktemp -d`
		cp -r "$servers_manager_path/server-types/" "$tmpdir" 2>/dev/null
		cp -r "$servers_manager_path/usual-plugins/" "$tmpdir" 2>/dev/null
		
		# git needs empty folders
		sudo rm -rf "$servers_manager_path" 2>/dev/null
		sudo rm -rf "$clients_manager_path" 2>/dev/null

		# get git files
		git clone --branch "$branch" https://github.com/rogermiranda1000/WatchWolf-ServersManager.git "$servers_manager_path"
		git clone --branch "$branch" https://github.com/rogermiranda1000/WatchWolf-Client.git "$clients_manager_path"
		
		# restore back previous servers/plugins (if any)
		cp -r "$tmpdir/server-types/" "$servers_manager_path" 2>/dev/null
		cp -r "$tmpdir/usual-plugins/" "$servers_manager_path" 2>/dev/null
		
		mkdir -p "$servers_manager_path/server-types/Spigot"
		mkdir -p "$servers_manager_path/server-types/Paper"

		if [ `docker -v >/dev/null 2>&1 ; echo $?` -ne 0 ]; then
			echo "[e] Docker is not installed, or is currently stopped. Check https://docs.docker.com/get-docker/." >&2
			exit 1
		fi

		# ServersManager dependencies
		sudo docker pull openjdk:8
		sudo docker pull openjdk:16
		sudo docker pull openjdk:17
		sudo docker pull ubuntu

		if [ $no_spigot -eq 0 ]; then
			dos2unix "$servers_manager_path/SpigotBuilder.sh" "$servers_manager_path/PaperBuilder.sh"
			
			source "$servers_manager_path/SpigotBuilder.sh" # getAllVersions/buildVersion
			source "$servers_manager_path/PaperBuilder.sh" # getAllPaperVersions/buildPaperVersion
			
			# download the first <num_processes> Spigot versions
			num_downloading_containers=`getAllVersions | grep -c $'\n'`
			num_pending_containers=$(($num_downloading_containers > $num_processes ? $num_downloading_containers - $num_processes : 0))
			while read version; do
				buildVersion "$servers_manager_path/server-types/Spigot" "$version" >/dev/null 2>&1
			done <<< "$(getAllVersions | head -n $num_processes)" # get the first <num_processes> versions
		fi

		# download usual plugins
		while read usual_plugin; do
			usual_plugin_name=`echo "$usual_plugin" | jq -r -c '.name + "-" + .version + "-" + .min_mc_version + "-" + .max_mc_version + ".jar"'`
			usual_plugin_url=`echo "$usual_plugin" | jq -r -c '.url'`

			# @ref https://github.com/rogermiranda1000/WatchWolf-ServersManager/blob/fdd45da8fa787b201a48ccca565a4e9f1415b7c3/ServersManager.sh#L56
			spigot_id=`echo "$usual_plugin_url" | grep -o -P '(?<=spigotmc.org/resources/)[^/]+' | grep -o -P '\d+$'`
			if [ -z "$spigot_id" ]; then
				wget -O "$servers_manager_path/usual-plugins/$usual_plugin_name" "$usual_plugin_url"
			else
				# Spigot plugin; get plugin from Spiget website
				spigot_plugin_name=`wget -q -O - "https://api.spiget.org/v2/resources/$spigot_id" | jq -r .name`
				
				# TODO download a specific version doesn't work
				#spigot_plugin_version=`echo "$usual_plugin_url" | grep -o -P '(?<=/download\?version=)[^/]+$'`
				#if [ -z "$spigot_plugin_version" ]; then
					usual_plugin_url="https://api.spiget.org/v2/resources/$spigot_id/download"
				#else
				#	usual_plugin_url="https://api.spiget.org/v2/resources/$spigot_id/versions/$spigot_plugin_version/download"
				#fi
				
				wget -O "$servers_manager_path/usual-plugins/$usual_plugin_name" "$usual_plugin_url"
			fi
		done <<< `curl -s -N https://watchwolf.dev/api/v1/usual-plugins | jq -c '."usual-plugins" | .[]'` # all usual plugins urls
		
		# WatchWolf Server as usual-plugins
		watchwolf_server_versions_base_path="https://watchwolf.dev/versions"
		web_contents=`wget -q -O - "$watchwolf_server_versions_base_path"`
		higher_version=`echo "$web_contents" | grep -o -P '(?<=WatchWolf-)[\d.]+(?=-)' | sort --reverse --version-sort --field-separator=. | head -1` # get the current higher version
		higher_version_file=`echo "$web_contents" | grep -o -P "WatchWolf-${higher_version//./\\.}-[\d.]+-[\d.]+\.jar"`
		wget "$watchwolf_server_versions_base_path/$higher_version_file" -P "$servers_manager_path/usual-plugins"

		# ClientsManager dependencies
		sudo docker pull nikolaik/python-nodejs
		sudo docker build --tag clients-manager "$clients_manager_path"
		
		if [ $no_spigot -eq 0 ]; then
			# all ended; wait for the Spigot versions to finish
			current_downloading_containers=`sudo docker container ls -a | grep 'Spigot_build_' -c`
			dots=""
			while [ $(($current_downloading_containers + $num_pending_containers)) -gt 0 ]; do
				while read version; do
					if [ ! -z "$version" ]; then
						# still versions remaining, and there's a place to run them
						buildVersion "$servers_manager_path/server-types/Spigot" "$version" >/dev/null 2>&1
						((num_pending_containers--))
						((current_downloading_containers++))
					fi
				done <<< "$( getAllVersions | tail -n $num_pending_containers | head -n $(($num_processes > $current_downloading_containers ? $num_processes - $current_downloading_containers : 0)) )" # get enought versions of the remaining versions to fill the threads
				
				echo "Spigot containers still running (this process can take up to 1 hour in an average computer)"
				echo -ne "Waiting all Spigot containers to finish$dots ($(( $num_downloading_containers-$num_pending_containers-$current_downloading_containers ))/$num_downloading_containers)      \r"
				
				dots="$dots."
				if [ ${#dots} -gt 3 ]; then
					dots=""
				fi
				
				sleep 15
				current_downloading_containers=`sudo docker container ls -a | grep 'Spigot_build_' -c`
			done
			# Spigot ended, now wait for Paper
			
			while read version; do
				if [ ! -z "$version" ]; then
					# still versions remaining, and there's a place to run them
					buildPaperVersion "$servers_manager_path/server-types/Paper" "$version" #>/dev/null 2>&1
				fi
			done <<< "$(getAllPaperVersions)"
		fi
		
		echo -ne '\nWatchWolf built.\n'
		;;
		
	"install" )
		if [ "$0" == "/usr/bin/watchwolf" ]; then
			echo "[e] 'bash WatchWolfSetup.sh --install' can only be executed from the original path. Check that location with 'stat /usr/bin/watchwolf'." >&2
			exit 1
		fi
		
		wsl=`cat /proc/version | grep -i -c 'microsoft'`
		script_path="$(pwd)/$0"
		
		# accessible from everywhere
		chmod +x "$script_path"
		sudo ln -sf "$script_path" /bin/watchwolf # run WatchWolf from any place
		
		if [ $no_startup -eq 0 ]; then
			# run at startup
			if [ $wsl -eq 0 ]; then
				echo "[w] Install has only been tested with WSL. Report any problem in https://github.com/watch-wolf/WatchWolf/issues" >&2
			
				# create service
				service_contents=$(cat <<-END
					[Unit]
					Description=Launches WatchWolf ServersManager and WatchWolf ClientsManager
					
					[Service]
					ExecStart=bash "$script_path" --run --path "$base_path"
					
					[Install]
					WantedBy=multi-user.target
				END
				)
				sudo bash -c "echo '$service_contents' > /etc/systemd/system/watchwolf.service" # create service
				sudo systemctl enable watchwolf # init service
			else
				# WSL
				echo "Running WatchWolf at startup will prompt a CMD asking for the WSL password each time."
				echo "To make this task more pleasant, this script will disable the WSL admin password."
				echo "Do you want to disable the WSL password? (D)isable"
				echo "Do you want to keep the WSL password, thus prompting the CMD on each startup? (K)eep"
				echo "Do you want to exit (don't launch WatchWolf at startup; manually run './WatchWolf --run' each time)? (E)xit"
				read -p 'D/K/E: ' option
				while [ `echo "$option" | grep -i -E -c '^[DKE]'` -eq 0 ]; do
					read -p 'Unknown option. Use (D)isable, (K)eep, or (E)xit: ' option
				done
				
				if [ `echo "$option" | grep -i -E -c '^[DK]'` -ne 0 ]; then # Exit?
					if [ `echo "$option" | grep -i -E -c '^D'` -ne 0 ]; then
						# don't keep; disable sudo password
						sudo bash -c "echo '`whoami` ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/`whoami`" && sudo chmod 0440 /etc/sudoers.d/`whoami` # @ref https://www.folkstalk.com/tech/ubuntu-wsl-disable-sudo-password-prompt-with-code-examples/
						echo "WSL password disabled"
					fi
					
					# create "service"
					base=`/mnt/c/Windows/System32/cmd.exe /c 'echo %USERPROFILE%' | sed 's/\r$//'` # get the base path
					base=`echo "$base" | sed 's_\\\\_/_g' | sed 's_C:/_/mnt/c/_g'` # in WSL the directory delimiter is '/' (not '\'), and 'C:' is '/mnt/c'
					windows_start_folder="$base/AppData/Roaming/Microsoft/Windows/Start Menu/Programs/Startup" # @ref https://www.thewindowsclub.com/startup-folder-in-windows-8
					echo "wsl bash \"$script_path\" --run --path \"$base_path\"" > "$windows_start_folder/WatchWolf.bat"
					echo "Launch on startup done"
				fi
			fi
		fi
		;;
	
	"uninstall" )
		wsl=`cat /proc/version | grep -i -c 'microsoft'`
		sudo rm /bin/watchwolf
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
		wsl_mode(){ echo "echo 'Hello world'" | powershell.exe >/dev/null 2>&1; return $?; }
		get_ip(){ wsl_mode; if [ $? -eq 0 ]; then echo "(Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias Ethernet).IPAddress" | powershell.exe 2>/dev/null | tail -n2 | head -n1; else hostname -I | awk '{print $1}';fi }
		sudo docker run --privileged=true -i --rm --name ServersManager -p 8000:8000 -v /var/run/docker.sock:/var/run/docker.sock -v "$servers_manager_path":"$servers_manager_path" --env MACHINE_IP=$(get_ip) --env PUBLIC_IP=$(curl ifconfig.me) --env WSL_MODE=$(wsl_mode ; echo $? | grep -c 0) ubuntu:latest sh -c "echo '[*] Preparing ServersManager...' ; apt-get -qq update ; DEBIAN_FRONTEND=noninteractive apt-get install -y socat docker.io gawk procmail dos2unix jq unzip wget >/dev/null ; echo '[*] ServersManager ready.' ; cd $servers_manager_path ; dos2unix ServersManager.sh ServersManagerConnector.sh SpigotBuilder.sh PaperBuilder.sh ConnectorHelper.sh ; chmod +x ServersManager.sh ServersManagerConnector.sh SpigotBuilder.sh PaperBuilder.sh ConnectorHelper.sh ; rm ServersManager.lock 2>/dev/null ; socat -d -d tcp-l:8000,pktinfo,keepalive,keepidle=10,keepintvl=10,keepcnt=100,ignoreeof,fork system:'bash ./ServersManagerConnector.sh'" >/dev/null 2>&1 & disown
		
		# run ClientsManager
		sudo docker run -i --rm --name ClientsManager -p 7000-7199:7000-7199 --env MACHINE_IP=$(get_ip) --env PUBLIC_IP=$(curl ifconfig.me) clients-manager:latest >/dev/null 2>&1 & disown
		
		dots=""
		while [ `sudo docker container ls -a | grep -c -E 'ClientsManager|ServersManager'` -lt 2 ]; do
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
