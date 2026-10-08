"""
Делает картинки для шагов инструкции из сырых скриншотов: обрезает лишнее, размывает личное
(аватарки, имена контактов), рисует стрелку и обводку "жми сюда", номер шага и подпись сверху.

    python tools/annotate.py <папка со скриншотами> src/main/resources/steps

Какие файлы и что с ними делать - в STEPS ниже. Координаты в пикселях исходного скриншота.
"""
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ACCENT = (255, 59, 48)  # красный iOS
FONT_BOLD = "C:/Windows/Fonts/segoeuib.ttf"

STEPS = {
    "ios-import-1.jpg": dict(
        source="20261008-174028-30.jpg",
        # середина экрана пустая: склеиваем верх (имя файла) и низ (панель с кнопками)
        pieces=[(75, 190), (1150, 1265)],
        target=(295, 1210), radius=34,
        caption="1. Откройте файл и нажмите «Поделиться»",
    ),
    "ios-import-2.jpg": dict(
        source="20261008-174028-31.jpg",
        pieces=[(665, 1265)],
        blur=[(14, 752, 576, 1168)],  # чаты и контакты
        target=(530, 714), radius=32,
        caption="2. Telegram предложит чат: жмите значок справа",
    ),
    "ios-import-3.jpg": dict(
        source="20261008-174028-32.jpg",
        pieces=[(515, 1245)],
        blur=[(20, 662, 580, 852)],  # контакты с фото и именами
        target=(490, 938), radius=56,
        caption="3. В ряду приложений нажмите «Ещё»",
    ),
    "ios-import-4.jpg": dict(
        source="20261008-174028-33.jpg",
        pieces=[(655, 985)],
        target=(150, 820), radius=0, box=(40, 788, 540, 852),
        caption="4. Выберите AmneziaWG (не AmneziaVPN!)",
    ),
}


def font(size):
    return ImageFont.truetype(FONT_BOLD, size)


def arrow(draw, start, end, width=12):
    """Толстая стрелка с белой окантовкой, чтобы читалась на любом фоне."""
    angle = math.atan2(end[1] - start[1], end[0] - start[0])
    head = width * 3.2
    base = (end[0] - head * math.cos(angle), end[1] - head * math.sin(angle))
    left = (end[0] - head * math.cos(angle - 0.5), end[1] - head * math.sin(angle - 0.5))
    right = (end[0] - head * math.cos(angle + 0.5), end[1] - head * math.sin(angle + 0.5))
    for color, extra in (((255, 255, 255), 8), (ACCENT, 0)):
        draw.line([start, base], fill=color, width=width + extra)
        if extra:
            draw.polygon([end, left, right], fill=color, outline=color, width=extra)
        else:
            draw.polygon([end, left, right], fill=color)


def build(spec, src_dir):
    shot = Image.open(src_dir / spec["source"]).convert("RGB")
    for box in spec.get("blur", []):
        region = shot.crop(box).filter(ImageFilter.GaussianBlur(18)).filter(ImageFilter.GaussianBlur(18))
        shot.paste(region, box[:2])

    # склеиваем куски по вертикали, запоминая, куда сдвинулась цель
    pieces = spec["pieces"]
    gap = 24
    height = sum(b - a for a, b in pieces) + gap * (len(pieces) - 1)
    body = Image.new("RGB", (shot.width, height), (255, 255, 255))
    y = 0
    offsets = []
    for a, b in pieces:
        body.paste(shot.crop((0, a, shot.width, b)), (0, y))
        offsets.append((a, b, y))
        y += b - a + gap

    def move(point):
        x, py = point
        for a, b, at in offsets:
            if a <= py <= b:
                return x, py - a + at
        raise ValueError(f"{point} is outside of kept pieces")

    draw = ImageDraw.Draw(body)
    tx, ty = move(spec["target"])
    if "box" in spec:
        x0, y0 = move(spec["box"][:2])
        x1, y1 = move(spec["box"][2:])
        for color, w in (((255, 255, 255), 10), (ACCENT, 6)):
            draw.rounded_rectangle((x0, y0, x1, y1), radius=14, outline=color, width=w)
        tip = (x1 - 6, (y0 + y1) / 2)
        arrow(draw, (min(tip[0] + 0, body.width - 20), tip[1] - 110), (tip[0] - 40, y0 - 4))
    else:
        r = spec["radius"]
        for color, w in (((255, 255, 255), 10), (ACCENT, 6)):
            draw.ellipse((tx - r, ty - r, tx + r, ty + r), outline=color, width=w)
        # стрелка подлетает сверху-слева или сверху-справа, смотря где место
        dx = -1 if tx > body.width / 2 else 1
        start = (tx + dx * (r + 120), ty - r - 120)
        if start[1] < 20:
            start = (tx + dx * (r + 150), ty + r + 110)
        k = (r + 10) / math.dist(start, (tx, ty))
        end = (tx + (start[0] - tx) * k, ty + (start[1] - ty) * k)
        arrow(draw, start, end)

    # шапка с подписью
    header_h = 92
    out = Image.new("RGB", (body.width, body.height + header_h), (255, 255, 255))
    out.paste(body, (0, header_h))
    d = ImageDraw.Draw(out)
    d.rectangle((0, 0, out.width, header_h), fill=(28, 28, 30))
    caption = spec["caption"]
    size = 30
    while d.textlength(caption, font=font(size)) > out.width - 40:
        size -= 1
    d.text((out.width / 2, header_h / 2), caption, font=font(size), fill=(255, 255, 255), anchor="mm")
    return out


def main():
    src_dir, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, spec in STEPS.items():
        build(spec, src_dir).save(out_dir / name, quality=88)
        print("wrote", out_dir / name)


if __name__ == "__main__":
    main()
