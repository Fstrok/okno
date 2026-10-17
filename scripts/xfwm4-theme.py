#!/usr/bin/env python3
"""Okno's window frames for xfwm4: Arc-Dark colours, a 40 px title bar and
44 px buttons, easy to hit with a finger. Vector images (xfwm4 loads them
through gdk-pixbuf), so they stay sharp at any desktop scale.

    scripts/xfwm4-theme.py DIR   ->  DIR/themerc and DIR/*.svg
"""
import os
import sys

H = 40          # title bar height
BW = 44         # button width
SIDE = 2        # left, right and bottom border
BG = {"active": "#2f343f", "inactive": "#353945"}
FG = {"active": "#d3dae3", "inactive": "#7c818c"}
EDGE = "#262a33"

GLYPHS = {
    "close": '<path d="M-6-6 6 6M6-6-6 6"/>',
    "hide": '<path d="M-6 5H6"/>',
    "maximize": '<rect x="-6" y="-6" width="12" height="12" rx="2"/>',
    "maximize-toggled": '<rect x="-6" y="-3" width="9" height="9" rx="2"/>'
                        '<path d="M-3-6H4a2 2 0 0 1 2 2v7"/>',
    "menu": "",  # the window's own icon is drawn over it (show_app_icon)
}


def svg(w, h, body):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
            f'viewBox="0 0 {w} {h}">{body}</svg>\n')


def button(glyph, state):
    focus = "inactive" if state == "inactive" else "active"
    fg, bg = FG[focus], BG[focus]
    body = f'<rect width="{BW}" height="{H}" fill="{bg}"/><rect y="{H - 1}" width="{BW}" height="1" fill="{EDGE}"/>'
    if state in ("prelight", "pressed"):
        if glyph == "close":
            fill, fg = ("#e0565b" if state == "prelight" else "#c4444a"), "#ffffff"
        else:
            fill = "#3e4451" if state == "prelight" else "#4b5162"
        body += f'<rect x="4" y="4" width="{BW - 8}" height="{H - 9}" rx="8" fill="{fill}"/>'
    body += (f'<g transform="translate({BW / 2} {H / 2 - 0.5})" fill="none" stroke="{fg}" '
             f'stroke-width="2" stroke-linecap="round" stroke-linejoin="round">{GLYPHS[glyph]}</g>')
    return svg(BW, H, body)


def main(out):
    os.makedirs(out, exist_ok=True)

    def put(name, text):
        with open(os.path.join(out, name), "w") as f:
            f.write(text)

    put("themerc", f"""button_offset=2
button_spacing=2
show_app_icon=true
full_width_title=true
title_horizontal_offset=10
title_shadow_active=false
title_shadow_inactive=false
active_text_color={FG["active"]}
inactive_text_color={FG["inactive"]}
""")
    for focus in ("active", "inactive"):
        title = svg(1, H, f'<rect width="1" height="{H}" fill="{BG[focus]}"/><rect y="{H - 1}" width="1" height="1" fill="{EDGE}"/>')
        for part in ["title-1", "title-2", "title-3", "title-4", "title-5", "top-left", "top-right"]:
            put(f"{part}-{focus}.svg", title)
        put(f"left-{focus}.svg", svg(SIDE, 1, f'<rect width="{SIDE}" height="1" fill="{BG[focus]}"/>'))
        put(f"right-{focus}.svg", svg(SIDE, 1, f'<rect width="{SIDE}" height="1" fill="{BG[focus]}"/>'))
        for part in ["bottom", "bottom-left", "bottom-right"]:
            w = 1 if part == "bottom" else SIDE
            put(f"{part}-{focus}.svg", svg(w, SIDE, f'<rect width="{w}" height="{SIDE}" fill="{BG[focus]}"/>'))
    for glyph in GLYPHS:
        name = glyph.replace("-toggled", "")
        suffix = "-toggled" if glyph.endswith("toggled") else ""
        for state in ("active", "inactive", "prelight", "pressed"):
            put(f"{name}{suffix}-{state}.svg", button(glyph, state))


main(sys.argv[1])
