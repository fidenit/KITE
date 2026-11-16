# 영상에서 일정 간격으로 프레임을 뽑아 시각을 적은 한 장짜리 모음 그림을 만든다.
#   python sheet.py 영상.mp4 출력.png 시작초 끝초 간격초 [열 수] [칸 폭]
import subprocess, sys
from PIL import Image, ImageDraw, ImageFont

path, out = sys.argv[1], sys.argv[2]
t0, t1, step = float(sys.argv[3]), float(sys.argv[4]), float(sys.argv[5])
cols = int(sys.argv[6]) if len(sys.argv) > 6 else 4
tw = int(sys.argv[7]) if len(sys.argv) > 7 else 480
th = tw * 9 // 16
times = []
t = t0
while t <= t1 + 1e-6:
    times.append(t)
    t += step
rows = (len(times) + cols - 1) // cols
sheet = Image.new("RGB", (cols * tw, rows * th), (0, 0, 0))
f = ImageFont.truetype("C:/Windows/Fonts/malgunbd.ttf", 16)
for n, t in enumerate(times):
    b = subprocess.run(["ffmpeg", "-loglevel", "error", "-ss", f"{t:.3f}", "-i", path, "-frames:v", "1", "-vf", f"scale={tw}:{th}", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], capture_output=True).stdout
    if len(b) < tw * th * 3:
        continue
    img = Image.frombytes("RGB", (tw, th), b)
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 58, 22), fill=(200, 30, 30))
    d.text((4, 2), f"{t:.1f}", font=f, fill=(255, 255, 255))
    sheet.paste(img, ((n % cols) * tw, (n // cols) * th))
sheet.save(out)
print(out, len(times))
