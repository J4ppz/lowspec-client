"""
Generates the LowSpec Client art at build time (needs only numpy):
  - 6 panorama faces (night lake + low-poly mountains + aurora + stars + moon)
  - 3 glass button sprites (normal / hover / disabled) + nine-slice metadata
"""
import json
import os
import struct
import zlib

import numpy as np

ROOT = os.environ.get("LOWSPEC_RES", "src/main/resources")


def write_png(path, arr):
    arr = np.ascontiguousarray(arr.astype(np.uint8))
    h, w, c = arr.shape
    ctype = 6 if c == 4 else 2
    raw = np.concatenate(
        [np.zeros((h, 1), dtype=np.uint8), arr.reshape(h, w * c)], axis=1
    ).tobytes()

    def chunk(t, d):
        body = struct.pack(">I", len(d)) + t + d
        return body + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, ctype, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


# ---------------------------------------------------------------- panorama
N = 512

# (forward, right, up) per face. Matches the game's own panorama capture:
# 0 = south (+Z), 1 = west (-X), 2 = north (-Z), 3 = east (+X), 4 = up, 5 = down.
FACES = [
    ((0, 0, 1), (-1, 0, 0), (0, 1, 0)),
    ((-1, 0, 0), (0, 0, -1), (0, 1, 0)),
    ((0, 0, -1), (1, 0, 0), (0, 1, 0)),
    ((1, 0, 0), (0, 0, 1), (0, 1, 0)),
    ((0, 1, 0), (-1, 0, 0), (0, 0, -1)),
    ((0, -1, 0), (-1, 0, 0), (0, 0, 1)),
]


def lerp(a, b, t):
    return a + (b - a) * t


def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def col(r, g, b):
    return np.array([r, g, b], dtype=np.float64) / 255.0


def peaks(az, base, parts):
    h = base
    for amp, freq, ph in parts:
        h = h + amp * (1.0 - np.abs(np.sin(freq * az + ph)))
    return h


def sky_and_land(az, el, dirs):
    """Colour for elevation el >= 0 (az/el are arrays, dirs unit vectors mirrored to el>=0)."""
    t = np.clip(el / 1.25, 0, 1)
    zen, mid, low, hor = col(5, 7, 26), col(38, 22, 92), col(92, 50, 168), col(70, 226, 196)
    c = np.empty(el.shape + (3,))
    k1 = smooth(0.0, 0.55, t)[..., None]
    k2 = smooth(0.35, 1.0, t)[..., None]
    base = lerp(low, mid, k1)
    base = lerp(base, zen, k2)
    glow = np.exp(-el / 0.11)[..., None]
    c[:] = base + hor * glow * 0.55

    # moon
    m = np.array([0.52, 0.40, 0.76])
    m = m / np.linalg.norm(m)
    dot = np.clip(dirs[..., 0] * m[0] + dirs[..., 1] * m[1] + dirs[..., 2] * m[2], -1, 1)
    ang = np.arccos(dot)
    disc = smooth(0.085, 0.074, ang)
    halo = np.exp(-ang * 7.0) * 0.55 + np.exp(-ang * 22.0) * 0.55
    c += (col(215, 235, 255) * (disc[..., None] * 1.6)) + col(150, 190, 255) * halo[..., None] * 0.55

    # stars (3D hashed cells, so they are seamless across faces)
    cell = np.floor(dirs * 170.0)
    hsh = np.sin(cell[..., 0] * 12.9898 + cell[..., 1] * 78.233 + cell[..., 2] * 37.719) * 43758.5453
    hsh = hsh - np.floor(hsh)
    star = np.clip((hsh - 0.9972) / 0.0028, 0, 1) ** 1.5
    star *= smooth(0.02, 0.35, el) * (1.0 - disc)
    c += star[..., None] * col(220, 230, 255) * 1.4

    # aurora ribbons
    for (c0, wd, f1, p1, f2, amp, colA, colB, strength) in (
        (0.66, 0.085, 2, 1.0, 5, 0.08, col(40, 255, 170), col(150, 90, 255), 0.70),
        (0.50, 0.060, 3, 2.3, 7, 0.05, col(80, 200, 255), col(190, 90, 255), 0.45),
    ):
        e0 = c0 + 0.09 * np.sin(f1 * az + p1) + amp * np.sin(f2 * az + 0.7)
        rib = np.exp(-(((el - e0) / wd) ** 2))
        mod = 0.5 + 0.5 * np.sin(3 * az + 4.0 * el + 2.0 * np.sin(az))
        tint = np.clip((el - 0.40) / 0.45, 0, 1)[..., None]
        c += lerp(colA, colB, tint) * (rib * mod * strength)[..., None]

    # mountains (three low-poly ridges, far to near)
    layers = [
        (peaks(az, 0.150, [(0.095, 2, 0.3), (0.055, 5, 1.7), (0.030, 11, 0.4), (0.014, 23, 2.0)]),
         col(60, 46, 126), 0.28),
        (peaks(az, 0.085, [(0.080, 3, 2.0), (0.045, 7, 0.9), (0.022, 17, 3.0)]),
         col(34, 22, 80), 0.14),
        (peaks(az, 0.020, [(0.060, 4, 4.0), (0.034, 9, 1.2), (0.016, 21, 0.5)]),
         col(12, 9, 38), 0.05),
    ]
    for h, mc, haze in layers:
        mask = (el < h)
        shade = 1.0 - 0.55 * np.clip(el / np.maximum(h, 1e-3), 0, 1)
        lit = mc[None, None, :] * (0.75 + 0.45 * shade[..., None])
        lit = lit + hor * np.exp(-el / 0.05)[..., None] * haze * 0.8
        c = np.where(mask[..., None], lit, c)
    return c


