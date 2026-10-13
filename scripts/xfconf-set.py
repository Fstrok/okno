#!/usr/bin/env python3
"""Sets properties in an xfconf channel file without replacing the rest.

Debian's packaged channel files (e.g. xfce4-session.xml) carry essential
defaults such as the session definition, so Okno edits single properties
instead of shipping whole files.

    xfconf-set.py FILE /group/name TYPE VALUE [/other TYPE VALUE ...]
"""
import sys
import xml.etree.ElementTree as ET


def child(parent, name):
    for prop in parent.findall("property"):
        if prop.get("name") == name:
            return prop
    return ET.SubElement(parent, "property", {"name": name, "type": "empty"})


def main(path, *assignments):
    if not assignments or len(assignments) % 3:
        sys.exit(__doc__)
    tree = ET.parse(path)
    root = tree.getroot()
    for i in range(0, len(assignments), 3):
        prop_path, kind, value = assignments[i:i + 3]
        node = root
        for name in prop_path.strip("/").split("/"):
            node = child(node, name)
        node.set("type", kind)
        node.set("value", value)
    ET.indent(tree, "  ")
    tree.write(path, encoding="UTF-8", xml_declaration=True)


if __name__ == "__main__":
    main(*sys.argv[1:])
