"""
LowSpec capes, build-time generator (numpy + Pillow).

Each cape is painted as a lit material (woven cloth with drape folds, embroidered gold thread, leather
grain, real turbulence fire / aurora), not as a gradient. Canvas is the standard 64x32 cape layout at 5x
(320x160); the visible face is the 50x80 area at (5,5). The whole canvas is filled so the same texture
also works as elytra wings.

Animated capes are written as numbered frames  <name>_<i>.png ; static ones as <name>.png.
"""
import os

import numpy as np
from PIL import Image, ImageFilter

RES = os.environ.get("LOWSPEC_RES", "src/main/resources")
OUT = os.path.join(RES, "assets/lowspec/textures/cape")
S = 5
W, H = 64 * S, 32 * S
FX, FY, FW, FH = 1 * S, 1 * S, 10 * S, 16 * S
FRAMES = int(os.environ.get("CAPE_FRAMES", "24"))

YS, XS = np.mgrid[0:H, 0:W].astype(np.float64)
U = (XS + 0.5 - FX) / FW
V = (YS + 0.5 - FY) / FH


def col(r, g, b):
    return np.array([r, g, b], dtype=np.float64) / 255.0


# ------------------------------------------------------------------ noise
def gnoise(x, y, px, py, seed):
    """Periodic gradient noise. x,y in lattice units, wraps every px / py cells."""
    rng = np.random.default_rng(seed)
    ang = rng.random((py, px)) * 2 * np.pi
    gx, gy = np.cos(ang), np.sin(ang)
    x0 = np.floor(x).astype(int)
    y0 = np.floor(y).astype(int)
    fx, fy = x - x0, y - y0

    def dot(ix, iy, dx, dy):
        return gx[iy % py, ix % px] * dx + gy[iy % py, ix % px] * dy

    n00 = dot(x0, y0, fx, fy)
    n10 = dot(x0 + 1, y0, fx - 1, fy)
    n01 = dot(x0, y0 + 1, fx, fy - 1)
    n11 = dot(x0 + 1, y0 + 1, fx - 1, fy - 1)
    u = fx * fx * fx * (fx * (fx * 6 - 15) + 10)
    v = fy * fy * fy * (fy * (fy * 6 - 15) + 10)
    return (n00 * (1 - u) + n10 * u) * (1 - v) + (n01 * (1 - u) + n11 * u) * v


def fbm(x, y, px, py, seed, octaves=5, gain=0.5):
    total, amp, norm = 0.0, 1.0, 0.0
    for o in range(octaves):
        total = total + amp * gnoise(x * 2 ** o, y * 2 ** o, px * 2 ** o, py * 2 ** o, seed + o * 17)
        norm += amp
        amp *= gain
    return total / norm * 1.6


def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def blur(a, radius):
    img = Image.fromarray((np.clip(a, 0, 1) * 255).astype(np.uint8), "L").filter(ImageFilter.GaussianBlur(radius))
    return np.asarray(img).astype(np.float64) / 255.0


# ------------------------------------------------------------------ shared lighting
LIGHT = np.array([-0.45, -0.6, 0.66])
LIGHT = LIGHT / np.linalg.norm(LIGHT)
HALF = LIGHT + np.array([0, 0, 1.0])
HALF = HALF / np.linalg.norm(HALF)


def drape(seed=3):
    """Vertical drape folds that open up towards the hem, plus a little cloth wobble."""
    h = np.zeros_like(U)
    for i, (f, a, ph) in enumerate([(1.7, 1.0, 0.25), (3.3, 0.6, 1.4), (6.1, 0.28, 0.7), (11.0, 0.1, 2.1)]):
        warp = 0.10 * np.sin(V * 2.4 + i) + 0.05 * fbm(U * 3, V * 3, 4, 4, seed + i, 2)
        h = h + a * np.sin(2 * np.pi * (U * f + ph + warp))
    h = h * (0.25 + 0.9 * np.clip(V, 0, 1.3))
    h = h + 0.35 * fbm(U * 2.2, V * 3.2, 5, 6, seed + 40, 3)
    return h * 1.15


