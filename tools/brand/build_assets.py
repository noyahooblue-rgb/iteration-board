#!/usr/bin/env python3
"""Iteration Board brand suite builder (repo copy, adapted from the ajenttde asset pack).

Repo layout: sources live in frontend/public/brand/*.svg; outputs go to
frontend/public/ (favicon.ico, apple-touch-icon.png, icons/*, brand/*).
Run from anywhere:  python3 tools/brand/build_assets.py


Production ONLY: renders Claude's finalized SVGs into the full asset suite.
No shape or colour of the source artwork is modified anywhere in this script.
Badges / lockups / mockups are compositions AROUND the rendered icon.

Renderer: Chromium 129.0.6668.29 headless (Playwright build chromium-1134),
chosen because cairosvg 2.9.1 misrenders the source (drops the drop-shadow
filter and breaks the loop crossing clip). Verified against claude preview.
Composition: Pillow 10.2.0. Font: Noto Sans CJK SC (SIL OFL 1.1).
"""
import io, os, re, shutil, struct, subprocess, sys
from PIL import Image, ImageDraw, ImageFont

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
BASE = os.path.join(REPO, 'frontend', 'public')
SRC = os.path.join(BASE, 'brand')  # 定稿源 SVG 存档处（frontend/public/brand/）
CHROME = os.path.expanduser('~/.cache/ms-playwright/chromium-1134/chrome-linux/chrome')
FONT_R = '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'
FONT_B = '/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc'
TMP = '/tmp/brand-build'
os.makedirs(TMP, exist_ok=True)

# ---------------------------------------------------------------- fonts
def sc_index(path):
    """Find the 'SC' face index inside a Noto Sans CJK ttc."""
    from fontTools.ttLib import TTCollection
    ttc = TTCollection(path)
    for i, f in enumerate(ttc.fonts):
        names = f['name'].names
        for n in names:
            try:
                s = n.toUnicode()
            except Exception:
                continue
            if 'SC' in s.split() or s.endswith(' SC'):
                return i
    return 0

IDX_R = sc_index(FONT_R)
IDX_B = sc_index(FONT_B)

def font(path, size, idx):
    return ImageFont.truetype(path, size, index=idx)

# ---------------------------------------------------------------- chromium render
PAGE = os.path.join(TMP, 'render.html')
with open(PAGE, 'w') as fh:
    fh.write('<!doctype html><html><head><meta charset="utf-8"><style>'
             'html,body{margin:0;padding:0;background:transparent}img{display:block}'
             '</style></head><body><img id="i"></body><script>'
             'const q=new URLSearchParams(location.search);const img=document.getElementById("i");'
             'img.src=q.get("src");img.width=+q.get("w");img.height=+q.get("h");'
             '</script></html>')

_cache = {}
def render(svg_path, size):
    """Rasterize an SVG at an exact pixel size with Chromium. Returns RGBA Image.

    NOTE (measured on this build, Chromium 129 headless): a screenshot
    window of exactly size x size loses the bottom ~87 px of painted
    content. The window is therefore made 220 px taller and the result
    cropped to the top-left size x size, which is complete."""
    key = (svg_path, size)
    if key in _cache:
        return _cache[key].copy()
    out = os.path.join(TMP, f'r_{abs(hash(key))}.png')
    url = f'file://{PAGE}?src=file://{svg_path}&w={size}&h={size}'
    subprocess.run([CHROME, '--headless', '--disable-gpu', '--no-sandbox',
                    '--hide-scrollbars', '--force-device-scale-factor=1',
                    '--default-background-color=00000000',
                    '--virtual-time-budget=15000',
                    f'--window-size={size},{size + 220}',
                    f'--screenshot={out}', url],
                   check=True, capture_output=True, timeout=180)
    img = Image.open(out).convert('RGBA').crop((0, 0, size, size))
    _cache[key] = img
    return img.copy()

MASTER = os.path.join(SRC, 'brand-master.svg')
DARK = os.path.join(SRC, 'brand-dark.svg')
SMALL = os.path.join(SRC, 'brand-small.svg')
SPARK = os.path.join(SRC, 'brand-ai-spark.svg')

