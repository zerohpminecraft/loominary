#!/usr/bin/env python3
"""Assembles Episode 2 (docs/videos/ep02-web-editor.md) from generated assets.

Inputs (produce first):
  web/e2e/media/.pw-artifacts/**/video.webm    cd web && npm run broll
  web/e2e/media/ep02-*-marks.json              timing sidecars written by the ep02 broll tests
  docs/videos/out/card-*.png                   cd web && node ../docs/videos/tools/record-cards.mjs
  docs/wiki/assets/**                          the committed wiki stills

Output: docs/videos/out/ep02/ep02-web-editor.mp4 (captions burned in)
        docs/videos/out/ep02/ep02-web-editor.srt (sidecar, same cues)

Same architecture as assemble-ep01.py, plus:
  - per-segment 'texts' overlays (drawtext) for the dither-montage algorithm names,
    timed from the broll marks so each name sits on its own algorithm;
  - a spoken() rewrite pass so DECtalk pronounces sRGB/AV1/PSNR/JJN correctly while
    the burned caption keeps the printed form.

  python3 docs/videos/tools/assemble-ep02.py [--codec av1] [--no-voice]
"""
import json, os, re, shutil, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RAW  = ROOT / 'docs/videos/out/raw'
OUT  = ROOT / 'docs/videos/out/ep02'
TMP  = OUT / 'tmp'
BROLL = ROOT / 'web/e2e/media/.pw-artifacts'
MARKS = ROOT / 'web/e2e/media'
SHOTS_GAME = ROOT / 'docs/wiki/assets/game'
CARDS = ROOT / 'docs/videos/out'

def run(*args):
    r = subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', *map(str, args)])
    if r.returncode != 0: sys.exit(f'ffmpeg failed: {args}')

def probe(path):
    return float(subprocess.check_output(['ffprobe', '-v', 'quiet', '-show_entries',
        'format=duration', '-of', 'csv=p=0', str(path)]).strip())

def broll(name):
    return next(BROLL.glob(f'*{name}*/video.webm'))

def marks(name):
    return json.load(open(MARKS / f'ep02-{name}-marks.json'))

# ── Narration synth (same chain as ep01: DECtalk Perfect Paul, else espeak-ng) ─
DECTALK = os.environ.get('DECTALK_DIR')
if DECTALK and not (Path(DECTALK) / 'say').exists():
    DECTALK = None
ESPEAK = os.environ.get('ESPEAK_NG') or shutil.which('espeak-ng')
VOICE = '--no-voice' not in sys.argv and (DECTALK or ESPEAK) is not None

# Caption keeps the printed form; the synthesizer gets a pronounceable rewrite.
SPOKEN = [
    ('sRGB', 'S R G B'),
    ('AV1', 'A V one'),
    ('PSNR', 'P S N R'),
    ('Floyd–Steinberg', 'Floyd Steinberg'),
    ('Shiau-Fan', 'Shau Fan'),
    ('Jarvis-Judice-Ninke', 'Jarvis, Judice, Ninkey'),
    ('delta-E', 'delta E'),
    ('—', ', '),   # em dash reads as a pause
    ('–', ' '),
]
def spoken(text):
    for a, b in SPOKEN:
        text = text.replace(a, b)
    return text

def say(text, path):
    raw = path.with_suffix('.raw.wav')
    if DECTALK:
        subprocess.run(['./say', '-pre', '[:phoneme on][:rate 170]',
                        '-a', spoken(text), '-fo', str(raw)], check=True, cwd=DECTALK)
        post = ('highpass=f=90,equalizer=f=2600:t=q:w=1.2:g=3,'
                'loudnorm=I=-18:TP=-2:LRA=7')
    else:
        subprocess.run([ESPEAK, '-v', 'en-us+m3', '-s', '132', '-p', '30', '-g', '8',
                        '-a', '100', '-w', str(raw), spoken(text)], check=True)
        post = ('highpass=f=90,lowpass=f=9000,equalizer=f=2800:t=q:w=1.2:g=4,'
                'loudnorm=I=-18:TP=-2:LRA=7')
    run('-i', raw, '-af', post, '-ar', '48000', path)
    raw.unlink()

