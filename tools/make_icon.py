#!/usr/bin/env python3
"""make_icon.py — draw Little Journal's app icon for iOS and Android.

A ruled journal page with a voice waveform across it. Generic stationery
iconography: it has to read as "notebook" and "speech" at 40 points, which is
the only size that really matters.

    python3 tools/make_icon.py            # write both platforms
    python3 tools/make_icon.py --preview  # just preview.png, to look at it

The two platforms want opposite things, which is why this writes them
differently rather than resizing one master:

  iOS      full bleed, RGB, **no alpha** — App Store Connect rejects an icon
           with an alpha channel outright.
  Android  inset onto transparency — the system draws its own mask around the
           icon, and one that fills its square gets clipped or looks wrong.
           Android Lint flags this as IconLauncherShape.

Adapted from ~/Code/AppStoreListings/tools/make_icons.py, which does the same
job for the other apps. This copy lives here so the repo can rebuild its own
icon without reaching outside itself.
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

SS = 4                 # supersample, then Lanczos down: cheap antialiasing
MASTER = 1024
CANVAS = MASTER * SS

BG = (26, 24, 34)
PAGE = (243, 238, 226)
RULE = (203, 197, 186)
SPINE = (92, 70, 150)
WAVE = (108, 82, 178)

IOS_ICONSET = Path("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset")
ANDROID_RES = Path("app/src/androidMain/res")
ANDROID_DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ANDROID_INSET = 0.84   # leave a margin for the system's mask


def _rounded(draw, box, radius, fill):
    draw.rounded_rectangle([v * SS for v in box], radius=radius * SS, fill=fill)


def draw_master():
    img = Image.new("RGB", (CANVAS, CANVAS), BG)
    d = ImageDraw.Draw(img)

    # the page, then the bound edge over its left side
    _rounded(d, (232, 168, 792, 856), 56, PAGE)
    _rounded(d, (232, 168, 320, 856), 56, SPINE)
    _rounded(d, (292, 168, 340, 856), 0, PAGE)

    # ruled lines, heavy enough to survive being shrunk to 48px
    for y in (296, 380, 464):
        _rounded(d, (392, y, 716, y + 18), 9, RULE)

    # the waveform: what makes it a *talking* journal rather than a notebook
    x = 386
    for height in (86, 148, 226, 292, 226, 148, 86):
        _rounded(d, (x, 646 - height // 2, x + 34, 646 + height // 2), 17, WAVE)
        x += 52

    return img.resize((MASTER, MASTER), Image.LANCZOS)


def write_ios(master):
    IOS_ICONSET.mkdir(parents=True, exist_ok=True)
    out = IOS_ICONSET / "Icon-1024.png"
    master.convert("RGB").save(out, "PNG")     # convert() drops any alpha
    print(f"  {out}  1024x1024 RGB")


def write_android(master):
    rgba = master.convert("RGBA")
    for name, px in ANDROID_DENSITIES.items():
        inner = int(px * ANDROID_INSET)
        canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        art = rgba.resize((inner, inner), Image.LANCZOS)
        offset = (px - inner) // 2
        canvas.paste(art, (offset, offset), art)
        d = ANDROID_RES / f"mipmap-{name}"
        d.mkdir(parents=True, exist_ok=True)
        canvas.save(d / "ic_launcher.png", "PNG")
        print(f"  {d}/ic_launcher.png  {px}x{px} inset {inner}px")


def main():
    master = draw_master()
    if "--preview" in sys.argv:
        master.save("preview.png")
        print("  preview.png")
        return 0
    write_ios(master)
    write_android(master)
    return 0


if __name__ == "__main__":
    sys.exit(main())