def save(img, relpath):
    p = os.path.join(BASE, relpath)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    img.save(p)
    print('wrote', relpath, img.size)
    return p

# ---------------------------------------------------------------- 1. SVG files
# 仓库版：源 SVG 已存档在 frontend/public/brand/，此处仅校验齐全（favicon.svg 的
# prefers-color-scheme 合成版由集成脚本生成，见仓库提交说明，不在此覆盖）。
svgdir = SRC
for name in ['brand-master.svg', 'brand-dark.svg', 'brand-small.svg', 'brand-ai-spark.svg']:
    assert os.path.exists(os.path.join(svgdir, name)), name
    print('source ok brand/' + name)

def make_mono(target, out_name):
    """Monochrome derivative of master: every paint becomes `target`.
    Geometry untouched. Filters removed (a black-alpha shadow would break
    monochrome); all stop opacities set to 1 so gradients collapse to solid."""
    s = open(MASTER).read()
    s = re.sub(r'stop-color="[^"]*"', f'stop-color="{target}"', s)
    s = re.sub(r'stop-opacity="[^"]*"', 'stop-opacity="1"', s)
    s = re.sub(r'fill="url\(#[^)]*\)"', f'fill="{target}"', s)
    s = re.sub(r'fill="#[0-9a-fA-F]{3,8}"', f'fill="{target}"', s)
    s = re.sub(r'fill="#fff"', f'fill="{target}"', s)
    s = re.sub(r'stroke="[^"]*"', f'stroke="{target}"', s)
    s = re.sub(r'stroke-opacity="[^"]*"', 'stroke-opacity="1"', s)
    s = re.sub(r'\s*filter="url\(#[^)]*\)"', '', s)
    with open(os.path.join(svgdir, out_name.replace('brand-mono-', 'brand-mono-')), 'w') as fh:
        fh.write(s)
    print('wrote svg/' + out_name)

make_mono('#FFFFFF', 'brand-mono-white.svg')
make_mono('#000000', 'brand-mono-black.svg')

# ---------------------------------------------------------------- 2/3. PNG sets
SIZES = [16, 32, 48, 64, 128, 180, 192, 512, 1024]
for variant, svg in [('master', MASTER), ('dark', DARK)]:
    for sz in SIZES:
        save(render(svg, sz), f'brand/png/{variant}/icon-{sz}.png')
save(render(MASTER, 180), 'apple-touch-icon.png')

# ---------------------------------------------------------------- 4. manifest
save(render(MASTER, 192), 'icons/icon-192.png')
save(render(MASTER, 512), 'icons/icon-512.png')
mask = Image.new('RGBA', (512, 512), (0x0A, 0x6C, 0xFF, 255))
inner = render(MASTER, 410)
mask.alpha_composite(inner, (51, 51))
save(mask, 'icons/icon-maskable-512.png')

# ---------------------------------------------------------------- ICO (manual, PNG payloads)
def build_ico(entries, path):
    """entries: list of (size, png_bytes)."""
    header = struct.pack('<HHH', 0, 1, len(entries))
    offset = 6 + 16 * len(entries)
    dirs, blobs = b'', b''
    for size, data in entries:
        b = size if size < 256 else 0
        dirs += struct.pack('<BBBBHHII', b, b, 0, 0, 1, 32, len(data), offset)
        blobs += data
        offset += len(data)
    with open(path, 'wb') as fh:
        fh.write(header + dirs + blobs)
    print('wrote favicon.ico', [s for s, _ in entries])

def png_bytes(img):
    buf = io.BytesIO()
    img.save(buf, format='PNG')
    return buf.getvalue()

build_ico([(16, png_bytes(render(SMALL, 16))),
           (32, png_bytes(render(SMALL, 32))),
           (48, png_bytes(render(MASTER, 48)))],
          os.path.join(BASE, 'favicon.ico'))

# ---------------------------------------------------------------- 5. badges (v2 geometry, overlay only)
TASK = (0x00, 0x7A, 0xFF)
UNREAD_RED = (0xFF, 0x3B, 0x30)
DEFECT_RED = (0xFF, 0x2D, 0x55)