# ── Sources & marks ───────────────────────────────────────────────────────────
tour   = broll('pages-tour');            tour_d   = probe(tour)
imp    = broll('import-drop-and-GIF');    imp_m    = marks('import');   imp_d  = probe(imp)
grid   = broll('grid-and-crop-ep02');     grid_d   = probe(grid)
adj    = broll('tuning-sliders');        adj_d    = probe(adj)
cmode  = broll('color-mode-srgb');       cm_m     = marks('colormode'); cmode_d = probe(cmode)
sxp    = broll('srgb-export-panel');     sxp_m    = marks('srgb');     sxp_d  = probe(sxp)
pal    = broll('palette-presets');       pal_m    = marks('palette');  pal_d  = probe(pal)
cov    = broll('coverage-score');        cov_m    = marks('coverage'); cov_d  = probe(cov)
dit    = broll('dither-montage');        dit_m    = marks('dither');   dit_d  = probe(dit)
tools  = broll('editor-toolbox');        tools_d  = probe(tools)
req    = broll('requantize-selection');  req_m    = marks('requant');  req_d  = probe(req)
filt   = broll('filters-on-selection');  filt_d   = probe(filt)
ppan   = broll('palette-panel');         ppan_d   = probe(ppan)
codec  = broll('export-stats-and-codecs'); cod_m  = marks('codec');    codec_d = probe(codec)
meta   = broll('export-metadata-and-3d'); meta_m  = marks('meta');     meta_d = probe(meta)
sess   = broll('sessions-restore');      sess_d   = probe(sess)

# ── Dither-montage name overlays ──────────────────────────────────────────────
ALGO_NAMES = [
    ('FS', 'Floyd-Steinberg'), ('Sierra', 'Sierra'), ('Sierra2', 'Sierra Two-Row'),
    ('SierraL', 'Sierra Lite'), ('Shiau', 'Shiau-Fan'), ('JJN', 'Jarvis-Judice-Ninke'),
    ('Stucki', 'Stucki'), ('Atk', 'Atkinson'), ('Bayer', 'Bayer (ordered)'), ('None', 'No dithering'),
]
MONTAGE_START = dit_m['FS']
def montage_texts():
    """[(t0, t1, label)] relative to the montage slice start."""
    out = []
    keys = [k for k, _n in ALGO_NAMES]
    for i, (k, name) in enumerate(ALGO_NAMES):
        t0 = dit_m[k] - MONTAGE_START + 0.15
        t1 = (dit_m[keys[i + 1]] if i + 1 < len(keys) else dit_m['knobs']) - MONTAGE_START - 0.05
        out.append((t0, t1, name))
    return out

