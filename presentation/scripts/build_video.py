"""Renders Sethu_Presentation_Video.mp4 (1920x1080, 30 fps, narration + soft music, burned-in captions).

All visuals are illustrations / illustrative mockups drawn in PIL. No stock footage, no AI images.
"""
import json
import math
import subprocess
import sys
import wave
from functools import lru_cache
from pathlib import Path

import imageio_ffmpeg
import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter

import art
from art import font

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "assets"
AUDIO = ASSETS / "audio"
GEN = ASSETS / "generated"
W, H, FPS = 1920, 1080, 30
CYAN, BLUE, AMBER, RED, GREEN, WHITE, GREY = art.CYAN, art.BLUE, art.AMBER, art.RED, art.APP_GREEN, art.WHITE, art.GREY

NARR = json.loads((ROOT / "scripts" / "narration.json").read_text(encoding="utf-8"))


def wav_len(i):
    w = wave.open(str(AUDIO / f"narr_{i + 1}.wav"))
    return w.getnframes() / w.getframerate()


# scene: planned duration, narration start offset inside the scene
PLAN = [(15, 0.8), (15, 0.6), (18, 6.0), (14, 1.0), (20, 2.0), (23, 4.0), (15, 3.0)]
SCENES = []
t0 = 0.0
for i, (d, off) in enumerate(PLAN):
    d = max(d, off + wav_len(i) + 1.5)
    SCENES.append(dict(i=i, start=t0, dur=d, voff=off, text=NARR[i], tts=wav_len(i)))
    t0 += d
TOTAL = t0
TR = 0.6  # cross-fade length


def ease(p):
    p = max(0.0, min(1.0, p))
    return 1 - (1 - p) ** 3


def seg(t, a, b):
    return ease((t - a) / (b - a)) if b > a else float(t >= a)


# --------------------------------------------------------------------------------------------- cached assets
@lru_cache(maxsize=None)
def bg(name):
    return Image.open(GEN / name).convert("RGB").resize((W, H))


@lru_cache(maxsize=None)
def flood():
    return art.flood_scene().convert("RGB")


@lru_cache(maxsize=None)
def phone_img(role, state, h):
    p = art.phone(role, state, 1.0)
    return p.resize((int(p.size[0] * h / p.size[1]), h), Image.LANCZOS)


@lru_cache(maxsize=None)
def badge(size, kind):
    if kind == "cyan":
        return art.bt_badge(size)
    if kind == "amber":
        return art.bt_badge(size, ring=art.AMBER, fill=(60, 40, 10), active=True)
    if kind == "grey":
        return art.bt_badge(size, ring=(120, 130, 150), fill=(30, 34, 46), active=False)
    return art.bt_badge(size, ring=art.AMBER, fill=(60, 40, 10), active=False)


