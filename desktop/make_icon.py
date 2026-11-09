from PIL import Image, ImageDraw
from pathlib import Path
im=Image.new('RGBA',(256,256),'#090d13');d=ImageDraw.Draw(im)
d.polygon([(26,40),(128,15),(230,40),(213,197),(128,240),(43,197)],fill='#121a23',outline='#23e6d9',width=4)
for x in (62,154):
    d.line((x,78,x+38,118),fill='#c8ff31',width=12)
    d.line((x+38,78,x,118),fill='#c8ff31',width=12)
d.line([(65,161),(81,180),(97,161),(113,180),(129,161),(145,180),(161,161),(177,180),(193,161)],fill='#23e6d9',width=7)
dest=Path(__file__).resolve().parents[1]/'build'/'ghostlink.ico';dest.parent.mkdir(exist_ok=True)
im.save(dest,sizes=[(16,16),(32,32),(48,48),(64,64),(128,128),(256,256)])
