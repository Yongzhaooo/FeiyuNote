"""Render the 肥鱼笔记 promo video from an emulator screen recording.

Usage:
  python scripts/make-promo.py [--vertical] [--raw build/promo/raw.mp4] [--music song.m4a --music-start 50] [--out ...]

The raw recording comes from UiFlowTest#promoWalkthrough (-e promo true) captured with
adb screenrecord; the segment times below match that walkthrough. --vertical renders a
1080x1920 (9:16) cut for phones, otherwise 1920x1080. Music is optional and must be a
local file you have the right to use; the default credits are for 《Let Me Go》, used with
permission from @星落落_oi.
"""
import argparse
import math
import subprocess
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
FONT = str(ROOT / "app/src/main/res/font/noto_sans_sc_medium.ttf")
FONT_REG = str(ROOT / "app/src/main/res/font/noto_sans_sc_regular.ttf")
ART = ROOT / "app/src/main/res/drawable-nodpi"
FPS = 30
INDIGO, INK, SOFT = (62, 84, 166), (36, 44, 92), (92, 102, 140)
BLUSH, SKY = (236, 120, 160), (120, 190, 230)

# (start, end) in the raw recording, grouped into captioned scenes.
SCENES = [
    ([(9.0, 12.5)], "一屏看清你的学习", "课程 · 刷题本 · 公共聊天", "whale_02_02"),
    ([(12.2, 13.8), (15.4, 24.6)], "拍下问题，问个明白", "引导式讲解，公式离线渲染", "whale_01_01"),
    ([(26.4, 37.2)], "累了就和鱼鱼聊聊", "软萌又体贴，聊天记录只存本机", "whale_01_08"),
    ([(40.0, 47.6)], "设置更直观", "交流群一键复制 · 聊天和学习按需开关", "whale_02_04"),
    ([(52.6, 56.4)], "深色模式也可爱", "浅色深色，一键切换", "whale_02_10"),
]
INTRO, FEEDBACK, OUTRO = 4.0, 4.5, 6.0
QQ = "1079399140"
# 小红书 throttles posts that show QQ numbers: --xhs drops them and blurs the in-app group card,
# which is on screen from about 40.0 s to 44.5 s of the raw recording (y 585-910 at 1080 px wide).
# The blur runs on the raw stream before trimming so it cannot drift from the cut.
XHS = False
XHS_BLUR_RAW = (39.0, 45.0)
XHS_BLUR_BOX = (30, 585, 1020, 325)  # x, y, w, h
CREDITS = ["配乐：《Let Me Go》 作者 @星落落_oi（B站，已获授权） · 表情包：Hidcote（小红书）",
           "鲸鱼娘人设：上善无形、ZipZipPipe · 演示内容为模拟回答"]

# Per-orientation geometry. Sticker entries are (name, size, center, entrance delay).
LAYOUTS = {
    "landscape": dict(
        size=(1920, 1080), phone_h=930, phone_xy=(330, 75), cap_xy=(960, 300), cap_title=84, cap_sub=40,
        scene_sticker=(300, (1560, 820)), cx=1000,
        intro=dict(stickers=[("whale_02_03", 300, (420, 540), 0), ("whale_02_09", 220, (1530, 560), 0.25)], y=430),
        feedback=dict(stickers=[("whale_01_07", 300, (420, 540), 0)], cx=1120, y=360),
        outro=dict(stickers=[("whale_02_03", 260, (300, 540), 0), ("whale_02_02", 220, (1660, 560), 0.25)], y=300),
        credit=lambda w, h: [((w - 40, h - 62), "rs"), ((w - 40, h - 30), "rs")],
    ),
    "vertical": dict(
        size=(1080, 1920), phone_h=1200, phone_xy=(258, 520), cap_xy=(70, 170), cap_title=72, cap_sub=36,
        scene_sticker=(220, (900, 330)), cx=540,
        intro=dict(stickers=[("whale_02_03", 340, (540, 560), 0), ("whale_02_09", 240, (540, 1460), 0.25)], y=930),
        feedback=dict(stickers=[("whale_01_07", 360, (540, 560), 0)], cx=540, y=920),
        outro=dict(stickers=[("whale_02_03", 280, (330, 1380), 0), ("whale_02_02", 230, (770, 1400), 0.25)], y=640),
        credit=lambda w, h: [((w // 2, h - 70), "ms"), ((w // 2, h - 34), "ms")],
    ),
}


def font(size, regular=False):
    return ImageFont.truetype(FONT_REG if regular else FONT, size)


def ease_out_back(x):
    x = min(max(x, 0.0), 1.0)
    c1, c3 = 1.70158, 2.70158
    return 1 + c3 * (x - 1) ** 3 + c1 * (x - 1) ** 2


def ease_out(x):
    x = min(max(x, 0.0), 1.0)
    return 1 - (1 - x) ** 3


def badge(name, size):
    """A sticker in a white circle with a sky ring, like the app's avatars."""
    s = size * 2
    im = Image.open(ART / f"{name}.webp").convert("RGBA").resize((s, s), Image.LANCZOS)
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, s - 1, s - 1), fill=255)
    out = Image.new("RGBA", (s + 24, s + 24), (0, 0, 0, 0))
    d = ImageDraw.Draw(out)
    d.ellipse((0, 0, s + 23, s + 23), fill=(255, 255, 255, 255))
    d.ellipse((4, 4, s + 19, s + 19), outline=SKY + (255,), width=6)
    out.paste(im, (12, 12), mask)
    return out.resize((size + 12, size + 12), Image.LANCZOS)


def background(w, h):
    bg = Image.new("RGB", (w, h))
    top, bottom = (234, 243, 253), (252, 238, 244)
    px = bg.load()
    for y in range(h):
        for x in range(w):
            t = 0.7 * y / h + 0.3 * x / w
            px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(top, bottom))
    return bg.convert("RGBA")


