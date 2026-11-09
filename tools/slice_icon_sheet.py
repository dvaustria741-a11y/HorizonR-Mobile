#!/usr/bin/env python3
"""Cut the ChatGPT icon sprite sheet (4 columns x 3 rows) into white, transparent launcher icons.

usage: python3 tools/slice_icon_sheet.py icon_sheet.png
writes packaging/android/res/drawable-nodpi/ic_ui_<name>.png (128x128)
"""
import sys
from pathlib import Path
from PIL import Image

NAMES = [
    "game", "mods", "skins", "servers",
    "store", "logs", "settings", "folder",
    "worlds", "shaders", "play", "download",
]
COLS, ROWS, OUT = 4, 3, 128
dest = Path(__file__).resolve().parent.parent / "packaging/android/res/drawable-nodpi"

sheet = Image.open(sys.argv[1]).convert("RGBA")
cw, ch = sheet.width // COLS, sheet.height // ROWS
for i, name in enumerate(NAMES):
    cell = sheet.crop(((i % COLS) * cw, (i // COLS) * ch, (i % COLS + 1) * cw, (i // COLS + 1) * ch))
    # brightness becomes alpha, colour is forced to pure white (works for white-on-black or white-on-transparent sheets)
    px = cell.load()
    for y in range(cell.height):
        for x in range(cell.width):
            r, g, b, a = px[x, y]
            px[x, y] = (255, 255, 255, min(a, max(r, g, b)))
    bbox = cell.getbbox()
    if bbox:
        cell = cell.crop(bbox)
    side = max(cell.size)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(cell, ((side - cell.width) // 2, (side - cell.height) // 2))
    canvas.resize((OUT, OUT), Image.LANCZOS).save(dest / f"ic_ui_{name}.png")
    print("wrote", f"ic_ui_{name}.png")
