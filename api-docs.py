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

def htmlParser(text: str) -> str:
    text = text.replace('<', '&lt;')
    text = text.replace('>', '&gt;')
    text = text.replace('\n', '<br/>')
    return text

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

        if not is_async_return and "return" in petition:
            # we have to add the return to the operation
            current = "(draw-column-headers)\n"

            current += "(draw-box \"0b" + '{0:03b}'.format(data["WatchWolfComponent"]["DestinyId"]) + "\" {:span 3})\n"
            current += "(draw-box \"1\")\n"
            current += "(draw-box \"0b" + '{0:012b}'.format(petition["contents"][0]["value"]) + "\" {:span 12})\n"

            for content in petition["return"]["contents"]:
                current += _contentToEntry(content)

            current += "(draw-bottom)"
            r.append(Petition('PetitionReturn', petition["FunctionName"], current))

    # TODO add `wrap-link` to refere to docs

    return r

def generateSVG(componentPath: str, out: str):
    Path(out).mkdir(parents=True, exist_ok=True)

    with open(componentPath) as f:
        c = json.load(f)
    sources = _formatJSON(c)
    for source in sources:
        diagram = generate(source.svg_code)

        source_out_path = os.path.join(out, f"{source.type}_{source.function_name}.svg")
        with open(source_out_path, "w") as f:
            f.write(diagram)

def _generateComponentsDescriptor(contents: List[json]) -> str:
    if len(contents) > 0 and contents[0]["type"] == "_operation":
        contents = contents[1:]

    if len(contents) == 0:
        return ""

    r = "<table class='componentsDescriptor'>\n"
    r += "<thead>\n<tr>\n"
    r += "<th>Argument</th>\n"
    r += "<th>Type</th>\n"
    r += "<th>Description</th>\n"
    r += "</tr>\n</thead>\n"

    r += "<tbody>\n"
    for arg in contents:
        r += "<tr>\n"

        r += f"<td>{arg["name"]}</td>\n"
        r += f"<td>{arg["type"]}</td>\n"
        r += f"<td>{htmlParser(arg["description"])}</td>\n" # TODO replace inner '\n' by '<br>'?

        r += "</tr>\n"
    r += "</tbody>\n"

    r += "</table>\n"
    return r

def generateMD(componentPath: str, remoteSvgPath: str, out: str):
    with open(componentPath) as f:
        c = json.load(f)
    with open(out, "w") as f:
        f.write("<!-- This .md file was auto-generated; Do not modify. -->")
        f.write(f"{c["WatchWolfComponent"]["name"]}\n")
        f.write(("=" * len(c["WatchWolfComponent"]["name"])) + '\n')
        f.write(f"{htmlParser(c["WatchWolfComponent"]["description"])}\n")
        
        f.write("\n\nPetitions\n")
        f.write("---------\n")

        idToName = {}
        for petition in c["WatchWolfComponent"]["petitions"]:
            idToName[petition["FunctionName"]] = petition["name"]

            f.write(f"\n\n### {petition["name"]}\n")
            f.write(f"{htmlParser(petition["description"])}\n")

            f.write(f"![{c["WatchWolfComponent"]["name"]} - {petition["name"]} petition]({remoteSvgPath}/petition_{petition["FunctionName"]}.svg)\n")

            f.write(_generateComponentsDescriptor(petition["contents"]))
            
            if "return" in petition:
                f.write(f"\n#### {petition["name"]} return\n")
                if "description" in petition["return"]:
                    f.write(f"{htmlParser(petition["return"]["description"])}\n")

                f.write(f"![{c["WatchWolfComponent"]["name"]} - {petition["name"]} petition]({remoteSvgPath}/PetitionReturn_{petition["FunctionName"]}.svg)\n")

                f.write(_generateComponentsDescriptor(petition["return"]["contents"]))
        
        f.write("\n\nAsync Returns\n")
        f.write("-------------\n")

        for petition in c["WatchWolfComponent"]["AsyncReturns"]:
            f.write(f"\n\n### {petition["name"]}\n")
            f.write(f"{htmlParser(petition["description"])}\n")
            if "RelatesTo" in petition:
                f.write(f"<span class='relatesTo'>This return is sent as a response for calling <a href='javascript:void(0);'>{petition["RelatesTo"] if not petition["RelatesTo"] in idToName else idToName[petition["RelatesTo"]]}</a>.</span>\n") # TODO href reference

            f.write(f"![{c["WatchWolfComponent"]["name"]} - {petition["name"]} petition]({remoteSvgPath}/AsyncReturn_{petition["FunctionName"]}.svg)\n")

            f.write(_generateComponentsDescriptor(petition["contents"]))

def main():
    components_path = './API/definitions'
    components = [ os.path.join(components_path, f) for f in os.listdir(components_path) if f.endswith('.json') ]
    
    for component in components:
        svgFolder = component[:component.rfind('.')]
        mdPath = component[:component.rfind('.')] + '.md'
        generateSVG(component, svgFolder)
        generateMD(component, 'https://watchwolf.dev/assets/javadocs/api/' + component[component.rfind('/')+1:component.rfind('.')], mdPath)

if __name__ == '__main__':
    main()