"""Generates the block/item textures and mod icon for Simple ME Terminal.

Pure Python (no Pillow needed). Run from the project root:
    python tools/gen_textures.py
"""
import os
import random
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEX = os.path.join(ROOT, "src", "main", "resources", "assets", "simpleme", "textures", "block")
ITEM_TEX = os.path.join(ROOT, "src", "main", "resources", "assets", "simpleme", "textures", "item")
ICON = os.path.join(ROOT, "src", "main", "resources", "assets", "simpleme", "icon.png")


def write_png(path, pixels, w, h):
    raw = b""
    for y in range(h):
        raw += b"\x00"
        for x in range(w):
            raw += bytes(pixels[y * w + x])

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def rgb(hex_str, a=255):
    hex_str = hex_str.lstrip("#")
    return (int(hex_str[0:2], 16), int(hex_str[2:4], 16), int(hex_str[4:6], 16), a)


def shade(c, d):
    return (max(0, min(255, c[0] + d)), max(0, min(255, c[1] + d)), max(0, min(255, c[2] + d)), c[3])


def casing(seed):
    """Metal casing: light bevelled frame with a darker recessed centre."""
    rnd = random.Random(seed)
    px = [None] * 256
    base = rgb("8a8f99")
    for y in range(16):
        for x in range(16):
            c = shade(base, rnd.randint(-6, 6))
            if x == 0 or y == 0:
                c = shade(base, 38)
            elif x == 15 or y == 15:
                c = shade(base, -48)
            elif x == 1 or y == 1:
                c = shade(base, 18)
            elif x == 14 or y == 14:
                c = shade(base, -26)
            px[y * 16 + x] = c
    # rivets in the corners
    for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        px[y * 16 + x] = rgb("c9ced6")
        px[(y + 1) * 16 + x] = shade(base, -30) if y < 15 else px[(y + 1) * 16 + x]
    return px


def side():
    px = casing(1)
    inner = rgb("5b6069")
    for y in range(4, 12):
        for x in range(4, 12):
            c = inner
            if x == 4 or y == 4:
                c = shade(inner, -22)
            elif x == 11 or y == 11:
                c = shade(inner, 26)
            px[y * 16 + x] = c
    # small cyan status line
    for x in range(6, 10):
        px[7 * 16 + x] = rgb("3fd8e6")
        px[8 * 16 + x] = rgb("1f8fa0")
    return px


def top():
    px = casing(2)
    vent = rgb("3e424a")
    for y in (4, 6, 8, 10):
        for x in range(4, 12):
            px[y * 16 + x] = vent
            px[(y + 1) * 16 + x] = shade(px[(y + 1) * 16 + x], 12)
    return px


def front():
    px = casing(3)
    rnd = random.Random(42)
    screen = rgb("0e1a24")
    for y in range(2, 14):
        for x in range(2, 14):
            c = screen
            if x == 2 or y == 2:
                c = rgb("05090d")
            elif x == 13 or y == 13:
                c = rgb("2a3a46")
            px[y * 16 + x] = c
    # item "cells" on the screen
    palette = [rgb("3fd8e6"), rgb("59f0a0"), rgb("f0c75e"), rgb("e27b5b"), rgb("9b8cf2"), rgb("3fd8e6")]
    for gy in range(3):
        for gx in range(3):
            x0 = 4 + gx * 3
            y0 = 4 + gy * 3
            if rnd.random() < 0.85:
                col = palette[rnd.randrange(len(palette))]
                px[y0 * 16 + x0] = col
                px[y0 * 16 + x0 + 1] = shade(col, -40)
                px[(y0 + 1) * 16 + x0] = shade(col, -40)
                px[(y0 + 1) * 16 + x0 + 1] = shade(col, -80)
    # glowing bottom line
    for x in range(4, 12):
        px[12 * 16 + x] = rgb("1f8fa0")
    return px


def transfer_side():
    """Transfer chest side: casing with an arrow pointing up (items leave the chest)."""
    px = casing(4)
    inner = rgb("4a3f5c")
    for y in range(4, 12):
        for x in range(4, 12):
            c = inner
            if x == 4 or y == 4:
                c = shade(inner, -20)
            elif x == 11 or y == 11:
                c = shade(inner, 24)
            px[y * 16 + x] = c
    arrow = rgb("3fd8e6")
    for x in range(7, 9):
        for y in range(7, 11):
            px[y * 16 + x] = shade(arrow, -50)
    for i, y in enumerate(range(5, 8)):
        for x in range(7 - i, 9 + i):
            px[y * 16 + x] = arrow
    return px