def badge(base_img, kind):
    img = base_img.convert('RGBA').copy()
    n = img.width
    d = ImageDraw.Draw(img)
    ring = max(2, round(n * 0.006))
    if kind == 'count':
        r = n * 0.20
        cx, cy = n - r - n * 0.02, r + n * 0.02
        d.ellipse([cx - r - ring, cy - r - ring, cx + r + ring, cy + r + ring], fill=(255, 255, 255, 255))
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=TASK + (255,))
        f = font(FONT_B, int(r * 1.15), IDX_B)
        d.text((cx, cy), '3', font=f, fill=(255, 255, 255, 255), anchor='mm')
    else:
        color = UNREAD_RED if kind == 'unread' else DEFECT_RED
        r = n * 0.11
        cx, cy = n - r - n * 0.03, r + n * 0.03
        d.ellipse([cx - r - ring, cy - r - ring, cx + r + ring, cy + r + ring], fill=(255, 255, 255, 255))
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color + (255,))
    return img

for kind in ['count', 'unread', 'defect']:
    for sz in (512, 64):
        save(badge(render(MASTER, sz), kind), f'brand/badge-{kind}-{sz}.png')

# ---------------------------------------------------------------- 6. lockups
NAME = '迭代小组平台'
DARK_TEXT = (0x10, 0x24, 0x3E, 255)
WHITE = (0xFF, 0xFF, 0xFF, 255)
DARK_BG = (0x0B, 0x1F, 0x4D, 255)