def weave(strength=0.06, period=3.6, seed=5):
    wx = np.sin(2 * np.pi * XS / period)
    wy = np.sin(2 * np.pi * YS / period)
    irregular = 1 + 0.5 * fbm(XS / 9, YS / 9, 40, 20, seed, 2)
    return strength * wx * wy * irregular


def light(height, color, spec, spec_pow=22, rim=0.0, slope=1.0, glint=None, ambient=0.34):
    gy, gx = np.gradient(height)
    nx, ny, nz = -gx * slope, -gy * slope, np.ones_like(height)
    nn = np.sqrt(nx * nx + ny * ny + nz * nz)
    nx, ny, nz = nx / nn, ny / nn, nz / nn
    diff = np.clip(nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2], 0, 1) / LIGHT[2]
    shade = ambient + (1 - ambient) * np.clip(diff, 0, 1.25)
    out = color * shade[..., None]
    sp = np.clip(nx * HALF[0] + ny * HALF[1] + nz * HALF[2], 0, 1) ** spec_pow
    if glint is not None:
        sp = sp * (1 + 2.2 * glint)
        out = out * (1 + 0.14 * glint)[..., None]
    out = out + (spec * sp)[..., None] * np.array([1.0, 0.97, 0.9])
    if rim > 0:
        r = (1 - nz) ** 1.4
        out = out + (rim * r)[..., None] * (0.35 + 0.65 * color)
    return out


def finish(c, name_edge=True):
    # darker towards the top (it tucks under the neck), soft edge darkening on the visible face
    top = 0.80 + 0.20 * smooth(-0.02, 0.38, V)
    inside = (U >= 0) & (U < 1) & (V >= 0) & (V < 1)
    c = c * np.where(inside, top, 1.0)[..., None]
    c = np.clip(c, 0, 1.4)
    c = c / (1.0 + 0.18 * np.maximum(c - 0.85, 0))          # gentle highlight roll-off
    c = np.clip(c, 0, 1) ** (1 / 1.04)
    rgba = np.zeros((H, W, 4), dtype=np.uint8)
    rgba[..., :3] = (c * 255 + 0.5).astype(np.uint8)
    rgba[..., 3] = 255
    return rgba


# ------------------------------------------------------------------ gold trim and emblem
GOLD = col(178, 140, 60)


def edge_dist():
    """Distance (in texture px) from the nearest border of the visible face; <0 outside."""
    return np.minimum.reduce([XS - FX, FX + FW - 1 - XS, YS - FY, FY + FH - 1 - YS])


def trim_masks():
    e = edge_dist()
    outer = smooth(3.0, 3.7, e) * (1 - smooth(5.3, 6.0, e))     # main gold band
    inner = np.exp(-(((e - 8.3) / 0.55) ** 2))                  # fine inner line
    band = np.clip(outer + inner, 0, 1)
    relief = np.sin(np.clip((e - 3.2) / 2.6, 0, 1) * np.pi) * outer + inner * 0.8
    hem = (e >= 0) & (e < 3.2)
    stitch_line = (np.abs(e - 11.2) < 0.55)
    dash = (np.sin(2 * np.pi * (XS + YS) / 6.5) > 0.1) & stitch_line & (e > 0)
    return band, relief, hem, dash, e


def emblem(cu=0.5, cv=0.44):
    """Embroidered medallion: ring + eight-point star. Returns (gold mask, relief height, inner recess)."""
    dx = (XS - (FX + cu * FW))
    dy = (YS - (FY + cv * FH))
    r = np.sqrt(dx * dx + dy * dy)
    th = np.arctan2(dy, dx)
    ring = np.exp(-(((r - 15.0) / 0.75) ** 2)) + 0.8 * np.exp(-(((r - 12.6) / 0.45) ** 2))
    star_r = 11.0 * (0.20 + 0.80 * np.abs(np.cos(4 * th)) ** 3.0)
    star = smooth(0.7, -0.7, r - star_r) * smooth(0, 1.0, 11.5 - r)
    core = smooth(2.4, 1.3, r)
    dots = np.zeros_like(r)
    for k in range(8):
        a = (k + 0.5) * np.pi / 4
        dots = np.maximum(dots, smooth(1.5, 0.9, np.sqrt((dx - 8.2 * np.cos(a)) ** 2 + (dy - 8.2 * np.sin(a)) ** 2)))
    gold = np.clip(ring * 1.2 + star + core + dots * 0.8, 0, 1)
    relief = ring * 0.8 + star * 0.9 + core * 0.7 + dots * 0.5
    recess = smooth(14.2, 11.8, r) * (1 - gold)
    return gold, relief, recess


