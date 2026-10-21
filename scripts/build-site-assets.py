#!/usr/bin/env python3
"""Regenerate the images the GitHub Pages site (docs/) uses. Needs Pillow (with WebP support).

    python3 scripts/build-site-assets.py            # everything
    python3 scripts/build-site-assets.py screens    # only the web-size screenshots (do this after replacing them)
    python3 scripts/build-site-assets.py brand      # only og-image.png and the favicons

The site shows 540 px wide WebP copies of the Play Store screenshots in
fastlane/metadata/android/en-US/images/phoneScreenshots/ (same file names, `.webp`), so the page stays light.
The README keeps linking the full-size originals. Output is deterministic for the same inputs.
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SHOTS = ROOT / "fastlane/metadata/android/en-US/images/phoneScreenshots"
LOGO = ROOT / "docs/images/logo.png"
OUT = ROOT / "docs/assets"

SCREEN_WIDTH = 540
FONT_DIRS = ["/usr/share/fonts/google-roboto", "/usr/share/fonts/dejavu-sans-fonts", "/usr/share/fonts/liberation-sans-fonts"]
FONT_FILES = {"bold": ["Roboto-Bold.ttf", "DejaVuSans-Bold.ttf", "LiberationSans-Bold.ttf"],
              "medium": ["Roboto-Medium.ttf", "DejaVuSans.ttf", "LiberationSans-Regular.ttf"]}

INDIGO_DEEP = (28, 23, 91)     # #1c175b, the feature graphic's dark corner
INDIGO_GLOW = (73, 56, 167)    # #4938a7, its lit corner
CREAM = (255, 246, 229)        # #fff6e5
LAVENDER = (199, 190, 244)
PEACH = (242, 185, 133)


def font(weight: str, size: int) -> ImageFont.FreeTypeFont:
    for d in FONT_DIRS:
        for name in FONT_FILES[weight]:
            p = Path(d) / name
            if p.exists():
                return ImageFont.truetype(str(p), size)
    raise SystemExit("No suitable TTF font found; install Roboto or DejaVu Sans.")


def screens() -> None:
    dest = OUT / "screens"
    dest.mkdir(parents=True, exist_ok=True)
    for src in sorted(SHOTS.glob("*.png")):
        im = Image.open(src).convert("RGB")
        h = round(im.height * SCREEN_WIDTH / im.width)
        im = im.resize((SCREEN_WIDTH, h), Image.LANCZOS)
        target = dest / (src.stem + ".webp")
        im.save(target, "WEBP", quality=78, method=6)
        print(f"{target.relative_to(ROOT)}  {im.size[0]}x{im.size[1]}  {target.stat().st_size // 1024} KB")


def rounded(im: Image.Image, radius: int) -> Image.Image:
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, im.width - 1, im.height - 1), radius, fill=255)
    out = im.convert("RGBA")
    out.putalpha(mask)
    return out


def phone(shot: Path, width: int) -> Image.Image:
    """A screenshot with rounded corners and a thin light edge, as an RGBA image."""
    im = Image.open(shot).convert("RGB")
    im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
    r = round(width * 0.11)
    framed = Image.new("RGBA", (im.width + 8, im.height + 8), (0, 0, 0, 0))
    ImageDraw.Draw(framed).rounded_rectangle((0, 0, framed.width - 1, framed.height - 1), r + 4, fill=(255, 246, 229, 70))
    framed.alpha_composite(rounded(im, r), (4, 4))
    return framed


def og_image() -> None:
    w, h = 1200, 630
    # Diagonal glow from the top-left, like the feature graphic.
    bg = Image.new("RGB", (w, h), INDIGO_DEEP)
    glow = Image.new("RGB", (w, h), INDIGO_GLOW)
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).ellipse((-380, -420, 780, 640), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(160))
    bg = Image.composite(glow, bg, mask)
    canvas = bg.convert("RGBA")

    # Two phones on the right, bleeding off the bottom edge.
    back = phone(SHOTS / "1_alarms.png", 250)
    front = phone(SHOTS / "3_missions.png", 270)
    for im, x, y in ((back, 770, 200), (front, 900, 72)):
        shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        ImageDraw.Draw(shadow).rounded_rectangle((x - 6, y + 14, x + im.width + 6, y + im.height + 14), 40, fill=(8, 6, 40, 150))
        canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(22)))
        canvas.alpha_composite(im, (x, y))

    d = ImageDraw.Draw(canvas)
    logo = Image.open(LOGO).convert("RGBA").resize((132, 132), Image.LANCZOS)
    canvas.alpha_composite(logo, (64, 70))
    d.text((216, 98), "WakeNudge", font=font("bold", 76), fill=CREAM)
    d.text((66, 252), "Free, open-source alarm clock", font=font("bold", 40), fill=CREAM)
    d.text((66, 304), "for Android with wake-up missions", font=font("bold", 40), fill=CREAM)
    d.text((66, 378), "Math, QR, NFC, steps and squats to get you up.", font=font("medium", 26), fill=LAVENDER)

    # Translucent pills must be composited, not drawn straight onto the RGBA canvas.
    pills = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    pd = ImageDraw.Draw(pills)
    x = 66
    f = font("medium", 26)
    for label in ("Offline", "No ads", "No tracking"):
        tw = pd.textlength(label, font=f)
        pd.rounded_rectangle((x, 466, x + tw + 44, 466 + 54), 27, fill=(255, 246, 229, 30), outline=(255, 246, 229, 120), width=2)
        pd.text((x + 22, 466 + 27), label, font=f, fill=CREAM, anchor="lm")
        x += int(tw + 44 + 14)
    canvas.alpha_composite(pills)
    d = ImageDraw.Draw(canvas)
    d.text((66, 568), "github.com/riyadmondol2006/WakeNudge", font=font("medium", 24), fill=PEACH)

    OUT.mkdir(parents=True, exist_ok=True)
    out = OUT / "og-image.png"
    final = canvas.convert("RGB")
    # libimagequant keeps the gradients smooth in 256 colours (about 70 KB); the plain quantisers band the logo.
    # Without it, fall back to a larger truecolour PNG rather than a banded one.
    from PIL import features
    if features.check("libimagequant"):
        final.quantize(colors=256, method=Image.Quantize.LIBIMAGEQUANT, dither=Image.Dither.FLOYDSTEINBERG).save(out, optimize=True)
    else:
        final.save(out, optimize=True)
    print(f"{out.relative_to(ROOT)}  1200x630  {out.stat().st_size // 1024} KB")


def favicons() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    logo = Image.open(LOGO).convert("RGBA")
    # Trim the transparent margin so the mark fills the small sizes.
    logo = logo.crop(logo.getbbox())
    side = max(logo.size)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.alpha_composite(logo, ((side - logo.width) // 2, (side - logo.height) // 2))

    def sized(n: int) -> Image.Image:
        return square.resize((n, n), Image.LANCZOS)

    for n, name in ((32, "favicon-32.png"), (192, "icon-192.png"), (512, "icon-512.png")):
        sized(n).save(OUT / name, optimize=True)
    # Apple touch icons must be opaque: put the mark on the brand indigo with a safe margin.
    touch = Image.new("RGBA", (180, 180), INDIGO_DEEP + (255,))
    mark = square.resize((132, 132), Image.LANCZOS)
    touch.alpha_composite(mark, (24, 24))
    touch.convert("RGB").save(OUT / "apple-touch-icon.png", optimize=True)
    sized(48).save(OUT / "favicon.ico", sizes=[(16, 16), (32, 32), (48, 48)])
    for name in ("favicon-32.png", "icon-192.png", "icon-512.png", "apple-touch-icon.png", "favicon.ico"):
        print(f"docs/assets/{name}  {(OUT / name).stat().st_size // 1024 or 1} KB")


if __name__ == "__main__":
    what = set(sys.argv[1:]) or {"screens", "brand"}
    unknown = what - {"screens", "brand"}
    if unknown:
        raise SystemExit(f"unknown target(s): {', '.join(sorted(unknown))}")
    if "screens" in what:
        screens()
    if "brand" in what:
        og_image()
        favicons()