BUBBLES = [(i * 0.137 % 1, (i * 0.61) % 1, 18 + (i * 37) % 70, 0.25 + (i % 5) * 0.08) for i in range(18)]


def draw_bubbles(img, t):
    w, h = img.size
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for bx, by, r, speed in BUBBLES:
        y = (by * (h + 200) - t * speed * 60) % (h + 200) - 100
        x = bx * w + 18 * math.sin(t * 0.8 + bx * 10)
        d.ellipse((x - r, y - r, x + r, y + r), fill=(255, 255, 255, 70), outline=(255, 255, 255, 160), width=3)
        d.ellipse((x - r * 0.45, y - r * 0.55, x - r * 0.15, y - r * 0.25), fill=(255, 255, 255, 150))
    img.alpha_composite(layer)


def phone_shell(pw, ph):
    pad = 16
    w, h = pw + pad * 2, ph + pad * 2
    shadow = Image.new("RGBA", (w + 80, h + 80), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((40, 52, w + 40, h + 52), 70, fill=(60, 80, 150, 70))
    shadow = shadow.filter(ImageFilter.GaussianBlur(22))
    body = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(body).rounded_rectangle((0, 0, w - 1, h - 1), 66, fill=(255, 255, 255, 255), outline=(214, 226, 246, 255), width=3)
    mask = Image.new("L", (pw, ph), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, pw - 1, ph - 1), 52, fill=255)
    return shadow, body, mask, pad