# ── Segment plan ──────────────────────────────────────────────────────────────
# (kind, src, dur, cues[, boxes[, texts]])
# kind: game (explicit (start, dur, speed) slice of game.mkv) | broll (path, off,
# dur) | card / still (image). Cue text is BOTH narration and burned captions.
SEGS = [
    ('card', CARDS / 'card-ep02-title.png', 5.0, [
        "Episode two: the web editor. Every setting, in the order you will meet them. None will be skipped.",
    ]),
    ('broll', (tour, 0.0, tour_d), None, [
        "Three pages: import, edit, export. Import decides ninety percent of your quality, so that is where we start.",
    ]),
    ('broll', (imp, 0.0, imp_m['gifLoaded'] - 0.3), None, [
        "Step one: drop in an image. Nothing uploads. Your browser does the work — and it keeps the original, which will matter later.",
    ]),
    ('broll', (imp, imp_m['gifLoaded'] - 0.3, imp_d - imp_m['gifLoaded'] + 0.3), None, [
        "Yes, it takes GIFs. Animation is episode three.",
    ]),
    ('broll', (grid, 0.0, grid_d * 0.62), None, [
        "One tile is 128 by 128 pixels in-game. Loominary reads your aspect ratio and suggests a grid. Type 2 by 3 and you have multi-tile art, that is episode four. Today, one tile.",
    ]),
    ('broll', (grid, grid_d * 0.62, grid_d * 0.38), None, [
        "Scale stretches to fill it; center crop trims the edges. Choose crop.",
    ]),
    ('broll', (adj, 0.5, adj_d - 0.5), None, [
        "Adjustments: brightness, contrast, saturation. The map palette is muted and dark-biased, so push saturation and lift brightness a little. Small moves.",
    ]),
    ('broll', (cmode, 0.0, cm_m['paletteBack'] + 1.0), None, [
        "Step four is a decision. Map palette quantizes every pixel to Minecraft's colors: editable, ditherable, classic.",
    ]),
    ('broll', (cmode, cm_m['srgbAgain'] - 0.8, cmode_d - cm_m['srgbAgain'] + 0.8), None, [
        "Full color skips the palette. True 24-bit sRGB, shipped as a lossy AV1 stream, painted straight into the map texture. Sixteen million colors on a vanilla map.",
    ]),
    ('broll', (sxp, max(0.0, sxp_m['fidelity'] - 4.0), sxp_d - max(0.0, sxp_m['fidelity'] - 4.0)), None, [
        "Full color gets a quality slider and a fidelity readout in delta-E and PSNR. Viewers need the latest release. Vanilla players still see decorative carpet either way.",
        "We continue in palette mode — the palette is where the settings live.",
    ]),
    ('card', CARDS / 'card-palette-presets.png', 8.0, [
        "Palette: six presets. Flat fullblock, 61 colors. Staircase fullblock, 183. All shades, 244, including one shade no block placement can produce.",
    ]),
    ('broll', (pal, pal_m['portrait'] - 0.3, pal_d - pal_m['portrait'] + 0.3), None, [
        "Greyscale keeps colors under a chroma threshold you control.",
    ]),
    ('broll', (pal, pal_m['flat-carpet'] - 0.3, pal_m['portrait'] - pal_m['flat-carpet']), None, [
        "And two carpet presets, sixteen colors each, for people building actual carpet.",
    ]),
    ('broll', (cov, max(0.0, cov_m['poorFit'] - 1.5), cov_d - max(0.0, cov_m['poorFit'] - 1.5)), None, [
        "The coverage score judges how well the palette fits this image. Green at seventy-five percent. It measures the palette, not the dithering — so fix it here, not later.",
    ], [(0, 8, 668, 344, 66)]),
    ('broll', (dit, 0.0, MONTAGE_START), None, [
        "Quantization. Dithering spreads each pixel's matching error onto its neighbors. There are ten algorithms.",
    ]),
    ('broll', (dit, MONTAGE_START, dit_m['knobs'] - MONTAGE_START), None, [
        "Error diffusion: Floyd–Steinberg, three Sierras, Shiau-Fan, Jarvis-Judice-Ninke, Stucki. Atkinson, which drops error on purpose. Bayer, ordered. And none.",
    ], None, montage_texts()),
    ('broll', (dit, dit_m['knobs'] - 0.5, dit_d - dit_m['knobs'] + 0.5), None, [
        "Each has a strength slider. There is serpentine scanning. There is chroma boost.",
    ]),
    ('broll', (tools, 0.5, tools_d - 0.5), None, [
        "The editor. Brush, fill, rectangle, lasso, magic wand — a real pixel editor working directly in map colors, so nothing you paint can be undisplayable. Right-click picks up any color under the cursor.",
    ]),
    ('broll', (req, 0.0, req_m['wandDone'] + 0.8), None, [
        "Select an area: rectangle, lasso, or wand.",
    ]),
    ('broll', (req, req_m['wandDone'] + 0.8, req_m['bayerCommit'] + 1.2 - req_m['wandDone'] - 0.8), None, [
        "Requantize re-runs the whole quantizer on those pixels only: any dither, any palette, any strength, pulled fresh from your original source image. The rest of the art does not move.",
    ]),
    ('broll', (req, req_m['bayerCommit'] + 1.2, req_d - req_m['bayerCommit'] - 1.2), None, [
        "Bayer for the sky. Floyd–Steinberg for the lake.",
    ]),
    ('broll', (filt, 0.5, filt_d - 0.5), None, [
        "Filters: smooth, median, sharpen, posterize. Posterize flattens photos into poster art. They respect your selection too.",
    ]),
    ('broll', (ppan, 0.5, ppan_d * 0.55), None, [
        "The palette panel counts every pixel of every color. Sort by frequency, lightness, chroma, hue, or natural byte order.",
    ]),
    ('broll', (ppan, ppan_d * 0.55, ppan_d * 0.45), None, [
        "A color used six times is costing you compression for nothing. Control-click queues it; merge sends its pixels to a color you choose. This is also how you rescue an over-budget export.",
    ]),
    ('broll', (codec, max(0.0, cod_m['stats'] - 1.0), codec_d - max(0.0, cod_m['stats'] - 1.0)), None, [
        "Export shows the byte counts.",
    ]),
    ('card', CARDS / 'card-codec-table.png', 9.0, [
        "Carpet alone carries 8,176 bytes. Add the shade channel: 10,192. Add overflow banners: 13,466. All three: 15,482. Banners alone need no platform at all: 5,290. These numbers are exact.",
    ]),
    ('broll', (meta, meta_m['meta'] - 0.5, meta_m['viewer3d'] - meta_m['meta'] + 0.5), None, [
        "Give it a title and an author, the metadata travels with the art. The password field encrypts art for people you choose. Episode six.",
    ]),
    ('broll', (meta, meta_m['viewer3d'], meta_d - meta_m['viewer3d']), None, [
        "And before you export, preview the schematic in 3D. What you see is what you place.",
    ]),
    ('broll', (sess, 0.0, sess_d), None, [
        "Everything auto-saves in your browser, source image included. Close the tab. It will all be here tomorrow.",
    ]),
    ('card', CARDS / 'card-ep02-end.png', 6.0, [
        "Next episode, your art starts moving.",
    ]),
]
# Normalize: every entry is (kind, src, dur, cues, boxes, texts).
SEGS = [(*s, *([None] * (6 - len(s)))) for s in SEGS]