def render_face(face):
    fwd, right, up = (np.array(v, dtype=np.float64) for v in FACES[face])
    u = (np.arange(N) + 0.5) / N * 2 - 1
    v = 1 - (np.arange(N) + 0.5) / N * 2
    U, V = np.meshgrid(u, v)
    d = fwd[None, None, :] + U[..., None] * right[None, None, :] + V[..., None] * up[None, None, :]
    d = d / np.linalg.norm(d, axis=2, keepdims=True)

    el = np.arcsin(np.clip(d[..., 1], -1, 1))
    az = np.arctan2(d[..., 0], d[..., 2])

    below = el < 0
    ripple = 0.0035 * np.sin(az * 150 + el * 700) + 0.002 * np.sin(az * 61 - el * 300)
    el_m = np.where(below, -el + ripple * np.clip(-el * 6, 0, 1), el)
    el_m = np.abs(el_m)
    dm = d.copy()
    dm[..., 1] = np.sin(el_m)
    horiz = np.sqrt(np.maximum(1 - dm[..., 1] ** 2, 1e-9))
    hn = np.sqrt(d[..., 0] ** 2 + d[..., 2] ** 2)
    dm[..., 0] = d[..., 0] / np.maximum(hn, 1e-9) * horiz
    dm[..., 2] = d[..., 2] / np.maximum(hn, 1e-9) * horiz

    c = sky_and_land(az, el_m, dm)

    # lake: darker, tinted, darker towards the bottom
    depth = np.clip(-el / 1.3, 0, 1)[..., None]
    water = c * 0.55 + col(8, 24, 52) * 0.45
    water = water * (1.0 - 0.6 * depth) + col(3, 6, 18) * 0.6 * depth
    c = np.where(below[..., None], water, c)

    c = 1.0 - np.exp(-1.35 * c)
    c = np.clip(c, 0, 1) ** (1 / 1.05)
    rng = np.random.default_rng(face + 7)
    c = c + (rng.random(c.shape) - 0.5) * (2.0 / 255.0)
    return (np.clip(c, 0, 1) * 255.0 + 0.5)


def gen_panorama():
    outdir = os.path.join(ROOT, "assets/minecraft/textures/gui/title/background")
    for f in range(6):
        write_png(os.path.join(outdir, "panorama_%d.png" % f), render_face(f))


# ---------------------------------------------------------------- buttons
S = 4            # texture px per GUI px
GW, GH = 200, 20
RADIUS = 6       # GUI px
BORDER = 6       # nine-slice border (GUI px)


def sdf_round_rect(w, h, r):
    ys, xs = np.mgrid[0:h, 0:w]
    px = xs + 0.5 - w / 2
    py = ys + 0.5 - h / 2
    qx = np.abs(px) - (w / 2 - r)
    qy = np.abs(py) - (h / 2 - r)
    outside = np.sqrt(np.maximum(qx, 0) ** 2 + np.maximum(qy, 0) ** 2)
    inside = np.minimum(np.maximum(qx, qy), 0)
    return outside + inside - r


