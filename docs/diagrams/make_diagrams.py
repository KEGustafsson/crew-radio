"""Generates the draw.io diagrams and the screen mock-ups under docs/diagrams from a small
description, so they can be re-made after a change without hand-editing XML. Export to PNG
with draw.io desktop:

    "C:/Program Files/draw.io/draw.io.exe" -x -f png -s 1.5 -b 16 -o docs/images/<name>.png docs/diagrams/<name>.drawio

(scale 1 for the screen mock-ups, 1.5 for the flowcharts; the markdown gives each image a display width)

Keep `-b 16` for every phone mock-up. The export crops to the drawing, so the border is the only
thing that sets the canvas size, and the README puts phones side by side: a mock-up exported with
a different border lands on a different canvas and its phone is then drawn a different size.
screen-settings.png was once exported with a 10-pixel border and came out 3 % larger than the phone
beside it. Every phone is now the same 360 x 800 frame, a real phone's screen, so all of them
export the same and a screen longer than that is cut off at the bottom edge as a screenshot is.

Run:  python docs/diagrams/make_diagrams.py

The screens are drawn, not photographed - they carry nobody's device name - but they are drawn
from the layouts, not from memory: positions, sizes, colours, letter spacing, the icons and every
word come from app/src/main/res. When a screen changes, change the mock-up in the same commit; a
picture that no longer matches the app is worse than no picture.
"""
import base64
import html
import os

HERE = os.path.dirname(os.path.abspath(__file__))
NL = "\n"

# Palette for the flowcharts: printed on white.
BOX = "rounded=1;whiteSpace=wrap;html=1;fontFamily=Helvetica;fontSize=12;"
UI = BOX + "fillColor=#E1F5FE;strokeColor=#0288D1;"
SVC = BOX + "fillColor=#E8F5E9;strokeColor=#2E7D32;"
ENGINE = BOX + "fillColor=#FFF8E1;strokeColor=#F9A825;"
NET = BOX + "fillColor=#F3E5F5;strokeColor=#7B1FA2;"
AUDIO = BOX + "fillColor=#FBE9E7;strokeColor=#D84315;"
NOTE = "text;html=1;align=left;verticalAlign=top;whiteSpace=wrap;fontFamily=Helvetica;fontSize=11;fontColor=#555555;"
TITLE = NOTE + "fontSize=16;fontStyle=1;fontColor=#000000;"
DECISION = "rhombus;whiteSpace=wrap;html=1;fontFamily=Helvetica;fontSize=11;fillColor=#FFFDE7;strokeColor=#F9A825;"
STEP = BOX + "fillColor=#FFFFFF;strokeColor=#455A64;"
END = BOX + "fillColor=#ECEFF1;strokeColor=#455A64;"
START = "ellipse;whiteSpace=wrap;html=1;fontFamily=Helvetica;fontSize=12;fillColor=#CFD8DC;strokeColor=#455A64;"
PHONE = BOX + "fillColor=#E1F5FE;strokeColor=#0288D1;fontSize=13;fontStyle=1;"
EDGE = "edgeStyle=orthogonalEdgeStyle;rounded=1;html=1;endArrow=block;endFill=1;fontFamily=Helvetica;fontSize=11;strokeColor=#455A64;"
EDGE_DASH = EDGE + "dashed=1;"
EDGE_BI = EDGE + "startArrow=block;startFill=1;"

# Palette for the screens: the app's own dark theme, value for value from
# app/src/main/res/values/colors.xml. These pictures stand in for screenshots, so a colour that
# changes there has to change here, and nothing on a screen mock-up may be invented.
BG = "#101416"                  # surface, and the window background every screen is drawn on
CARD = "#171C1E"                # surface_card
OUTLINE = "#2E3436"
PRIMARY = "#4FD8EB"
ON_PRIMARY = "#00363D"          # the word TALK on the disc
PRIMARY_CONTAINER = "#004F58"   # the hint under it
SECONDARY = "#B1CBD0"
TEXT = "#E1E3E3"
TEXT_DIM = "#899294"
TEAL_DIM = "#7FA6AC"            # text_teal_dim: the CHANNEL label
TILE_ON = "#0F2A2F"             # tile_selected
DOT_IDLE = "#3A4547"
TALKING = "#81C784"
ON_AIR = "#93000A"
ON_AIR_TEXT = "#FFDAD6"
ERROR = "#FFB4AB"
BEZEL = "#3A444A"               # the drawn screen edge, not part of the app
PAGE = "#FFFFFF"                # what draw.io leaves behind the drawing; the screen edge masks with it
# android:fontFamily="monospace" is Roboto Mono on the phone: a *sans-serif* monospace, not a
# typewriter face. Courier came out wrong on the talk disc; this stack picks the closest one
# installed on Windows, macOS and Linux in turn.
MONO = "fontFamily=Consolas, Roboto Mono, DejaVu Sans Mono, monospace;"


def diagram(name, title, nodes, edges, width=1100, height=700, background=None):
    """nodes: (id, label, x, y, w, h, style); edges: (src, dst, label, style, [points])."""
    cells = ['<mxCell id="0"/>', '<mxCell id="1" parent="0"/>']
    for nid, label, x, y, w, h, style in nodes:
        cells.append(
            f'<mxCell id="{nid}" value="{html.escape(label, quote=True)}" style="{style}" vertex="1" parent="1">'
            f'<mxGeometry x="{x}" y="{y}" width="{w}" height="{h}" as="geometry"/></mxCell>'
        )
    for i, e in enumerate(edges):
        src, dst, label, style = e[0], e[1], e[2], e[3]
        points = e[4] if len(e) > 4 else []
        geo = '<mxGeometry relative="1" as="geometry">'
        if points:
            geo += '<Array as="points">' + "".join(f'<mxPoint x="{px}" y="{py}"/>' for px, py in points) + "</Array>"
        geo += "</mxGeometry>"
        cells.append(
            f'<mxCell id="e{i}" value="{html.escape(label, quote=True)}" style="{style}" edge="1" parent="1" '
            f'source="{src}" target="{dst}">{geo}</mxCell>'
        )
    bg = f' background="{background}"' if background else ""
    xml = (
        '<mxfile host="Electron" type="device">'
        f'<diagram id="{name}" name="{html.escape(title, quote=True)}">'
        f'<mxGraphModel dx="1" dy="1" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" '
        f'fold="1" page="1" pageScale="1" pageWidth="{width}" pageHeight="{height}" math="0" shadow="0"{bg}>'
        "<root>" + "".join(cells) + "</root></mxGraphModel></diagram></mxfile>"
    )
    with open(os.path.join(HERE, name + ".drawio"), "w", encoding="utf-8") as f:
        f.write(xml)
    print("wrote", name + ".drawio")


