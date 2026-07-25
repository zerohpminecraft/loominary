#!/usr/bin/env python3
"""Assembles Episode 3 (docs/videos/ep03-animated-art.md) from generated assets.

Inputs (produce first):
  web/e2e/media/.pw-artifacts/**/video.webm   cd web && npx playwright test \
      --config e2e/playwright.config.ts --project=broll -g ep03
  web/e2e/media/ep03-*-marks.json             timing sidecars from the ep03 broll tests
  docs/videos/out/card-ep03-*.png             cd web && node ../docs/videos/tools/record-cards.mjs \
      ep03-title ep03-codec ep03-budget ep03-end
  web/e2e/fixtures/sample-anim.gif            clean 12-frame loop (cold open, sync, grid)
  docs/wiki/assets/game/status-decoding-anim.gif   the on-map DECODING progress bar
  docs/videos/out/raw/game.mkv                ep01 in-game capture (placement recap slice)

Output: docs/videos/out/ep03/ep03-animated-art.mp4 (captions burned in)
        docs/videos/out/ep03/ep03-animated-art.srt (sidecar, same cues)

Architecture matches assemble-ep02.py, plus new segment kinds for this episode's
"game-heavy" back half, produced without a live capture:
  anim  — a GIF looped inside an item-frame border (the cold open / decode bar)
  sync  — two synced copies of a GIF side by side (the two-client sync shot)
  grid  — a 2x2 synced tiling of a GIF (tiles advancing together)
  game  — a sped-up slice of game.mkv (the place/scan/lock/frame recap)

  python3 docs/videos/tools/assemble-ep03.py [--no-voice]
"""
import json, os, shutil, subprocess, sys
from pathlib import Path

ROOT  = Path(__file__).resolve().parents[3]
OUT   = ROOT / 'docs/videos/out/ep03'
TMP   = OUT / 'tmp'
BROLL = ROOT / 'web/e2e/media/.pw-artifacts'
MARKS = ROOT / 'web/e2e/media'
CARDS = ROOT / 'docs/videos/out'
GAME  = ROOT / 'docs/videos/out/raw/game.mkv'
ANIM  = ROOT / 'web/e2e/fixtures/sample-anim.gif'
DECGIF = ROOT / 'docs/wiki/assets/game/status-decoding-anim.gif'
GAME_EP03 = ROOT / 'docs/videos/out/raw/game-ep03.mkv'   # real in-game wall of animated arts

# Capture markers (offsets into game-ep03.mkv), from scripts/gen-game-capture.py run.
MK = {}
_mk = ROOT / 'docs/videos/out/raw/markers-ep03.txt'
if _mk.exists():
    for _l in _mk.read_text().split('\n'):
        _p = _l.split()
        if len(_p) == 2: MK[_p[0]] = float(_p[1])

def run(*args):
    r = subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', *map(str, args)])
    if r.returncode != 0: sys.exit(f'ffmpeg failed: {args}')

def probe(path):
    return float(subprocess.check_output(['ffprobe', '-v', 'quiet', '-show_entries',
        'format=duration', '-of', 'csv=p=0', str(path)]).strip())

def broll(name):
    return next(BROLL.glob(f'*{name}*/video.webm'))

def marks(name):
    return json.load(open(MARKS / f'ep03-{name}-marks.json'))

# ── Narration synth (same chain as ep01/ep02: DECtalk Perfect Paul, else espeak) ─
DECTALK = os.environ.get('DECTALK_DIR')
if DECTALK and not (Path(DECTALK) / 'say').exists():
    DECTALK = None
ESPEAK = os.environ.get('ESPEAK_NG') or shutil.which('espeak-ng')
VOICE = '--no-voice' not in sys.argv and (DECTALK or ESPEAK) is not None

