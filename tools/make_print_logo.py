# Builds app/src/main/res/drawable-nodpi/evowria_logo_print.png, the 1-bit
# logo at the foot of the table card, from the website logo
# (evowria/public/images/evowria-logo.png):
#   python make_print_logo.py <evowria-logo.png> <out.png> 184
# The wordmark is solid black with a white halo so it stays readable over the
# leaves; the watercolour leaves are lightened and dithered. Needs Pillow.
import sys
from PIL import Image, ImageChops, ImageFilter
src, out, width = sys.argv[1], sys.argv[2], int(sys.argv[3])
im = Image.open(src).convert("RGB")
# Crop to the drawing (anything not near-white).
gray = im.convert("L")
bbox = gray.point(lambda v: 255 if v < 235 else 0).getbbox()
im = im.crop(bbox)
r, g, b = im.split()
# Wordmark = the brown ink: red clearly above green and blue.
rg = ImageChops.subtract(r, g)
word = rg.point(lambda v: 255 if v > 30 else 0)
h = round(im.height * width / im.width)
word = word.resize((width, h), Image.LANCZOS).point(lambda v: 255 if v > 110 else 0)
halo = word.filter(ImageFilter.MaxFilter(5))
leaves = im.convert("L").resize((width, h), Image.LANCZOS)
# Lighten the watercolour so it reads as a soft grey under the black letters.
leaves = leaves.point(lambda v: int(255 - (255 - v) * 0.55))
leaves = leaves.convert("1", dither=Image.FLOYDSTEINBERG).convert("L")
lw, hw, ww = leaves.load(), halo.load(), word.load()
outimg = Image.new("1", (width, h), 1)
o = outimg.load()
for y in range(h):
    for x in range(width):
        if ww[x, y]:
            o[x, y] = 0
        elif hw[x, y]:
            o[x, y] = 1
        else:
            o[x, y] = 0 if lw[x, y] < 128 else 1
outimg.save(out, optimize=True)
print(width, h)
