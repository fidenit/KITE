# KITE 소개 영상 편집: 녹화한 토막(a, b, e, d)을 이어 붙이고, 카메라 확대·커서·자막·챕터 제목을 입혀 1080p MP4 로 만든다.
# 녹화 때 남긴 .jsonl(프레임마다 카드 자리, 마우스 자리, 지금 일)을 읽어 어디를 확대할지와 자막 시각을 정한다.
#   python edit.py 출력.mp4 [--preview]
import bisect
import json
import math
import os
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

PR = os.path.dirname(os.path.abspath(__file__))
FPS = 60
SW, SH = 2160, 1215          # 녹화 화소
LW, LH = 1600.0, 900.0       # 앱의 논리 좌표
K = SW / LW
OW, OH = 1920, 1080          # 결과 화소
PREVIEW = "--preview" in sys.argv
if PREVIEW:
    OW, OH = 960, 540
S = OW / 1920.0              # 결과 크기 배율 (미리보기면 절반)
FB = "C:/Windows/Fonts/malgunbd.ttf"
FR = "C:/Windows/Fonts/malgun.ttf"
ACCENT = (240, 146, 45)
BG = (17, 18, 20)


def font(path, size):
    return ImageFont.truetype(path, max(8, int(round(size * S))))


def ease(u):
    u = max(0.0, min(1.0, u))
    return u * u * (3 - 2 * u)


# ---------------------------------------------------------------- 녹화 토막
class Clip:
    def __init__(self, name):
        self.name = name
        self.path = os.path.join(PR, name + ".mp4")
        self.side, self.marks = [], {}
        with open(self.path + ".jsonl", encoding="utf-8") as f:
            for line in f:
                j = json.loads(line)
                if "mark" in j:
                    self.marks[j["mark"]] = j["t"]
                elif j.get("screen", [0])[0] < 3000:
                    self.side.append(j)
        self.ts = [f["t"] for f in self.side]
        self.nframes = int(open(self.path + ".done").read().strip())
        self.end = self.nframes / FPS
        # 프레임마다 "방금 바뀐 카드", 카드가 처음 나타난 시각과 종류
        last_rev, last_change = {}, {}
        self.first_seen, self.kind = {}, {}
        self.active = []
        for f in self.side:
            for c in f["cards"]:
                cid, kind, rev = c[0], c[1], c[2]
                self.kind[cid] = kind
                if cid not in self.first_seen:
                    self.first_seen[cid] = f["t"]
                if last_rev.get(cid) != rev:
                    last_rev[cid] = rev
                    last_change[cid] = f["t"]
            self.active.append(max(last_change.items(), key=lambda kv: kv[1])[0] if last_change else None)
        self.proc, self.idx, self.buf = None, -1, None

    def i(self, t):
        return max(0, bisect.bisect_right(self.ts, t) - 1)

    def at(self, t):
        return self.side[self.i(t)]

    def frame(self, t):
        want = min(int(round(t * FPS)), self.nframes - 1)
        if self.proc is None:
            self.proc = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-i", self.path, "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                                         stdout=subprocess.PIPE, bufsize=SW * SH * 3 * 2)
        n = SW * SH * 3
        while self.idx < want:
            b = self.proc.stdout.read(n)
            if len(b) < n:
                break
            self.buf, self.idx = b, self.idx + 1
        return Image.frombuffer("RGB", (SW, SH), self.buf, "raw", "RGB", 0, 1)

    def close(self):
        if self.proc:
            self.proc.kill()
            self.proc = None

    # 사건 찾기
    def first(self, pred, after=0.0):
        for f in self.side[self.i(after):]:
            if f["t"] >= after and pred(f):
                return f["t"]
        return None

    def rect(self, t, cid):
        f = self.at(t)
        for c in f["cards"]:
            if c[0] == cid:
                return (c[4], c[5], c[6], c[7])
        if f.get("task") == cid and f.get("slot"):
            return tuple(f["slot"])
        return None


