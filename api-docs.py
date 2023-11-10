#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from javascript import require
generate = require('bytefield-svg', 'latest')

def main():
    source = """
(draw-column-headers)
(draw-box "Address" {:span 4})
(draw-box "Size" {:span 2})
(draw-box 0 {:span 2})
(draw-gap "Payload")
(draw-bottom)
    """
    diagram = generate(source)
    print(diagram)

if __name__ == '__main__':
    main()