def stitched_fabric(fabric, spec, spec_pow, rim, glint, trim_col=GOLD, emblem_on=True, damask=None):
    drp = drape()
    wv = weave()
    band, relief, hem, dash, e = trim_masks()
    height = drp + wv
    color = np.ones((H, W, 3)) * fabric
    if damask is not None:
        dm, dh = damask
        color = color * (1 + 0.20 * dm)[..., None]
        height = height + dh
    # hem: folded-over edge, slightly lighter, with a fold shadow line
    color = np.where(hem[..., None], color * 1.06, color)
    height = height + np.where(hem, 0.5, 0) + np.where((e > 2.2) & (e < 3.4), -0.7, 0)

    gold_mask = band.copy()
    height = height + relief * 1.6
    if emblem_on:
        g, rel, rec = emblem()
        gold_mask = np.clip(gold_mask + g, 0, 1)
        height = height + rel * 1.7 - rec * 0.5
    # stitching (thread dashes) just inside the trim
    gold_mask = np.clip(gold_mask + dash * 0.9, 0, 1)
    height = height + dash * 0.35

    thread = 1 + 0.10 * fbm(XS / 2.5, YS / 2.5, 128, 64, 21, 2)
    gold_color = trim_col[None, None, :] * thread[..., None]
    color = color * (1 - gold_mask[..., None]) + gold_color * gold_mask[..., None]
    sp = spec * (1 - gold_mask) + 0.95 * gold_mask
    spow = spec_pow
    out = light(height, color, sp, spec_pow=spow, rim=rim, slope=1.0, glint=glint)
    # gold reads a little warmer / brighter than lit cloth
    out = out + (0.10 * gold_mask)[..., None] * trim_col
    return out


def sheen(t, tmax):
    """Soft diagonal highlight that sweeps over the cloth once per loop."""
    prog = t / float(tmax)
    pos = -0.35 + 1.7 * prog
    proj = (U * 0.55 + V * 0.85)
    return np.exp(-(((proj - pos) / 0.16) ** 2))


# ------------------------------------------------------------------ the capes
def royal(t=None):
    g = sheen(t, FRAMES) if t is not None else None
    return stitched_fabric(col(118, 14, 28), spec=0.10, spec_pow=14, rim=0.42, glint=g)


def emerald(t=None):
    g = sheen(t, FRAMES) if t is not None else None
    return stitched_fabric(col(10, 94, 66), spec=0.34, spec_pow=26, rim=0.12, glint=g, trim_col=col(224, 196, 120))


def midnight(t=None):
    g = sheen(t, FRAMES) if t is not None else None
    # damask: two interleaved lattices of soft ovals, tone-on-tone (the motif is satin, the ground matte)
    p, q = 15.0, 22.0
    m1 = (np.cos(2 * np.pi * XS / p) + 1) / 2 * (np.cos(2 * np.pi * YS / q) + 1) / 2
    m2 = (np.cos(2 * np.pi * (XS / p + 0.5)) + 1) / 2 * (np.cos(2 * np.pi * (YS / q + 0.5)) + 1) / 2
    motif = np.clip(smooth(0.50, 0.70, m1) + 0.75 * smooth(0.50, 0.70, m2), 0, 1)
    # a thin carved outline around each motif so it reads as woven pattern
    ring = np.exp(-(((m1 - 0.50) / 0.05) ** 2)) * 0.5 + np.exp(-(((m2 - 0.50) / 0.05) ** 2)) * 0.4
    motif = np.clip(motif + ring, 0, 1)
    height = motif * 0.7
    return stitched_fabric(col(18, 30, 70), spec=0.26, spec_pow=26, rim=0.10, glint=g,
                           trim_col=col(176, 180, 196), damask=(motif * 0.7, height))