# ================================================================ screens (mock-ups)
#
# One drawing pixel is one Android dp, and every position below is read off the layout it draws:
# activity_main.xml, activity_status.xml with card_status.xml, row_kv.xml, row_status_peer.xml,
# res/xml/preferences.xml through AndroidX's preference_material.xml, and sheet_ask.xml. The icons
# are the app's own vector drawables, embedded as SVG. The phone is 360 x 800 dp, which is a real
# phone's screen, so a screen longer than that is cut off at the bottom edge exactly as a
# screenshot of it would be.

FRAME_W, FRAME_H = 360, 800
STATUS_BAR = 28                 # the system bar the activities pad themselves under (edge to edge)
PAD = 16                        # the root padding of activity_main.xml and the Status scroll view
CW = FRAME_W - 2 * PAD          # 328: the width every row on the main screen gets


def mix(color, alpha, over=BG):
    """A View's alpha, resolved against the ground it sits on: the PNG export has no opacity."""
    c = [int(color[i:i + 2], 16) for i in (1, 3, 5)]
    b = [int(over[i:i + 2], 16) for i in (1, 3, 5)]
    return "#" + "".join(f"{round(u * alpha + v * (1 - alpha)):02X}" for u, v in zip(c, b))


def mono_w(s, size, tracking=0.0):
    """Courier advances 0.6 em a glyph; android:letterSpacing is in em and adds one gap each."""
    return int(len(s) * size * (0.6 + tracking) + 0.5)


def txt(nid, s, x, y, w, h, size=12, color=TEXT, bold=False, align="left", mono=True, tracking=0.0,
        valign="middle", font=None):
    """One TextView. `tracking` is android:letterSpacing, in em, the way the layouts give it."""
    face = font or (MONO if mono else "fontFamily=Helvetica;")
    st = (f"text;html=1;whiteSpace=wrap;align={align};verticalAlign={valign};fontSize={size};fontColor={color};"
          + face + ("fontStyle=1;" if bold else "")
          + (f"letterSpacing={round(size * tracking, 2)};" if tracking else "")
          + "spacing=0;spacingLeft=0;spacingRight=0;spacingTop=0;spacingBottom=0;")
    return (nid, s, x, y, w, h, st)


def rect(nid, x, y, w, h, fill=CARD, stroke=OUTLINE, r=12, sw=1, label="", color=TEXT, size=12,
         mono=True, tracking=0.0, align="center"):
    """A shape drawable: `r` is its <corners> radius in dp and `sw` its <stroke> width."""
    st = (f"rounded=1;absoluteArcSize=1;arcSize={2 * r};whiteSpace=wrap;html=1;"
          f"fillColor={fill};strokeColor={stroke};strokeWidth={sw};fontSize={size};fontColor={color};"
          f"align={align};verticalAlign=middle;"
          + (MONO if mono else "fontFamily=Helvetica;")
          + (f"letterSpacing={round(size * tracking, 2)};" if tracking else ""))
    return (nid, label, x, y, w, h, st)


def circle(nid, x, y, d, fill, stroke="none", sw=1):
    return (nid, "", x, y, d, d,
            f"ellipse;whiteSpace=wrap;html=1;fillColor={fill};strokeColor={stroke};strokeWidth={sw};")


# The app's own vector drawables: Android pathData is SVG path data, so these are the files under
# app/src/main/res/drawable transcribed, and the colour is the imageTintList the code sets. Change
# a drawable and change it here; a mock-up with the wrong glyph is worse than no mock-up.
_ICONS = {
    "signal": ("0 0 22 18",
               '<path d="M0,12 h4 v6 h-4 z" fill="{c}"/><path d="M6,8 h4 v10 h-4 z" fill="{c}"/>'
               '<path d="M12,4 h4 v14 h-4 z" fill="{c}"/><path d="M18,0 h4 v18 h-4 z" fill="{c}"/>'),
    "wifi": ("0 0 24 24",
             '<g fill="none" stroke="{c}" stroke-width="2" stroke-linecap="round">'
             '<path d="M2,9 A15,15 0 0 1 22,9"/><path d="M5.5,12.5 A10,10 0 0 1 18.5,12.5"/>'
             '<path d="M9,16 A5,5 0 0 1 15,16"/></g>'
             '<path d="M12,18.5 A1.2,1.2 0 1 1 12,20.9 A1.2,1.2 0 1 1 12,18.5 Z" fill="{c}"/>'),
    "bluetooth": ("0 0 24 24",
                  '<path d="M7,7 L17,17 L12,22 L12,2 L17,7 L7,17" fill="none" stroke="{c}" '
                  'stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>'),
    "aware": ("0 0 24 24",
              '<g fill="none" stroke="{c}" stroke-width="2" stroke-linecap="round">'
              '<path d="M12,10 A2,2 0 1 1 12,14 A2,2 0 1 1 12,10 Z"/>'
              '<path d="M8.5,8.5 A5,5 0 0 0 8.5,15.5"/><path d="M15.5,8.5 A5,5 0 0 1 15.5,15.5"/>'
              '<path d="M5.5,5.5 A9,9 0 0 0 5.5,18.5"/><path d="M18.5,5.5 A9,9 0 0 1 18.5,18.5"/></g>'),
    "volume": ("0 0 24 24",
               '<path fill="{c}" d="M3,9v6h4l5,5V4L7,9H3zm13.5,3c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,'
               '-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,'
               '-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"/>'),
    "ask": ("0 0 24 24",
            '<path d="M12,2.5 A9.5,9.5 0 1 1 12,21.5 A9.5,9.5 0 1 1 12,2.5 Z" fill="none" '
            'stroke="{c}" stroke-width="1.6"/>'
            '<path d="M12,5.5 L14.6,12 L12,18.5 L9.4,12 Z" fill="{c}"/>'),
    "more": ("0 0 24 24",
             '<g fill="{c}"><path d="M12,3 A2,2 0 1 1 12,7 A2,2 0 1 1 12,3 Z"/>'
             '<path d="M12,10 A2,2 0 1 1 12,14 A2,2 0 1 1 12,10 Z"/>'
             '<path d="M12,17 A2,2 0 1 1 12,21 A2,2 0 1 1 12,17 Z"/></g>'),
    # Not the app's: the toolbar's up arrow and the two system-bar glyphs, which the platform draws.
    "back": ("0 0 24 24",
             '<path d="M20,12 H5 M12,5 L5,12 L12,19" fill="none" stroke="{c}" stroke-width="2" '
             'stroke-linecap="round" stroke-linejoin="round"/>'),
    "battery": ("0 0 24 12",
                '<rect x="0.5" y="0.5" width="20" height="11" rx="3" fill="none" stroke="{c}"/>'
                '<rect x="2.5" y="2.5" width="13" height="7" rx="1.5" fill="{c}"/>'
                '<path d="M22,4 v4" stroke="{c}" stroke-width="2" stroke-linecap="round"/>'),
}