def caption_layer(L, title, sub, scene_no):
    layer = Image.new("RGBA", L["size"], (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    x, y = L["cap_xy"]
    d.rounded_rectangle((x, y, x + 150, y + 52), 26, fill=BLUSH + (255,))
    d.text((x + 75, y + 26), f"{scene_no} / {len(SCENES)}", font=font(28), fill="white", anchor="mm")
    d.text((x, y + 80), title, font=font(L["cap_title"]), fill=INK)
    d.text((x, y + 100 + L["cap_title"]), sub, font=font(L["cap_sub"], regular=True), fill=SOFT)
    return layer


def credit_layer(L):
    w, h = L["size"]
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for text, (xy, anchor) in zip(CREDITS, L["credit"](w, h)):
        d.text(xy, text, font=font(22, regular=True), fill=(120, 128, 160), anchor=anchor)
    layer.alpha_composite(badge("whale_02_02", 64), (40, 30))
    d.text((126, 64), "肥鱼笔记 0.4", font=font(34), fill=INK, anchor="lm")
    return layer


def pill(td, cx, y, text, size, alpha, half_w, fill=(255, 255, 255), color=INDIGO, strength=0.85):
    td.rounded_rectangle((cx - half_w, y - size, cx + half_w, y + size), size, fill=fill + (int(alpha * strength),))
    td.text((cx, y), text, font=font(size), fill=color + (alpha,), anchor="mm")


def card_text(kind, L, td, local):
    """Text for the intro, feedback and outro cards; each line fades in on its own delay."""
    fade = lambda delay: int(255 * ease_out((local - delay) / 0.6))
    vertical = L["size"][1] > L["size"][0]
    c = L[kind]
    cx, y = c.get("cx", L["cx"]), c["y"]
    if kind == "intro":
        a = fade(0.3)
        td.text((cx, y), "肥鱼笔记", font=font(150), fill=INK + (a,), anchor="mm")
        td.rounded_rectangle((cx - 80, y + 100, cx + 80, y + 154), 27, fill=BLUSH + (a,))
        td.text((cx, y + 127), "0.4.0", font=font(32), fill=(255, 255, 255, a), anchor="mm")
        tag = "拍下问题，聊透思路\n留下自己的笔记" if vertical else "拍下问题，聊透思路，留下自己的笔记"
        td.multiline_text((cx, y + 240), tag, font=font(46, regular=True), fill=SOFT + (a,), anchor="mm", align="center", spacing=18)
    elif kind == "feedback":
        td.text((cx, y), "鱼鱼还在长大", font=font(84), fill=INK + (fade(0.2),), anchor="mm")
        td.text((cx, y + 120), "长期会完善课程知识库", font=font(44, regular=True), fill=SOFT + (fade(0.5),), anchor="mm")
        td.text((cx, y + 190), "现在最需要你的试用和反馈", font=font(44, regular=True), fill=SOFT + (fade(0.7),), anchor="mm")
        pill(td, cx, y + 310, "评论区告诉鱼鱼你的想法" if XHS else f"反馈群 QQ：{QQ}", 44, fade(1.0), 380, fill=BLUSH, color=(255, 255, 255), strength=1)
    else:
        td.text((cx, y), "来和鱼鱼一起学习吧～", font=font(80 if vertical else 84), fill=INK + (fade(0.3),), anchor="mm")
        lines = ["下载：feiyunote.cangming.fyi/feiyu", f"QQ 交流群：{QQ}", "GitHub：Yongzhaooo/FeiyuNote"]
        if XHS:
            lines.pop(1)
        gap = 110 if vertical else 96
        for i, line in enumerate(lines):
            pill(td, cx, y + 140 + i * gap, line, 40, fade(0.5 + i * 0.25), 420)
        ty = y + 140 + len(lines) * gap + (30 if vertical else 10)
        thanks = ["鸣谢 B站 @星落落_oi 授权配乐《Let Me Go》", "鸣谢 小红书 Hidcote 的鲸鱼娘表情包"]
        for i, line in enumerate(thanks):
            td.text((cx, ty + i * 50), line, font=font(34, regular=True), fill=SOFT + (fade(1.4 + i * 0.2),), anchor="mm")


def phone_frames(raw, pw, ph, total):
    """Concatenate scene clips into one 30 fps stream at phone size; yields PIL images."""
    n = sum(len(sc[0]) for sc in SCENES)
    if XHS:
        x, y, w, h = XHS_BLUR_BOX
        a, b = XHS_BLUR_RAW
        parts = [f"[0:v]split[base][cp];[cp]crop={w}:{h}:{x}:{y},boxblur=24:3[bl];"
                 f"[base][bl]overlay={x}:{y}:enable='between(t,{a},{b})',split={n}" + "".join(f"[s{i}]" for i in range(n))]
    else:
        parts = [f"[0:v]split={n}" + "".join(f"[s{i}]" for i in range(n))]
    labels = []
    for scene in SCENES:
        for start, end in scene[0]:
            i = len(labels)
            parts.append(f"[s{i}]trim={start}:{end},setpts=PTS-STARTPTS,fps={FPS},scale={pw}:{ph}:flags=lanczos[v{i}]")
            labels.append(f"[v{i}]")
    graph = ";".join(parts) + ";" + "".join(labels) + f"concat=n={len(labels)}:v=1:a=0,format=rgb24[out]"
    proc = subprocess.Popen(["ffmpeg", "-v", "error", "-i", str(raw), "-filter_complex", graph, "-map", "[out]",
                             "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    size = pw * ph * 3
    for _ in range(total):
        buf = proc.stdout.read(size)
        if len(buf) < size:
            break
        yield Image.frombytes("RGB", (pw, ph), buf)
    proc.stdout.close()
    proc.wait()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vertical", action="store_true", help="1080x1920 phone cut")
    ap.add_argument("--xhs", action="store_true", help="小红书 cut without QQ group (implies --vertical)")
    ap.add_argument("--raw", default=str(ROOT / "build/promo/raw.mp4"))
    ap.add_argument("--out")
    ap.add_argument("--music")
    ap.add_argument("--music-start", type=float, default=0.0, help="seconds into the song to start from")
    args = ap.parse_args()
    global XHS
    XHS = args.xhs
    args.vertical |= XHS
    L = LAYOUTS["vertical" if args.vertical else "landscape"]
    suffix = "-xhs" if XHS else "-vertical" if args.vertical else ""
    out = args.out or str(ROOT / f"build/promo/feiyu-promo{suffix}.mp4")
    W, H = L["size"]
    ph = L["phone_h"]
    pw = round(ph * 1080 / 2300)
    px0, py = L["phone_xy"]

    scene_lens = [sum(e - s for s, e in sc[0]) for sc in SCENES]
    body_len = sum(scene_lens)
    body_end = INTRO + body_len
    duration = body_end + FEEDBACK + OUTRO
    frames = round(duration * FPS)
    bounds, acc = [], INTRO
    for n in scene_lens:
        bounds.append((acc, acc + n))
        acc += n

    bg = background(W, H)
    shadow, shell, mask, pad = phone_shell(pw, ph)
    credit = credit_layer(L)
    subs = [s.replace("交流群一键复制 · ", "") if XHS else s for _, _, s, _ in SCENES]
    captions = [caption_layer(L, t, s, i + 1) for i, ((_, t, _, _), s) in enumerate(zip(SCENES, subs))]

    st_size, st_center = L["scene_sticker"]
    stickers = [badge(sc[3], st_size) for sc in SCENES]
    cards = {k: [(badge(n, s), c, delay) for n, s, c, delay in L[k]["stickers"]] for k in ("intro", "feedback", "outro")}

    silent = str(Path(out).with_suffix(".silent.mp4"))
    enc = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}",
                            "-r", str(FPS), "-i", "-", "-c:v", "libx264", "-preset", "slow", "-crf", "18",
                            "-pix_fmt", "yuv420p", "-movflags", "+faststart", silent], stdin=subprocess.PIPE)
    phone = phone_frames(args.raw, pw, ph, round(body_len * FPS) + 2)
    last_screen = None
    for f in range(frames):
        t = f / FPS
        img = bg.copy()
        draw_bubbles(img, t)
        if t < INTRO or t >= body_end:
            kind, start = ("intro", 0) if t < INTRO else ("feedback", body_end) if t < body_end + FEEDBACK else ("outro", body_end + FEEDBACK)
            local = t - start
            for j, (im, (cx, cy), delay) in enumerate(cards[kind]):
                k = ease_out_back((local - delay) / 0.8)
                s = im.resize((max(1, int(im.width * k)),) * 2)
                bob = (10 if j % 2 == 0 else -10) * math.sin(t * 3)
                img.alpha_composite(s, (cx - s.width // 2, int(cy - s.height // 2 + bob)))
            text = Image.new("RGBA", (W, H), (0, 0, 0, 0))
            card_text(kind, L, ImageDraw.Draw(text), local)
            img.alpha_composite(text)
            if kind != "intro":
                img.alpha_composite(credit)
        else:
            screen = next(phone, None) or last_screen
            last_screen = screen
            i = next(k for k, (s, e) in enumerate(bounds) if s <= t < e) if t < bounds[-1][1] else len(bounds) - 1
            s, e = bounds[i]
            enter = ease_out((t - INTRO) / 0.6)
            px = px0 - int((1 - enter) * 120)
            img.alpha_composite(shadow, (px - pad - 40, py - pad - 40))
            img.alpha_composite(shell, (px - pad, py - pad))
            img.paste(screen, (px, py), mask)
            # Caption slides up and fades in at each scene start.
            k = ease_out((t - s) / 0.5)
            cap = captions[i]
            if k < 1:
                cap = cap.copy()
                cap.putalpha(cap.getchannel("A").point(lambda v: int(v * k)))
            img.alpha_composite(cap, (0, int((1 - k) * 40)))
            st = stickers[i]
            sk = ease_out_back((t - s) / 0.6)
            st = st.resize((max(1, int(st.width * sk)),) * 2)
            bob = 8 * math.sin(t * 2.6)
            img.alpha_composite(st, (st_center[0] - st.width // 2, int(st_center[1] - st.height // 2 + bob)))
            img.alpha_composite(credit)
        enc.stdin.write(img.convert("RGB").tobytes())
    enc.stdin.close()
    enc.wait()

    if args.music:
        fade_out = max(duration - 2.5, 0)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", silent, "-ss", str(args.music_start), "-i", args.music,
                        "-filter_complex", f"[1:a]atrim=0:{duration},asetpts=PTS-STARTPTS,afade=t=in:d=0.8,afade=t=out:st={fade_out}:d=2.5,volume=0.9[a]",
                        "-map", "0:v", "-map", "[a]", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest", out], check=True)
        Path(silent).unlink()
    else:
        Path(silent).replace(out)
    print(f"{out}: {W}x{H}, {duration:.1f}s, {frames} frames")


if __name__ == "__main__":
    main()
