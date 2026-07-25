#!/usr/bin/env python3
"""Assembles Episode 4 (docs/videos/ep05-autonomous-printing.md) from generated assets.

Same architecture as assemble-ep03.py (one-row captions via wrap_chunks + burn.srt;
segment kinds anim/sync/grid/game for the in-game beats produced without a live
capture). The in-game beats are tagged REAL for a re-shoot; until then they use the
generated substitutes below.

Inputs (produce first):
  cd web && npx playwright test --config e2e/playwright.config.ts --project=broll -g ep04
  cd web && node ../docs/videos/tools/record-cards.mjs ep04-title ep04-budget ep04-mux ep04-end

  python3 docs/videos/tools/assemble-ep04.py [--no-voice]
"""
import json, os, shutil, subprocess, sys
from pathlib import Path

ROOT  = Path(__file__).resolve().parents[3]
OUT   = ROOT / 'docs/videos/out/ep05'
TMP   = OUT / 'tmp'
BROLL = ROOT / 'web/e2e/media/.pw-artifacts'
CARDS = ROOT / 'docs/videos/out'
GAME  = ROOT / 'docs/videos/out/raw/game.mkv'
ANIM  = ROOT / 'web/e2e/fixtures/sample-anim.gif'
ANIM_HEAVY = ROOT / 'web/e2e/fixtures/sample-anim-heavy.gif'
GAME_EP05 = ROOT / 'docs/videos/out/raw/game-ep05.mkv'   # real in-game: multi-tile art + blank donor maps

MK = {}
_mk = ROOT / 'docs/videos/out/raw/markers-game-ep05.txt'
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

# ── Narration synth (DECtalk Perfect Paul, else espeak-ng) ─────────────────────
DECTALK = os.environ.get('DECTALK_DIR')
if DECTALK and not (Path(DECTALK) / 'say').exists():
    DECTALK = None
ESPEAK = os.environ.get('ESPEAK_NG') or shutil.which('espeak-ng')
VOICE = '--no-voice' not in sys.argv and (DECTALK or ESPEAK) is not None