def icon(nid, name, x, y, w, h, color):
    box, body = _ICONS[name]
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{box}" width="{w}" height="{h}">'
           + body.format(c=color) + "</svg>")
    data = base64.b64encode(svg.encode("utf-8")).decode("ascii")
    return (nid, "", x, y, w, h,
            f"shape=image;html=1;imageAspect=0;image=data:image/svg+xml,{data};")


def signal_bars(nid, x, y, w, h, color, lit, over=BG):
    """ic_signal.xml as the level-list it is: `lit` of the four bars in `color`, the rest at the
    0.3 fillAlpha of ic_signal_<n>.xml, resolved against the ground the view sits on."""
    dim = mix(color, 0.3, over)
    bars = [("M0,12 h4 v6 h-4 z", 0), ("M6,8 h4 v10 h-4 z", 1), ("M12,4 h4 v14 h-4 z", 2), ("M18,0 h4 v18 h-4 z", 3)]
    body = "".join(f'<path d="{d}" fill="{color if i < lit else dim}"/>' for d, i in bars)
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 22 18" width="{w}" height="{h}">{body}</svg>'
    data = base64.b64encode(svg.encode("utf-8")).decode("ascii")
    return (nid, "", x, y, w, h, f"shape=image;html=1;imageAspect=0;image=data:image/svg+xml,{data};")


def helv_w(s, size):
    """Roughly what Helvetica advances a string of mixed text: 0.55 em a glyph."""
    return int(len(s) * size * 0.55 + 0.5)


def phone_body(nid, x, y):
    return (nid, "", x, y, FRAME_W, FRAME_H,
            f"rounded=1;absoluteArcSize=1;arcSize=32;whiteSpace=wrap;html=1;fillColor={BG};strokeColor=none;")


def phone_edge(nid, x, y, spill=0):
    """Drawn last: a mask over whatever the screen was too short to show, then the screen edge."""
    n = []
    if spill > 0:
        # Exactly the frame's width, never a pixel more: the export crops to the drawing, so a
        # wider mask would set the canvas width instead of the phone and the same phone would come
        # out a different size in two pictures the README shows at one width.
        n.append((nid + "m", "", x, y + FRAME_H, FRAME_W, spill + 4,
                  f"rounded=0;whiteSpace=wrap;html=1;fillColor={PAGE};strokeColor=none;"))
    n.append((nid + "e", "", x, y, FRAME_W, FRAME_H,
              f"rounded=1;absoluteArcSize=1;arcSize=32;whiteSpace=wrap;html=1;fillColor=none;"
              f"strokeColor={BEZEL};strokeWidth=2;"))
    return n


def system_bar(p, x, y, tint=TEXT):
    """The status bar the app draws under. No carrier and no device name: it is a drawing.

    The `sys` in the ids is not decoration: the channel row on the main screen is `p + "sw"`, and
    a wifi glyph called the same thing made two cells with one id in every drawing that has both.
    """
    return [
        txt(p + "clk", "14:25", x + 18, y + 4, 60, 20, 12, tint, mono=False),
        icon(p + "sysw", "wifi", x + FRAME_W - 72, y + 6, 16, 16, tint),
        icon(p + "syss", "signal", x + FRAME_W - 51, y + 8, 15, 12, tint),
        icon(p + "sysb", "battery", x + FRAME_W - 32, y + 8, 20, 10, tint),
    ]


def toggle(nid, x, y, on):
    """A Material 3 switch: a 52 x 32 track, a 24 dp handle when on and a 16 dp one when off."""
    if on:
        return [rect(nid + "t", x, y, 52, 32, fill=PRIMARY, stroke=PRIMARY, r=16, sw=2),
                circle(nid + "k", x + 24, y + 4, 24, ON_PRIMARY)]
    return [rect(nid + "t", x, y, 52, 32, fill=CARD, stroke=OUTLINE, r=16, sw=2),
            circle(nid + "k", x + 8, y + 8, 16, TEXT_DIM)]


# ---------------------------------------------------------------- the main screen

