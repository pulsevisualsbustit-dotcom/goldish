#!/usr/bin/env python3
"""Собирает оформление лаунчера из исходного арта.

Источники:
  * C:\\Users\\User\\Desktop\\for loader\\start.png  — фон и сплэш
  * src/main/resources/assets/goldish/textures/gui/logo.png — логотип клиента

Запуск:  python tools/make_assets.py
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter

HERE = Path(__file__).resolve().parent
LOADER = HERE.parent
PROJECT = LOADER.parent
OUT = LOADER / "assets"
ART = Path(r"C:\Users\User\Desktop\for loader\start.png")
LOGO = PROJECT / "src" / "main" / "resources" / "assets" / "goldish" / "textures" / "gui" / "logo.png"

W, H = 1672, 941


def cover(img: Image.Image, size: tuple[int, int]) -> Image.Image:
    """Масштабирует с обрезкой по центру (object-fit: cover)."""
    tw, th = size
    scale = max(tw / img.width, th / img.height)
    nw, nh = round(img.width * scale), round(img.height * scale)
    resized = img.resize((nw, nh), Image.LANCZOS)
    left = (nw - tw) // 2
    top = (nh - th) // 2
    return resized.crop((left, top, left + tw, top + th))


def vignette(img: Image.Image, strength: float = 0.55) -> Image.Image:
    """Мягкое радиальное затемнение по краям (без ступенек)."""
    w, h = img.size
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    nx = (xx / w - 0.5) * 2.0
    ny = (yy / h - 0.5) * 2.0
    dist = np.sqrt(nx * nx + ny * ny) / 1.35
    mask = np.clip((dist - 0.42) / 0.58, 0.0, 1.0) ** 1.7 * strength
    mask = (mask * 255).astype(np.uint8)
    black = Image.new("RGB", (w, h), (3, 2, 1))
    return Image.composite(black, img, Image.fromarray(mask, "L"))


def erase_logo(img: Image.Image) -> Image.Image:
    """Аккуратно заклеивает центральный логотип куском арта справа.

    После размытия и затемнения шов не виден, зато в центре фона нет
    светящегося логотипа, который мешал бы карточкам интерфейса.
    """
    w, h = img.size
    patch = img.crop((int(w * 0.60), int(h * 0.08), int(w * 0.60) + int(w * 0.30), int(h * 0.08) + int(h * 0.66)))
    patch = patch.transpose(Image.FLIP_LEFT_RIGHT).resize((int(w * 0.30), int(h * 0.66)), Image.LANCZOS)
    feather = Image.new("L", patch.size, 0)
    draw = ImageDraw.Draw(feather)
    draw.rectangle([70, 60, patch.width - 70, patch.height - 60], fill=255)
    feather = feather.filter(ImageFilter.GaussianBlur(80))
    result = img.copy()
    result.paste(patch, (int(w * 0.345), int(h * 0.10)), feather)
    return result


def tint(img: Image.Image, hue_shift: float, sat: float = 1.0) -> Image.Image:
    """Сдвиг оттенка через HSV (hue_shift в градусах)."""
    hsv = img.convert("HSV")
    h, s, v = hsv.split()
    shift = int(hue_shift / 360.0 * 255)
    h = h.point(lambda x: (x + shift) % 256)
    if sat != 1.0:
        s = s.point(lambda x: max(0, min(255, int(x * sat))))
    return Image.merge("HSV", (h, s, v)).convert("RGB")


def main() -> int:
    if not ART.is_file():
        raise SystemExit(f"нет исходного арта: {ART}")
    OUT.mkdir(parents=True, exist_ok=True)
    art = Image.open(ART).convert("RGB")
    logo = Image.open(LOGO).convert("RGBA")

    # --- сплэш ------------------------------------------------------------
    art.save(OUT / "splash.png")
    logo.resize((320, 320), Image.LANCZOS).save(OUT / "logo_big.png")
    logo.save(OUT / "logo.png")

    # --- фон основного окна ----------------------------------------------
    # Берём правую часть арта (закат, водопад, вода) — там нет логотипа,
    # поэтому фон остаётся чистым даже под прозрачными карточками.
    ambient = cover(art.crop((960, 140, 1672, 900)), (1600, 1000))
    ambient = ambient.filter(ImageFilter.GaussianBlur(22))
    ambient = ImageEnhance.Brightness(ambient).enhance(0.66)
    ambient = ImageEnhance.Color(ambient).enhance(1.18)
    base = vignette(ambient, 0.55)
    base.save(OUT / "bg_gold.jpg", quality=88, optimize=True)

    variants = {
        "bg_azure.jpg": 172,
        "bg_violet.jpg": -118,
        "bg_emerald.jpg": 112,
    }
    for name, shift in variants.items():
        tinted = tint(base, shift, 1.0)
        tinted.save(OUT / name, quality=88, optimize=True)

    # --- герой-картинка и миниатюры --------------------------------------
    crops = {
        "hero.jpg": (1000, 40, 1672, 800),
        "news_1.jpg": (0, 110, 520, 610),
        "news_2.jpg": (1030, 90, 1672, 690),
        "news_3.jpg": (0, 480, 520, 941),
        "mode_skyblock.jpg": (1010, 60, 1470, 400),
        "mode_anarchy.jpg": (0, 0, 460, 330),
        "mode_technologic.jpg": (1150, 330, 1610, 670),
        "mode_survival.jpg": (0, 250, 460, 580),
    }
    for name, box in crops.items():
        crop = art.crop(box)
        if name.startswith("news") or name.startswith("mode"):
            crop = ImageEnhance.Brightness(crop).enhance(0.88)
        crop.save(OUT / name, quality=90, optimize=True)

    # --- превью тем для страницы «Внешний вид» ---------------------------
    preview = cover(art, (480, 280)).filter(ImageFilter.GaussianBlur(3))
    preview = ImageEnhance.Brightness(preview).enhance(0.72)
    for name, shift in {"theme_gold.jpg": 0, "theme_azure.jpg": 172,
                        "theme_violet.jpg": -118, "theme_emerald.jpg": 112}.items():
        (preview if shift == 0 else tint(preview, shift)).save(OUT / name, quality=88)

    # --- иконка окна ------------------------------------------------------
    icon = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    draw = ImageDraw.Draw(icon)
    draw.rounded_rectangle([8, 8, 248, 248], radius=56, fill=(20, 16, 10, 255),
                           outline=(246, 196, 83, 255), width=6)
    mark = logo.resize((200, 200), Image.LANCZOS)
    icon.alpha_composite(mark, (28, 28))
    icon.save(OUT / "icon.png")

    print("готово:", OUT)
    for path in sorted(OUT.iterdir()):
        print(f"  {path.name:24} {path.stat().st_size // 1024:>6} КБ")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
