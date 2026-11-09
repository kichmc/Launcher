from PIL import Image, ImageDraw, ImageFilter, ImageChops
import math

S = 1024          # master canvas = the 108dp adaptive-icon canvas
SS = 2            # supersampling for smooth edges
W = S * SS

def px(v): return int(round(v * SS))

# ---------- geometry of the AK monogram (centre-lines, in 1024 space) ----------
STROKE = 70
R = STROKE / 2
TOP, BOT = 376, 648                 # letter height 272 + stroke -> 342 tall
A_LEFT, A_RIGHT = 276, 452          # A feet
A_APEX_X = (A_LEFT + A_RIGHT) / 2
K_STEM_X, K_ARM_X = 564, 740
MID_Y = (TOP + BOT) / 2

def lines():
    ax = A_APEX_X
    # crossbar sits at 68% down the A; find the legs' x there
    t = 0.68
    y_bar = TOP + (BOT - TOP) * t
    left_x = ax - (ax - A_LEFT) * t
    right_x = ax + (A_RIGHT - ax) * t
    return [
        ((A_LEFT, BOT), (ax, TOP)),            # A left leg
        ((ax, TOP), (A_RIGHT, BOT)),           # A right leg
        ((left_x + 6, y_bar), (right_x - 6, y_bar)),  # A crossbar
        ((K_STEM_X, TOP), (K_STEM_X, BOT)),    # K stem
        ((K_STEM_X, MID_Y), (K_ARM_X, TOP)),   # K upper arm
        ((K_STEM_X, MID_Y), (K_ARM_X, BOT)),   # K lower arm
    ]

def draw_strokes(draw, fill):
    for (x1, y1), (x2, y2) in lines():
        draw.line([(px(x1), px(y1)), (px(x2), px(y2))], fill=fill, width=px(STROKE))
        for (x, y) in ((x1, y1), (x2, y2)):
            draw.ellipse([px(x - R), px(y - R), px(x + R), px(y + R)], fill=fill)

def down(im): return im.resize((S, S), Image.LANCZOS)

# ---------- background: deep navy -> purple, warm glow top-right ----------
def make_bg():
    bg = Image.new("RGBA", (W, W))
    px_ = bg.load()
    c1, c2 = (16, 23, 54), (58, 28, 82)       # #101736 -> #3A1C52
    for y in range(W):
        for x in range(W):
            t = (x + y) / (2 * (W - 1))
            px_[x, y] = (int(c1[0] + (c2[0]-c1[0])*t), int(c1[1] + (c2[1]-c1[1])*t), int(c1[2] + (c2[2]-c1[2])*t), 255)
    glow = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    g = ImageDraw.Draw(glow)
    cx, cy, rad = px(840), px(170), px(560)
    for i in range(40, 0, -1):
        r = rad * i / 40
        a = int(150 * (1 - i / 40) ** 1.6)
        g.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 138, 91, a))
    glow = glow.filter(ImageFilter.GaussianBlur(px(30)))
    bg.alpha_composite(glow)
    return down(bg)

# ---------- foreground: white AK with an amber accent on the K arm tip ----------
def make_fg():
    mask = Image.new("L", (W, W), 0)
    draw_strokes(ImageDraw.Draw(mask), 255)
    letters = Image.new("RGBA", (W, W), (255, 255, 255, 0))
    letters.putalpha(mask)

    # soft shadow for depth
    shadow = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    sm = mask.filter(ImageFilter.GaussianBlur(px(14)))
    shadow.putalpha(sm.point(lambda v: int(v * 0.35)))
    shadow = ImageChops.offset(shadow, 0, px(10))

    out = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    out.alpha_composite(shadow)
    out.alpha_composite(letters)

    # amber -> coral gradient on the K arms (the "spark")
    grad = Image.new("RGBA", (W, W))
    gp = grad.load()
    for y in range(W):
        t = y / (W - 1)
        col = (255, int(195 - 70 * t), int(113 - 24 * t), 255)   # #FFC371 -> #FF7A59-ish
        for x in range(W):
            gp[x, y] = col
    arm_mask = Image.new("L", (W, W), 0)
    ad = ImageDraw.Draw(arm_mask)
    for (x1, y1), (x2, y2) in lines()[4:]:
        ad.line([(px(x1), px(y1)), (px(x2), px(y2))], fill=255, width=px(STROKE))
        for (x, y) in ((x1, y1), (x2, y2)):
            ad.ellipse([px(x - R), px(y - R), px(x + R), px(y + R)], fill=255)
    stem_mask = Image.new("L", (W, W), 0)
    ImageDraw.Draw(stem_mask).rectangle([px(K_STEM_X - R), 0, px(K_STEM_X + R), W], fill=255)
    arm_mask = ImageChops.subtract(arm_mask, stem_mask)
    arm_layer = grad.copy(); arm_layer.putalpha(arm_mask)
    out.alpha_composite(arm_layer)
    return down(out)

# ---------- monochrome: same shape, one colour (the system tints it) ----------
def make_mono():
    mask = Image.new("L", (W, W), 0)
    draw_strokes(ImageDraw.Draw(mask), 255)
    out = Image.new("RGBA", (W, W), (255, 255, 255, 0)); out.putalpha(mask)
    return down(out)

bg, fg, mono = make_bg(), make_fg(), make_mono()
bg.save("bg_1024.png"); fg.save("fg_1024.png"); mono.save("mono_1024.png")

# ---------- preview under different launcher masks ----------
def masked(shape, size=432):
    comp = bg.copy(); comp.alpha_composite(fg)
    comp = comp.resize((size, size), Image.LANCZOS)
    vis = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(vis)
    # visible area is the central 72/108 of the canvas
    inset = size * (108 - 72) / 108 / 2
    box = [inset, inset, size - inset, size - inset]
    if shape == "circle": d.ellipse(box, fill=255)
    elif shape == "squircle": d.rounded_rectangle(box, radius=(size - 2*inset) * 0.32, fill=255)
    else: d.rounded_rectangle(box, radius=(size - 2*inset) * 0.14, fill=255)
    out = Image.new("RGBA", (size, size), (40, 40, 48, 255))
    out.paste(comp, (0, 0), vis)
    return out.crop((int(inset)-6, int(inset)-6, size-int(inset)+6, size-int(inset)+6))

shots = [masked("circle"), masked("squircle"), masked("square")]
w = sum(s.width for s in shots) + 20 * (len(shots) + 1)
sheet = Image.new("RGBA", (w, shots[0].height + 40), (40, 40, 48, 255))
x = 20
for s in shots:
    sheet.paste(s, (x, 20)); x += s.width + 20
sheet.save("icon_masks_preview.png")
print("done", sheet.size)
