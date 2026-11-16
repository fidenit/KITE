# 영상에서 앞 프레임과 똑같은 프레임(=찍지 못해 되풀이한 프레임)이 움직이는 구간에 얼마나 끼었는지 센다.
import subprocess, sys
import numpy as np

path = sys.argv[1]
w, h = 480, 270
p = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-i", path, "-vf", f"scale={w}:{h}", "-f", "rawvideo", "-pix_fmt", "gray", "-"], stdout=subprocess.PIPE)
prev = None
diffs = []
while True:
    buf = p.stdout.read(w * h)
    if len(buf) < w * h:
        break
    f = np.frombuffer(buf, np.uint8).astype(np.int16)
    if prev is not None:
        diffs.append(float(np.abs(f - prev).mean()))
    prev = f
d = np.array(diffs)
moving = d > 0.05
# 움직이는 프레임 사이에 낀 0 (앞뒤로 움직임이 있는데 이 프레임만 같은 것)
stall = [i for i in range(1, len(d) - 1) if d[i] <= 0.0005 and moving[i - 1] and moving[i + 1]]
print(f"frames {len(d) + 1}, moving {int(moving.sum())}, stalls inside motion {len(stall)}")
if stall:
    print("stall frames (s):", [round(i / 60, 2) for i in stall[:30]])