def leather(t=None):
    # cellular grain
    gx_, gy_ = XS / 7.0, YS / 7.0
    ix, iy = np.floor(gx_), np.floor(gy_)
    best = np.full(XS.shape, 9.0)
    second = np.full(XS.shape, 9.0)
    for oy in (-1, 0, 1):
        for ox in (-1, 0, 1):
            cx, cy = ix + ox, iy + oy
            h1 = np.sin(cx * 127.1 + cy * 311.7) * 43758.5453
            h2 = np.sin(cx * 269.5 + cy * 183.3) * 43758.5453
            jx, jy = h1 - np.floor(h1), h2 - np.floor(h2)
            d = np.sqrt((gx_ - (cx + jx)) ** 2 + (gy_ - (cy + jy)) ** 2)
            second = np.where(d < best, best, np.minimum(second, d))
            best = np.minimum(best, d)
    crease = smooth(0.0, 0.22, second - best)
    grain = crease * 0.55 + 0.25 * fbm(XS / 4, YS / 4, 80, 40, 9, 3)

    drp = drape(11) * 1.1
    band, relief, hem, dash, e = trim_masks()
    height = drp + grain * 0.7 + np.where(hem, 0.4, 0)
    color = np.ones((H, W, 3)) * col(30, 24, 22)
    color = color * (0.85 + 0.3 * crease)[..., None]

    # silver studs along the border + stitching
    studs = np.zeros_like(XS)
    stud_h = np.zeros_like(XS)
    step = 17.0
    for side in range(4):
        if side == 0:      # top
            pts = [(FX + 6 + k * step, FY + 6) for k in range(int((FW - 12) / step) + 1)]
        elif side == 1:    # bottom
            pts = [(FX + 6 + k * step, FY + FH - 7) for k in range(int((FW - 12) / step) + 1)]
        elif side == 2:    # left
            pts = [(FX + 6, FY + 6 + k * step) for k in range(int((FH - 12) / step) + 1)]
        else:              # right
            pts = [(FX + FW - 7, FY + 6 + k * step) for k in range(int((FH - 12) / step) + 1)]
        for (px, py) in pts:
            r = np.sqrt((XS - px) ** 2 + (YS - py) ** 2)
            studs = np.maximum(studs, smooth(2.1, 1.5, r))
            stud_h = np.maximum(stud_h, np.sqrt(np.clip(1 - (r / 2.1) ** 2, 0, 1)) * 2.0)
    height = height + stud_h + dash * 0.3
    silver = col(150, 154, 162)
    color = color * (1 - np.clip(studs + dash * 0.7, 0, 1)[..., None]) + silver * np.clip(studs + dash * 0.7, 0, 1)[..., None]
    sp = 0.18 * (1 - studs) + 0.9 * studs
    out = light(height, color, sp, spec_pow=18, rim=0.05, slope=1.1)
    return out


