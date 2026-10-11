"""
Картинки «как добавить конфиг на Android» из скриншотов друга: стирает нарисованные от руки
красные круги, убирает лишнее (старый туннель, чужие файлы), рисует плавные стрелки и подпись.

    python tools/annotate_android.py <папка со скриншотами> src/main/resources/steps

Координаты - в пикселях исходного скриншота (590x1280).
"""
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ACCENT = (255, 69, 58)
FONT_BOLD = "C:/Windows/Fonts/segoeuib.ttf"
FONT = "C:/Windows/Fonts/segoeui.ttf"
FONT_SEMI = "C:/Windows/Fonts/seguisb.ttf"
SCALE = 4  # стрелки рисуем крупнее и уменьшаем - так края гладкие
WHITE = (255, 255, 255)


def plus_button(draw):
    """Кнопка «+» AmneziaWG: после стирания круга вокруг неё рисуем её заново."""
    cx, cy, r = 508, 1110, 52
    draw.rounded_rectangle((cx - r, cy - r, cx + r, cy + r), radius=26, fill=(217, 226, 255))
    draw.line((cx - 13, cy, cx + 13, cy), fill=(40, 57, 101), width=3)
    draw.line((cx, cy - 13, cx, cy + 13), fill=(40, 57, 101), width=3)


def file_row(draw):
    """Строка с файлом: новое имя и подпись (маркер друга задевал её)."""
    draw.rectangle((40, 440, 590, 568), fill=WHITE)
    draw.rectangle((52, 484, 80, 520), fill=(115, 115, 115))  # значок файла
    draw.polygon([(72, 484), (80, 492), (72, 492)], fill=WHITE)
    draw.text((133, 487), "friends.conf", font=ImageFont.truetype(FONT, 31), fill=(20, 20, 22), anchor="lm")
    draw.text((133, 533), "9 окт., 18,90 кБ, Файл BIN", font=ImageFont.truetype(FONT, 23), fill=(110, 110, 112), anchor="lm")
    draw.rectangle((0, 571, 590, 720), fill=WHITE)  # чужое видео ниже - убираем
    draw.rectangle((40, 345, 470, 405), fill=WHITE)
    draw.text((45, 375), "Файлы в папке «Скачанные»", font=ImageFont.truetype(FONT_SEMI, 22), fill=(10, 10, 10), anchor="lm")


def import_sheet(draw):
    """Шторка «Импорт»: чистим стык затемнения и шторки, возвращаем значок файла."""
    draw.rectangle((0, 600, 590, 636), fill=(101, 101, 101))
    draw.rectangle((0, 637, 590, 655), fill=(252, 252, 254))
    draw.rectangle((40, 684, 84, 728), fill=(252, 252, 254))
    draw.polygon([(48, 690), (66, 690), (75, 699), (75, 722), (48, 722)], fill=(87, 94, 112))


STEPS = {
    "android-import-1.jpg": dict(
        source="20261011-184749-307.jpg",
        background=[((0, 0, 590, 1280), (252, 252, 254))],
        redraw=plus_button,
        # строка со старым туннелем не нужна: у новичка список пустой
        pieces=[(55, 175), (860, 1190)],
        arrow=[(215, 930), (430, 905), (466, 1052)],
        caption="1. Откройте AmneziaWG и нажмите «+»",
    ),
    "android-import-2.jpg": dict(
        source="20261011-184758-308.jpg",
        background=[((0, 0, 590, 637), (101, 101, 101)), ((0, 637, 590, 1280), (252, 252, 254))],
        redraw=import_sheet,
        pieces=[(560, 960)],
        arrow=[(565, 905), (590, 770), (528, 726)],
        caption="2. Выберите «Импорт из файла или архива»",
    ),
    "android-import-3.jpg": dict(
        source="20261011-184803-309.jpg",
        background=[((0, 0, 590, 1280), WHITE)],
        protect=[(40, 240, 590, 310)],  # красные значки «Изображения», «Аудио» - настоящие
        redraw=file_row,
        pieces=[(150, 700)],
        arrow=[(470, 680), (470, 560), (350, 498)],
        caption="3. Нажмите на файл friends.conf",
    ),
}


def inside(box, x, y):
    return box[0] <= x < box[2] and box[1] <= y < box[3]


