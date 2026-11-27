"""Troca o texto do banner da Android TV (res/drawable-xhdpi/tv_banner.png) por 'Eli Player'."""
import sys
from PIL import Image, ImageDraw, ImageFont

path = sys.argv[1]
im = Image.open(path).convert("RGBA")
px = im.load()
d = ImageDraw.Draw(im)
# apaga "IPTV Player" repetindo a cor do fundo (degradê vertical) de cada linha
for y in range(118, 184):
    c = px[225, y]
    d.line([(228, y), (im.width - 1, y)], fill=c)
font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 49)
d.text((242, 165), "Eli Player", font=font, fill=(236, 238, 248, 255), anchor="ls")
im.save(path)