def button_sprite(top, bottom, fill_a, border_rgb, border_a, glow_rgb=None, glow_a=0.0, gloss=0.16):
    w, h = GW * S, GH * S
    d = sdf_round_rect(w, h, RADIUS * S)
    inside = np.clip(0.5 - d, 0, 1)
    bw = 1.25 * S
    ring = np.clip(0.5 - (np.abs(d + bw / 2) - bw / 2), 0, 1)

    t = (np.arange(h) / (h - 1))[:, None]
    fill = np.zeros((h, w, 3))
    for i in range(3):
        fill[..., i] = top[i] + (bottom[i] - top[i]) * t
    g = np.clip(1.0 - t / 0.55, 0, 1) ** 2 * gloss
    fill = fill + g[..., None] * 255.0
    if glow_rgb is not None:
        inner = np.clip(-d / (5.0 * S), 0, 1)
        edge = (1.0 - inner) ** 2 * glow_a
        fill = fill + edge[..., None] * np.array(glow_rgb, dtype=np.float64)

    fa = inside * fill_a
    ba = ring * border_a
    alpha = ba + fa * (1 - ba)
    brgb = np.array(border_rgb, dtype=np.float64)
    rgb = (brgb[None, None, :] * ba[..., None] + np.clip(fill, 0, 255) * (fa * (1 - ba))[..., None])
    rgb = rgb / np.maximum(alpha[..., None], 1e-6)
    out = np.zeros((h, w, 4))
    out[..., :3] = np.clip(rgb, 0, 255)
    out[..., 3] = np.clip(alpha, 0, 1) * 255.0
    return out + 0.0


def gen_buttons():
    outdir = os.path.join(ROOT, "assets/minecraft/textures/gui/sprites/widget")
    meta = {"gui": {"scaling": {"type": "nine_slice", "width": GW, "height": GH, "border": BORDER}}}
    sprites = {
        "button": button_sprite((38, 44, 70), (14, 16, 32), 0.92, (170, 190, 255), 0.30),
        "button_highlighted": button_sprite(
            (52, 66, 104), (20, 28, 52), 0.96, (85, 255, 170), 0.95, glow_rgb=(40, 200, 140), glow_a=0.55, gloss=0.22),
        "button_disabled": button_sprite((26, 27, 34), (16, 16, 22), 0.70, (255, 255, 255), 0.12, gloss=0.05),
    }
    for name, arr in sprites.items():
        write_png(os.path.join(outdir, name + ".png"), arr)
        with open(os.path.join(outdir, name + ".png.mcmeta"), "w") as f:
            json.dump(meta, f)


# ---------------------------------------------------------------- logo (name tag glyph)
def gen_logo():
    k = 4
    w = 12 * k
    px = np.zeros((w, w, 4))

    def fill(x0, y0, x1, y1, c):
        px[y0 * k:y1 * k, x0 * k:x1 * k] = c

    rim = [(2, 10), (1, 11)] + [(0, 12)] * 8 + [(1, 11), (2, 10)]
    inner = {1: (3, 9), 2: (2, 10), 9: (2, 10), 10: (3, 9)}
    for r, (a, b) in enumerate(rim):
        fill(a, r, b, r + 1, (223, 251, 255, 230))
    top, bot = np.array([122, 92, 255.0]), np.array([61, 224, 192.0])
    for r in range(1, 11):
        a, b = inner.get(r, (1, 11))
        c = top + (bot - top) * ((r - 1) / 9.0)
        fill(a, r, b, r + 1, (c[0], c[1], c[2], 255))
    sl = (slice(2 * k, 3 * k + k // 2), slice(3 * k, 8 * k))
    px[sl][..., :3] += (255 - px[sl][..., :3]) * 85 / 255.0
    fill(4, 3, 6, 9, (255, 255, 255, 255))
    fill(4, 7, 9, 9, (255, 255, 255, 255))
    fill(8, 3, 10, 5, (255, 240, 122, 255))
    write_png(os.path.join(ROOT, "assets/lowspec/textures/font/logo.png"), px)


if __name__ == "__main__":
    gen_logo()
    gen_buttons()
    gen_panorama()
    print("assets generated")