# Caption keeps the printed form; the synthesizer gets a pronounceable rewrite.
SPOKEN = [
    ('AV1', 'A V one'),
    ('GIFs', 'giffs'), ('GIF', 'giff'),
    ('n-th', 'enth'),
    ('DECODING', 'decoding'),
    ('—', ', '), ('–', ' '),
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
imp   = broll('ep03-import-drop');    imp_d   = probe(imp)
strip = broll('ep03-frame-strip');    strip_d = probe(strip)
dly   = broll('ep03-frame-delays');   dly_d   = probe(dly)
ops   = broll('ep03-frame-ops');      ops_d   = probe(ops)
thin  = broll('ep03-thin-stride');    thin_d  = probe(thin)
prev  = broll('ep03-export-preview'); prev_d  = probe(prev);  prev_m  = marks('preview')
loss  = broll('ep03-lossy-toggle');   loss_d  = probe(loss);  loss_m  = marks('lossy')
stat  = broll('ep03-export-stats');   stat_d  = probe(stat)

loss_split = min(loss_d - 0.5, loss_m['recomputed'] + 3.0)   # toggle+1st recompute | nudge+2nd

# ── Segment plan ──────────────────────────────────────────────────────────────
# (kind, src, dur, cues)
#   card  — CARDS/card-<name>.png (holds; stretches to fit narration)
#   broll — (webm, off, dur) slice of a recording
#   anim  — (gif, px) looped in an item-frame border
#   sync  — (gif, px) two synced copies side by side
#   grid  — (gif, px) 2x2 synced tiling
#   game  — (start, slice_len) sped-up slice of game.mkv (speed derived to ≈narration)
# Cue text is BOTH narration and burned caption, verbatim from the script
# (authoring ★ marks and `code` backticks stripped for the caption).
SEGS = [
    ('gamevid', (MK['cold-a'], MK['cold-b']), None, [
        "That's a vanilla map. On a vanilla server. It's playing a GIF. So is every one of these. Let me explain."]),
    ('card', CARDS / 'card-ep03-title.png', 5.0, [
        "Episode three. Your art starts moving, and it moves the same way for everyone watching it. "
        "We will get to why that is hard, and why it is solved."]),
    ('broll', (imp, 0.0, imp_d), None, [
        "Drop an animated GIF into the web editor. The import page previews the first frame and warns "
        "you that every frame gets quantized when you proceed, so a long GIF takes a moment. Everything "
        "else is exactly episode one: palette, dither, adjustments, per frame."]),
    ('broll', (strip, 0.0, strip_d), None, [
        "Proceed, and the editor grows a frame strip along the bottom. Scrub it, play it with spacebar. "
        "It shows the frame count and the delay on the current frame. Every frame is a full static image "
        "you can paint, and the tools from episode two all still work, one frame at a time."]),
    ('broll', (dly, 0.0, dly_d), None, [
        "Each frame carries its own delay in milliseconds. Nudge one frame, or hit all to stamp one delay "
        "across the whole animation."]),
    ('broll', (ops, 0.0, ops_d), None, [
        "The frame operations: clone a frame, insert a blank one, delete, and move frames left or right. "
        "This is where you make the loop seamless, so the last frame flows into the first."]),
    ('broll', (thin, 0.0, thin_d), None, [
        "If the GIF is too long, two thinning tools. Stride keeps every n-th frame and accumulates the "
        "delays it removes, so total runtime holds. Skip drops every n-th frame and merges the delay into "
        "its neighbor."]),
    ('card', CARDS / 'card-ep03-budget.png', 4.0, [
        "A single map frame is sixteen thousand three hundred and eighty-four bytes. One tile of map data "
        "carries, compressed, at most about fifteen thousand. One frame barely fits, and sixty do not,"]),
    ('card', CARDS / 'card-ep03-codec.png', 4.0, [
        "unless you notice that frames repeat, and reach for a video codec. Loominary encodes your frames "
        "as AV1, the codec behind YouTube, losslessly, over the palette indices. It is automatic, and runs "
        "only when it beats plain compression."]),
    ('broll', (prev, max(0.0, prev_m['playing'] - 0.6), prev_d - max(0.0, prev_m['playing'] - 0.6)), None, [
        "The same AV1 decoder runs in your browser preview and inside the game. The preview at the right is "
        "not a mockup of the result. It is the result. What you see is what every player gets."]),
    ('broll', (loss, 0.0, loss_split), None, [
        "Sometimes lossless can't win. A heavily dithered GIF is full of noise, and noise is exactly what a "
        "codec cannot compress. So you flip on Lossy animation. The animation then ships as lossy AV1 colour, "
        "re-quantized to the palette on decode. You get a Quality slider and a readout: roughly what percent "
        "of pixels differ from the original."]),
    ('broll', (loss, loss_split, loss_d - loss_split), None, [
        "Most of those differing pixels are invisible dither shifts, so trust the preview, not the number."]),
    ('broll', (stat, 0.0, stat_d), None, [
        "Budget lever one, before you touch lossy: fewer distinct colours, the single biggest win. Lever two, "
        "counter-intuitively: do not dither your imports for animation. Dither noise is the codec's enemy. Let "
        "lossy mode handle the gradients instead."]),
    ('gamevid', (MK['mount-a'], MK['mount-b']), None, [
        "In game, it is episode one again, at speed, once per piece. Place the platform, scan it with an empty "
        "map, lock it at a cartography table, and frame it."]),
    ('anim', (DECGIF, 500), None, [
        "A heavy animation is not instant. The map paints a live decode bar: DECODING, a green fill, a "
        "percentage, then it starts to play. That bar is the decoder working off the render thread."]),
    ('gamevid', (MK['show-a'], MK['show-b']), None, [
        "And this is the whole point. A wall of vanilla maps, every one of them moving. Different sizes, "
        "different palettes, full colour next to map colour. No server plugin, no permissions, just the mod, "
        "and the mod is free."]),
    ('gamevid', (MK['play-a'], MK['play-b']), None, [
        "Playback runs on the wall clock, real-world milliseconds, not game ticks. That is what lets a fast "
        "animation flip frames quicker than Minecraft's fifty-millisecond tick, instead of crawling at twenty "
        "frames a second. And because it is wall-clock, every viewer lands on the same frame at the same "
        "instant, whoever is watching."]),
    ('gamevid', (MK['wide-a'], MK['wide-b']), None, [
        "Split your art across several tiles and it holds together too. Every tile of the grid advances as one "
        "group, so a big animation never tears across the seams."]),
    ('card', CARDS / 'card-ep03-end.png', 6.0, [
        "Next episode: art spread across many tiles, and what happens when one tile's bytes refuse to fit."]),
]

OUT.mkdir(parents=True, exist_ok=True); TMP.mkdir(exist_ok=True)

# Synthesize narration first: card/generated durations stretch to fit their lines.
vo = {}      # (seg_idx, cue_idx) -> (wav_path, duration)
if VOICE:
    for i, (_k, _s, _d, cues) in enumerate(SEGS):
        for j, cue in enumerate(cues):
            w = TMP / f'vo{i:02d}_{j}.wav'
            say(cue, w)
            vo[(i, j)] = (w, probe(w))

def cue_slot(i, j, cue):
    reading = max(2.2, len(cue) / 16)
    return max(reading, vo[(i, j)][1] + 0.5) if (i, j) in vo else reading

# ── Build normalized clips ────────────────────────────────────────────────────
NORM_V = ['-r', '30', '-c:v', 'libx264', '-preset', 'medium', '-crf', '18', '-pix_fmt', 'yuv420p']
BG = '0x0b1020'
FRAME_BG = '0x0e1524'
SCALE = f'scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color={BG}'

def framed(px):
    b = px + 34
    return f"scale={px}:{px}:flags=neighbor,pad={b}:{b}:17:17:color={FRAME_BG},pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color={BG}"

clips = []
for i, (kind, src, dur, cues) in enumerate(SEGS):
    clip = TMP / f'seg{i:02d}.mp4'
    need = sum(cue_slot(i, j, c) for j, c in enumerate(cues)) + 0.8
    if kind == 'broll':
        path, off, d = src
        vf = SCALE
        pad = max(0.0, need - d)
        if pad: vf += f',tpad=stop_mode=clone:stop_duration={pad:.3f}'
        run('-ss', f'{off:.3f}', '-t', f'{d:.3f}', '-i', path, '-vf', vf, *NORM_V, '-an', clip)
    elif kind == 'card':
        run('-loop', '1', '-t', f'{max(dur, need):.3f}', '-i', src, '-vf', SCALE, *NORM_V, '-an', clip)
    elif kind == 'anim':
        gif, px = src
        run('-ignore_loop', '0', '-i', gif, '-t', f'{need:.3f}', '-vf', framed(px), *NORM_V, '-an', clip)
    elif kind == 'sync':
        gif, px = src
        b = px + 26
        cell = (f"scale={px}:{px}:flags=neighbor,pad={b}:{b}:13:13:color={FRAME_BG}")
        fc = (f"[0:v]{cell}[l];[1:v]{cell}[r];[l][r]hstack=inputs=2,"
              f"pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color={BG}[v]")
        run('-ignore_loop', '0', '-i', gif, '-ignore_loop', '0', '-i', gif, '-t', f'{need:.3f}',
            '-filter_complex', fc, '-map', '[v]', *NORM_V, '-an', clip)
    elif kind == 'grid':
        gif, px = src
        fc = (f"[0:v]scale={px}:{px}:flags=neighbor,split=4[a][b][c][d];"
              f"[a][b]hstack[t];[c][d]hstack[m];[t][m]vstack,"
              f"pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color={BG}[v]")
        run('-ignore_loop', '0', '-i', gif, '-t', f'{need:.3f}',
            '-filter_complex', fc, '-map', '[v]', *NORM_V, '-an', clip)
    elif kind == 'game':
        start, slice_len = src
        speed = max(1.0, slice_len / max(need, 0.1))
        vf = f'{SCALE},setpts=PTS/{speed:.4f}'
        run('-ss', f'{start:.3f}', '-t', f'{slice_len:.3f}', '-i', GAME, '-vf', vf, *NORM_V, '-an', clip)
        d_have = probe(clip)
        if d_have < need:   # hold the last frame under any trailing narration
            held = TMP / f'seg{i:02d}h.mp4'
            run('-i', clip, '-vf', f'tpad=stop_mode=clone:stop_duration={need - d_have:.3f}',
                *NORM_V, '-an', held)
            held.replace(clip)
    elif kind == 'gamevid':   # real in-game footage (game-ep03.mkv), compressed to fit narration
        start, end = src
        avail = max(0.1, end - start)
        speed = max(1.0, avail / max(need, 0.1))
        vf = f'{SCALE},setpts=PTS/{speed:.4f}'
        run('-ss', f'{start:.3f}', '-t', f'{avail:.3f}', '-i', GAME_EP03, '-vf', vf, *NORM_V, '-an', clip)
        d_have = probe(clip)
        if d_have < need:
            held = TMP / f'seg{i:02d}h.mp4'
            run('-i', clip, '-vf', f'tpad=stop_mode=clone:stop_duration={need - d_have:.3f}',
                *NORM_V, '-an', held)
            held.replace(clip)
    clips.append(clip)

# ── Captions + narration timeline (identical guardrails to ep01/ep02) ──────────
def fmt_ts(t):
    ms = int(round(t * 1000)); h, ms = divmod(ms, 3600000); m, ms = divmod(ms, 60000); s, ms = divmod(ms, 1000)
    return f'{h:02d}:{m:02d}:{s:02d},{ms:03d}'

# Burned captions must never exceed ONE row (the voice is slow enough to read a
# single phrase at a time). Split each cue into short word-boundary chunks that
# fit one line at the caption font, shown sequentially across the cue's window.
MAX_CAP_CHARS = 48
def wrap_chunks(text, max_chars=MAX_CAP_CHARS):
    words, lines, cur = text.split(), [], ''
    for w in words:
        if cur and len(cur) + 1 + len(w) > max_chars:
            lines.append(cur); cur = w
        else:
            cur = f'{cur} {w}'.strip()
    if cur: lines.append(cur)
    return lines

# srt   = full-sentence sidecar (authoritative for reading / YouTube upload)
# burn  = chunked, one-row-at-a-time, used only for the burned-in subtitles
srt, burn, cue_events, t0 = [], [], [], 0.0
srt_n = burn_n = 1
vo_cursor = 0.0
VO_GAP = 0.35
for i, ((kind, src, dur, cues), clip) in enumerate(zip(SEGS, clips)):
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

srt_path = OUT / 'ep03-animated-art.srt'
srt_path.write_text('\n'.join(srt))
burn_path = TMP / 'burn.srt'
burn_path.write_text('\n'.join(burn))

# ── Concat video; audio = music bed + narration ───────────────────────────────
concat = TMP / 'concat.txt'
concat.write_text(''.join(f"file '{c}'\n" for c in clips))
silent = TMP / 'video.mp4'
run('-f', 'concat', '-safe', '0', '-i', concat, '-c', 'copy', silent)
total = probe(silent)

def asset(name):
    base = Path.home() / '.gradle/caches/fabric-loom/assets'
    index = next((base / 'indexes').glob('1.21.4*.json'))
    h = json.load(open(index))['objects'][f'minecraft/sounds/{name}.ogg']['hash']
    return base / 'objects' / h[:2] / h

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

has_nvenc = subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error',
    '-f', 'lavfi', '-i', 'testsrc2=size=256x256:rate=30', '-t', '0.2',
    '-c:v', 'h264_nvenc', '-f', 'null', '-'], capture_output=True).returncode == 0
VCODEC = (['-c:v', 'h264_nvenc', '-preset', 'p6', '-rc', 'vbr', '-cq', '23',
           '-b:v', '0', '-maxrate', '12M', '-bufsize', '24M']
          if has_nvenc else ['-c:v', 'libx264', '-preset', 'slow', '-crf', '20'])

final = OUT / 'ep03-animated-art.mp4'
run(*inputs, '-filter_complex', ';'.join(filters + [
        f"[0:v]subtitles={burn_path}:force_style='{sub_style}'[vout]"]),
    '-map', '[vout]', '-map', '[aout]',
    '-r', '30', *VCODEC, '-pix_fmt', 'yuv420p',
    '-c:a', 'aac', '-b:a', '160k', '-movflags', '+faststart', final)

print(f'{final}  ({probe(final):.1f}s, {final.stat().st_size/1e6:.1f} MB)'
      + ('' if VOICE else '  [no narration]'))
print(f'{srt_path}  ({srt_n - 1} cues, {burn_n - 1} burned rows)')
