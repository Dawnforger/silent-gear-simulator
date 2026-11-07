import math, sys
from PIL import Image, ImageDraw

OUT = sys.argv[1]
N, S = 32, 16  # 32x32 pixel art, scaled x16 -> 512


def base():
    im = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    # rounded dark slab with a lighter rim, MC-GUI style
    d.rounded_rectangle([0, 0, N - 1, N - 1], 4, fill=(30, 33, 44, 255))
    d.rounded_rectangle([1, 1, N - 2, N - 2], 3, outline=(58, 63, 82, 255))
    return im, d


def px(d, pts, c):
    for p in pts:
        d.point(p, fill=c)


def save(im, name):
    im.resize((N * S, N * S), Image.NEAREST).save(f"{OUT}/{name}")


# ---------------- SGSim: pickaxe with mixed-material head + tooltip ----------------
im, d = base()
# handle (wood) bottom-left -> head
for i in range(15):
    x, y = 5 + i, 27 - i
    d.point((x, y), fill=(140, 96, 52, 255))
    d.point((x + 1, y), fill=(98, 64, 32, 255))
    d.point((x, y - 1), fill=(176, 128, 72, 255))
# head: arc centred on the handle end, three material units (diamond / gold / iron)
mats = [((92, 236, 222), (32, 170, 160)),
        ((252, 219, 70), (214, 160, 20)),
        ((214, 214, 222), (150, 150, 165))]
cx, cy = 5.5, 26.5
for y in range(N):
    for x in range(N):
        dx, dy = x - cx, cy - y
        r = math.hypot(dx, dy); a = math.degrees(math.atan2(dy, dx))
        taper = 1.6 * (1 - abs(a - 45) / 32)
        if 14 <= a <= 76 and 19.2 - taper <= r <= 20.6 + 0.3:
            seg = 0 if a < 33 else 1 if a < 57 else 2
            hi, lo = mats[seg]
            d.point((x, y), fill=(lo if r > 20.0 or abs(a - 33) < 1.5 or abs(a - 57) < 1.5 else hi) + (255,))
# tooltip panel (bottom right), drawn over the head tip like a hover tooltip
d.rectangle([17, 20, 29, 29], fill=(16, 0, 16, 240))
d.rectangle([17, 20, 29, 29], outline=(80, 0, 255, 255))
d.line([19, 22, 25, 22], fill=(255, 255, 255, 255))
for i, (end, c) in enumerate([(27, (85, 255, 85)), (24, (85, 255, 255)), (26, (255, 170, 0))]):
    d.line([19, 24 + i * 2, end, 24 + i * 2], fill=c + (255,))
# grade sparkle
px(d, [(6, 5), (5, 6), (7, 6), (6, 7), (6, 6)], (255, 255, 170, 255))
save(im, "sgsim-logo.png")

# ---------------- Effect Grid: grid of effect tiles with names + radial sweep ----------------
im, d = base()
cols = [(95, 205, 70), (230, 70, 60), (70, 140, 235),
        (240, 175, 40), (170, 90, 220), (90, 215, 215)]
glyph = (255, 255, 255, 230)
for i, c in enumerate(cols):
    gx, gy = 4 + (i % 3) * 9, 4 + (i // 3) * 13
    dark = tuple(v // 4 for v in c) + (255,)
    d.rectangle([gx, gy, gx + 6, gy + 6], fill=dark)
    d.rectangle([gx, gy, gx + 6, gy + 6], outline=c + (255,))
    # little potion bottle glyph
    d.rectangle([gx + 2, gy + 3, gx + 4, gy + 5], fill=c + (255,))
    d.point((gx + 2, gy + 3), fill=(255, 255, 255, 255))
    d.point((gx + 3, gy + 2), fill=(200, 200, 210, 255))
    d.point((gx + 3, gy + 1), fill=(140, 100, 60, 255))
    # name text bar + duration bar underneath
    d.line([gx, gy + 8, gx + 6, gy + 8], fill=(225, 225, 225, 255))
    d.line([gx + 1, gy + 10, gx + 4 - (i % 2), gy + 10], fill=(140, 140, 150, 255))
# radial sweep over two tiles (draw at high res for smoothness, then pixelate)
big = im.resize((N * S, N * S), Image.NEAREST)
ov = Image.new("RGBA", big.size, (0, 0, 0, 0))
od = ImageDraw.Draw(ov)
for i, frac in ((1, 0.62), (5, 0.3)):
    gx, gy = 4 + (i % 3) * 9, 4 + (i // 3) * 13
    box = [(gx + 0.5) * S, (gy + 0.5) * S, (gx + 6.5) * S, (gy + 6.5) * S]
    od.pieslice([box[0] - 40, box[1] - 40, box[2] + 40, box[3] + 40], -90, -90 + 360 * (1 - frac),
                fill=(0, 0, 0, 175))
    m = Image.new("L", big.size, 0)
    ImageDraw.Draw(m).rectangle([gx * S + S, gy * S + S, (gx + 6) * S - 1, (gy + 6) * S - 1], fill=255)
    clip = Image.new("RGBA", big.size, (0, 0, 0, 0))
    clip.paste(ov, (0, 0), m)
    big = Image.alpha_composite(big, clip)
    ov = Image.new("RGBA", big.size, (0, 0, 0, 0)); od = ImageDraw.Draw(ov)
big.save(f"{OUT}/effect-grid-logo.png")