def esc_drawtext(s):
    return s.replace('\\', '\\\\').replace(':', '\\:').replace("'", "\\\\'").replace(',', '\\,')

def texts_vf(texts):
    """drawtext chain for burned-in overlay labels (montage algorithm names)."""
    if not texts: return ''
    parts = []
    for (t0, t1, label) in texts:
        parts.append(
            f"drawtext=font=Ubuntu:text='{esc_drawtext(label)}':fontsize=46:fontcolor=white"
            f":box=1:boxcolor=black@0.55:boxborderw=14:x=(w-text_w)/2:y=64"
            f":enable='between(t,{t0:.2f},{t1:.2f})'")
    return ',' + ','.join(parts)

def boxes_vf(boxes, i, cues):
    """drawbox chain for callouts, gated to the bound cue's window (ep01 scheme)."""
    if not boxes: return ''
    parts = []
    for (cj, x, y, w, h) in boxes:
        b0 = 0.4 + sum(cue_slot(i, k, cues[k]) for k in range(cj)) + 0.2
        b1 = b0 - 0.2 + cue_slot(i, cj, cues[cj]) - 0.4
        parts.append(f"drawbox=x={x}:y={y}:w={w}:h={h}:color=0x6fb3ff@0.9:t=5"
                     f":enable='between(t,{b0:.2f},{b1:.2f})'")
    return ',' + ','.join(parts)

OUT.mkdir(parents=True, exist_ok=True); TMP.mkdir(exist_ok=True)

# Synthesize narration first: card/still durations stretch to fit their lines.
vo = {}      # (seg_idx, cue_idx) -> (wav_path, duration)
if VOICE:
    for i, (_k, _s, _d, cues, _b, _t) in enumerate(SEGS):
        for j, cue in enumerate(cues):
            w = TMP / f'vo{i:02d}_{j}.wav'
            say(cue, w)
            vo[(i, j)] = (w, probe(w))

def cue_slot(i, j, cue):
    reading = max(2.2, len(cue) / 16)
    return max(reading, vo[(i, j)][1] + 0.5) if (i, j) in vo else reading

# ── Build normalized clips ────────────────────────────────────────────────────
NORM_V = ['-r', '30', '-c:v', 'libx264', '-preset', 'medium', '-crf', '18', '-pix_fmt', 'yuv420p']
SCALE = 'scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=0x0b1020'

