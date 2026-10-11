"""Procedural artwork for the Sethu presentation (PIL only; no stock photos, no AI images).

Everything here is ILLUSTRATION. Phone UIs drawn by phone() are illustrative mockups that follow the app's real
colour palette (ui/theme/Color.kt) and real labels - they are NOT screenshots of the running application.
"""
import math
import random
from pathlib import Path

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

FONTS = Path(r"C:\Windows\Fonts")


def font(size, weight="regular"):
    name = {"regular": "segoeui.ttf", "bold": "segoeuib.ttf", "semi": "seguisb.ttf", "light": "segoeuil.ttf"}[weight]
    return ImageFont.truetype(str(FONTS / name), size)


# --- palette (deck) -----------------------------------------------------------------------------------------
NAVY = (5, 11, 26)
NAVY2 = (10, 22, 48)
BLUE = (47, 123, 255)
CYAN = (53, 224, 255)
RED = (255, 59, 78)
AMBER = (255, 176, 32)
WHITE = (234, 242, 255)
GREY = (150, 168, 196)

# --- palette (app mockups, from Color.kt) ----------------------------------------------------------------------
APP_BG = (12, 16, 14)
APP_CARD = (20, 26, 23)
APP_BORDER = (30, 41, 34)
APP_GREEN = (0, 223, 129)
APP_GREEN_DK = (18, 40, 29)
APP_RED = (255, 51, 51)
APP_TEXT = (243, 247, 245)
APP_TEXT2 = (134, 151, 142)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def vgrad(w, h, stops):
    """stops: [(pos 0..1, (r,g,b)), ...]"""
    ys = np.linspace(0, 1, h)
    out = np.zeros((h, 3), dtype=np.float32)
    for c in range(3):
        out[:, c] = np.interp(ys, [s[0] for s in stops], [s[1][c] for s in stops])
    arr = np.repeat(out[:, None, :], w, axis=1)
    return Image.fromarray(arr.astype(np.uint8), "RGB")


def glow(layer, radius, strength=1.0):
    g = layer.filter(ImageFilter.GaussianBlur(radius))
    if strength != 1.0:
        a = g.getchannel("A").point(lambda v: min(255, int(v * strength)))
        g.putalpha(a)
    return g


def vignette(img, strength=0.55):
    w, h = img.size
    y, x = np.ogrid[:h, :w]
    d = np.sqrt(((x - w / 2) / (w / 2)) ** 2 + ((y - h / 2) / (h / 2)) ** 2)
    m = np.clip(1 - strength * np.clip(d - 0.35, 0, 1) ** 1.5, 0, 1)
    arr = np.asarray(img.convert("RGB")).astype(np.float32) * m[..., None]
    return Image.fromarray(arr.astype(np.uint8))


def grain(img, amount=7, seed=1):
    rng = np.random.default_rng(seed)
    n = rng.normal(0, amount, (img.size[1], img.size[0], 1))
    arr = np.asarray(img.convert("RGB")).astype(np.float32) + n
    return Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))


# =============================================================================================== flood scene
def _palm(d, x, base_y, h, lean, col):
    pts = []
    for i in range(12):
        t = i / 11
        pts.append((x + lean * (t ** 1.7) * h, base_y - t * h))
    d.line(pts, fill=col, width=max(3, int(h * 0.025)))
    top = pts[-1]
    for k in range(8):
        a = math.radians(-160 + k * 46)
        ln = h * 0.42
        mid = (top[0] + math.cos(a) * ln * 0.55, top[1] + math.sin(a) * ln * 0.35 - ln * 0.12)
        end = (top[0] + math.cos(a) * ln, top[1] + math.sin(a) * ln * 0.55 + ln * 0.18)
        d.line([top, mid, end], fill=col, width=max(2, int(h * 0.018)))


