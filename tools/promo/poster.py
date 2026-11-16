# README 에 거는 영상 그림: 완성본에서 한 프레임을 뽑아 재생 단추와 "Watch the 2-minute video" 띠를 얹는다.
#   python poster.py KITE-promo.mp4 시각초 ..\..\docs\images\promo.jpg [폭]
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

src, at, out = sys.argv[1], float(sys.argv[2]), sys.argv[3]
W = int(sys.argv[4]) if len(sys.argv) > 4 else 1000
H = W * 9 // 16
raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-ss", f"{at:.3f}", "-i", src, "-frames:v", "1",
                      "-vf", f"scale={W}:{H}:flags=lanczos", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                     capture_output=True, check=True).stdout
im = Image.frombytes("RGB", (W, H), raw).convert("RGBA")
# 재생 단추: 가운데 반투명 원 + 흰 삼각형 (4배로 그려 줄여서 매끈하게)
k = 4
lay = Image.new("RGBA", (W * k, H * k), (0, 0, 0, 0))
d = ImageDraw.Draw(lay)
cx, cy, r = W * k // 2, int(H * 0.43) * k, 46 * k
d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(12, 12, 14, 200), outline=(255, 255, 255, 230), width=3 * k)
t = 20 * k
d.polygon([(cx - t * 0.7, cy - t), (cx - t * 0.7, cy + t), (cx + t * 1.05, cy)], fill=(255, 255, 255, 245))
# 오른쪽 위: 길이 표시
f = ImageFont.truetype("C:/Windows/Fonts/malgunbd.ttf", 15 * k)
label = "▶  Watch the 2-minute video"
tw = d.textlength(label, font=f)
x1, y1 = W * k - 16 * k, 14 * k
d.rounded_rectangle((x1 - tw - 28 * k, y1, x1, y1 + 34 * k), radius=17 * k, fill=(240, 146, 45, 235))
d.text((x1 - 14 * k, y1 + 17 * k), label, font=f, fill=(20, 20, 22, 255), anchor="rm")
lay = lay.resize((W, H), Image.LANCZOS)
im.alpha_composite(lay)
im.convert("RGB").save(out, quality=90)
print(out, im.size)
