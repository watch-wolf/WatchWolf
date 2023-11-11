#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import json
from pathlib import Path
from typing import List

from javascript import require
generate = require('bytefield-svg', 'latest')

class Petition:
    def __init__(self, type: str, function_name: str, svg_code: str):
        self.type = type
        self.function_name = function_name
        self.svg_code = svg_code

def _contentToEntry(content: json) -> str:
    if content["type"] == "String" or content["type"] == "ServerType" or content["type"][-2:] == '[]': # arrays
        return "(draw-gap \"" + content["name"] + "\")"

    raise Exception(f"Unrecognised type: '{content["type"]}'")

def _formatJSON(data: json) -> List[Petition]:
    to_process = [ (petition,False) for petition in data["WatchWolfComponent"]["petitions"] ] + \
                [ (petition,True) for petition in data["WatchWolfComponent"]["AsyncReturns"] ]

    r = []

    for petition,is_async_return in to_process:
        current = "(draw-column-headers)\n"

        current += "(draw-box \"0b" + '{0:03b}'.format(data["WatchWolfComponent"]["DestinyId"]) + "\" {:span 3})\n"
        current += "(draw-box \"" + ('1' if is_async_return else '0') + "\")\n"
        assert len(petition["contents"]) > 0 and petition["contents"][0]["type"] == "_operation"
        current += "(draw-box \"0b" + '{0:012b}'.format(petition["contents"][0]["value"]) + "\" {:span 12})\n"

        for content in petition["contents"][1:]:
            current += _contentToEntry(content)

        current += "(draw-bottom)"
        r.append(Petition('AsyncReturn' if is_async_return else 'petition', petition["FunctionName"], current))

    # TODO add `wrap-link` to refere to docs

    return r

def generateSVG(component: str, out: str):
    Path(out).mkdir(parents=True, exist_ok=True)

    with open(component) as f:
        c = json.load(f)
    sources = _formatJSON(c)
    for source in sources:
        diagram = generate(source.svg_code)

        source_out_path = os.path.join(out, f"{source.type}_{source.function_name}.svg")
        with open(source_out_path, "w") as f:
            f.write(diagram)

def main():
    components_path = './API/definitions'
    components = [ os.path.join(components_path, f) for f in os.listdir(components_path) if f.endswith('.json') ]
    
    for component in components:
        svgFolder = component[:component.rfind('.')]
        generateSVG(component, svgFolder)

if __name__ == '__main__':
    main()