def erase_red(img, background, protect):
    """Красный маркер (с запасом на ореол от JPEG) заливаем цветом фона своей области."""
    w, h = img.size
    px = img.load()
    hits = set()
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            if r > 120 and r - g > 55 and r - b > 45 and not any(inside(p, x, y) for p in protect):
                hits.add((x, y))
    pad = 6
    for x0, y0 in hits:
        for y in range(max(0, y0 - pad), min(h, y0 + pad + 1)):
            for x in range(max(0, x0 - pad), min(w, x0 + pad + 1)):
                if any(inside(p, x, y) for p in protect):
                    continue
                for box, color in background:
                    if inside(box, x, y):
                        px[x, y] = color
                        break


def bezier(p0, p1, p2, steps=80):
    return [
        ((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t ** 2 * p2[0],
         (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t ** 2 * p2[1])
        for t in (i / steps for i in range(steps + 1))
    ]


def pretty_arrow(img, start, control, end, width=11):
    """Плавная изогнутая стрелка: мягкая тень, белая окантовка, яркое тело, острый наконечник."""
    w, h = img.size
    s = SCALE
    p0, p1, p2 = [(x * s, y * s) for x, y in (start, control, end)]
    angle = math.atan2(p2[1] - p1[1], p2[0] - p1[0])
    head = width * s * 3.0
    base = (p2[0] - head * 0.8 * math.cos(angle), p2[1] - head * 0.8 * math.sin(angle))
    line = bezier(p0, p1, base)
    wing = 0.42
    tip = [
        p2,
        (p2[0] - head * math.cos(angle - wing), p2[1] - head * math.sin(angle - wing)),
        (p2[0] - head * 0.72 * math.cos(angle), p2[1] - head * 0.72 * math.sin(angle)),
        (p2[0] - head * math.cos(angle + wing), p2[1] - head * math.sin(angle + wing)),
    ]

    def shape(color, extra):
        layer = Image.new("RGBA", (w * s, h * s), (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        lw = (width + extra) * s
        d.line(line, fill=color, width=lw, joint="curve")
        r = lw / 2
        d.ellipse((p0[0] - r, p0[1] - r, p0[0] + r, p0[1] + r), fill=color)  # круглый хвост
        d.polygon(tip, fill=color, outline=color, width=extra * s if extra else 0)
        return layer.resize((w, h), Image.LANCZOS)

    result = img.convert("RGBA")
    result.alpha_composite(shape((0, 0, 0, 90), 6).filter(ImageFilter.GaussianBlur(5)), (3, 4))
    result.alpha_composite(shape(WHITE + (255,), 7))
    result.alpha_composite(shape(ACCENT + (255,), 0))
    return result.convert("RGB")


def build(spec, src_dir):
    shot = Image.open(src_dir / spec["source"]).convert("RGB")
    erase_red(shot, spec["background"], spec.get("protect", []))
    if "redraw" in spec:
        spec["redraw"](ImageDraw.Draw(shot))
    shot = pretty_arrow(shot, *spec["arrow"])

    pieces = spec["pieces"]
    gap = 24
    body = Image.new("RGB", (shot.width, sum(b - a for a, b in pieces) + gap * (len(pieces) - 1)), (236, 236, 240))
    y = 0
    for a, b in pieces:
        body.paste(shot.crop((0, a, shot.width, b)), (0, y))
        y += b - a + gap

    header_h = 92
    out = Image.new("RGB", (body.width, body.height + header_h), WHITE)
    out.paste(body, (0, header_h))
    d = ImageDraw.Draw(out)
    d.rectangle((0, 0, out.width, header_h), fill=(28, 28, 30))
    caption = spec["caption"]
    size = 30
    while d.textlength(caption, font=ImageFont.truetype(FONT_BOLD, size)) > out.width - 40:
        size -= 1
    d.text((out.width / 2, header_h / 2), caption, font=ImageFont.truetype(FONT_BOLD, size), fill=WHITE, anchor="mm")
    return out


def main():
    src_dir, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, spec in STEPS.items():
        build(spec, src_dir).save(out_dir / name, quality=88)
        print("wrote", out_dir / name)


if __name__ == "__main__":
    main()
