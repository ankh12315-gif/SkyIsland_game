"""
SkyIsland launcher icon builder.

    python make_icon.py

Reads   launcher/skyisland-icon-source.jpg   (the 1024x1024 square source art)
Writes  launcher/skyisland.ico               (multi size, used by windres)
        launcher/icon-preview.png            (256x256, just for eyeballing)

Why the alpha mask
------------------
The source art is a rounded square sitting on a solid black background. A
Windows icon that keeps those black corners turns into a dark blob at 16x16.
So we knock the black frame out to transparency.

The corner radius is MEASURED, not guessed: on the top row of a rounded square
the black run ends exactly at x == radius. Two safety rules keep this from
eating real art:
  1. the mask is only applied when all four corners are actually near black,
  2. a pixel is made transparent only if the mask says "outside" AND the pixel
     is itself near black. A pixel carrying colour is never touched, so an
     over-estimated radius can at worst leave a sliver, never punch a hole.
"""

import os
import sys

from PIL import Image, ImageChops, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "skyisland-icon-source.jpg")
ICO = os.path.join(HERE, "skyisland.ico")
PNG = os.path.join(HERE, "icon-preview.png")

# Windows wants the small ones; Explorer and the taskbar pick what they need.
SIZES = [16, 20, 24, 32, 40, 48, 64, 128, 256]

BLACK_MAX = 40      # a channel max <= this counts as "the black frame"
MAX_RADIUS_FRAC = 0.30  # a black run longer than this is a margin, not a corner


def main():
    if not os.path.isfile(SRC):
        print("ERROR: source art not found: %s" % SRC)
        return 1

    im = Image.open(SRC).convert("RGBA")
    w, h = im.size
    px = im.load()
    print("source      : %dx%d" % (w, h))

    corners = [px[0, 0], px[w - 1, 0], px[0, h - 1], px[w - 1, h - 1]]
    print("corners     : %s" % (corners,))
    print("centre      : %s" % (px[w // 2, h // 2],))

    def black(p):
        return max(p[0], p[1], p[2]) <= BLACK_MAX

    if not all(black(c) for c in corners):
        print("mask        : NONE (corners are not black - keeping the image opaque)")
        radius = 0
    else:
        run_x = 0
        while run_x < w // 2 and black(px[run_x, 0]):
            run_x += 1
        run_y = 0
        while run_y < h // 2 and black(px[0, run_y]):
            run_y += 1
        print("black run   : top row ends at x=%d, left col ends at y=%d" % (run_x, run_y))
        frac = max(run_x, run_y) / float(w)
        if run_x < 3 or run_y < 3 or frac > MAX_RADIUS_FRAC:
            print("mask        : NONE (that is a margin, not a corner radius - keeping it opaque)")
            radius = 0
        else:
            radius = int(round((run_x + run_y) / 2.0))
            print("mask        : rounded rect, radius=%d (%.1f%% of width)" % (radius, 100.0 * radius / w))

    if radius > 0:
        ss = 4  # supersample the mask edge so the corner is not stair-stepped
        m = Image.new("L", (w * ss, h * ss), 0)
        ImageDraw.Draw(m).rounded_rectangle(
            [0, 0, w * ss - 1, h * ss - 1], radius=radius * ss, fill=255
        )
        mask = m.resize((w, h), Image.LANCZOS)

        r, g, b, _a = im.split()
        brightest = ImageChops.lighter(ImageChops.lighter(r, g), b)
        not_black = brightest.point(lambda v: 255 if v > BLACK_MAX else 0)

        alpha = ImageChops.lighter(mask, not_black)
        im.putalpha(alpha)
        print("alpha       : applied")

    im.save(ICO, format="ICO", sizes=[(s, s) for s in SIZES])
    im.resize((256, 256), Image.LANCZOS).save(PNG)

    back = Image.open(ICO)
    print("ico         : %s  (%d bytes)" % (ICO, os.path.getsize(ICO)))
    print("ico sizes   : %s" % (sorted(back.info.get("sizes", [])),))
    print("preview     : %s" % PNG)
    return 0


if __name__ == "__main__":
    sys.exit(main())