def main_screen(p, x, y, state, peers=None, talking=None, ask_row=False, scrim=1.0, level=3,
                tiles=((False, "wifi", "WLAN"), (True, "bluetooth", "BLUETOOTH"), (True, "aware", "AWARE"))):
    """activity_main.xml. state: 'off' | 'on' | 'air'; `scrim` dims it under the ask sheet.

    The tiles are dimmed while the phone is on the channel (they are a before-Connect choice), and
    the talk disc and the mute glyph while it is off it, the way MainActivity.syncUi does it. Off
    the channel the roster is empty, so the count is 0 and the bars beside it are all unlit; on it,
    `level` is the weakest link aboard, the way renderRoster draws it.
    """
    on = state != "off"
    live = state == "air"
    if peers is None:
        peers = "2" if on else "0"
    if not on:
        level = 0

    def c(col, alpha=1.0):
        return mix(col, alpha * scrim)

    n = [phone_body(p + "f", x, y)]
    n += system_bar(p, x, y, c(TEXT))
    cl = x + PAD
    top = y + STATUS_BAR + PAD

    # Header: the channel label, or who is talking; the crew name; the head count; the menu.
    label = ("● %s TALKING" % talking) if talking else "CHANNEL"
    n.append(txt(p + "h1", label, cl, top, 220, 16, 12, c(TALKING if talking else TEAL_DIM), tracking=0.18))
    n.append(txt(p + "h2", "CREW RADIO", cl, top + 16, 200, 40, 27, c(PRIMARY), bold=True, tracking=0.1,
                 font="fontFamily=Helvetica;"))
    box_w = 12 + 22 + 10 + mono_w(peers, 18) + 12
    box_x = cl + CW - 44 - 4 - box_w
    n.append(rect(p + "hb", box_x, top + 6, box_w, 44, fill="none", stroke=c(OUTLINE), r=12))
    weak = on and level <= 1
    n.append(signal_bars(p + "hi", box_x + 12, top + 19, 22, 18,
                         c(ERROR if weak else TALKING if talking else PRIMARY), level))
    n.append(txt(p + "hc", peers, box_x + 44, top + 6, box_w - 56, 44, 18,
                 c(ERROR if weak else TALKING if talking else TEXT), bold=True))
    n.append(icon(p + "hm", "more", cl + CW - 34, top + 16, 24, 24, c(SECONDARY)))

    # Transport tiles: an icon over a label, teal and filled in when the transport is switched on.
    ty = top + 56 + 12
    tile_alpha = 0.55 if on else 1.0
    tx = cl
    for i, ((active, glyph, name), tw) in enumerate(zip(tiles, (103, 102, 103))):
        fill = c(TILE_ON, tile_alpha) if active else "none"
        tint = c(PRIMARY if active else TEXT_DIM, tile_alpha)
        n.append(rect(p + f"t{i}", tx, ty, tw, 76, fill=fill,
                      stroke=c(PRIMARY if active else OUTLINE, tile_alpha), r=14, sw=2 if active else 1))
        n.append(icon(p + f"ti{i}", glyph, tx + tw // 2 - 13, ty + 14, 26, 26, tint))
        n.append(txt(p + f"tl{i}", name, tx, ty + 46, tw, 16, 12, tint, align="center", tracking=0.08))
        tx += tw + 10
    ry = ty + 76

    # The Bluetooth peer row: a before-Connect choice, so the app hides it while on the channel.
    if tiles[1][0] and not on:
        ry += 12
        n.append(rect(p + "pr", cl, ry, CW, 48, fill="none", stroke=c(OUTLINE), r=12))
        n.append(txt(p + "prl", "PEER · SKIPPER'S PHONE", cl + 16, ry, CW - 32, 48, 13,
                     c(SECONDARY), tracking=0.06))
        ry += 48

    # The channel row: the whole row toggles, and the switch mirrors it.
    ry += 12
    n.append(rect(p + "sw", cl, ry, CW, 52, fill="none", stroke=c(OUTLINE), r=12))
    n.append(txt(p + "swl", "ON CHANNEL" if on else "OFF · TAP TO JOIN", cl + 16, ry, 220, 52, 13,
                 c(PRIMARY if on else TEXT_DIM), tracking=0.08))
    n += toggle(p + "swt", cl + CW - 12 - 52, ry + 10, on)
    ry += 52

    # The volume row: the app's own mute, the phone's call volume, and the step it is on.
    ry += 12
    n.append(rect(p + "vr", cl, ry, CW, 52, fill="none", stroke=c(OUTLINE), r=12))
    n.append(icon(p + "vi", "volume", cl + 16, ry + 14, 24, 24, c(SECONDARY, 1.0 if on else 0.55)))
    sx, slider = cl + 56, 204
    n.append(rect(p + "vt", sx, ry + 23, slider, 6, fill=c(OUTLINE), stroke="none", r=3))
    knob = sx + 11 + round((9 - 1) / (15 - 1) * (slider - 22))   # step 9 of a 1..15 call-volume stream
    n.append(rect(p + "vf", sx, ry + 23, knob - sx, 6, fill=c(PRIMARY), stroke="none", r=3))
    n.append(circle(p + "vk", knob - 11, ry + 15, 22, c(PRIMARY)))
    n.append(txt(p + "vv", "9", cl + CW - 16 - 44, ry, 44, 52, 14, c(TEXT), bold=True, align="right"))
    ry += 52

    # Ask boat data: hidden altogether unless a Signal K server is set and the setting is on.
    if ask_row:
        ry += 12
        n.append(rect(p + "ar", cl, ry, CW, 52, fill="none", stroke=c(OUTLINE), r=12))
        n.append(icon(p + "ai", "ask", cl + 14, ry + 14, 24, 24, c(PRIMARY)))
        n.append(txt(p + "al", "ASK BOAT DATA", cl + 52, ry, 220, 52, 13, c(SECONDARY), tracking=0.08))
        ry += 52

    # The disc: min(width, height) of whatever space is left, which on a phone is the width.
    ry += 8
    area = y + FRAME_H - PAD - ry
    d = min(CW, area)
    cx, cy = cl + CW // 2, ry + area // 2
    alpha = 1.0 if on else 0.55
    big, hint = ("ON AIR", "RELEASE TO LISTEN") if live else ("TALK", "HOLD" if on else "JOIN THE CHANNEL FIRST")
    n.append(circle(p + "d", cx - d // 2 + 6, cy - d // 2 + 6, d - 12,
                    c(ON_AIR if live else PRIMARY, alpha), c(ERROR if live else OUTLINE, alpha), 12))
    n.append(txt(p + "d1", big, cx - d // 2, cy - 34, d, 48, 40,
                 c(ON_AIR_TEXT if live else ON_PRIMARY, alpha), bold=True, align="center", tracking=0.14))
    n.append(txt(p + "d2", hint, cx - d // 2, cy + 16, d, 18, 12,
                 c(ERROR if live else PRIMARY_CONTAINER, alpha), bold=True, align="center", tracking=0.14))
    return n


def main_phone(p, x, y, **kw):
    return main_screen(p, x, y, **kw) + phone_edge(p, x, y)


# ---------------------------------------------------------------- the Status screen

def toolbar(p, x, y, title):
    """A MaterialToolbar in the layout, on the window background: no bar colour, no elevation."""
    return [icon(p + "tb", "back", x + 16, y + STATUS_BAR + 16, 24, 24, TEXT),
            txt(p + "tt", title, x + 72, y + STATUS_BAR, 200, 56, 22, TEXT, mono=False)]


def status_card(p, key, x, ytop, rows_h, title, aside, cut=None):
    """card_status.xml: 16/12/16/8 padding, a teal title with an aside, then the rows.

    `cut` is where the screen ends: the last card on a scrolling screen is drawn only that far,
    so it runs off the bottom edge instead of standing complete under it."""
    h = 12 + 16 + 4 + rows_h + 8
    if cut is not None:
        h = min(h, cut - ytop)
    n = [rect(p + key, x + PAD, ytop, CW, h, fill=CARD, stroke=CARD, r=14)]
    n.append(txt(p + key + "t", title, x + PAD + 16, ytop + 12, 150, 16, 12, TEAL_DIM, tracking=0.16))
    n.append(txt(p + key + "a", aside, x + PAD + CW - 16 - 190, ytop + 12, 190, 16, 12, TEAL_DIM,
                 align="right", tracking=0.16))
    return n, ytop + 32, ytop + h


def kv_rows(p, key, x, ytop, rows, cut=None):
    """row_kv.xml: a spaced mono label, its value flush right, 36 dp a row. A row given a third
    element draws that many of the link meter's bars after the value, 10 dp apart, the way the
    WI-FI SIGNAL row carries them as a compound drawable."""
    n = []
    for i, row in enumerate(rows):
        k, v = row[0], row[1]
        ry = ytop + i * 36
        if cut is not None and ry >= cut:
            break
        n.append(txt(p + f"{key}k{i}", k, x + PAD + 16, ry, 150, 36, 12, TEXT_DIM, tracking=0.08))
        right = x + PAD + CW - 16
        if len(row) > 2:
            n.append(signal_bars(p + f"{key}b{i}", right - 22, ry + 9, 22, 18, PRIMARY, row[2], over=CARD))
            right -= 22 + 10
        n.append(txt(p + f"{key}v{i}", v, x + PAD + 166, ry, right - (x + PAD + 166), 36, 15, TEXT, align="right", mono=False))
    return n


def status_screen(p, x, y):
    """Draw the Status screen and return its nodes and the bottom of its scrollable content."""
    n = [phone_body(p + "f", x, y)]
    n += system_bar(p, x, y)
    n += toolbar(p, x, y, "Status")
    cl = x + PAD
    top = y + STATUS_BAR + 56 + PAD

    n.append(txt(p + "h1", "CHANNEL", cl, top, 200, 16, 12, TEAL_DIM, tracking=0.18))
    n.append(txt(p + "h2", "CREW RADIO", cl, top + 16, 200, 40, 27, PRIMARY, bold=True, tracking=0.1,
                 font="fontFamily=Helvetica;"))
    pill_w = mono_w("ON CHANNEL", 13, 0.1) + 28
    n.append(rect(p + "pill", cl + CW - pill_w, top + 6, pill_w, 44, fill="none", stroke=OUTLINE, r=12,
                  label="ON CHANNEL", color=PRIMARY, size=13, tracking=0.1))

    # CREW: row_status_peer.xml, 56 dp a crew member (6 + 24 + 2 + 18 + 6). The bars are the
    # member's link level, 12 dp before the meta text, green with the rest of the row while talking.
    crew = [("Mate", "TALKING", TALKING, 4, "on BT+Aware · id 7a91c2e0 · heard just now"),
            ("Skipper's phone", "AWARE · 1 HOP", TEXT_DIM, 3, "on BT · id cfe7198c · heard just now")]
    nodes, rows, bottom = status_card(p, "c1", x, top + 68, len(crew) * 56, "CREW", "2 ABOARD")
    n += nodes
    for i, (name, meta, meta_c, level, detail) in enumerate(crew):
        ry = rows + i * 56
        n.append(circle(p + f"pd{i}", cl + 16, ry + 13, 10, meta_c if meta_c == TALKING else DOT_IDLE))
        n.append(txt(p + f"pn{i}", name, cl + 38, ry + 6, 170, 24, 17, TEXT, mono=False))
        meta_w = mono_w(meta, 12, 0.06)
        n.append(signal_bars(p + f"pl{i}", cl + CW - 16 - meta_w - 12 - 22, ry + 9, 22, 18,
                             ERROR if level <= 1 else TALKING if meta_c == TALKING else PRIMARY, level, over=CARD))
        n.append(txt(p + f"pm{i}", meta, cl + CW - 156, ry + 6, 140, 24, 12, meta_c, align="right",
                     tracking=0.06))
        n.append(txt(p + f"pt{i}", detail, cl + 38, ry + 32, CW - 54, 18, 13, TEXT_DIM, mono=False))

    # THIS PHONE. The voice-gate row only appears while a mic monitor runs, so it is not here.
    phone = [("MY NAME", "Deckhand"), ("MODE", "Half duplex"), ("RELAY", "On"), ("CODEC", "Opus"),
             ("AUDIO", "Headset · Jabra"), ("CALL VOLUME", "9 / 15"), ("HOP LIMIT", "4"),
             ("VERSION", "1.140 · 0d9e190"), ("UPDATES", "")]
    nodes, rows, bottom = status_card(p, "c2", x, bottom + 12, len(phone) * 36, "THIS PHONE", "428deaea")
    n += nodes
    n += kv_rows(p, "ph", x, rows, phone)
    # The one row on this screen that does something: teal, and it opens the Releases page.
    n.append(txt(p + "upd", "Check for updates", cl + 166, rows + 8 * 36, CW - 182, 36, 15, PRIMARY,
                 align="right", mono=False))

    # NETWORK: the interfaces the phone actually has, then the endpoints. Cut off by the screen
    # edge, which is what the rest of a scrolling screen looks like in a screenshot.
    net = [("WLAN0", "192.168.0.35/24"), ("AWARE_DATA0", "fe80::1234:5678:9abc:def0"),
           ("WI-FI SIGNAL", "-58 dBm", 3),
           ("MULTICAST", "239.255.42.1:47474"), ("AWARE", "crewradio"),
           ("CHANNEL KEY", "…pd2h (ends)"), ("BLUETOOTH", "Mate's phone")]
    cut = y + FRAME_H
    nodes, rows, bottom = status_card(p, "c3", x, bottom + 12, len(net) * 36, "NETWORK", "BT + AWARE",
                                      cut=cut + 20)
    n += nodes
    n += kv_rows(p, "nw", x, rows, net, cut=cut)
    return n, bottom


def status_phone(p, x, y):
    n, bottom = status_screen(p, x, y)
    return n + phone_edge(p, x, y, spill=bottom - (y + FRAME_H))


# ---------------------------------------------------------------- the Settings screen

def settings_screen(p, x, y):
    """res/xml/preferences.xml through AndroidX's preference_material.xml: 16 dp padding, a 16 sp
    title over a 14 sp summary with 16 dp above and below, categories in teal 16 dp further down.
    The list is far longer than one screen; this is the top of it, as a screenshot would be."""
    n = [phone_body(p + "f", x, y)]
    n += system_bar(p, x, y)
    n += toolbar(p, x, y, "Settings")
    cy = y + STATUS_BAR + 56

    items = [
        ("cat", "Me"),
        ("row", "My name", ["Deckhand"]),
        ("cat", "Channel"),
        ("row", "Channel name", ["CREW RADIO"]),
        ("row", "Channel key", ["•••• •••• pd2h"]),
        ("row", "Show the key", ["Read it out to the crew; it is never shown in",
                                 "full anywhere else."]),
        ("row", "Share the key", ["Send it to another phone. Anyone who sees it",
                                  "can join the channel."]),
        ("row", "New random key", ["Makes a fresh key on this phone. Every other",
                                   "phone on the crew has to be given the new one."]),
        ("cat", "Talking"),
        ("sw", "Full duplex", ["Hold the big button to talk; others are",
                               "muted while you hold it."], False),
    ]
    for i, it in enumerate(items):
        if it[0] == "cat":
            cy += 16
            n.append(txt(p + f"i{i}", it[1], x + 16, cy + 8, 260, 20, 14, PRIMARY, bold=True, mono=False))
            cy += 36
            continue
        lines = it[2]
        w = 260 if it[0] == "sw" else CW
        h = 16 + 22 + 20 * len(lines) + 16
        n.append(txt(p + f"i{i}", it[1], x + 16, cy + 16, w, 22, 16, TEXT, mono=False))
        for j, line in enumerate(lines):
            n.append(txt(p + f"s{i}_{j}", line, x + 16, cy + 38 + j * 20, w, 20, 14, TEXT_DIM, mono=False))
        if it[0] == "sw":
            n += toggle(p + f"g{i}", x + FRAME_W - 68, cy + h // 2 - 16, it[3])
        cy += h
    return n, cy


def settings_phone(p, x, y):
    n, bottom = settings_screen(p, x, y)
    return n + phone_edge(p, x, y, spill=bottom - (y + FRAME_H))


# ---------------------------------------------------------------- the Ask boat data sheet

def ask_sheet(p, x, y, state):
    """sheet_ask.xml over the main screen. state: 'listening' | 'answered'.

    The screen behind it is drawn in dimmed colours rather than under an opacity layer, so the
    export is the same on every draw.io version; only the sheet is at full strength, which is
    what these two pictures are about.
    """
    answered = state == "answered"
    n = main_screen(p, x, y, "on", ask_row=True, scrim=0.30)

    body = (12 + 4 + 14 + 16 + (0 if answered else 14 + 36) + 14 + 46
            + (6 + 26 + 8 + 16 if answered else 0) + 14 + 40 + 18)
    sy = y + FRAME_H - body
    # 28 dp of it falls past the bottom edge, so the sheet's own rounded bottom never shows.
    n.append(rect(p + "sh", x, sy, FRAME_W, body + 28, fill=CARD, stroke=CARD, r=28))
    n.append(rect(p + "gr", x + FRAME_W // 2 - 17, sy + 12, 34, 4, fill=OUTLINE, stroke="none", r=2))

    cy = sy + 30
    n.append(txt(p + "st", "HEARD" if answered else "LISTENING", x, cy, FRAME_W, 16, 12, TEAL_DIM,
                 align="center", tracking=0.18))
    cy += 16
    if not answered:
        # LevelBars: nine 5 dp bars, 4 dp apart, growing from the middle of a 36 dp strip.
        cy += 14
        bx = x + FRAME_W // 2 - (5 * 9 + 4 * 8) // 2
        for i, lv in enumerate((0.12, 0.45, 0.80, 0.35, 1.00, 0.62, 0.28, 0.55, 0.18)):
            bh = round(5 + lv * 31)
            n.append(rect(p + f"lb{i}", bx + i * 9, cy + (36 - bh) // 2, 5, bh, fill=PRIMARY,
                          stroke="none", r=3))
        cy += 36
    cy += 14
    if answered:
        n.append(txt(p + "hd", "Heading", x + 20, cy, CW, 46, 17, TEXT, align="center", mono=False))
        cy += 52
        n.append(txt(p + "an", "heading 245 degrees.", x + 20, cy, CW, 26, 20, PRIMARY, bold=True,
                     align="center", mono=False))
        cy += 34
        n.append(txt(p + "dt", "navigation.headingMagnetic · 2 s ago", x + 20, cy, CW, 16, 12,
                     TEXT_DIM, align="center"))
        cy += 16
    else:
        n.append(txt(p + "hd", "Heading, speed, depth, wind, position…", x + 20, cy, CW, 46, 17,
                     TEXT_DIM, align="center", mono=False))
        cy += 46
    cy += 14

    pill_w = mono_w("Whole crew", 11, 0.08) + 28
    again_w, done_w = 90, 62
    total = pill_w + (10 + again_w if answered else 0) + 10 + done_w
    bx = x + (FRAME_W - total) // 2
    n.append(rect(p + "pill", bx, cy + 2, pill_w, 36, fill="none", stroke=PRIMARY, r=14,
                  label="Whole crew", color=PRIMARY, size=11, tracking=0.08))
    bx += pill_w + 10
    if answered:
        n.append(txt(p + "b1", "Ask again", bx, cy, again_w, 40, 14, PRIMARY, align="center", mono=False))
        bx += again_w + 10
    n.append(txt(p + "b2", "Done" if answered else "Cancel", bx, cy, done_w, 40, 14, SECONDARY,
                 align="center", mono=False))
    return n + phone_edge(p, x, y, spill=28)


# Every phone is the same 360 x 800 frame, so every mock-up exports onto the same canvas and the
# README can put any two of them side by side without one coming out larger than the other.
PAIR_X = 416        # one canvas width across, so the gutter is just the two 16-pixel borders

diagram("screens", "The screens", nodes=(
    [txt("t0", "Off the channel", 20, 20, FRAME_W, 30, 14, "#000000", bold=True, align="center", mono=False)]
    + main_phone("a", 20, 60, state="off")
    + [txt("t1", "On the channel", 436, 20, FRAME_W, 30, 14, "#000000", bold=True, align="center", mono=False)]
    + main_phone("b", 436, 60, state="on", talking="MATE")
    + [txt("t2", "Talking", 852, 20, FRAME_W, 30, 14, "#000000", bold=True, align="center", mono=False)]
    + main_phone("c", 852, 60, state="air")
), edges=[], width=1240, height=900)

diagram("screen-main", "Main screen", nodes=main_phone("a", 20, 20, state="on", talking="MATE"),
        edges=[], width=400, height=840)
diagram("screen-on-air", "On air", nodes=main_phone("a", 20, 20, state="air"), edges=[], width=400, height=840)
diagram("screen-status", "Status screen", nodes=status_phone("s", 20, 20), edges=[], width=400, height=840)
diagram("screen-settings", "Settings screen", nodes=settings_phone("s", 20, 20), edges=[], width=400, height=840)

# The README shows these three pairs, each as ONE image. Two <img> tags side by side wrap onto
# separate lines on a phone, which put the screens one after the other exactly where a reader
# wants to compare them; a single image cannot wrap.
diagram("screens-quickstart", "Quick start", nodes=(
    main_phone("a", 20, 20, state="on", talking="MATE") + main_phone("b", PAIR_X, 20, state="air")
), edges=[], width=800, height=840)
diagram("screens-detail", "Status and settings", nodes=(
    status_phone("s", 20, 20) + settings_phone("t", PAIR_X, 20)
), edges=[], width=800, height=840)
# The add-on, so the pair reads left to right: the question going in, the answer coming back.
diagram("screens-ask", "Ask boat data", nodes=(
    ask_sheet("a", 20, 20, "listening") + ask_sheet("b", PAIR_X, 20, "answered")
), edges=[], width=800, height=840)


# ================================================================ flowcharts

# ---------------------------------------------------------------- 1. architecture
diagram("architecture", "How the app is built", nodes=[
    ("t", "Crew Radio - what runs where", 40, 20, 500, 30, TITLE),
    ("ui", "Main screen" + NL + "(MainActivity)", 40, 80, 170, 70, UI),
    ("st", "Status screen", 40, 170, 170, 50, UI),
    ("se", "Settings screen", 40, 240, 170, 50, UI),
    ("svc", "Background service (PttService)" + NL + "keeps the channel alive with the screen off," + NL
     + "holds the notification, the talk keys," + NL + "the wake lock and the Wi-Fi lock", 280, 80, 300, 110, SVC),
    ("eng", "Engine (PttEngine)" + NL + "roster of the crew, relay, packet order," + NL + "who is talking, the voice gate",
     280, 230, 300, 90, ENGINE),
    ("cap", "Microphone" + NL + "(AudioCapture)" + NL + "16 kHz, 20 ms frames", 40, 400, 170, 70, AUDIO),
    ("enc", "Opus encoder" + NL + "(platform codec)", 40, 500, 170, 60, AUDIO),
    ("pk", "Packet" + NL + "18-byte header +" + NL + "Opus/PCM frame, or a hello" + NL + "(roster heartbeat)", 280, 460, 170, 80, END),
    ("lan", "WLAN" + NL + "UDP multicast + broadcast", 530, 400, 160, 60, NET),
    ("bt", "Bluetooth" + NL + "RFCOMM link to one peer", 530, 480, 160, 60, NET),
    ("aw", "Wi-Fi Aware" + NL + "phone-to-phone, no router", 530, 560, 160, 60, NET),
    ("dec", "Opus decoder" + NL + "per talker", 800, 400, 190, 50, AUDIO),
    ("mix", "Mixer" + NL + "one queue per talker," + NL + "loss concealment, cue tones", 800, 480, 190, 70, AUDIO),
    ("play", "Speaker, earpiece or headset" + NL + "(AudioPlayback + AudioRoute)", 800, 580, 190, 60, AUDIO),
    ("n1", "Every phone is the same: there is no server and no master. Each phone sends its own frames on every"
     + NL + "transport it has on and, with Relay on (the default) and hops remaining, forwards what it hears to its"
     + NL + "other transports, so a phone that has both Wi-Fi Aware and Bluetooth bridges the two.", 40, 660, 900, 60, NOTE),
], edges=[
    ("ui", "svc", "binds while visible", EDGE),
    ("st", "eng", "polls once a second", EDGE_DASH + "exitX=1;exitY=0.5;entryX=0;entryY=0.3;"),
    ("se", "eng", "settings pushed live", EDGE_DASH + "exitX=1;exitY=0.5;entryX=0;entryY=0.7;"),
    ("svc", "eng", "", EDGE),
    ("eng", "cap", "keys the mic", EDGE + "exitX=0.15;exitY=1;entryX=0.5;entryY=0;"),
    ("cap", "enc", "", EDGE),
    ("enc", "pk", "", EDGE),
    ("pk", "lan", "", EDGE + "exitX=1;exitY=0.3;entryX=0;entryY=0.5;"),
    ("pk", "bt", "", EDGE + "exitX=1;exitY=0.5;entryX=0;entryY=0.5;"),
    ("pk", "aw", "", EDGE + "exitX=1;exitY=0.7;entryX=0;entryY=0.5;"),
    ("lan", "eng", "", EDGE_DASH + "exitX=0.5;exitY=0;entryX=0.85;entryY=1;"),
    ("bt", "eng", "received" + NL + "packets", EDGE_DASH + "exitX=1;exitY=0.5;entryX=1;entryY=0.7;", [(740, 510), (740, 293)]),
    ("aw", "eng", "", EDGE_DASH + "exitX=1;exitY=0.5;entryX=1;entryY=0.85;", [(720, 590), (720, 306)]),
    ("eng", "dec", "decode and play", EDGE + "exitX=1;exitY=0.3;entryX=0.5;entryY=0;"),
    ("dec", "mix", "", EDGE),
    ("mix", "play", "", EDGE),
], width=1020, height=740)

# ---------------------------------------------------------------- 2. packet flow (receive path)
diagram("packet-flow", "What happens to a received packet", nodes=[
    ("t", "A packet arrives on any transport", 40, 20, 500, 30, TITLE),
    ("s", "packet", 40, 70, 100, 50, START),
    ("d1", "our own?", 200, 60, 120, 70, DECISION),
    ("d2", "seen before?" + NL + "(kind, sender, number)", 370, 60, 140, 70, DECISION),
    ("r", "relay: forward to every other transport" + NL + "and link, hop count minus one" + NL + "(if relay is on and hops remain)",
     560, 60, 260, 70, STEP),
    ("drop", "drop", 210, 170, 100, 50, END),
    ("drop2", "drop: a duplicate" + NL + "via another path", 380, 170, 120, 60, END),
    ("d3", "a hello?", 370, 280, 140, 70, DECISION),
    ("ro", "update the roster:" + NL + "name, transports, hops, seen now", 560, 280, 260, 70, STEP),
    ("ta", "mark the sender as talking", 370, 400, 140, 60, STEP),
    ("d4", "half duplex and" + NL + "we are transmitting?", 370, 500, 140, 80, DECISION),
    ("ign", "not played" + NL + "(radio semantics)", 200, 510, 120, 60, END),
    ("gap", "gap in the sender's numbers?" + NL + "reserve up to 3 slots to conceal", 560, 505, 260, 70, STEP),
    ("dec", "decode (Opus) or take raw PCM", 560, 605, 260, 50, STEP),
    ("mix", "mixer: queue per talker, summed," + NL + "missing slots faded from the last frame", 560, 685, 260, 60, STEP),
    ("out", "speaker / earpiece / headset", 560, 775, 260, 50, END),
], edges=[
    ("s", "d1", "", EDGE),
    ("d1", "d2", "no", EDGE),
    ("d1", "drop", "yes", EDGE),
    ("d2", "r", "no", EDGE),
    ("d2", "drop2", "yes", EDGE),
    ("r", "d3", "", EDGE, [(690, 250), (440, 250)]),
    ("d3", "ro", "yes", EDGE),
    ("d3", "ta", "no", EDGE),
    ("ta", "d4", "", EDGE),
    ("d4", "ign", "yes", EDGE),
    ("d4", "gap", "no", EDGE),
    ("gap", "dec", "", EDGE),
    ("dec", "mix", "", EDGE),
    ("mix", "out", "", EDGE),
], width=880, height=860)

# ---------------------------------------------------------------- 3. talk keys
diagram("talk-keys", "Ways to key the mic", nodes=[
    ("t", "How the mic gets keyed", 40, 20, 500, 30, TITLE),
    ("b", "Big button on screen", 40, 80, 180, 50, UI),
    ("v", "Phone volume keys" + NL + "(screen on or off)", 40, 150, 180, 60, UI),
    ("h", "Headset / media button" + NL + "(wired, or Bluetooth when it" + NL + "reaches the app)", 40, 230, 180, 70, UI),
    ("vox", "Your voice" + NL + "(Voice keys the mic)", 40, 320, 180, 60, UI),
    ("hd", "Half duplex: hold to talk" + NL + "Full duplex: tap to toggle", 300, 80, 220, 50, STEP),
    ("vt", "press = on, next press = off" + NL + "(a held key counts once)", 300, 150, 220, 60, STEP),
    ("ht", "click = on / off" + NL + "hold = talk while held", 300, 230, 220, 70, STEP),
    ("g", "speech opens the gate," + NL + "1.5 s of quiet closes it", 300, 320, 220, 60, STEP),
    ("d", "with a Bluetooth headset:" + NL + "only if the setting is on", 560, 300, 200, 50, DECISION + "fontSize=10;"),
    ("d2", "on the phone itself:" + NL + "only while it is at your ear" + NL + "(proximity sensor)", 560, 370, 200, 60, DECISION + "fontSize=10;"),
    ("mic", "Mic on" + NL + "(ON AIR)", 820, 190, 120, 70, END + "fillColor=#FFCDD2;strokeColor=#C62828;fontStyle=1;"),
    ("fb", "Feedback: the disc turns red, a short buzz on the phone," + NL + "optionally one tone in the ear (two when off)."
     + NL + "Half duplex: others are muted while you are on air.", 300, 430, 460, 60, NOTE),
], edges=[
    ("b", "hd", "", EDGE), ("v", "vt", "", EDGE), ("h", "ht", "", EDGE), ("vox", "g", "", EDGE),
    ("hd", "mic", "", EDGE), ("vt", "mic", "", EDGE), ("ht", "mic", "", EDGE),
    ("g", "d", "", EDGE), ("g", "d2", "", EDGE),
    ("d", "mic", "yes", EDGE), ("d2", "mic", "yes", EDGE),
], width=980, height=520)

# ---------------------------------------------------------------- 4. audio route
diagram("audio-route", "Where the voice goes", nodes=[
    ("t", "Where the sound goes (Settings > Talking > Audio output)", 40, 20, 600, 30, TITLE),
    ("s", "on channel", 40, 80, 110, 50, START),
    ("d0", "setting?", 200, 70, 120, 70, DECISION),
    ("sp", "Loudspeaker", 780, 70, 150, 50, END),
    ("d1", "Bluetooth headset" + NL + "connected?", 380, 160, 150, 80, DECISION),
    ("bt", "Bluetooth headset" + NL + "(mic and ear)", 780, 175, 150, 50, END),
    ("d2", "wired / USB" + NL + "headset plugged in?", 380, 270, 150, 80, DECISION),
    ("wh", "Wired headset", 780, 285, 150, 50, END),
    ("d3", "phone at the ear?" + NL + "(proximity sensor)", 380, 380, 150, 80, DECISION),
    ("ep", "Earpiece" + NL + "+ voice keys the mic" + NL + "+ screen dark", 780, 380, 150, 70, END),
    ("sp2", "Loudspeaker" + NL + "+ the button", 780, 470, 150, 50, END),
    ("ep2", "Earpiece always" + NL + "(voice keys the mic at the ear)", 200, 480, 200, 60, END),
    ("n", "With the default setting the route follows changes live: connect a headset and the voice moves to it,"
     + NL + "lift the phone to your ear and it becomes a phone call, put it down and it is a radio again."
     + NL + "The two forced settings ignore headsets and the ear.", 40, 570, 700, 60, NOTE),
], edges=[
    ("s", "d0", "", EDGE),
    ("d0", "sp", "always the loudspeaker", EDGE),
    ("d0", "d1", "default", EDGE, [(260, 200)]),
    ("d0", "ep2", "earpiece", EDGE, [(260, 510)]),
    ("d1", "bt", "yes", EDGE),
    ("d1", "d2", "no", EDGE),
    ("d2", "wh", "yes", EDGE),
    ("d2", "d3", "no", EDGE),
    ("d3", "ep", "yes", EDGE),
    ("d3", "sp2", "no", EDGE, [(455, 495)]),
], width=980, height=640)

# ---------------------------------------------------------------- 5. mesh
diagram("mesh", "A crew of three phones", nodes=[
    ("t", "Example: three phones, two kinds of link, everyone hears everyone", 40, 20, 700, 30, TITLE),
    ("a", "Phone A" + NL + "Wi-Fi Aware + Bluetooth", 60, 120, 200, 70, PHONE),
    ("b", "Phone B" + NL + "Wi-Fi Aware only", 420, 120, 200, 70, PHONE),
    ("c", "Phone C" + NL + "Bluetooth only" + NL + "(no Wi-Fi Aware hardware)", 60, 300, 200, 80, PHONE),
    ("n1", "A hears B over Aware and forwards it to C over Bluetooth (relay). C hears A directly."
     + NL + "B reaches C only through A: on B's Status screen C shows as \"1 hop\"."
     + NL + "Each packet carries a hop count (4 by default) so nothing circulates forever,"
     + NL + "and a seen-list drops copies that arrive by two paths.", 320, 260, 500, 90, NOTE),
    ("n2", "On a boat with a WLAN router, WLAN is the third kind of link; a phone with WLAN and"
     + NL + "Bluetooth on at once bridges them the same way.", 320, 370, 500, 40, NOTE),
], edges=[
    ("a", "b", "Wi-Fi Aware", EDGE_BI + "strokeColor=#7B1FA2;strokeWidth=2;"),
    ("a", "c", "Bluetooth", EDGE_BI + "strokeColor=#0288D1;strokeWidth=2;"),
], width=880, height=440)

# ---------------------------------------------------------------- 6. links overview (the idea)
diagram("links", "One app, every link", nodes=[
    ("t", "One app, every kind of link between the phones", 40, 20, 700, 30, TITLE),
    ("p", "Your phone" + NL + "Crew Radio", 340, 200, 160, 70, PHONE),
    ("l1", "WLAN" + NL + "the boat's router or a hotspot:" + NL + "everyone on the same network", 40, 80, 220, 70, NET),
    ("l2", "Wi-Fi Aware" + NL + "phone to phone, no router," + NL + "Wi-Fi range", 40, 320, 220, 70, NET),
    ("l3", "Bluetooth" + NL + "phone to phone, close range," + NL + "any Android 10+ phone", 580, 80, 220, 70, NET),
    ("l4", "Relay (a setting, on by default, not a link):" + NL + "what this phone hears on one link it repeats on its other links,"
     + NL + "up to the hop limit, so it bridges them", 300, 320, 480, 60, ENGINE + "dashed=1;"),
    ("n", "Tick the links you have (WLAN, Wi-Fi Aware, Bluetooth). Phones find each other, no server, no account, no internet."
     + NL + "Lose one link and the others carry on; a phone in the middle bridges the rest.", 40, 440, 760, 40, NOTE),
], edges=[
    ("p", "l1", "", EDGE_BI + "strokeColor=#7B1FA2;"),
    ("p", "l2", "", EDGE_BI + "strokeColor=#7B1FA2;"),
    ("p", "l3", "", EDGE_BI + "strokeColor=#0288D1;"),
    ("p", "l4", "", EDGE_DASH + "strokeColor=#F9A825;endArrow=none;"),
], width=860, height=520)
