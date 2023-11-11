#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os

from javascript import require
generate = require('bytefield-svg', 'latest')

def generateSVG(component: str, out: str):
    source = """
    (draw-column-headers)
    (draw-box "Address" {:span 4})
    (draw-box "Size" {:span 2})
    (draw-box 0 {:span 2})
    (draw-gap "Payload")
    (draw-bottom)
        """
    diagram = generate(source)

    with open(out, "w") as f:
        f.write(diagram)

def main():
    components_path = './API/definitions'
    components = [ os.path.join(components_path, f) for f in os.listdir(components_path) if f.endswith('.json') ]
    
    for component in components:
        svgName = component[:component.rfind('.')] + '.svg'
        generateSVG(component, svgName)

if __name__ == '__main__':
    main()