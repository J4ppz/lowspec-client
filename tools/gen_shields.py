"""
Builds the optional "Full shield banners" resource pack at build time.

Vanilla shields show a banner squeezed 2x down into a 10x20 area. This takes the
real 20x40 banner front from the game's own textures and places it, pixel for pixel,
into a 2x resolution shield texture, so nothing is squeezed.
Needs: Pillow. Never fails the build: if the game files can't be fetched it just skips.
"""
import io
import json
import os
import re
import sys
import urllib.request
import zipfile

from PIL import Image

RES = os.environ.get("LOWSPEC_RES", "src/main/resources")
OUT = os.path.join(RES, "resourcepacks/full_shield_banners/assets/minecraft/textures/entity/shield")
VERSION = os.environ.get("MC_VERSION", "26.2")
TOKEN = os.environ.get("GITHUB_TOKEN", "")


def fetch(url, timeout=60, auth=False):
    headers = {"User-Agent": "lowspec-build"}
    if auth and TOKEN:
        headers["Authorization"] = "Bearer " + TOKEN
    return urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=timeout).read()


def from_mojang():
    manifest = json.loads(fetch("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
    entry = next(v for v in manifest["versions"] if v["id"] == VERSION)
    meta = json.loads(fetch(entry["url"]))
    jar = fetch(meta["downloads"]["client"]["url"], timeout=300)
    z = zipfile.ZipFile(io.BytesIO(jar))
    found = {}
    for n in z.namelist():
        m = re.fullmatch(r"assets/minecraft/textures/entity/banner/([a-z0-9_]+)\.png", n)
        if m:
            found[m.group(1)] = z.read(n)
    return found


def from_github():
    base = "https://api.github.com/repos/InventivetalentDev/minecraft-assets/contents/assets/minecraft/textures/entity/banner"
    for ref in (VERSION, "26.1", "1.21.11", "1.21.4"):
        try:
            listing = json.loads(fetch(base + "?ref=" + ref, auth=True))
            found = {}
            for it in listing:
                if it["name"].endswith(".png"):
                    found[it["name"][:-4]] = fetch(it["download_url"])
            if found:
                print("using banner textures from github assets", ref)
                return found
        except Exception as e:  # noqa: BLE001
            print("github", ref, "failed:", e)
    return {}


def convert(png_bytes):
    im = Image.open(io.BytesIO(png_bytes)).convert("RGBA")
    if im.size != (64, 64):
        return None
    front = im.crop((1, 1, 21, 41))                 # the full 20x40 banner front
    out = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
    out.paste(front, (4, 4))                        # vanilla 10x20 shield area, at 2x resolution
    return out


def main():
    found = {}
    for source in (from_mojang, from_github):
        try:
            found = source()
        except Exception as e:  # noqa: BLE001
            print(source.__name__, "failed:", e)
            found = {}
        if found:
            break
    if not found:
        print("WARNING: could not get banner textures; skipping the shield pack")
        return 0
    os.makedirs(OUT, exist_ok=True)
    n = 0
    for name, data in found.items():
        img = convert(data)
        if img is None:
            continue
        img.save(os.path.join(OUT, name + ".png"))
        n += 1
    print("shield textures written:", n)
    return 0


if __name__ == "__main__":
    sys.exit(main())