def transfer_top():
    """Transfer chest top: a recessed opening with ender-like particles."""
    px = casing(5)
    rnd = random.Random(7)
    hole = rgb("120c1c")
    for y in range(3, 13):
        for x in range(3, 13):
            c = hole
            if x == 3 or y == 3:
                c = shade(hole, -10)
            elif x == 12 or y == 12:
                c = rgb("3a2d52")
            px[y * 16 + x] = c
    for _ in range(12):
        x = rnd.randint(4, 11)
        y = rnd.randint(4, 11)
        px[y * 16 + x] = rnd.choice([rgb("9b5cf2"), rgb("c08cff"), rgb("3fd8e6")])
    return px


def transfer_front(active):
    """Transfer chest front: a round "eye" that glows while the chest is switched on."""
    px = casing(6)
    ring = rgb("2b2236")
    if active:
        core, glow, spark = rgb("1f8fa0"), rgb("3fd8e6"), rgb("d8fbff")
    else:
        core, glow, spark = rgb("2a2a30"), rgb("4a4a52"), rgb("5c5c66")
    for y in range(3, 13):
        for x in range(3, 13):
            dx = x - 7.5
            dy = y - 7.5
            d = (dx * dx + dy * dy) ** 0.5
            if d <= 2.2:
                px[y * 16 + x] = spark if d <= 1.0 else glow
            elif d <= 3.6:
                px[y * 16 + x] = core
            elif d <= 4.9:
                px[y * 16 + x] = ring
    # status lamp
    lamp = rgb("59f0a0") if active else rgb("8a2a2a")
    px[13 * 16 + 12] = lamp
    px[13 * 16 + 13] = shade(lamp, -40)
    return px


def wireless_terminal():
    """Item: a hand-held terminal with a screen and an antenna."""
    clear = (0, 0, 0, 0)
    px = [clear] * 256
    body = rgb("8a8f99")
    screen = rgb("0e1a24")
    for y in range(4, 16):
        for x in range(3, 13):
            c = body
            if x == 3 or y == 4:
                c = shade(body, 30)
            elif x == 12 or y == 15:
                c = shade(body, -45)
            px[y * 16 + x] = c
    # antenna
    for y in range(0, 4):
        px[y * 16 + 10] = rgb("5b6069")
    px[0 * 16 + 10] = rgb("e27b5b")
    # screen with item cells
    for y in range(6, 11):
        for x in range(5, 11):
            px[y * 16 + x] = screen
    cells = [rgb("3fd8e6"), rgb("59f0a0"), rgb("f0c75e"), rgb("9b8cf2")]
    for i, (x, y) in enumerate(((6, 7), (8, 7), (6, 9), (8, 9))):
        px[y * 16 + x] = cells[i]
    # buttons
    px[12 * 16 + 5] = rgb("3fd8e6")
    px[12 * 16 + 7] = rgb("59f0a0")
    px[12 * 16 + 9] = rgb("e27b5b")
    px[14 * 16 + 5] = shade(body, -25)
    px[14 * 16 + 6] = shade(body, -25)
    px[14 * 16 + 9] = shade(body, -25)
    px[14 * 16 + 10] = shade(body, -25)
    return px


def upscale(px, factor):
    out = []
    size = 16 * factor
    for y in range(size):
        for x in range(size):
            out.append(px[(y // factor) * 16 + (x // factor)])
    return out


if __name__ == "__main__":
    write_png(os.path.join(TEX, "terminal_side.png"), side(), 16, 16)
    write_png(os.path.join(TEX, "terminal_top.png"), top(), 16, 16)
    write_png(os.path.join(TEX, "terminal_front.png"), front(), 16, 16)
    write_png(os.path.join(TEX, "transfer_chest_side.png"), transfer_side(), 16, 16)
    write_png(os.path.join(TEX, "transfer_chest_top.png"), transfer_top(), 16, 16)
    write_png(os.path.join(TEX, "transfer_chest_front.png"), transfer_front(True), 16, 16)
    write_png(os.path.join(TEX, "transfer_chest_front_off.png"), transfer_front(False), 16, 16)
    write_png(os.path.join(ITEM_TEX, "wireless_terminal.png"), wireless_terminal(), 16, 16)
    write_png(ICON, upscale(front(), 8), 128, 128)
    print("textures written to", TEX, "and", ITEM_TEX)
