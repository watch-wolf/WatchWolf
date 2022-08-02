# WatchWolf
[WatchWolf](http://watchwolf.dev/) is a standard to test Minecraft plugins.

## Standard
The standard can be seen [here](https://github.com/watch-wolf/WatchWolf/blob/main/Standard/Protocols.pdf). Also, you can see a full implementation example in [TODO](TODO).

## Build PDF
`pdflatex -synctex=1 -interaction=nonstopmode %.tex|bibtex "Protocols"|makeglossaries %|pdflatex -synctex=1 -interaction=nonstopmode %.tex|pdflatex -synctex=1 -interaction=nonstopmode %.tex`