clips = []       # (path, has_audio)
for i, (kind, src, dur, cues, boxes, texts) in enumerate(SEGS):
    clip = TMP / f'seg{i:02d}.mp4'
    # GUARDRAIL: every segment must hold ALL of its narration (see ep01).
    need = sum(cue_slot(i, j, c) for j, c in enumerate(cues)) + 0.8
    if kind in ('card', 'still'):
        dur = max(dur, need)
    if kind == 'broll':
        path, off, d = src
        vf = SCALE + boxes_vf(boxes, i, cues) + texts_vf(texts)
        pad = max(0.0, need - d)
        if pad: vf += f',tpad=stop_mode=clone:stop_duration={pad:.3f}'
        run('-ss', f'{off:.3f}', '-t', f'{d:.3f}', '-i', path,
            '-vf', vf, *NORM_V, '-an', clip)
        clips.append((clip, False))
    elif kind == 'still':
        # Ken Burns push-in (see ep01 for the zoompan traps this dodges).
        frames = int(dur * 30)
        vf = ("scale=7680:4320:force_original_aspect_ratio=decrease,"
              "pad=7680:4320:(ow-iw)/2:(oh-ih)/2:color=0x0b1020,"
              f"zoompan=z='min(zoom+0.0006,1.18)':x='iw/2-(iw/zoom/2)'"
              f":y='ih/2-(ih/zoom/2)':d={frames}:s=7680x4320:fps=30,"
              "scale=1920:1080")
        run('-i', src, '-vf', vf, '-frames:v', str(frames), *NORM_V, '-an', clip)
        clips.append((clip, False))
    else:  # card — plain hold
        run('-loop', '1', '-t', f'{dur:.3f}', '-i', src, '-vf', SCALE, *NORM_V, '-an', clip)
        clips.append((clip, False))

# ── Captions + narration timeline (identical guardrails to ep01) ──────────────
def fmt_ts(t):
    ms = int(round(t * 1000)); h, ms = divmod(ms, 3600000); m, ms = divmod(ms, 60000); s, ms = divmod(ms, 1000)
    return f'{h:02d}:{m:02d}:{s:02d},{ms:03d}'

# Burned captions never exceed one row: split each cue into short word-boundary
# chunks shown sequentially across its window; full sentences stay in the .srt.
MAX_CAP_CHARS = 48
def wrap_chunks(text, max_chars=MAX_CAP_CHARS):
    text = text.replace('—', ',')
    words, lines, cur = text.split(), [], ''
    for w in words:
        if cur and len(cur) + 1 + len(w) > max_chars:
            lines.append(cur); cur = w
        else:
            cur = f'{cur} {w}'.strip()
    if cur: lines.append(cur)
    return lines

srt, burn, cue_events, t0 = [], [], [], 0.0
srt_n = burn_n = 1
vo_cursor = 0.0
VO_GAP = 0.35
for i, ((kind, src, dur, cues, _b, _t), (clip, _a)) in enumerate(zip(SEGS, clips)):
    d_real = probe(clip)
    lead = min(0.4, d_real * 0.05)
    t = t0 + lead
    for j, cue in enumerate(cues):
        cd = cue_slot(i, j, cue)
        t = max(t, vo_cursor + VO_GAP if vo_cursor else t)
        end = min(t + cd, t0 + d_real - 0.1)
        if end <= t:
            print(f'WARNING: cue {srt_n} ("{cue[:40]}…") does not fit segment {i}')
            end = t + cd
        srt.append(f'{srt_n}\n{fmt_ts(t)} --> {fmt_ts(end)}\n{cue}\n'); srt_n += 1
        chunks = wrap_chunks(cue)
        tot = sum(len(c) for c in chunks) or 1
        ct = t
        for k, c in enumerate(chunks):
            c_end = end if k == len(chunks) - 1 else ct + (end - t) * (len(c) / tot)
            burn.append(f'{burn_n}\n{fmt_ts(ct)} --> {fmt_ts(c_end)}\n{c}\n'); burn_n += 1
            ct = c_end
        if (i, j) in vo:
            cue_events.append((t + 0.1, vo[(i, j)][0]))
            vo_cursor = t + 0.1 + vo[(i, j)][1]
        t = end
    t0 += d_real

