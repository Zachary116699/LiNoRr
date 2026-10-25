"""Generate small launcher assets from the supplied artwork, never upscale it."""
from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parents[1]
source = Image.open(root / "design/icon.png").convert("RGB")
res = root / "app/src/main/res"
# Android density buckets: maximum launcher size is 192px at xxxhdpi.
for density, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    target = res / ("mipmap-" + density)
    target.mkdir(exist_ok=True)
    source.resize((size, size), Image.Resampling.LANCZOS).save(target / "ic_launcher.webp", quality=92, method=6)
# Full-bleed 108dp canvas. The launcher alone applies its circle/squircle mask.
target = res / "drawable-nodpi"
target.mkdir(exist_ok=True)
background = Image.open(root / "design/background.png").convert("RGB")
background.resize((432, 432), Image.Resampling.LANCZOS).save(target / "launcher_back.webp", quality=90, method=6)
foreground = Image.open(root / "design/monochrome.png").convert("RGBA")
foreground.alpha_composite(Image.open(root / "design/figure.png").convert("RGBA"))
canvas = Image.new("RGBA", (432, 432))
canvas.alpha_composite(foreground.resize((288, 288), Image.Resampling.LANCZOS), (72, 72))
canvas.save(target / "launcher_art.webp", quality=92, method=6)
mono = Image.open(root / "design/monochrome.png").convert("RGBA")
mono = mono.crop(mono.getchannel("A").getbbox())
mono.thumbnail((230, 230), Image.Resampling.LANCZOS)
canvas = Image.new("RGBA", (432, 432))
canvas.alpha_composite(mono, ((432 - mono.width) // 2, (432 - mono.height) // 2))
canvas.save(target / "launcher_monochrome.webp", lossless=True, method=6)