SPOKEN = [
    ('AV1', 'A V one'), ('GIFs', 'giffs'), ('GIF', 'giff'),
    ('LOOM', 'loom'), ('litematic', 'lite matic'), ('n-th', 'enth'),
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

# ── Sources: ep05 uses only the real in-game capture (game-ep05.mkv, gamevid
#    kind sliced by markers-ep05.txt) plus cards. No web broll. ──────────────────

# ── Segment plan ──────────────────────────────────────────────────────────────
# (kind, src, dur, cues). Cue text is BOTH narration and caption source.
SEGS = [
    ('gamevid', (MK['print-b'], MK['mid']), None, [
        "I am not touching the keyboard. Watch the ground."]),
    ('card', CARDS / 'card-ep05-title.png', 5.0, [
        "This is Loominary printing a carpet floor by itself. It walks your player, lays every carpet, "
        "restocks when it runs low, and fixes what it misses. One command starts it."]),
    ('card', CARDS / 'card-ep05-mods.png', 5.0, [
        "Before you try this, the one hard requirement. Placement is done by a Litematica printer, and it "
        "has to be the continuous fork, IceTank's, linked below. Base Litematica only places on right-click "
        "and will not lay a floor. Loominary drives the fork for you; you never configure it."]),
    ('gamevid', (MK['setup'] - 2.5, MK['setup'] + 1.5), None, [
        "Setup is the normal build flow. Export the schematic from Loominary, load it in Litematica, and "
        "place the ghost where you want the floor. The printer only prints a placement that exists."]),
    ('gamevid', (MK['chests'] - 2.0, MK['chests'] + 2.0), None, [
        "Then a chest station near the site, stocked with the carpet colours the art uses. The mod pulls "
        "from these, so they need to hold enough."]),
    ('gamevid', (MK['cat-a'], MK['cat-done']), None, [
        "First it takes stock. Before it lays a single carpet, Loominary walks your storage and opens every "
        "chest once, noting which colour lives where. That catalogue is what lets it restock on its own "
        "later, and it saves it, so it only does this the first time."]),
    ('gamevid', (MK['cmd'] - 1.0, MK['restock-a']), None, [
        "Now the whole thing: one command. Walk print. It plans a serpentine path across the unbuilt floor. "
        "It is short on almost every colour, so it goes to the chests it just catalogued, fills up, and then "
        "starts laying. You do not run those steps."]),
    ('gamevid', (MK['print-a'], MK['print-b']), None, [
        "It walks the centre of a band, the printer lays the columns within reach, it turns at the end and "
        "starts the next band. The pacing adapts: it crawls through dense, many-colour work and sprints "
        "across the sparse stretches, so it is not spamming placements the server will reject."]),
    ('gamevid', (MK['print-b'], MK['mid']), None, [
        "Most of a floor is repetition, so this is a timelapse. In real time you would leave it running."]),
    ('gamevid', (MK['restock-a'], MK['restock-b']), None, [
        "Run low on a colour and it does not stop. Using that catalogue, it walks to the chests that hold "
        "what it needs, refills, and carries on from where it left off. This is real carpet leaving your "
        "chests and going into the floor."]),
    ('gamevid', (MK['mid'], MK['restock2']), None, [
        "The printer is a real physics placer, so reach and timing sometimes skip a cell. Before a band "
        "counts as done, it darts back and lays anything it missed. It tries not to leave holes."]),
    ('gamevid', (MK['restock2'], MK['done']), None, [
        "There is no progress bar. The chat is the readout: it tells you it started, when it is restocking, "
        "and when it is done. And it keeps printing while the window is not focused, so you can alt-tab and "
        "let it work."]),
    ('gamevid', (MK['restock2'], MK['done']), None, [
        "One stop command. Loominary stop halts this and every other automation at once."]),
    ('gamevid', (MK['done'], MK['scan']), None, [
        "When the floor is finished it stops itself and says so."]),
    ('card', CARDS / 'card-ep05-caveats.png', 6.0, [
        "The honest limits. It needs the printer fork, a placed schematic, and stocked chests. It walks in "
        "straight lines, so it wants open, flat, line-of-sight floor: walls and corners will defeat it. It "
        "self-heals most misses, but it is not perfect. And server automation rules differ, so check yours "
        "before you run it on someone else's server."]),
    ('gamevid', (MK['scan'], MK['decode-b']), None, [
        "Scan, frame, done. A floor that would have eaten an evening took a coffee break."]),
    # Silent hold on the framed map so the audience watches the animation play (empty hand,
    # frame raised a block for a clear look). Cue-less → duration comes from `dur`.
    ('gamevid', (MK['decode-b'], MK['decode-c']), 3.2, []),
    ('card', CARDS / 'card-ep05-end.png', 6.0, [
        "Next episode: locking your art behind a password."]),
]

OUT.mkdir(parents=True, exist_ok=True); TMP.mkdir(exist_ok=True)

vo = {}
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
    # A cue-less segment is a silent hold (e.g. lingering on the finished map art); its
    # length is taken straight from `dur`. Otherwise fit the narration + a little padding.
    need = float(dur) if (dur and not cues) else sum(cue_slot(i, j, c) for j, c in enumerate(cues)) + 0.8
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
        if d_have < need:
            held = TMP / f'seg{i:02d}h.mp4'
            run('-i', clip, '-vf', f'tpad=stop_mode=clone:stop_duration={need - d_have:.3f}',
                *NORM_V, '-an', held)
            held.replace(clip)
    elif kind == 'gamevid':   # real in-game footage (game-ep05.mkv), compressed to fit narration
        start, end = src
        avail = max(0.1, end - start)
        speed = max(1.0, avail / max(need, 0.1))
        vf = f'{SCALE},setpts=PTS/{speed:.4f}'
        run('-ss', f'{start:.3f}', '-t', f'{avail:.3f}', '-i', GAME_EP05, '-vf', vf, *NORM_V, '-an', clip)
        d_have = probe(clip)
        if d_have < need:
            held = TMP / f'seg{i:02d}h.mp4'
            run('-i', clip, '-vf', f'tpad=stop_mode=clone:stop_duration={need - d_have:.3f}',
                *NORM_V, '-an', held)
            held.replace(clip)
    clips.append(clip)

# ── Captions + narration timeline ─────────────────────────────────────────────
def fmt_ts(t):
    ms = int(round(t * 1000)); h, ms = divmod(ms, 3600000); m, ms = divmod(ms, 60000); s, ms = divmod(ms, 1000)
    return f'{h:02d}:{m:02d}:{s:02d},{ms:03d}'

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

srt_path = OUT / 'ep05-autonomous-printing.srt'
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

final = OUT / 'ep05-autonomous-printing.mp4'
run(*inputs, '-filter_complex', ';'.join(filters + [
        f"[0:v]subtitles={burn_path}:force_style='{sub_style}'[vout]"]),
    '-map', '[vout]', '-map', '[aout]',
    '-r', '30', *VCODEC, '-pix_fmt', 'yuv420p',
    '-c:a', 'aac', '-b:a', '160k', '-movflags', '+faststart', final)

print(f'{final}  ({probe(final):.1f}s, {final.stat().st_size/1e6:.1f} MB)'
      + ('' if VOICE else '  [no narration]'))
print(f'{srt_path}  ({srt_n - 1} cues, {burn_n - 1} burned rows)')