def lockup_horizontal(dark_bg):
    icon = render(MASTER, 200)
    f = font(FONT_B, 96, IDX_B)
    tmp = Image.new('RGBA', (10, 10))
    tw = ImageDraw.Draw(tmp).textlength(NAME, font=f)
    W, H = int(60 + 200 + 44 + tw + 60), 320
    img = Image.new('RGBA', (W, H), DARK_BG if dark_bg else (255, 255, 255, 255))
    img.alpha_composite(icon, (60, 60))
    d = ImageDraw.Draw(img)
    d.text((60 + 200 + 44, H // 2), NAME, font=f,
           fill=WHITE if dark_bg else DARK_TEXT, anchor='lm')
    return img

def lockup_stacked(dark_bg):
    icon = render(MASTER, 240)
    f = font(FONT_B, 84, IDX_B)
    tmp = Image.new('RGBA', (10, 10))
    tw = ImageDraw.Draw(tmp).textlength(NAME, font=f)
    W = int(max(240, tw) + 120)
    H = 60 + 240 + 36 + 120 + 48
    img = Image.new('RGBA', (W, H), DARK_BG if dark_bg else (255, 255, 255, 255))
    img.alpha_composite(icon, ((W - 240) // 2, 60))
    d = ImageDraw.Draw(img)
    d.text((W // 2, 60 + 240 + 36 + 60), NAME, font=f,
           fill=WHITE if dark_bg else DARK_TEXT, anchor='mm')
    return img

save(lockup_horizontal(False), 'brand/lockup-horizontal-light.png')
save(lockup_horizontal(True), 'brand/lockup-horizontal-dark.png')
save(lockup_stacked(False), 'brand/lockup-stacked-light.png')
save(lockup_stacked(True), 'brand/lockup-stacked-dark.png')

# ---------------------------------------------------------------- 7a. login page mock
def vgrad(w, h, top, bottom):
    base = Image.new('RGBA', (1, h))
    for y in range(h):
        t = y / max(h - 1, 1)
        base.putpixel((0, y), tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(4)))
    return base.resize((w, h))

login = vgrad(1200, 1500, (0xE8, 0xF3, 0xFF, 255), (0xFF, 0xFF, 0xFF, 255))
d = ImageDraw.Draw(login)
icon = render(MASTER, 340)
login.alpha_composite(icon, ((1200 - 340) // 2, 210))
f_title = font(FONT_B, 88, IDX_B)
d.text((600, 640), NAME, font=f_title, fill=DARK_TEXT, anchor='mm')
f_sub = font(FONT_R, 36, IDX_R)
d.text((600, 716), '团队迭代 · 任务看板', font=f_sub, fill=(0x5A, 0x6B, 0x82, 255), anchor='mm')
card = [260, 830, 940, 1290]
d.rounded_rectangle(card, radius=28, fill=(255, 255, 255, 255), outline=(0xD8, 0xE2, 0xEE, 255), width=2)
f_lab = font(FONT_R, 34, IDX_R)
f_btn = font(FONT_B, 40, IDX_B)
for i, lab in enumerate(['账号', '密码']):
    y = 890 + i * 148
    d.text((310, y), lab, font=f_lab, fill=(0x33, 0x44, 0x5E, 255), anchor='lm')
    d.rounded_rectangle([310, y + 34, 890, y + 108], radius=14, outline=(0xC4, 0xD2, 0xE4, 255), width=2)
d.rounded_rectangle([310, 830 + 330, 890, 830 + 330 + 84], radius=16, fill=(0x0A, 0x6C, 0xFF, 255))
d.text((600, 830 + 330 + 42), '登 录', font=f_btn, fill=WHITE, anchor='mm')
f_foot = font(FONT_R, 28, IDX_R)
d.text((600, 1400), '迭代小组平台 · 登录页组合示例', font=f_foot, fill=(0x8A, 0x99, 0xAD, 255), anchor='mm')
save(login, '/tmp/brand-mock-login-page.png') if False else None
# (仓库版跳过 mockups/checks 示例图，仅定稿资产包保留)

# ---------------------------------------------------------------- 7b. document cover
cover = Image.new('RGBA', (1024, 1414), (255, 255, 255, 255))
d = ImageDraw.Draw(cover)
icon = render(MASTER, 200)
cover.alpha_composite(icon, (72, 72))
f_t = font(FONT_B, 92, IDX_B)
f_s = font(FONT_R, 40, IDX_R)
f_m = font(FONT_R, 32, IDX_R)
d.text((72, 380), NAME, font=f_t, fill=DARK_TEXT, anchor='lm')
d.text((72, 470), '使用说明书', font=f_s, fill=(0x33, 0x44, 0x5E, 255), anchor='lm')
d.rectangle([72, 560, 952, 568], fill=(0x0A, 0x6C, 0xFF, 255))
d.text((72, 640), '文档封面排版示例 · 图标与标题组合', font=f_m, fill=(0x8A, 0x99, 0xAD, 255), anchor='lm')
d.text((72, 1300), '版本 1.1.0', font=f_m, fill=(0x8A, 0x99, 0xAD, 255), anchor='lm')
pass  # 仓库版跳过示例图

# ---------------------------------------------------------------- 8. check sheets
def label(draw, xy, text, fill, size=30, bold=True, anchor='lm'):
    draw.text(xy, text, font=font(FONT_B if bold else FONT_R, size, IDX_B if bold else IDX_R),
              fill=fill, anchor=anchor)

# 8a. size check: real 16/32 renders, pixel zoom, light & dark grounds
variants = [('标准版 master', MASTER), ('小尺寸版 small', SMALL), ('深色版 dark', DARK)]
panel_w, panel_h = 1180, 3 * 258 + 84
sheet = Image.new('RGBA', (panel_w * 2, panel_h), (255, 255, 255, 255))
d = ImageDraw.Draw(sheet)
grounds = [(0, (0xE9, 0xED, 0xF2, 255), DARK_TEXT, '浅色底'),
           (panel_w, (0x1B, 0x1E, 0x24, 255), WHITE, '深色底')]
for gx, gcol, tcol, gname in grounds:
    d.rectangle([gx, 0, gx + panel_w, panel_h], fill=gcol)
    label(d, (gx + 36, 42), f'品牌图标 · 尺寸核查 · {gname}（实渲染）', tcol, 34)
    for ri, (vname, svg) in enumerate(variants):
        y = 84 + ri * 258
        label(d, (gx + 36, y + 18), vname, tcol, 28)
        i16, i32 = render(svg, 16), render(svg, 32)
        x = gx + 36
        sheet.alpha_composite(i16, (x, y + 108))
        label(d, (x, y + 140), '16', tcol, 24)
        sheet.alpha_composite(i16.resize((168, 168), Image.NEAREST), (x + 70, y + 52))
        label(d, (x + 70, y + 238), '16px ×10.5', tcol, 24)
        sheet.alpha_composite(i32, (x + 330, y + 108))
        label(d, (x + 330, y + 156), '32', tcol, 24)
        sheet.alpha_composite(i32.resize((168, 168), Image.NEAREST), (x + 400, y + 52))
        label(d, (x + 400, y + 238), '32px ×5.25', tcol, 24)
pass  # 仓库版跳过核查图

# 8b. browser tab bars (light chrome / dark chrome) x (master / dark favicon)
def tabstrip(dark_chrome, favicon_svg):
    W, H = 1240, 150
    bar = (0x20, 0x21, 0x24, 255) if dark_chrome else (0xDE, 0xE1, 0xE6, 255)
    tab = (0x35, 0x36, 0x3A, 255) if dark_chrome else (0xFF, 0xFF, 0xFF, 255)
    txt = WHITE if dark_chrome else DARK_TEXT
    img = Image.new('RGBA', (W, H), bar)
    dd = ImageDraw.Draw(img)
    dd.rounded_rectangle([24, 28, 520, 96], radius=14, fill=tab)
    img.alpha_composite(render(favicon_svg, 32), (48, 44))
    f = font(FONT_R, 30, IDX_R)
    dd.text((96, 62), NAME, font=f, fill=txt, anchor='lm')
    dd.rounded_rectangle([24, 108, W - 24, 138], radius=15,
                         fill=(0x2B, 0x2C, 0x30, 255) if dark_chrome else (0xFF, 0xFF, 0xFF, 255))
    dd.text((44, 123), 'iteration-board.local', font=font(FONT_R, 22, IDX_R),
            fill=(0x9A, 0xA4, 0xB0, 255), anchor='lm')
    return img

ROW_H = 196
tabs = Image.new('RGBA', (1240, ROW_H * 4 + 12), (255, 255, 255, 255))
d = ImageDraw.Draw(tabs)
rows = [('浅色浏览器 · 标准 favicon', False, MASTER), ('浅色浏览器 · 深色 favicon', False, DARK),
        ('深色浏览器 · 标准 favicon', True, MASTER), ('深色浏览器 · 深色 favicon', True, DARK)]
for i, (cap, dc, svg) in enumerate(rows):
    y = i * ROW_H + 6
    label(d, (8, y + 14), cap, DARK_TEXT, 26)
    tabs.alpha_composite(tabstrip(dc, svg), (0, y + 40))
pass  # 仓库版跳过核查图

# 8c. Windows taskbar strip
tb = Image.new('RGBA', (1240, 300), (255, 255, 255, 255))
d = ImageDraw.Draw(tb)
d.rectangle([0, 120, 1240, 216], fill=(0x20, 0x20, 0x20, 255))
for i, gx in enumerate([30, 130, 560, 660]):
    d.rounded_rectangle([gx, 140, gx + 56, 196], radius=10, fill=(0x3A, 0x3A, 0x3A, 255))
tb.alpha_composite(render(MASTER, 64), (250, 136))
tb.alpha_composite(render(MASTER, 48), (370, 144))
tb.alpha_composite(render(DARK, 64), (760, 136))
d.rectangle([250, 206, 314, 210], fill=(0x0A, 0x6C, 0xFF, 255))
f_clk = font(FONT_R, 30, IDX_R)
d.text((1210, 168), '14:32', font=f_clk, fill=WHITE, anchor='rm')
label(d, (272, 250), '标准 32px', DARK_TEXT, 24, anchor='mm')
label(d, (404, 250), '标准 24px', DARK_TEXT, 24, anchor='mm')
label(d, (792, 250), '深色 32px', DARK_TEXT, 26, anchor='mm')
label(d, (30, 60), 'Windows 任务栏（图标为实渲染，灰块为占位）', DARK_TEXT, 30, anchor='lm')
pass  # 仓库版跳过核查图

print('ALL DONE')