@lru_cache(maxsize=None)
def sprite(color, r):
    im = Image.new("RGBA", (r * 8, r * 8), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    c = r * 4
    d.ellipse([c - r, c - r, c + r, c + r], fill=color + (255,))
    g = art.glow(im, r * 1.4, 1.8)
    g.alpha_composite(im)
    return g


def paste_a(base, im, xy, a=1.0):
    if a <= 0:
        return
    if a < 1:
        im = im.copy()
        im.putalpha(ImageChops.multiply(im.getchannel("A"), Image.new("L", im.size, int(255 * a))))
    base.paste(im, (int(xy[0]), int(xy[1])), im)


def put_phone(base, role, state, cx, cy, h=600, a=1.0, dy=0, glow_color=None):
    im = phone_img(role, state, h)
    x, y = cx - im.size[0] // 2, cy - im.size[1] // 2 + dy
    if glow_color:
        g = Image.new("RGBA", (im.size[0] + 160, im.size[1] + 160), (0, 0, 0, 0))
        ImageDraw.Draw(g).rounded_rectangle([80, 80, 80 + im.size[0], 80 + im.size[1]], radius=60, outline=glow_color + (255,), width=6)
        g = art.glow(g, 22, 1.6)
        paste_a(base, g, (x - 80, y - 80), a)
    paste_a(base, im, (x, y), a)


def text_c(d, xy, text, f, fill, anchor="mm"):
    d.text(xy, text, font=f, fill=fill, anchor=anchor)


def chip(base, cx, cy, text, fg, bg_, size=30, a=1.0, pad=28):
    f = font(size, "semi")
    tmp = ImageDraw.Draw(Image.new("RGB", (10, 10)))
    tw = int(tmp.textlength(text, font=f))
    w, h = tw + pad * 2, size + 26
    im = Image.new("RGBA", (w + 8, h + 8), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([4, 4, 4 + w, 4 + h], radius=h // 2, fill=bg_ + (235,), outline=fg + (255,), width=3)
    d.text((4 + w // 2, 4 + h // 2 - 1), text, font=f, fill=fg, anchor="mm")
    paste_a(base, im, (cx - im.size[0] // 2, cy - im.size[1] // 2), a)


def link(base, p0, p1, prog, color, kind="cyan", dashed=False, broken=False, a=1.0):
    """Animated link from p0 to p1. prog 0..1 draws the line, badge appears at the end of the draw."""
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    x0, y0 = p0
    x1, y1 = p1
    mx = (x0 + x1) / 2
    if prog > 0:
        if broken:
            for (a0, a1) in [(0.0, 0.34), (0.66, 1.0)]:
                art.dashed_line(d, (x0 + (x1 - x0) * a0, y0), (x0 + (x1 - x0) * a1, y1), (150, 160, 180, 200), width=4, dash=10, gap=14)
        else:
            ex = x0 + (x1 - x0) * prog
            if dashed:
                art.dashed_line(d, (x0, y0), (ex, y1), color + (255,), width=5, dash=22, gap=14)
            else:
                d.line([x0, y0, ex, y1], fill=color + (255,), width=6)
    if prog >= 0.99 or broken:
        b = badge(110, "grey" if broken else kind)
        layer.paste(b, (int(mx - b.size[0] / 2), int(y0 - b.size[1] / 2)), b)
        if broken:
            d.line([mx - 26, y0 - 26, mx + 26, y0 + 26], fill=(255, 80, 90, 255), width=7)
            d.line([mx - 26, y0 + 26, mx + 26, y0 - 26], fill=(255, 80, 90, 255), width=7)
    glowl = art.glow(layer, 8, 0.9) if not broken else layer
    paste_a(base, glowl, (0, 0), a)
    paste_a(base, layer, (0, 0), a)


def packet(base, p0, p1, prog, color, r=15):
    if prog <= 0 or prog >= 1:
        return
    x = p0[0] + (p1[0] - p0[0]) * ease(prog)
    y = p0[1] + (p1[1] - p0[1]) * ease(prog)
    s = sprite(color, r)
    base.paste(s, (int(x - s.size[0] / 2), int(y - s.size[1] / 2)), s)


def ring(base, cx, cy, p, color=(255, 255, 255)):
    if p <= 0 or p >= 1:
        return
    r = 18 + 70 * p
    im = Image.new("RGBA", (300, 300), (0, 0, 0, 0))
    ImageDraw.Draw(im).ellipse([150 - r, 150 - r, 150 + r, 150 + r], outline=color + (int(255 * (1 - p)),), width=6)
    base.paste(im, (int(cx - 150), int(cy - 150)), im)


def label(base, cx, y, title, sub, a=1.0, color=WHITE):
    im = Image.new("RGBA", (700, 110), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.text((350, 30), title, font=font(40, "bold"), fill=color, anchor="mm")
    d.text((350, 80), sub, font=font(28), fill=CYAN, anchor="mm")
    paste_a(base, im, (cx - 350, y), a)


def top_badge(base, text, a=1.0):
    im = Image.new("RGBA", (1700, 46), (0, 0, 0, 0))
    ImageDraw.Draw(im).text((0, 23), text, font=font(22, "semi"), fill=(150, 165, 195), anchor="lm")
    paste_a(base, im, (48, 30), a)


def to_rgba(img):
    return img.convert("RGBA")


# --------------------------------------------------------------------------------------------- scenes
SX, RX, CY = 600, 1320, 490
LINK0, LINK1 = (SX + 168, CY), (RX - 168, CY)


def scene1(t):
    z = 1.0 + 0.07 * (t / SCENES[0]["dur"])
    f = flood()
    cw, chh = W / z, H / z
    cx, cy = 0.60 * W, 0.42 * H
    x0 = min(max(cx - cw / 2, 0), W - cw)
    y0 = min(max(cy - chh / 2, 0), H - chh)
    im = f.crop((int(x0), int(y0), int(x0 + cw), int(y0 + chh))).resize((W, H), Image.BILINEAR).convert("RGBA")
    d = ImageDraw.Draw(im)
    top_badge(im, "ILLUSTRATION - flood scenario, not a real event")
    a = seg(t, 1.5, 2.3)
    pulse = 0.75 + 0.25 * math.sin(t * 5)
    chip(im, 1560, 190, "NO NETWORK", (255, 110, 120), (70, 12, 22), size=44, a=a * pulse)
    chip(im, 1560, 290, "No mobile signal", (255, 150, 150), (50, 14, 22), size=28, a=seg(t, 4.5, 5.2))
    chip(im, 1560, 365, "No internet", (255, 150, 150), (50, 14, 22), size=28, a=seg(t, 5.3, 6.0))
    chip(im, 360, 190, "Three people on a terrace", (WHITE), (12, 20, 44), size=30, a=seg(t, 6.5, 7.2))
    chip(im, 360, 270, "One needs a medic. One needs evacuation.", (WHITE), (12, 20, 44), size=26, a=seg(t, 7.4, 8.1))
    chip(im, 960, 800, "...but nearby phones can still talk locally", CYAN, (10, 36, 80), size=36, a=seg(t, 10.5, 11.5))
    return im


def scene2(t):
    im = to_rgba(bg("bg_a.jpg")).copy()
    d = ImageDraw.Draw(im)
    top_badge(im, "ILLUSTRATIVE VISUALS - mockups, not app screenshots")
    # title reveal then move up
    mv = seg(t, 3.2, 4.4)
    ty = 520 - 330 * mv
    sc = 1.0 - 0.45 * mv
    ta = seg(t, 0.2, 1.6)
    tf = font(int(190 * sc), "bold")
    layer = Image.new("RGBA", (W, 360), (0, 0, 0, 0))
    ImageDraw.Draw(layer).text((W // 2, 150), "SETHU", font=tf, fill=WHITE, anchor="mm")
    layer = layer.filter(ImageFilter.GaussianBlur(0.0))
    paste_a(im, art.glow(layer, 18, 0.8), (0, int(ty - 150)), ta * 0.8)
    paste_a(im, layer, (0, int(ty - 150)), ta)
    sub_a = seg(t, 1.6, 2.6)
    subl = Image.new("RGBA", (W, 80), (0, 0, 0, 0))
    ImageDraw.Draw(subl).text((W // 2, 40), "Offline Emergency Communication & Disaster Response", font=font(int(46 * (1 - 0.25 * mv)), "semi"), fill=CYAN, anchor="mm")
    paste_a(im, subl, (0, int(ty + 130 - 55 * mv - 40)), sub_a)
    xs = [480, 960, 1440]
    roles = [("sender", "idle", "iQOO 15", "SOS Sender"), ("relay", "idle_empty", "Vivo Y15", "Relay"), ("coordinator", "listening", "Motorola Edge 30 Pro", "Coordinator")]
    for i, (x, (r, s_, n, sub)) in enumerate(zip(xs, roles)):
        p = seg(t, 4.6 + i * 0.5, 5.6 + i * 0.5)
        put_phone(im, r, s_, x, 595, h=440, a=p, dy=int(60 * (1 - p)))
        label(im, x, 820, n, sub, a=seg(t, 5.6 + i * 0.5, 6.4 + i * 0.5))
    # links: A solid, B dashed (a later, separate stage)
    la = seg(t, 8.5, 9.6)
    link(im, (xs[0] + 130, 595), (xs[1] - 130, 595), la, CYAN, "cyan")
    lb = seg(t, 9.6, 10.7)
    link(im, (xs[1] + 130, 595), (xs[2] - 130, 595), lb, AMBER, "amber2", dashed=True)
    chip(im, 720, 470, "STAGE A", CYAN, (14, 40, 100), size=24, a=la)
    chip(im, 1200, 470, "STAGE B", AMBER, (52, 38, 10), size=24, a=lb)
    chip(im, 960, 330, "Local Bluetooth. No cloud needed for the intended offline workflow.", WHITE, (12, 24, 52), size=26, a=seg(t, 11.0, 12.0))
    return im


def stage_scene(base, t, stage_label, color, fg_bg):
    chip(base, 960, 100, stage_label, color, fg_bg, size=30)


def scene3(t):
    im = to_rgba(bg("bg_b.jpg")).copy()
    top_badge(im, "ILLUSTRATIVE MOCKUPS - logic verified in simulated tests; physical Bluetooth testing in progress")
    stage_scene(im, t, "STAGE A  -  iQOO  <->  Vivo  (manual connection)", CYAN, (14, 40, 100))
    pa = seg(t, 0.2, 1.1)
    srole = "idle"
    rrole = "idle_empty"
    if t >= 1.5:
        rrole = "listening"
    if t >= 5.0:
        srole, rrole = "linked", "connected_empty"
    if t >= 9.0:
        rrole = "linked"
    if t >= 11.3:
        srole = "sent"
    put_phone(im, "sender", srole, SX, CY, a=pa, dy=int(40 * (1 - pa)))
    put_phone(im, "relay", rrole, RX, CY, a=pa, dy=int(40 * (1 - pa)))
    label(im, SX, 800, "iQOO 15", "SOS Sender", a=pa)
    label(im, RX, 800, "Vivo Y15", "Relay", a=pa)
    prog = seg(t, 3.9, 5.0)
    link(im, LINK0, LINK1, prog, CYAN)
    # steps
    if 1.5 <= t < 3.6:
        chip(im, 960, 160, "1  Vivo: tap Start Server  ->  Listening", AMBER, (52, 38, 10), size=30, a=seg(t, 1.5, 2.1))
    if 3.6 <= t < 6.8:
        chip(im, 960, 160, "2  iQOO: tap Vivo in Direct Device Link  (manual)", CYAN, (14, 40, 100), size=30, a=seg(t, 3.6, 4.2))
        ring(im, SX + 80, CY + 120, ((t - 3.7) % 1.2) / 1.2 if t < 4.9 else 0)
    if 6.8 <= t < 9.6:
        chip(im, 960, 160, "3  iQOO: send the SOS", RED, (60, 14, 22), size=30, a=seg(t, 6.8, 7.4))
        ring(im, SX, CY - 60, ((t - 7.0) % 1.0) if 7.0 < t < 8.0 else 0, color=(255, 120, 120))
    if t >= 9.6:
        chip(im, 960, 160, "Vivo saves it to local storage FIRST, then acknowledges" if t < 12.8 else "Pending Coordinator delivery - held on Vivo", GREEN if t < 12.8 else AMBER,
             (14, 48, 34) if t < 12.8 else (52, 38, 10), size=30, a=seg(t, 9.6, 10.2))
    packet(im, (LINK0[0] + 30, CY), (LINK1[0] - 30, CY), (t - 7.6) / 1.3, (255, 120, 120))
    packet(im, (LINK1[0] - 30, CY), (LINK0[0] + 30, CY), (t - 10.2) / 1.3, CYAN)
    chip(im, 960, 640, "ACK", GREEN, (14, 48, 34), size=26, a=seg(t, 11.3, 11.8) * (1 if t < 13.5 else 0))
    return im


def scene4(t):
    im = to_rgba(bg("bg_b.jpg")).copy()
    top_badge(im, "ILLUSTRATIVE MOCKUPS - logic verified in simulated tests; physical Bluetooth testing in progress")
    chip(im, 960, 100, "STAGE A ENDS  -  the first connection is gone", AMBER, (52, 38, 10), size=30)
    broken = t >= 1.6
    srole = "sent" if not broken else "ended"
    sa = 1.0 if not broken else 1 - 0.45 * seg(t, 1.6, 2.4)
    put_phone(im, "sender", srole, SX, CY, a=sa)
    put_phone(im, "relay", "linked" if not broken else "held", RX, CY, glow_color=AMBER if broken else None)
    label(im, SX, 800, "iQOO 15", "Bluetooth off" if broken else "SOS Sender", a=sa, color=WHITE)
    label(im, RX, 800, "Vivo Y15", "Relay - still running", a=1.0)
    link(im, LINK0, LINK1, 1.0, CYAN, broken=broken)
    if broken:
        if t < 4.2:
            chip(im, 960, 160, "Link ended  -  both phones show disconnected", RED, (60, 14, 22), size=30, a=seg(t, 2.0, 2.6))
        elif t < 9.0:
            chip(im, 960, 160, "Vivo is still running. The report is saved - still pending.", AMBER, (52, 38, 10), size=30, a=seg(t, 4.2, 4.8))
        else:
            chip(im, 960, 160, "Nothing is sent while there is no link", GREY, (24, 30, 48), size=30, a=seg(t, 9.0, 9.6))
    return im


def scene5(t):
    im = to_rgba(bg("bg_c.jpg")).copy()
    top_badge(im, "ILLUSTRATIVE MOCKUPS - logic verified in simulated tests; physical Bluetooth testing in progress")
    stage_scene(im, t, "STAGE B  -  Vivo  <->  Motorola  (manual connection)", AMBER, (52, 38, 10))
    pa = seg(t, 0.2, 1.0)
    rrole = "held"
    crole = "idle" if False else "listening"
    if t >= 5.0:
        rrole, crole = "linked_coord", "connected"
    if t >= 7.0:
        rrole = "forwarding"
    if t >= 8.7:
        crole = "received"
    if t >= 12.0:
        rrole = "delivered"
    put_phone(im, "relay", rrole, SX, CY, a=pa, dy=int(40 * (1 - pa)))
    put_phone(im, "coordinator", crole if t >= 1.2 else "listening", RX, CY, a=pa, dy=int(40 * (1 - pa)),
              glow_color=GREEN if crole == "received" else None)
    label(im, SX, 800, "Vivo Y15", "Relay", a=pa)
    label(im, RX, 800, "Motorola Edge 30 Pro", "Coordinator", a=pa)
    prog = seg(t, 3.9, 5.0)
    kind = "amber"
    link(im, LINK0, LINK1, prog, AMBER, kind=kind)
    if 1.2 <= t < 3.6:
        chip(im, 960, 160, "1  Motorola: tap Start Server  ->  Listening", AMBER, (52, 38, 10), size=30, a=seg(t, 1.2, 1.8))
    if 3.6 <= t < 6.8:
        chip(im, 960, 160, "2  User taps Motorola on Vivo  -  MANUAL connection", AMBER, (52, 38, 10), size=30, a=seg(t, 3.6, 4.2))
        ring(im, SX + 80, CY + 120, ((t - 3.7) % 1.2) / 1.2 if t < 4.9 else 0, color=(255, 200, 100))
    if 6.8 <= t < 12.0:
        chip(im, 960, 160, "AUTOMATIC forwarding  -  no Forward or Retry button", GREEN, (14, 48, 34), size=30, a=seg(t, 6.8, 7.4))
    if t >= 12.0:
        chip(im, 960, 160, "DELIVERED  -  recorded only after Motorola's ACK", GREEN, (14, 48, 34), size=30, a=seg(t, 12.0, 12.6))
    packet(im, (LINK0[0] + 30, CY), (LINK1[0] - 30, CY), (t - 7.2) / 1.4, (110, 175, 255))
    packet(im, (LINK1[0] - 30, CY), (LINK0[0] + 30, CY), (t - 10.4) / 1.4, GREEN)
    chip(im, 960, 380, "MANUAL", AMBER, (52, 38, 10), size=24, a=seg(t, 5.0, 5.5) * (1 if t < 6.8 else 0))
    chip(im, 960, 380, "AUTO", GREEN, (14, 48, 34), size=24, a=seg(t, 6.9, 7.4))
    chip(im, 960, 640, "Saved, then ACK", GREEN, (14, 48, 34), size=24, a=seg(t, 9.0, 9.6) * (1 if t < 12.0 else 0))
    chip(im, 960, 640, "iQOO not needed", CYAN, (14, 40, 100), size=24, a=seg(t, 13.5, 14.2))
    return im


def box(im, x, y, w, h, title, sub, color, a=1.0, active=0.0):
    c = Image.new("RGBA", (w + 60, h + 60), (0, 0, 0, 0))
    d = ImageDraw.Draw(c)
    d.rounded_rectangle([30, 30, 30 + w, 30 + h], radius=26, fill=(14, 28, 62, 240), outline=color + (255,), width=3 + int(3 * active))
    if active > 0:
        gl = art.glow(c, 16, 1.0 + active)
        c = gl.copy()
        d = ImageDraw.Draw(c)
        d.rounded_rectangle([30, 30, 30 + w, 30 + h], radius=26, fill=(14, 28, 62, 240), outline=color + (255,), width=3 + int(3 * active))
    d.text((30 + w // 2, 30 + h * 0.34), title, font=font(30, "bold"), fill=WHITE, anchor="mm")
    lines = sub.split("\n")
    for k, ln in enumerate(lines):
        d.text((30 + w // 2, 30 + h * 0.58 + k * 32), ln, font=font(23), fill=(185, 200, 228), anchor="mm")
    paste_a(im, c, (x - 30, y - 30), a)


def scene6(t):
    im = to_rgba(bg("bg_a.jpg")).copy()
    top_badge(im, "ILLUSTRATIVE DIAGRAM - modules that exist in the code")
    d = ImageDraw.Draw(im)
    d.text((W // 2, 120), "What runs behind the interface", font=font(52, "bold"), fill=WHITE, anchor="mm")
    cards = [("Local Bluetooth", "RFCOMM link between\nnearby phones", CYAN),
             ("Durable storage", "Report written to disk\nbefore any ACK", GREEN),
             ("Report IDs", "Unique ID per SOS\nduplicates ignored", AMBER),
             ("Acknowledgements", "Match ID, session\nand stage", (150, 120, 255)),
             ("Coordinator view", "Rule-based priority\nlatest report on top", CYAN)]
    cw, chh, gap = 320, 270, 36
    x0 = (W - (5 * cw + 4 * gap)) // 2
    for i, (ti, su, col) in enumerate(cards):
        a = seg(t, 4.0 + i * 2.2, 4.8 + i * 2.2)
        act = max(0.0, 1 - abs(((t - (4.4 + i * 2.2)) % 20) / 1.6)) if 4.0 + i * 2.2 <= t < 5.8 + i * 2.2 else 0.0
        x = x0 + i * (cw + gap)
        box(im, x, 250, cw, chh, ti, su, col, a=a, active=act)
        if i < 4:
            ax = x + cw + 4
            paste_arrow = seg(t, 4.8 + (i + 1) * 2.2, 5.2 + (i + 1) * 2.2)
            lay = Image.new("RGBA", (gap + 8, 40), (0, 0, 0, 0))
            ImageDraw.Draw(lay).polygon([(0, 8), (gap - 6, 8), (gap - 6, 0), (gap + 6, 20), (gap - 6, 40), (gap - 6, 32), (0, 32)], fill=(150, 170, 210, 255))
            paste_a(im, lay, (ax, 250 + chh // 2 - 20), paste_arrow)
    # on-device band
    ba = seg(t, 15.5, 16.5)
    band = Image.new("RGBA", (1700, 150), (0, 0, 0, 0))
    bd = ImageDraw.Draw(band)
    bd.rounded_rectangle([0, 0, 1699, 149], radius=30, fill=(14, 28, 62, 235), outline=(150, 120, 255, 255), width=3)
    bd.text((850, 38), "On-device only", font=font(30, "bold"), fill=(190, 170, 255), anchor="mm")
    bd.text((850, 95), "Offline voice (Vosk)   |   Local AI assist (Qwen2.5-0.5B, side-loaded)   |   Offline maps (Mapsforge)   |   Rule-based priority",
            font=font(26), fill=(210, 222, 245), anchor="mm")
    paste_a(im, band, (110, 590), ba)
    chip(im, 600, 790, "No INTERNET permission in the manifest", GREEN, (8, 40, 36), size=28, a=seg(t, 18.0, 18.8))
    chip(im, 1330, 790, "Not claimed: app-level encryption or authentication", AMBER, (52, 38, 10), size=26, a=seg(t, 19.2, 20.0))
    return im


def scene7(t):
    im = to_rgba(bg("bg_a.jpg")).copy()
    top_badge(im, "ILLUSTRATIVE MOCKUP - logic verified in simulated tests; physical Bluetooth testing in progress")
    if t < 7.8:
        pa = seg(t, 0.2, 1.2)
        put_phone(im, "coordinator", "received", 620, 520, h=780, a=pa, glow_color=GREEN)
        tx = seg(t, 1.2, 2.2)
        lay = Image.new("RGBA", (900, 260), (0, 0, 0, 0))
        d = ImageDraw.Draw(lay)
        d.text((0, 40), "The SOS is on the", font=font(60, "bold"), fill=WHITE, anchor="lm")
        d.text((0, 115), "Coordinator dashboard", font=font(60, "bold"), fill=GREEN, anchor="lm")
        d.text((0, 195), "Saved first. Acknowledged. Highlighted as the latest report.", font=font(30), fill=(190, 205, 232), anchor="lm")
        paste_a(im, lay, (1000, 380), tx)
        # fade to logo
        out = seg(t, 6.8, 7.8)
        if out > 0:
            logo = to_rgba(bg("bg_b.jpg"))
            im = Image.blend(im.convert("RGB"), logo.convert("RGB"), 0).convert("RGBA")
            dark = Image.new("RGBA", (W, H), (5, 11, 26, int(255 * out)))
            im.alpha_composite(dark)
    if t >= 7.6:
        p = seg(t, 7.6, 9.0)
        layer = Image.new("RGBA", (W, 400), (0, 0, 0, 0))
        ld = ImageDraw.Draw(layer)
        ld.text((W // 2, 150), "SETHU", font=font(230, "bold"), fill=WHITE, anchor="mm")
        paste_a(im, art.glow(layer, 20, 0.9), (0, 250), p * 0.8)
        paste_a(im, layer, (0, 250), p)
        tl = Image.new("RGBA", (W, 120), (0, 0, 0, 0))
        ImageDraw.Draw(tl).text((W // 2, 50), "Every message deserves a path.", font=font(64, "semi"), fill=CYAN, anchor="mm")
        paste_a(im, tl, (0, 600), seg(t, 9.6, 10.8))
        ft = Image.new("RGBA", (W, 60), (0, 0, 0, 0))
        ImageDraw.Draw(ft).text((W // 2, 30), "Illustrative visuals. Application logic verified in simulated tests; physical Bluetooth testing in progress.",
                                font=font(24), fill=(140, 155, 185), anchor="mm")
        paste_a(im, ft, (0, 930), seg(t, 12.0, 13.0))
    return im


SCENE_FN = [scene1, scene2, scene3, scene4, scene5, scene6, scene7]


# --------------------------------------------------------------------------------------------- captions
def sentences(text):
    parts, cur = [], ""
    for ch in text:
        cur += ch
        if ch in ".?!" and len(cur.strip()) > 12:
            parts.append(cur.strip())
            cur = ""
    if cur.strip():
        parts.append(cur.strip())
    return parts


CAPTIONS = []  # (start, end, text)
for sc in SCENES:
    ss = sentences(sc["text"])
    words = [len(s.split()) for s in ss]
    tot = sum(words)
    t = sc["start"] + sc["voff"]
    for s, w in zip(ss, words):
        dur = sc["tts"] * w / tot
        CAPTIONS.append((t, t + dur, s))
        t += dur


def wrap(text, f, maxw, d):
    words, lines, cur = text.split(), [], ""
    for w_ in words:
        trial = (cur + " " + w_).strip()
        if d.textlength(trial, font=f) <= maxw:
            cur = trial
        else:
            lines.append(cur)
            cur = w_
    lines.append(cur)
    return lines


_cap_cache = {}


def caption_layer(text):
    if text in _cap_cache:
        return _cap_cache[text]
    f = font(36, "semi")
    tmp = ImageDraw.Draw(Image.new("RGB", (10, 10)))
    lines = wrap(text, f, 1700, tmp)
    h = 30 + len(lines) * 48
    w = max(int(tmp.textlength(l, font=f)) for l in lines) + 90
    lay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    d.rounded_rectangle([0, 0, w - 1, h - 1], radius=26, fill=(4, 8, 20, 205))
    for k, ln in enumerate(lines):
        d.text((w // 2, 15 + 24 + k * 48), ln, font=f, fill=(245, 248, 255), anchor="mm")
    _cap_cache[text] = lay
    return lay


def add_caption(im, tg):
    for (a, b, text) in CAPTIONS:
        if a - 0.15 <= tg <= b + 0.35:
            al = min(1.0, (tg - (a - 0.15)) / 0.25, ((b + 0.35) - tg) / 0.25)
            lay = caption_layer(text)
            paste_a(im, lay, ((W - lay.size[0]) // 2, 1040 - lay.size[1]), max(0.0, al))
            return


# --------------------------------------------------------------------------------------------- frame
def frame_at(tg):
    idx = 0
    for sc in SCENES:
        if tg >= sc["start"]:
            idx = sc["i"]
    sc = SCENES[idx]
    tl = tg - sc["start"]
    im = SCENE_FN[idx](tl)
    # cross-fade into the next scene
    if idx < len(SCENES) - 1 and tl > sc["dur"] - TR:
        nxt = SCENE_FN[idx + 1](0.0)
        a = (tl - (sc["dur"] - TR)) / TR
        im = Image.blend(im.convert("RGB"), nxt.convert("RGB"), min(1.0, max(0.0, a))).convert("RGBA")
    add_caption(im, tg)
    # fade from / to black
    if tg < 0.8:
        im = Image.blend(Image.new("RGB", (W, H)), im.convert("RGB"), tg / 0.8).convert("RGBA")
    if tg > TOTAL - 0.9:
        im = Image.blend(im.convert("RGB"), Image.new("RGB", (W, H)), min(1.0, (tg - (TOTAL - 0.9)) / 0.9)).convert("RGBA")
    return im.convert("RGB")


# --------------------------------------------------------------------------------------------- audio
def build_audio(path):
    sr = 44100
    n = int((TOTAL + 0.5) * sr)
    voice = np.zeros(n, dtype=np.float32)
    for sc in SCENES:
        w = wave.open(str(AUDIO / f"narr_{sc['i'] + 1}.wav"))
        raw = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
        x = np.interp(np.linspace(0, len(raw) - 1, int(len(raw) * sr / w.getframerate())), np.arange(len(raw)), raw)
        s0 = int((sc["start"] + sc["voff"]) * sr)
        voice[s0:s0 + len(x)] += x[: n - s0]
    # soft ambient pad: A minor, slow swell, very quiet
    tt = np.arange(n) / sr
    pad = np.zeros(n, dtype=np.float32)
    for k, f0 in enumerate([110.0, 164.81, 220.0, 261.63, 329.63]):
        for det in (-0.4, 0.0, 0.4):
            pad += (0.55 / (1 + k * 0.35)) * np.sin(2 * np.pi * (f0 + det) * tt + k)
    swell = 0.6 + 0.4 * np.sin(2 * np.pi * tt / 11.0)
    pulse = 0.85 + 0.15 * np.sin(2 * np.pi * tt * 0.5)
    pad = pad * swell * pulse
    pad /= np.max(np.abs(pad)) + 1e-9
    env = np.clip(tt / 3.0, 0, 1) * np.clip((TOTAL + 0.4 - tt) / 3.0, 0, 1)
    music = pad * env * 0.07
    # gentle ducking under the voice
    duck = np.convolve(np.abs(voice), np.ones(2205) / 2205, mode="same")
    duck = 1.0 - 0.5 * np.clip(duck * 14, 0, 1)
    mix = voice * 1.0 + music * duck
    mix = np.tanh(mix * 1.1)
    st = np.stack([mix, mix], axis=1)
    pcm = (np.clip(st, -1, 1) * 32767).astype(np.int16)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def main(preview_only=False):
    out = ROOT / "Sethu_Presentation_Video.mp4"
    audio = AUDIO / "mix.wav"
    build_audio(audio)
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    nframes = int(TOTAL * FPS)
    print("scenes:", [(round(s["start"], 1), round(s["dur"], 1)) for s in SCENES], "total", round(TOTAL, 1), "frames", nframes)
    if preview_only:
        pdir = ASSETS / "video_preview"
        pdir.mkdir(exist_ok=True)
        for tg in [2, 6, 12, 19, 24, 29, 33, 37, 42, 47, 52, 57, 61, 66, 70, 74, 80, 84, 90, 96, 103, 108, 112, 117]:
            if tg < TOTAL:
                frame_at(tg).save(pdir / f"f_{tg:03d}.png")
        return
    cmd = [ff, "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
           "-i", str(audio), "-c:v", "libx264", "-preset", "medium", "-crf", "19", "-pix_fmt", "yuv420p",
           "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart", str(out)]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stderr=subprocess.DEVNULL)
    for fi in range(nframes):
        p.stdin.write(np.asarray(frame_at(fi / FPS), dtype=np.uint8).tobytes())
        if fi % 300 == 0:
            print("frame", fi, "/", nframes, flush=True)
    p.stdin.close()
    p.wait()
    print("done", out)


if __name__ == "__main__":
    main(preview_only="--preview" in sys.argv)