def ember(t=0):
    # charred wool with real turbulent flames
    drp = drape(7)
    wv = weave(0.07)
    height = drp * 0.8 + wv
    color = np.ones((H, W, 3)) * col(28, 20, 20)
    base = light(height, color, 0.05, spec_pow=10, rim=0.1)

    shift = (t / FRAMES) * 8.0
    px = 8
    warp = fbm(U * 3.0, V * 5.0 + shift, px, 8, 31, 4)
    n = fbm(U * 5.0 + warp * 0.9, V * 7.0 + shift + warp * 1.2, px * 2, 8, 43, 5)
    rise = smooth(0.18, 1.05, V)                              # flames are strongest at the hem
    flame = np.clip(n * 0.95 + rise * 1.25 - 0.86, 0, 1)
    flame = blur(flame ** 0.9, 0.55)
    ramp = np.zeros((H, W, 3))
    stops = [(0.0, col(0, 0, 0)), (0.18, col(110, 8, 0)), (0.42, col(226, 52, 0)),
             (0.68, col(255, 150, 20)), (0.9, col(255, 226, 120)), (1.0, col(255, 250, 220))]
    for (a0, c0), (a1, c1) in zip(stops[:-1], stops[1:]):
        m = (flame >= a0) & (flame <= a1)
        w = np.clip((flame - a0) / (a1 - a0), 0, 1)[..., None]
        ramp = np.where(m[..., None], c0 + (c1 - c0) * w, ramp)
    glow = blur(flame, 3.2)
    heat = blur(flame, 1.0)
    out = base * (1 - 0.7 * np.clip(heat * 2.4, 0, 1))[..., None] + ramp * 1.35
    out = out + col(255, 70, 8) * (glow ** 1.3 * 0.55)[..., None]
    # embers
    h = np.sin(np.floor(XS / 2) * 127.1 + np.floor((YS + t * 3.0 * 0) / 2) * 311.7) * 43758.5453
    h = h - np.floor(h)
    out = out + col(255, 170, 60) * (np.clip((h - 0.992) / 0.008, 0, 1) * smooth(0.3, 0.9, V))[..., None] * 0.9
    return out


def aurora(t=0):
    drp = drape(13)
    wv = weave(0.05)
    base = light(drp * 0.6 + wv, np.ones((H, W, 3)) * col(6, 10, 22), 0.05, spec_pow=10, rim=0.06)

    shift = (t / FRAMES) * 6.0
    # curtains: vertical rays whose lower edge wobbles
    edge = 0.74 + 0.10 * fbm(U * 3 + shift, V * 0 + 1.3, 6, 4, 61, 3)
    rays = fbm(U * 18.0 + shift * 2.0, V * 0.5 + 4.0, 36, 4, 67, 3)
    rays = np.clip(0.50 + rays * 2.2, 0, 1) ** 1.2
    fall = smooth(edge + 0.02, edge - 0.55, V) * smooth(edge + 0.10, edge - 0.01, V)
    sparkle = 0.75 + 0.5 * fbm(U * 4.0 - shift * 0.7, V * 2.0, 12, 8, 71, 3)
    inten = np.clip(fall * rays * sparkle * 1.35, 0, 1.2)
    hgt = np.clip((edge - V) / 0.55, 0, 1)
    c_low = col(70, 255, 120)
    c_mid = col(40, 225, 175)
    c_top = col(165, 80, 215)
    tint = np.where((hgt < 0.72)[..., None], c_low + (c_mid - c_low) * (hgt / 0.72)[..., None],
                    c_mid + (c_top - c_mid) * ((hgt - 0.72) / 0.28)[..., None])
    emissive = tint * inten[..., None] * 1.15
    emissive = emissive + blur(inten, 3.0)[..., None] * tint * 0.45
    # a few real stars
    h = np.sin(np.floor(XS / 2) * 127.1 + np.floor(YS / 2) * 311.7) * 43758.5453
    h = h - np.floor(h)
    stars = np.clip((h - 0.994) / 0.006, 0, 1) * (1 - np.clip(inten * 1.5, 0, 1)) * smooth(0.0, 0.5, 0.9 - V)
    return base * (1 - 0.5 * np.clip(inten, 0, 1))[..., None] + emissive + stars[..., None] * col(200, 215, 255) * 0.8


DESIGNS = [  # (name, function, animated)
    ("royal", royal, True),
    ("midnight", midnight, True),
    ("emerald", emerald, True),
    ("leather", leather, False),
    ("ember", ember, True),
    ("aurora", aurora, True),
]


def save(arr, name):
    Image.fromarray(arr, "RGBA").save(os.path.join(OUT, name + ".png"), optimize=True)


def main():
    os.makedirs(OUT, exist_ok=True)
    total = 0
    only = os.environ.get("CAPE_ONLY")
    for name, fn, animated in DESIGNS:
        if only and name not in only.split(","):
            continue
        if animated:
            for i in range(FRAMES):
                save(finish(fn(i)), "%s_%d" % (name, i))
                total += 1
        else:
            save(finish(fn()), name)
            total += 1
    print("cape textures written:", total)


if __name__ == "__main__":
    main()