for (a_t, a_w), (b_t, _b_w) in zip(cue_events, cue_events[1:]):
    a_end = a_t + probe(a_w)
    assert a_end <= b_t + 0.01, f'narration overlap: clip ending {a_end:.2f}s vs next start {b_t:.2f}s'

srt_path = OUT / 'ep02-web-editor.srt'
srt_path.write_text('\n'.join(srt))
burn_path = TMP / 'burn.srt'
burn_path.write_text('\n'.join(burn))

# ── Concat video; audio = music bed + narration ───────────────────────────────
concat = TMP / 'concat.txt'
concat.write_text(''.join(f"file '{c}'\n" for c, _a in clips))
silent = TMP / 'video.mp4'
run('-f', 'concat', '-safe', '0', '-i', concat, '-c', 'copy', silent)
total = probe(silent)

def asset(name):
    base = Path.home() / '.gradle/caches/fabric-loom/assets'
    index = next((base / 'indexes').glob('1.21.4*.json'))
    h = json.load(open(index))['objects'][f'minecraft/sounds/{name}.ogg']['hash']
    return base / 'objects' / h[:2] / h

# The game capture is headless (silent), so the bed always comes from the
# game's own music assets, exactly like ep01's fallback path.
BED_VOL = 0.11 if VOICE else 0.45
inputs, filters, mixins = ['-i', silent], [], []
idx = 1
inputs += ['-i', asset('music/game/clark')]
filters.append(f'[{idx}:a]volume={BED_VOL},afade=t=in:d=2,'
               f'apad,atrim=0:{total:.3f},afade=t=out:st={total-2.5:.3f}:d=2.5[bed]')
mixins.append('[bed]'); idx += 1

for t_at, wav in cue_events:
    inputs += ['-i', wav]
    delay_ms = int(t_at * 1000)
    filters.append(f'[{idx}:a]adelay={delay_ms}|{delay_ms},volume=1.0[v{idx}]')
    mixins.append(f'[v{idx}]'); idx += 1

filters.append(f"{''.join(mixins)}amix=inputs={len(mixins)}:normalize=0,alimiter=limit=0.92[aout]")

sub_style = ('FontName=Ubuntu,Fontsize=16,PrimaryColour=&H00F4F7FF,OutlineColour=&HA0000000,'
             'BorderStyle=1,Outline=2,Shadow=0,MarginV=20')

codec_arg = 'av1' if '--codec' in sys.argv and sys.argv[sys.argv.index('--codec') + 1] == 'av1' else 'h264'
if codec_arg == 'av1':
    VCODEC = ['-c:v', 'libsvtav1', '-preset', '5', '-crf', '32']
else:
    has_nvenc = subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error',
        '-f', 'lavfi', '-i', 'testsrc2=size=256x256:rate=30', '-t', '0.2',
        '-c:v', 'h264_nvenc', '-f', 'null', '-'], capture_output=True).returncode == 0
    VCODEC = (['-c:v', 'h264_nvenc', '-preset', 'p6', '-rc', 'vbr', '-cq', '23',
               '-b:v', '0', '-maxrate', '12M', '-bufsize', '24M']
              if has_nvenc else ['-c:v', 'libx264', '-preset', 'slow', '-crf', '20'])

final = OUT / 'ep02-web-editor.mp4'
run(*inputs, '-filter_complex', ';'.join(filters + [
        f"[0:v]subtitles={burn_path}:force_style='{sub_style}'[vout]"]),
    '-map', '[vout]', '-map', '[aout]',
    '-r', '30', *VCODEC, '-pix_fmt', 'yuv420p',
    '-c:a', 'aac', '-b:a', '160k', '-movflags', '+faststart', final)

print(f'{final}  ({probe(final):.1f}s, {final.stat().st_size/1e6:.1f} MB)'
      + ('' if VOICE else '  [no narration]'))
print(f'{srt_path}  ({srt_n - 1} cues, {burn_n - 1} burned rows)')