# ---------------------------------------------------------------- 카메라
class Spring:
    def __init__(self, x, w):
        self.x, self.v, self.w = x, 0.0, w

    def step(self, target, dt):
        a = self.w * self.w * (target - self.x) - 2 * self.w * self.v
        self.v += a * dt
        self.x += self.v * dt


def to_cam(rect, zmax):
    """보여 줄 곳(논리 좌표 사각형) → (가운데 x, 가운데 y, ln 확대). None 이면 전체."""
    if rect is None:
        return (LW / 2, LH / 2, 0.0)
    x, y, w, h = rect
    z = max(1.0, min(zmax, LW / max(w, 1), LH / max(h, 1)))
    return (x + w / 2, y + h / 2, math.log(z))


def crop_of(cx, cy, lz):
    z = math.exp(lz)
    cw, ch = LW / z, LH / z
    x0 = min(max(cx - cw / 2, 0), LW - cw)
    y0 = min(max(cy - ch / 2, 0), LH - ch)
    return x0, y0, cw, ch


def pad(r, p, extra_h=0.0):
    if r is None:
        return None
    x, y, w, h = r
    return (x - p, y - p, w + 2 * p, h + 2 * p + extra_h)


# ---------------------------------------------------------------- 커서
ARROW = [(0, 0), (0, 17.5), (4.3, 13.4), (7.1, 19.8), (9.8, 18.6), (7.0, 12.4), (12.6, 12.4)]
_sprites = {}


def cursor_sprite(scale):
    key = round(scale * 4) / 4
    if key in _sprites:
        return _sprites[key]
    big = 8
    m = 6  # 그림자 여백
    W, H = int((14 + 2 * m) * big), int((22 + 2 * m) * big)
    pts = [((x + m) * big, (y + m) * big) for x, y in ARROW]
    shadow = Image.new("L", (W, H), 0)
    ImageDraw.Draw(shadow).polygon([(x + 1.2 * big, y + 2.2 * big) for x, y in pts], fill=110)
    shadow = shadow.filter(ImageFilter.GaussianBlur(2.2 * big))
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    img.paste((0, 0, 0, 255), (0, 0), shadow)
    d = ImageDraw.Draw(img)
    d.polygon(pts, fill=(255, 255, 255, 255), outline=(20, 20, 20, 255), width=int(1.3 * big))
    w, h = max(1, int(W / big * key)), max(1, int(H / big * key))
    small = img.resize((w, h), Image.LANCZOS)
    _sprites[key] = (small, m * key)
    return _sprites[key]


def ring(radius, width, color, alpha):
    r = int(radius + width + 2)
    img = Image.new("RGBA", (2 * r * 4, 2 * r * 4), (0, 0, 0, 0))
    ImageDraw.Draw(img).ellipse((4 * (r - radius), 4 * (r - radius), 4 * (r + radius), 4 * (r + radius)),
                                outline=color + (int(255 * alpha),), width=int(width * 4))
    return img.resize((2 * r, 2 * r), Image.LANCZOS), r


# ---------------------------------------------------------------- 자막
_caps = {}