def _building(d, x, y_base, w, h, col, windows, rng, warm=True):
    d.rectangle([x, y_base - h, x + w, y_base], fill=col)
    if windows:
        cols = max(1, w // 26)
        rows = max(1, h // 34)
        for r in range(rows):
            for c in range(cols):
                if rng.random() < 0.18:
                    wx = x + 10 + c * (w - 20) / cols
                    wy = y_base - h + 14 + r * (h - 28) / rows
                    d.rectangle([wx, wy, wx + 8, wy + 12], fill=(255, 190, 90) if warm else (150, 190, 255))


def flood_scene(w=1920, h=1080, seed=7, people=True, phone_glow=True):
    rng = random.Random(seed)
    horizon = int(h * 0.50)
    sky = vgrad(w, horizon + 40, [(0, (6, 10, 30)), (0.45, (28, 38, 82)), (0.8, (150, 84, 70)), (1, (240, 150, 70))])
    img = Image.new("RGB", (w, h), NAVY)
    img.paste(sky, (0, 0))

    # clouds
    cl = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    cd = ImageDraw.Draw(cl)
    for _ in range(26):
        cx, cy = rng.randint(-100, w + 100), rng.randint(20, int(h * 0.42))
        rw, rh = rng.randint(220, 620), rng.randint(30, 90)
        cd.ellipse([cx - rw, cy - rh, cx + rw, cy + rh], fill=(14, 18, 40, 150))
        cd.ellipse([cx - rw * .8, cy - rh * .1, cx + rw * .8, cy + rh * .7], fill=(210, 120, 80, 38))
    cl = cl.filter(ImageFilter.GaussianBlur(30))
    img.paste(cl, (0, 0), cl)

    # far skyline (hazy), then near skyline
    far = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    fd = ImageDraw.Draw(far)
    x = -40
    while x < w:
        bw, bh = rng.randint(50, 120), rng.randint(40, 150)
        _building(fd, x, horizon + 6, bw, bh, (52, 52, 84, 255), False, rng)
        x += bw + rng.randint(2, 18)
    for px in range(60, w, rng.randint(230, 330)):
        _palm(fd, px, horizon + 6, rng.randint(120, 190), rng.choice([-0.18, 0.15, 0.22]), (40, 42, 70, 255))
    far = far.filter(ImageFilter.GaussianBlur(2.2))
    img.paste(far, (0, 0), far)

    near = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    nd = ImageDraw.Draw(near)
    x = -20
    while x < w:
        bw, bh = rng.randint(90, 190), rng.randint(70, 260)
        _building(nd, x, horizon + 60, bw, bh, (20, 22, 40, 255), True, rng)
        nd.rectangle([x - 4, horizon + 60 - bh - 6, x + bw + 4, horizon + 60 - bh], fill=(30, 32, 52, 255))
        x += bw + rng.randint(10, 60)
    # water tower
    tx = int(w * 0.30)
    nd.rectangle([tx - 5, horizon - 190, tx + 5, horizon + 60], fill=(16, 18, 34, 255))
    nd.polygon([(tx - 60, horizon - 190), (tx + 60, horizon - 190), (tx + 45, horizon - 270), (tx - 45, horizon - 270)], fill=(16, 18, 34, 255))
    for px in range(120, w, rng.randint(300, 420)):
        _palm(nd, px, horizon + 70, rng.randint(200, 300), rng.choice([-0.2, 0.18]), (12, 14, 28, 255))
    img.paste(near, (0, 0), near)

    # water: mirrored sky + ripples
    wy = horizon + 60
    refl = img.crop((0, 0, w, wy)).transpose(Image.FLIP_TOP_BOTTOM).resize((w, h - wy))
    refl = refl.filter(ImageFilter.GaussianBlur(4))
    tint = Image.new("RGB", refl.size, (8, 26, 52))
    refl = Image.blend(refl, tint, 0.55)
    img.paste(refl, (0, wy))
    wl = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    wd = ImageDraw.Draw(wl)
    for _ in range(900):
        yy = rng.randint(wy + 4, h)
        depth = (yy - wy) / (h - wy)
        xx = rng.randint(0, w)
        ln = int(10 + 90 * depth * rng.random())
        a = int(30 + 60 * (1 - depth) * rng.random())
        c = rng.choice([(255, 190, 120), (150, 190, 255), (255, 150, 90)])
        wd.line([xx, yy, xx + ln, yy], fill=c + (a,), width=1 + int(depth * 2))
    wl = wl.filter(ImageFilter.GaussianBlur(0.8))
    img.paste(wl, (0, 0), wl)

    # foreground terrace (right side) with silhouettes, rim-lit by the dusk horizon
    ledge_y = int(h * 0.80)
    terrace = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    td = ImageDraw.Draw(terrace)
    td.polygon([(int(w * 0.40), h), (int(w * 0.40), ledge_y + 30), (int(w * 0.45), ledge_y), (w, ledge_y - 6), (w, h)], fill=(9, 11, 20, 255))
    td.rectangle([int(w * 0.45), ledge_y - 24, w, ledge_y + 6], fill=(24, 26, 42, 255))
    img.paste(terrace, (0, 0), terrace)
    hand = None
    if people:
        P = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        pd_ = ImageDraw.Draw(P)
        col = (8, 9, 18, 255)

        def person(cx, base, s, arm_up=False, child=False):
            nonlocal hand
            bh = (250 if child else 330) * s
            top = base - bh
            hr = (24 if child else 30) * s
            pd_.ellipse([cx - hr, top - hr * 2.2, cx + hr, top - hr * 0.2], fill=col)
            pd_.rounded_rectangle([cx - 56 * s, top, cx + 56 * s, base + 40], radius=int(40 * s), fill=col)
            if arm_up:
                p0 = (cx + 34 * s, top + 36 * s)
                p1 = (cx + 92 * s, top - 90 * s)
                p2 = (cx + 100 * s, top - 190 * s)
                pd_.line([p0, p1, p2], fill=col, width=int(26 * s), joint="curve")
                pd_.ellipse([p2[0] - 17 * s, p2[1] - 17 * s, p2[0] + 17 * s, p2[1] + 17 * s], fill=col)
                hand = p2

        person(int(w * 0.56), h + 10, 1.45, arm_up=True)
        person(int(w * 0.70), h + 40, 1.30)
        person(int(w * 0.82), h + 90, 1.20, child=True)
        # rim light: top-left edges of the silhouettes catch the amber horizon glow
        al = P.getchannel("A")
        shifted = ImageChops.offset(al, 5, 6)
        rim_a = ImageChops.subtract(al, shifted).filter(ImageFilter.GaussianBlur(1.2)).point(lambda v: min(150, int(v * 0.9)))
        rim = Image.new("RGBA", (w, h), (255, 160, 80, 0))
        rim.putalpha(rim_a)
        img.paste(P, (0, 0), P)
        img.paste(rim, (0, 0), rim)
    rim2 = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(rim2).line([(int(w * 0.45), ledge_y - 24), (w, ledge_y - 24)], fill=(255, 150, 70, 150), width=3)
    rim2 = rim2.filter(ImageFilter.GaussianBlur(2))
    img.paste(rim2, (0, 0), rim2)

    if phone_glow and people and hand:
        px, py = int(hand[0]), int(hand[1] - 62)
        pl = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        pd = ImageDraw.Draw(pl)
        pd.rounded_rectangle([px - 34, py - 62, px + 34, py + 62], radius=9, fill=(40, 10, 16, 255), outline=(255, 120, 120, 255), width=3)
        gl = glow(pl, 24, 1.8)
        img.paste(gl, (0, 0), gl)
        pd = ImageDraw.Draw(pl)
        for i, hh in enumerate([12, 22, 32]):
            pd.rectangle([px - 20 + i * 14, py + 18 - hh, px - 10 + i * 14, py + 18], fill=(255, 90, 90, 255))
        pd.line([px - 24, py + 24, px + 24, py - 24], fill=(255, 255, 255, 255), width=5)
        img.paste(pl, (0, 0), pl)

    img = vignette(img, 0.6)
    return grain(img, 6, seed)


# =============================================================================================== backgrounds
def dark_bg(w=1920, h=1080, accent=BLUE, seed=3, glow_pos=(0.8, 0.15)):
    img = vgrad(w, h, [(0, (7, 14, 32)), (1, (4, 8, 20))])
    y, x = np.ogrid[:h, :w]
    gx, gy = glow_pos[0] * w, glow_pos[1] * h
    d = np.sqrt(((x - gx) / (w * 0.55)) ** 2 + ((y - gy) / (h * 0.7)) ** 2)
    m = np.clip(1 - d, 0, 1) ** 2
    arr = np.asarray(img).astype(np.float32)
    for c in range(3):
        arr[..., c] += m * accent[c] * 0.22
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
    # faint dot grid
    dots = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    dd = ImageDraw.Draw(dots)
    for gxx in range(0, w, 48):
        for gyy in range(0, h, 48):
            dd.ellipse([gxx, gyy, gxx + 2, gyy + 2], fill=(120, 160, 255, 22))
    img.paste(dots, (0, 0), dots)
    return vignette(img, 0.4)


def darken(img, amount=0.55, tint=NAVY):
    return Image.blend(img.convert("RGB"), Image.new("RGB", img.size, tint), amount)


# =============================================================================================== icons / glyphs
def bluetooth_glyph(size=256, color=(255, 255, 255, 255), width=None):
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    s = size
    cx = s / 2
    pts = [(-0.52, -0.42), (0.46, 0.42), (0.0, 0.86), (0.0, -0.86), (0.46, -0.42), (-0.52, 0.42)]
    pts = [(cx + p[0] * s * 0.5, s / 2 + p[1] * s * 0.5) for p in pts]
    d.line(pts, fill=color, width=width or max(4, s // 14), joint="curve")
    return im


def bt_badge(size=160, ring=CYAN, fill=(14, 40, 100), active=True):
    im = Image.new("RGBA", (size * 2, size * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    c = size
    r = int(size * 0.62)
    d.ellipse([c - r, c - r, c + r, c + r], fill=fill + (255,), outline=ring + (255,), width=max(3, size // 24))
    g = bluetooth_glyph(int(size * 0.8), (255, 255, 255, 255))
    im.paste(g, (c - g.size[0] // 2, c - g.size[1] // 2), g)
    if active:
        im.alpha_composite(glow(im, size // 6, 1.2))
        d = ImageDraw.Draw(im)
        d.ellipse([c - r, c - r, c + r, c + r], outline=ring + (255,), width=max(3, size // 24))
        im.paste(g, (c - g.size[0] // 2, c - g.size[1] // 2), g)
    return im.crop((c - int(size * 0.95), c - int(size * 0.95), c + int(size * 0.95), c + int(size * 0.95)))


def dashed_line(d, p0, p1, color, width=4, dash=18, gap=12):
    x0, y0 = p0
    x1, y1 = p1
    L = math.hypot(x1 - x0, y1 - y0)
    n = int(L // (dash + gap)) + 1
    for i in range(n):
        a = i * (dash + gap) / L
        b = min(1, (i * (dash + gap) + dash) / L)
        d.line([x0 + (x1 - x0) * a, y0 + (y1 - y0) * a, x0 + (x1 - x0) * b, y0 + (y1 - y0) * b], fill=color, width=width)


# =============================================================================================== phone mockups
def _rr(d, box, r, fill=None, outline=None, width=1):
    d.rounded_rectangle(box, radius=r, fill=fill, outline=outline, width=width)


def _chip(d, x, y, text, fg, bg, f, pad=12):
    tw = d.textlength(text, font=f)
    _rr(d, [x, y, x + tw + pad * 2, y + f.size + 14], 10, fill=bg)
    d.text((x + pad, y + 5), text, font=f, fill=fg)
    return tw + pad * 2


def phone(role, state="idle", scale=1.0, label=True):
    """Illustrative mockup. role: sender|relay|coordinator. state strings select what the screen shows."""
    W, H = 540, 1110
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    _rr(d, [0, 0, W - 1, H - 1], 70, fill=(26, 30, 44), outline=(90, 110, 150), width=4)
    _rr(d, [14, 14, W - 15, H - 15], 58, fill=APP_BG)
    d.rounded_rectangle([W / 2 - 60, 30, W / 2 + 60, 52], radius=11, fill=(0, 0, 0))
    f_s, f_m, f_l, f_h = font(22), font(26, "semi"), font(32, "bold"), font(40, "bold")
    x0, x1 = 40, W - 40
    y = 86
    title = {"sender": "SOS Sender", "relay": "Relay Mode", "coordinator": "Emergency Coordinator"}[role]
    sub = {"sender": "Send an emergency report", "relay": "Store-and-forward", "coordinator": "Priority Triage Dashboard"}[role]
    d.text((x0, y), title, font=f_l, fill=APP_TEXT)
    d.text((x0, y + 42), sub, font=f_s, fill=APP_GREEN)
    y += 100

    def link_card(y, text, color, sub_text=None):
        _rr(d, [x0, y, x1, y + (110 if sub_text else 84)], 22, fill=APP_CARD, outline=APP_BORDER, width=2)
        d.ellipse([x0 + 22, y + 30, x0 + 40, y + 48], fill=color)
        d.text((x0 + 56, y + 20), text, font=f_m, fill=APP_TEXT)
        if sub_text:
            d.text((x0 + 56, y + 62), sub_text, font=f_s, fill=APP_TEXT2)
        return y + (110 if sub_text else 84) + 22

    if role == "sender":
        if state == "idle":
            y = link_card(y, "Not connected", APP_TEXT2, "Open Direct Device Link")
        elif state in ("linked", "sent"):
            y = link_card(y, "Connected to Relay", APP_GREEN, "Direct Device Link")
        else:
            y = link_card(y, "Link ended", RED, "Report is already saved on the Relay")
        cx, cy = W // 2, y + 190
        for rr, a in [(170, 40), (150, 70), (130, 110)]:
            d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=(255, 51, 51, a)) if False else None
        glow_l = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        gd = ImageDraw.Draw(glow_l)
        gd.ellipse([cx - 140, cy - 140, cx + 140, cy + 140], fill=(255, 51, 51, 120))
        glow_l = glow_l.filter(ImageFilter.GaussianBlur(30))
        im.alpha_composite(glow_l)
        d = ImageDraw.Draw(im)
        d.ellipse([cx - 120, cy - 120, cx + 120, cy + 120], fill=APP_RED)
        d.text((cx, cy - 8), "SOS", font=font(82, "bold"), fill=(255, 255, 255), anchor="mm")
        y = cy + 160
        txt = {"idle": "Hold to send emergency report", "linked": "Report ready to send", "sent": "Acknowledged by Relay", "ended": "Acknowledged by Relay"}[state]
        d.text((cx, y), txt, font=f_m, fill=APP_TEXT if state in ("sent", "ended") else APP_TEXT2, anchor="mm")
        if state in ("sent", "ended"):
            _chip(d, cx - 150, y + 44, "ACKNOWLEDGED BY RELAY", APP_GREEN, APP_GREEN_DK, f_s)
        y += 120
        for lab in ["Voice input (offline)", "Add details"]:
            _rr(d, [x0, y, x1, y + 70], 18, fill=APP_CARD, outline=APP_BORDER, width=2)
            d.text((x0 + 24, y + 18), lab, font=f_m, fill=APP_TEXT)
            y += 88
    elif role == "relay":
        if state == "listening":
            y = link_card(y, "Listening", (255, 176, 32), "Waiting for a peer to connect")
        elif state == "idle_empty":
            y = link_card(y, "Not connected", APP_TEXT2, "Start Server to listen")
        elif state == "connected_empty":
            y = link_card(y, "Connected to Sender", APP_GREEN, "Direct Device Link")
        elif state == "linked":
            y = link_card(y, "Connected to Sender", APP_GREEN, "Direct Device Link")
        elif state in ("held", "idle"):
            y = link_card(y, "Not connected", APP_TEXT2, "Report is held on this phone")
        elif state == "linked_coord":
            y = link_card(y, "Connected to Coordinator", APP_GREEN, "Forwarding starts automatically")
        else:
            y = link_card(y, "Connected to Coordinator", APP_GREEN, "Link active")
        _rr(d, [x0, y, x1, y + 330], 24, fill=APP_CARD, outline=APP_BORDER, width=2)
        d.text((x0 + 24, y + 20), "Forwarding Queue", font=f_m, fill=APP_TEXT)
        d.text((x0 + 24, y + 62), "SOS-0001 (example)", font=f_s, fill=APP_TEXT2)
        chip_text, chip_fg, chip_bg = {
            "listening": ("EMPTY", APP_TEXT2, (30, 38, 34)),
            "idle_empty": ("EMPTY", APP_TEXT2, (30, 38, 34)),
            "connected_empty": ("EMPTY", APP_TEXT2, (30, 38, 34)),
            "linked": ("RECEIVED - PENDING COORDINATOR DELIVERY", (255, 176, 32), (52, 38, 10)),
            "held": ("RECEIVED - PENDING COORDINATOR DELIVERY", (255, 176, 32), (52, 38, 10)),
            "idle": ("EMPTY", APP_TEXT2, (30, 38, 34)),
            "linked_coord": ("RECEIVED - PENDING COORDINATOR DELIVERY", (255, 176, 32), (52, 38, 10)),
            "forwarding": ("FORWARDED - AWAITING ACK", (80, 170, 255), (14, 34, 64)),
            "delivered": ("DELIVERED TO COORDINATOR", APP_GREEN, APP_GREEN_DK),
        }[state]
        f_chip = font(16, "semi")
        _chip(d, x0 + 24, y + 110, chip_text, chip_fg, chip_bg, f_chip, pad=10)
        d.text((x0 + 24, y + 175), {"delivered": "Coordinator ACK received", "forwarding": "Waiting for Coordinator ACK",
                                    "held": "Saved to local storage", "linked": "Saved to local storage",
                                    "linked_coord": "Saved to local storage"}.get(state, ""), font=f_s, fill=APP_TEXT2)
        d.text((x0 + 24, y + 225), "No Forward button: automatic" if state in ("forwarding", "delivered", "linked_coord") else "", font=f_s, fill=APP_TEXT2)
    else:  # coordinator
        if state == "listening":
            y = link_card(y, "Listening", (255, 176, 32), "Waiting for the Relay")
        else:
            y = link_card(y, "Connected to Relay", APP_GREEN, "Direct Device Link")
        if state in ("received",):
            _rr(d, [x0, y, x1, y + 250], 24, fill=(20, 36, 27), outline=APP_GREEN, width=3)
            _chip(d, x0 + 20, y + 20, "LATEST RECEIVED", APP_GREEN, APP_GREEN_DK, f_s)
            d.text((x0 + 22, y + 82), "Medical", font=f_l, fill=APP_TEXT)
            d.text((x0 + 22, y + 130), "Priority: ", font=f_m, fill=APP_TEXT2)
            d.text((x0 + 22 + d.textlength("Priority: ", font=f_m), y + 130), "High (rule-based)", font=f_m, fill=(255, 176, 32))
            d.text((x0 + 22, y + 182), "Via Relay | saved on this phone", font=f_s, fill=APP_TEXT2)
            y += 276
        else:
            _rr(d, [x0, y, x1, y + 170], 24, fill=APP_CARD, outline=APP_BORDER, width=2)
            d.text((x0 + 24, y + 60), "No reports received yet", font=f_m, fill=APP_TEXT2)
            y += 196
        for lab in ["Critical  0", "High  1", "Medium  0"] if state == "received" else ["Critical  0", "High  0", "Medium  0"]:
            _rr(d, [x0, y, x1, y + 62], 16, fill=APP_CARD, outline=APP_BORDER, width=2)
            d.text((x0 + 22, y + 14), lab, font=f_m, fill=APP_TEXT)
            y += 78
    if label:
        d.text((W // 2, H - 52), "ILLUSTRATIVE MOCKUP", font=font(20, "semi"), fill=(120, 140, 130), anchor="mm")
    if scale != 1.0:
        im = im.resize((int(W * scale), int(H * scale)), Image.LANCZOS)
    return im


def paste_center(base, overlay, cx, cy):
    base.paste(overlay, (int(cx - overlay.size[0] / 2), int(cy - overlay.size[1] / 2)), overlay)


def shadowed(overlay, radius=26, opacity=170, offset=(0, 24)):
    w, h = overlay.size
    pad = radius * 3
    sh = Image.new("RGBA", (w + pad * 2, h + pad * 2), (0, 0, 0, 0))
    a = overlay.getchannel("A").point(lambda v: opacity if v > 10 else 0)
    sh.paste((0, 0, 0, 255), (pad + offset[0], pad + offset[1]), a)
    sh = sh.filter(ImageFilter.GaussianBlur(radius))
    out = Image.new("RGBA", sh.size, (0, 0, 0, 0))
    out.alpha_composite(sh)
    out.alpha_composite(overlay, (pad, pad))
    return out


if __name__ == "__main__":
    out = Path(__file__).resolve().parent.parent / "assets"
    out.mkdir(exist_ok=True)
    flood_scene().save(out / "flood_scene.png")
    dark_bg().save(out / "bg_dark.png")
    print("ok")
