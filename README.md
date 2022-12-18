# WatchWolf
[WatchWolf](http://watchwolf.dev/) is a standard to test Minecraft plugins.

## Standard
The standard can be seen [here](https://github.com/watch-wolf/WatchWolf/blob/main/Standard/Protocols.pdf). Also, you can see a full implementation example in [WatchWolf-Tester](https://github.com/rogermiranda1000/WatchWolf-Tester), [WatchWolf-ServersManager](https://github.com/rogermiranda1000/WatchWolf-ServersManager), [WatchWolf-Server](https://github.com/rogermiranda1000/WatchWolf-Server), and [WatchWolf-Client](https://github.com/rogermiranda1000/WatchWolf-Client).

### Build PDF
`pdflatex -synctex=1 -interaction=nonstopmode %.tex|bibtex "Protocols"|makeglossaries %|pdflatex -synctex=1 -interaction=nonstopmode %.tex|pdflatex -synctex=1 -interaction=nonstopmode %.tex`

Also run `rail Protocols` to generate the rail figures. More information [here](https://github.com/Holzhaus/latex-rail).

## Implementation
You can check an implementation example of all the modules here:
- [Tester](https://github.com/rogermiranda1000/WatchWolf-Tester)
- [ServersManager](https://github.com/rogermiranda1000/WatchWolf-ServersManager)
- [Server](https://github.com/rogermiranda1000/WatchWolf-Server)
- [ClientsManager & Client](https://github.com/rogermiranda1000/WatchWolf-Client)

### Build implementation
To use this implementation you'll need to download [ServersManager](https://github.com/rogermiranda1000/WatchWolf-ServersManager) and [WatchWolf-Client](https://github.com/rogermiranda1000/WatchWolf-Client) and run their requirements.

To make this task more easily you'll find [an script](https://github.com/watch-wolf/WatchWolf/blob/main/WatchWolfSetup.sh) that will do all the requirements needed. Note that you'll need Ubuntu to run the scripts; if you have Windows check [how to Install Linux on Windows with WSL](https://learn.microsoft.com/en-us/windows/wsl/install).

To run the script:

1. Download the script

   Run `wget https://raw.githubusercontent.com/watch-wolf/WatchWolf/main/WatchWolfSetup.sh`.

2. Run the script in `build` mode

   Run `sudo bash WatchWolfSetup.sh --build`.
   
   You can get the code from the develop branch by using `bash WatchWolfSetup.sh --build --dev`.
   
   While building the Spigot servers you'll launch `<thread number>-2` docker containers. To set a custom ammount of processes run `sudo bash WatchWolfSetup.sh --build --threads 4`, changing `4` for the desired number of threads.
   
3. (optional) Run the script in `install` mode

   Run `sudo bash WatchWolfSetup.sh --install`.
   
   If you want to avoid WatchWolf from starting at startup, run `sudo bash WatchWolfSetup.sh --install --disable-startup` instead.