def caption_img(title, sub):
    key = (title, sub)
    if key in _caps:
        return _caps[key]
    ft, fs = font(FB, 40), font(FR, 25)
    H = int(130 * S)
    txt = Image.new("RGBA", (OW, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(txt)
    d.text((OW / 2, 52 * S), title, font=ft, fill=(246, 246, 246, 255), anchor="ms")
    if sub:
        d.text((OW / 2, 96 * S), sub, font=fs, fill=(176, 178, 184, 255), anchor="ms")
    sh = Image.new("RGBA", (OW, H), (0, 0, 0, 0))
    sh.paste((0, 0, 0, 255), (0, 0), txt.getchannel("A").filter(ImageFilter.GaussianBlur(7 * S)).point(lambda v: int(v * 0.85)))
    img = Image.alpha_composite(sh, txt)
    _caps[key] = img
    return img


def scrim():
    h = int(380 * S)
    g = Image.new("L", (1, h))
    for y in range(h):
        g.putpixel((0, y), int(255 * 0.94 * min(1.0, (y / (h - 1)) / 0.7) ** 1.3))
    g = g.resize((OW, h))
    img = Image.new("RGBA", (OW, h), BG + (0,))
    img.putalpha(g)
    return img


SCRIM = None


def with_alpha(img, a):
    if a >= 0.999:
        return img
    out = img.copy()
    out.putalpha(img.getchannel("A").point(lambda v: int(v * a)))
    return out


def title_card(num, title, sub):
    img = Image.new("RGB", (OW, OH), BG)
    d = ImageDraw.Draw(img)
    cx, cy = OW / 2, OH / 2
    d.text((cx, cy - 78 * S), num, font=font(FB, 30), fill=ACCENT, anchor="ms")
    d.text((cx, cy + 10 * S), title, font=font(FB, 66), fill=(246, 246, 246), anchor="ms")
    d.text((cx, cy + 66 * S), sub, font=font(FR, 30), fill=(170, 172, 178), anchor="ms")
    return img


# ---------------------------------------------------------------- 토막 하나 그리기
class Seg:
    def __init__(self, clip, start, end, camera, captions, speed=None, zmax=1.7, w=4.6, cursor_until=None):
        self.clip, self.start, self.end = clip, start, end
        self.camera, self.captions = camera, captions
        self.speed = speed or []
        self.zmax, self.w = zmax, w
        self.cursor_until = cursor_until

    def speed_at(self, t):
        for a, b, s in self.speed:
            if a <= t < b:
                return s
        return 1.0

    def frames(self):
        c = self.clip
        cam0 = to_cam(self.camera(c, self.start), self.zmax)
        springs = [Spring(v, self.w) for v in cam0]
        t = self.start
        cur_seen = None
        clicks = []
        was_down = False
        while t < self.end:
            target = to_cam(self.camera(c, t), self.zmax)
            for s, v in zip(springs, target):
                s.step(v, 1.0 / FPS)
            x0, y0, cw, ch = crop_of(*(s.x for s in springs))
            src = c.frame(t)
            box = (x0 * K, y0 * K, (x0 + cw) * K, (y0 + ch) * K)
            img = src.resize((OW, OH), Image.LANCZOS, box=box).convert("RGBA")
            scale = OW / cw  # 논리 1 → 결과 화소
            st = c.at(t)
            # 커서와 클릭 물결
            if st.get("cur") and (self.cursor_until is None or t < self.cursor_until + 0.4):
                if cur_seen is None:
                    cur_seen = t
                a = min(1.0, (t - cur_seen) / 0.25)
                if self.cursor_until is not None and t > self.cursor_until:
                    a *= max(0.0, 1 - (t - self.cursor_until) / 0.4)
                px = (st["cur"][0] - x0) * scale
                py = (st["cur"][1] - y0) * scale
                down = st.get("down", False)
                if down and not was_down:
                    clicks.append((t, px, py))
                was_down = down
                for ct, cx, cy in clicks:
                    u = (t - ct) / 0.5
                    if 0 <= u <= 1:
                        r_img, r = ring((10 + 30 * ease(u)) * S, 3.2 * S, ACCENT, 0.75 * (1 - u) * a)
                        img.alpha_composite(r_img, (int(px - r), int(py - r)))
                # 커서는 화면과 함께 커지되, 많이 확대해도 너무 커지지 않게 한다 (전체 보기에서 화살표 높이 약 27px).
                sprite, off = cursor_sprite(min(scale, 1.2 * S * 1.3) * 1.15 * (0.88 if down else 1.0))
                img.alpha_composite(with_alpha(sprite, a), (int(px - off), int(py - off)))
            # 자막
            for (a0, a1, title, sub) in self.captions:
                if a0 is None or a1 is None or not (a0 - 0.01 <= t <= a1 + 0.35):
                    continue
                k = ease((t - a0) / 0.35) * (1 - ease((t - a1) / 0.35))
                if k <= 0.01:
                    continue
                img.alpha_composite(with_alpha(SCRIM, k), (0, OH - SCRIM.height))
                cap = caption_img(title, sub)
                img.alpha_composite(with_alpha(cap, k), (0, int(OH - 150 * S + 14 * S * (1 - k))))
            yield img.convert("RGB")
            t += self.speed_at(t) / FPS
        c.close()


class Title:
    def __init__(self, num, title, sub, secs=1.8):
        self.img = title_card(num, title, sub)
        self.n = int(secs * FPS)

    def frames(self):
        for _ in range(self.n):
            yield self.img


# ---------------------------------------------------------------- 대본 (자막과 카메라)
def build():
    A, B, E, D = Clip("a"), Clip("b"), Clip("e"), Clip("d")
    full = lambda c, t: None

    # A: 흐름 한 번 (여는 장면 → 말하기 → 판이 만들어짐 → 결정이 왼쪽으로 → 한 번 눌러 답함)
    a_ask = A.marks["ask"]
    a_busy = A.first(lambda f: f["busy"], a_ask)
    a_task = A.first(lambda f: f.get("task") == "d1", a_busy)
    a_answer = A.marks["answer"]
    a_after = A.first(lambda f: f["busy"], a_answer) or a_answer + 0.5
    a_end = A.marks["done"] + 2.4

    def cam_a(c, t):
        # 결정이 왼쪽에 와 있는 동안은 왼쪽을 조금 당겨 본다
        if a_task and a_task + 0.4 <= t < a_after + 1.0:
            return (0, 0, 1240, 698)
        return None

    seg_a = Seg(A, 0.0, a_end, cam_a, [
        (a_ask + 0.2, a_busy - 0.1, "Just say what you need.", "Type it, or say it out loud."),
        (a_busy + 0.2, a_task - 0.2, "KITE lays it out on the screen.", "No wall of text. A board you can act on."),
        (a_task + 0.1, a_answer + 0.2, "What needs you comes to your side.", None),
        (a_after + 0.1, a_end - 0.4, "One tap, and the board catches up.", None),
    ], speed=[(a_ask + 0.3, a_busy - 0.2, 1.4)], w=3.6)

    # B: 내용에 맞는 카드 — 카드가 써지는 곳을 따라가며 확대
    b_ask = B.marks["ask"]
    b_busy = B.first(lambda f: f["busy"], b_ask)
    b_done = B.first(lambda f: not f["busy"], b_busy + 0.5)
    b_focus = B.first(lambda f: f.get("task") == "f1", b_busy)
    b_end = B.end - 0.6

    def cam_b(c, t):
        if t < b_busy + 0.6:
            return (0, 420, 820, 480)  # 입력창 쪽
        if b_focus and t >= b_focus + 0.2:
            return None if t > b_focus + 2.2 else (0, 0, 1180, 664)
        i = c.i(t)
        act = c.active[i]
        r = c.rect(t, act) if act else None
        return pad(r, 34)

    order = sorted((t0, cid) for cid, t0 in B.first_seen.items())
    label = {"Metric": ("Numbers become big numbers.", None),
             "Chart": ("A trend becomes a chart.", None),
             "Timeline": ("A plan becomes a timeline.", None),
             "Checklist": ("What's left becomes a checklist.", None),
             "Input": ("A question becomes a form.", "It waits for you on the left.")}
    caps_b = [(b_ask + 0.2, b_busy - 0.05, "Describe it the way you'd tell a person.", None)]
    prev_kind = None
    for n, (t0, cid) in enumerate(order):
        kind = B.kind[cid]
        if kind == prev_kind:
            continue
        nxt = next((t1 for t1, c2 in order[n + 1:] if B.kind[c2] != kind), b_end - 0.3)
        title, sub = label.get(kind, ("", None))
        if title:
            caps_b.append((t0 + 0.05, nxt - 0.1, title, sub))
        prev_kind = kind
    seg_b = Seg(B, max(0.0, b_ask - 0.4), b_end, cam_b, caps_b, speed=[(b_ask, b_busy, 1.5)], zmax=1.75, w=4.2)

    # E: 긴 프로젝트 — 밤사이 일이 정리되고, 컨펌할 것이 하나씩 내 차례로 온다
    e_ask = E.marks["ask"]
    e_busy = E.first(lambda f: f["busy"], e_ask)
    e_r4 = E.first(lambda f: f.get("task") == "r4", e_busy)
    e_a1 = E.marks["answer1"]
    e_p3 = E.first(lambda f: f.get("task") == "p3", e_a1)
    e_a2 = E.marks["answer2"]
    e_o2 = E.first(lambda f: f.get("task") == "o2", e_a2)
    e_end = E.end - 0.6
    slot_view = (0, 0, 1060, 596)

    def cam_e(c, t):
        for t0 in (e_r4, e_p3, e_o2):
            if t0 and t0 - 0.2 <= t < t0 + 1.9:
                return slot_view
        return None

    seg_e = Seg(E, 0.4, e_end, cam_e, [
        (0.5, e_ask - 0.1, "Day 19 of a six-week project.", "The board is right where you left it."),
        (e_busy + 0.2, e_r4 - 0.1, "Overnight work is already checked off.", "Progress, bugs and tests update on their own."),
        (e_r4 + 0.1, e_a1 + 0.2, "Three things need your OK.", "They line up on the left, one at a time."),
        (e_p3 + 0.1, e_a2 + 0.2, "Answer one, and the next comes to you.", "The board turns to that part of the project."),
        (e_o2 + 0.1, e_end - 0.3, "Nothing slips.", "What's left waits its turn, today or next week."),
    ], w=3.8)

    # D: 자유로운 화면 조작 → 닫는 장면
    m = D.marks
    d_end = D.end - 0.2
    seg_d = Seg(D, 0.6, d_end, full, [
        (m["pan"] + 0.1, m["pick"] - 0.1, "Drag anywhere to look around.", None),
        (m["pick"] + 0.1, m["close"] - 0.1, "Tap a card to bring it close.", "Only what belongs with it stays on the board."),
        (m["close"] + 0.1, m["in"] - 0.1, "Close it, and you see the whole picture.", None),
        (m["in"] + 0.1, m["out"] - 0.1, "One scroll, one level.", "Groups, then topics, then cards."),
        (m["out"] + 0.1, m["end"] + 0.4, "And back out, just as easily.", None),
    ], cursor_until=m["end"] + 0.6)

    return [
        seg_a,
        Title("01", "Built to fit", "Each answer takes the shape it needs."),
        seg_b,
        Title("02", "Your turn comes to you", "Weeks into a project, nothing slips."),
        seg_e,
        Title("03", "Move freely", "Drag, scroll, tap. The board keeps up."),
        seg_d,
    ]


def main():
    global SCRIM
    out = next(a for a in sys.argv[1:] if not a.startswith("--"))
    SCRIM = scrim()
    items = build()
    enc = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{OW}x{OH}", "-r", str(FPS), "-i", "-",
                            "-c:v", "libx264", "-preset", "veryfast" if PREVIEW else "slow", "-crf", "24" if PREVIEW else "17",
                            "-pix_fmt", "yuv420p", "-movflags", "+faststart", out], stdin=subprocess.PIPE)
    X = int(0.4 * FPS)  # 장면 전환 길이
    prev = None
    total = 0
    for item in items:
        for n, img in enumerate(item.frames()):
            if prev is not None and n < X:
                img = Image.blend(prev, img, ease((n + 1) / X))
            enc.stdin.write(img.tobytes())
            total += 1
            last = img
        prev = last
    # 끝: 마지막 장면을 잠시 두었다가 어둡게
    for n in range(int(0.8 * FPS)):
        enc.stdin.write(Image.blend(prev, Image.new("RGB", (OW, OH), (0, 0, 0)), ease(n / (0.8 * FPS))).tobytes())
        total += 1
    enc.stdin.close()
    enc.wait()
    print(f"{out}: {total} frames, {total / FPS:.1f}s")


if __name__ == "__main__":
    main()
