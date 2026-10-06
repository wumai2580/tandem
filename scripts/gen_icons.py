"""Generate PWA icons (rounded-square logo: two overlapping device shapes)."""

from pathlib import Path

from PIL import Image, ImageDraw

OUT = Path(__file__).parent.parent / "web" / "public" / "icons"


def draw(size: int, maskable: bool = False) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = size
    inset = int(s * 0.08) if not maskable else 0
    # background rounded square
    d.rounded_rectangle([inset, inset, s - inset, s - inset], radius=int(s * 0.22), fill=(79, 70, 229, 255))
    # two overlapping "devices": tall phone + wide screen
    c = s / 100.0
    d.rounded_rectangle([28 * c, 22 * c, 52 * c, 74 * c], radius=6 * c, fill=(255, 255, 255, 255))
    d.rounded_rectangle([44 * c, 34 * c, 76 * c, 62 * c], radius=6 * c, fill=(34, 211, 238, 255))
    # inner screens
    d.rounded_rectangle([32 * c, 27 * c, 48 * c, 68 * c], radius=3 * c, fill=(79, 70, 229, 255))
    d.rounded_rectangle([48 * c, 38 * c, 72 * c, 58 * c], radius=3 * c, fill=(17, 24, 39, 255))
    return img


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    draw(192).save(OUT / "icon-192.png")
    draw(512).save(OUT / "icon-512.png")
    draw(512, maskable=True).save(OUT / "icon-maskable-512.png")
    draw(256).save(OUT / "tandem.ico", sizes=[(16, 16), (32, 32), (48, 48), (256, 256)])
    print(f"icons written to {OUT}")


if __name__ == "__main__":
    main()
