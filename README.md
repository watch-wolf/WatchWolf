# WatchWolf
[WatchWolf](http://watchwolf.dev/) is a standard to test Minecraft plugins.

## Standard
The standard can be seen [here](https://github.com/watch-wolf/WatchWolf/blob/main/Standard/Protocols.pdf).

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

   Run `wget https://raw.githubusercontent.com/watch-wolf/WatchWolf/main/WatchWolfSetup.sh`

2. Run the script in `build` mode

   Run `sudo bash WatchWolfSetup.sh --build`
   
   You can get the code from the develop branch by using `bash WatchWolfSetup.sh --build --dev`
   
3. (if you want to start WatchWolf on startup [recommended]) Run the script in `install` mode

   Run `sudo bash WatchWolfSetup.sh --install`