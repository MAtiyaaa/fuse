"""Draws one HTML page at an exact size with headless Chromium (its window is made taller, then cropped)."""
import sys, subprocess, os
from PIL import Image
CH='/opt/pw-browsers/chromium-1194/chrome-linux/chrome'
def render(html, w, h, out, transparent=False):
    tmp=out+'.full.png'
    args=[CH,'--headless=new','--no-sandbox','--disable-gpu','--hide-scrollbars',f'--window-size={w},{h+300}',f'--screenshot={tmp}']
    if transparent: args.append('--default-background-color=00000000')
    subprocess.run(args+['file://'+html],check=True,capture_output=True)
    Image.open(tmp).crop((0,0,w,h)).save(out)
    os.remove(tmp)
if __name__=='__main__':
    render(sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4], len(sys.argv)>5)
