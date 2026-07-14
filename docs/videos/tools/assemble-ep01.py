#!/usr/bin/env python3
"""Assembles Episode 1 (docs/videos/ep01-first-map-art.md) from generated assets.

Inputs (produce first):
  docs/videos/out/raw/game.mkv + markers.txt   scripts/game-video.sh
  web/e2e/media/.pw-artifacts/**/video.webm    cd web && npm run broll
  docs/videos/out/card-*.png                   rendered by the cards step
  docs/wiki/assets/**                          the committed wiki stills

Output: docs/videos/out/ep01/ep01-first-map-art.mp4 (captions burned in)
        docs/videos/out/ep01/ep01-first-map-art.srt (sidecar, same cues)

Narration is synthesized per cue with espeak-ng (the formant synthesizer — the same
lineage as the DECtalk voices; deliberately retro-robotic). Set ESPEAK_NG to the
binary and ESPEAK_DATA_PATH to its data dir if they aren't on PATH; without a
working espeak-ng the video builds silent-narration (captions + music only).
Music bed and the item-frame click come from captured game audio when the recording
has any (desktop sessions), else from the game's own sound assets (headless clients
initialise no audio device).

  python3 docs/videos/tools/assemble-ep01.py [--codec av1] [--no-voice]
"""
import json, os, shutil, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RAW  = ROOT / 'docs/videos/out/raw'
OUT  = ROOT / 'docs/videos/out/ep01'
TMP  = OUT / 'tmp'
BROLL = ROOT / 'web/e2e/media/.pw-artifacts'
SHOTS_WEB  = ROOT / 'docs/wiki/assets/web'
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

# ── Narration synth (espeak-ng, Hawking-adjacent formant voice) ───────────────
# Narrator, in order of preference:
#   1. DECtalk "Perfect Paul" (DECTALK_DIR → the dectalk/dectalk release dir with `say`
#      + DECtalk.conf; `say` must run from that dir to find its dictionaries) — the
#      genuine article, and the most intelligible formant synth ever shipped.
#   2. espeak-ng (ESPEAK_NG or on PATH) — same lineage, buzzier.
# Both get the same cleanup pass: sub-bass trim, presence boost where consonants
# live, and per-cue loudness normalization so no line lands quieter than another.
DECTALK = os.environ.get('DECTALK_DIR')
if DECTALK and not (Path(DECTALK) / 'say').exists():
    DECTALK = None
ESPEAK = os.environ.get('ESPEAK_NG') or shutil.which('espeak-ng')
VOICE = '--no-voice' not in sys.argv and (DECTALK or ESPEAK) is not None

def say(text, path):
    raw = path.with_suffix('.raw.wav')
    if DECTALK:
        subprocess.run(['./say', '-pre', '[:phoneme on][:rate 170]',
                        '-a', text, '-fo', str(raw)], check=True, cwd=DECTALK)
        post = ('highpass=f=90,equalizer=f=2600:t=q:w=1.2:g=3,'
                'loudnorm=I=-18:TP=-2:LRA=7')
    else:
        subprocess.run([ESPEAK, '-v', 'en-us+m3', '-s', '132', '-p', '30', '-g', '8',
                        '-a', '100', '-w', str(raw), text], check=True)
        post = ('highpass=f=90,lowpass=f=9000,equalizer=f=2800:t=q:w=1.2:g=4,'
                'loudnorm=I=-18:TP=-2:LRA=7')
    run('-i', raw, '-af', post, '-ar', '48000', path)
    raw.unlink()

# ── Game recording markers ────────────────────────────────────────────────────
markers = {}
for line in (RAW / 'markers.txt').read_text().split('\n'):
    if line.strip():
        name, off = line.rsplit(' ', 1)
        markers[name] = float(off)

def game_window(seg, pad_in=0.3, pad_out=0.3):
    a, b = markers[f'{seg}-a'] + pad_in, markers[f'{seg}-b'] - pad_out
    return a, b - a

# ── Segment plan ──────────────────────────────────────────────────────────────
# kind: game (slice of game.mkv, third field = timelapse speed) | broll (slice,
# silent) | card / still (image; duration stretches to fit the narration).
# The cue text is BOTH the narration and the burned captions.
wiz = broll('wizard'); wiz_d = probe(wiz)
adj = broll('adjustments'); adj_d = probe(adj)
tools = broll('editor-tools'); tools_d = probe(tools)
gifroll = broll('animated-GIF'); gif_d = probe(gifroll)

SEGS = [
    ('game', 'reveal', 4.0, [
        "This is a Minecraft map. Completely vanilla. Completely legal.",
        "It is showing a full-color image, because I put one there. Anyone with the mod sees it. Anyone without sees decorative carpet. Their loss.",
    ]),
    ('card', CARDS / 'card-title.png', 6.5, [
        "The tool is called Loominary. A client-side mod, plus a web editor. Nothing is uploaded. No server plugins. No permission slips.",
    ]),
    ('card', CARDS / 'card-install.png', 6.0, [
        "Installation: three jars, one mods folder. This is the hardest step, and it is not hard.",
    ]),
    ('broll', (wiz, 0.0, min(11.0, wiz_d * 0.4)), None, [
        "Drop your image into the editor. The preview is the exact in-game result, quantized to Minecraft's map palette. All 248 colors of it.",
    ]),
    ('broll', (adj, 1.0, min(10.0, adj_d - 1)), None, [
        "The palette is muted, so boost the saturation a little.",
        "The dithering is doing the heavy lifting here. It does not complain. Learn from it.",
    ]),
    ('broll', (tools, 1.0, min(7.5, tools_d - 1)), None, [
        "Step two is a full pixel editor. Touch up whatever bothers you. I am a video, not your supervisor.",
    ]),
    ('broll', (wiz, wiz_d * 0.62, min(9.0, wiz_d * 0.38)), None, [
        "Step three. Export. Carpet colors carry the bytes; banners catch the overflow. Press the button.",
    ]),
    ('card', CARDS / 'card-files.png', 7.0, [
        "Two files. JSON to the config folder. Litematic to the schematics folder. Do not swap them. People swap them.",
    ]),
    ('game', 'status', None, [
        "In game, loominary status confirms the load. It loaded. It always loads.",
    ]),
    ('game', 'plat', 4.5, [
        "Now, the platform: a 128-wide sheet of carpet, plus one polite row of blocks up north so the map shades correctly.",
        "Yes, there is a feature that walks out and places every carpet for you. That's another video. Contain yourself.",
    ]),
    ('game', 'scan', None, [
        "Stand on the platform. Use an empty map.",
        "Congratulations: the carpet is now data.",
    ]),
    ('game', 'dec', None, [
        "Lock the map. Frame the map.",
        "The mod reads the data off the map and paints the real image. No servers were consulted.",
        "Everyone with Loominary sees art. Everyone else sees modern art.",
    ]),
    ('broll', (gifroll, 1.0, min(6.0, gif_d - 1)), None, [
        "It does animated GIFs.",
    ]),
    ('still', SHOTS_WEB / 'export-multitile.png', 4.0, ["Wall-sized murals."]),
    ('still', SHOTS_GAME / 'status-locked.png', 4.5, [
        "And password-locked art, for your secrets. All coming up in this series.",
    ]),
    ('card', CARDS / 'card-end.png', 6.0, [
        "Editor and wiki are linked below. Go make something. The carpet is waiting.",
    ]),
]

OUT.mkdir(parents=True, exist_ok=True); TMP.mkdir(exist_ok=True)

# Synthesize narration first: card/still durations stretch to fit their lines.
vo = {}      # (seg_idx, cue_idx) -> (wav_path, duration)
if VOICE:
    for i, (_k, _s, _d, cues) in enumerate(SEGS):
        for j, cue in enumerate(cues):
            w = TMP / f'vo{i:02d}_{j}.wav'
            say(cue, w)
            vo[(i, j)] = (w, probe(w))

def cue_slot(i, j, cue):
    """Display/narration slot for one cue."""
    reading = max(2.2, len(cue) / 16)
    return max(reading, vo[(i, j)][1] + 0.5) if (i, j) in vo else reading

# ── Build normalized clips ────────────────────────────────────────────────────
NORM_V = ['-r', '30', '-c:v', 'libx264', '-preset', 'medium', '-crf', '18', '-pix_fmt', 'yuv420p']
SCALE = 'scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=0x0b1020'

clips = []       # (path, has_audio)
for i, (kind, src, dur, cues) in enumerate(SEGS):
    clip = TMP / f'seg{i:02d}.mp4'
    # GUARDRAIL: every segment must be long enough to hold ALL of its narration —
    # a cue that outruns its segment spills the voice into the next segment's
    # lines. Cards/stills stretch by definition; game/broll clips get their last
    # frame held (tpad clone) to make up any shortfall.
    need = sum(cue_slot(i, j, c) for j, c in enumerate(cues)) + 0.8
    if kind in ('card', 'still'):
        dur = max(dur, need)
    if kind == 'game':
        start, d = game_window(src)
        speed = dur or 1.0
        out_d = d / speed
        pad = max(0.0, need - out_d)
        if speed != 1.0:
            # Timelapse the shot; camera moves are silent, so drop audio (bed covers it).
            vf = f'{SCALE},setpts=PTS/{speed}'
            if pad: vf += f',tpad=stop_mode=clone:stop_duration={pad:.3f}'
            run('-ss', f'{start:.3f}', '-t', f'{d:.3f}', '-i', RAW / 'game.mkv',
                '-vf', vf, *NORM_V, '-an', clip)
            clips.append((clip, False))
        else:
            vf = SCALE
            extra = ([] if not pad else
                     ['-af', f'apad=pad_dur={pad:.3f}', '-t', f'{out_d + pad:.3f}'])
            if pad: vf += f',tpad=stop_mode=clone:stop_duration={pad:.3f}'
            run('-ss', f'{start:.3f}', '-t', f'{d:.3f}', '-i', RAW / 'game.mkv',
                '-vf', vf, *extra, *NORM_V, '-c:a', 'aac', '-b:a', '192k', clip)
            clips.append((clip, True))
    elif kind == 'broll':
        path, off, d = src
        vf = SCALE
        pad = max(0.0, need - d)
        if pad: vf += f',tpad=stop_mode=clone:stop_duration={pad:.3f}'
        run('-ss', f'{off:.3f}', '-t', f'{d:.3f}', '-i', path,
            '-vf', vf, *NORM_V, '-an', clip)
        clips.append((clip, False))
    elif kind == 'still':
        # Ken Burns push-in. Two zoompan traps handled here: (1) `d` means "output
        # frames PER INPUT FRAME", so the filter must be fed exactly ONE input frame
        # (no -loop) or every looped frame spawns its own d-frame zoom and a 4 s
        # still balloons into minutes; (2) the crop window is computed in integer
        # pixels, so zooming on a 4× supersampled canvas is what keeps a slow zoom
        # from jittering.
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

# ── Captions + narration timeline ─────────────────────────────────────────────
def fmt_ts(t):
    ms = int(round(t * 1000)); h, ms = divmod(ms, 3600000); m, ms = divmod(ms, 60000); s, ms = divmod(ms, 1000)
    return f'{h:02d}:{m:02d}:{s:02d},{ms:03d}'

srt, cue_events, cue_n, t0 = [], [], 1, 0.0
vo_cursor = 0.0   # GUARDRAIL: narration is strictly sequential — never overlapping
VO_GAP = 0.35
for i, ((kind, src, dur, cues), (clip, _a)) in enumerate(zip(SEGS, clips)):
    d_real = probe(clip)
    lead = min(0.4, d_real * 0.05)
    t = t0 + lead
    for j, cue in enumerate(cues):
        cd = cue_slot(i, j, cue)
        # Never start a cue before the previous voice line has finished.
        t = max(t, vo_cursor + VO_GAP if vo_cursor else t)
        end = min(t + cd, t0 + d_real - 0.1)
        if end <= t:   # segment overfull despite the clip guardrail — surface it
            print(f'WARNING: cue {cue_n} ("{cue[:40]}…") does not fit segment {i}; '
                  f'voice slides past the segment boundary')
            end = t + cd
        srt.append(f'{cue_n}\n{fmt_ts(t)} --> {fmt_ts(end)}\n{cue}\n')
        if (i, j) in vo:
            cue_events.append((t + 0.1, vo[(i, j)][0]))
            vo_cursor = t + 0.1 + vo[(i, j)][1]
        cue_n += 1; t = end
    t0 += d_real

# Hard assertion: no two narration clips may overlap on the final timeline.
for (a_t, a_w), (b_t, _b_w) in zip(cue_events, cue_events[1:]):
    a_end = a_t + probe(a_w)
    assert a_end <= b_t + 0.01, f'narration overlap: clip ending {a_end:.2f}s vs next start {b_t:.2f}s'

srt_path = OUT / 'ep01-first-map-art.srt'
srt_path.write_text('\n'.join(srt))

# ── Concat video; audio = music bed (ducked) + narration + game sound ─────────
concat = TMP / 'concat.txt'
concat.write_text(''.join(f"file '{c}'\n" for c, _a in clips))
silent = TMP / 'video.mp4'
run('-f', 'concat', '-safe', '0', '-i', concat, '-c', 'copy', silent)
total = probe(silent)

amb_start, amb_d = game_window('amb', 1.0, 1.0)

def is_silent(path, start, d):
    out = subprocess.run(['ffmpeg', '-ss', f'{start:.3f}', '-t', f'{d:.3f}', '-i', str(path),
                          '-af', 'volumedetect', '-vn', '-f', 'null', '-'],
                         capture_output=True, text=True).stderr
    for line in out.split('\n'):
        if 'max_volume' in line:
            return float(line.rsplit(' ', 2)[1]) < -70
    return True

captured_ok = not is_silent(RAW / 'game.mkv', amb_start, amb_d)

def asset(name):
    """Resolve a vanilla sound asset from the loom asset cache by index name."""
    base = Path.home() / '.gradle/caches/fabric-loom/assets'
    index = next((base / 'indexes').glob('1.21.4*.json'))
    h = json.load(open(index))['objects'][f'minecraft/sounds/{name}.ogg']['hash']
    return base / 'objects' / h[:2] / h

BED_VOL = 0.11 if VOICE else 0.45   # duck the music well under the narration
inputs, filters, mixins = ['-i', silent], [], []
idx = 1
if captured_ok:
    bed = TMP / 'bed.wav'
    run('-ss', f'{amb_start:.3f}', '-t', f'{amb_d:.3f}', '-i', RAW / 'game.mkv',
        '-vn', '-af', 'afade=t=in:d=1,afade=t=out:st=' + f'{amb_d-1:.3f}' + ':d=1', bed)
    inputs += ['-stream_loop', '-1', '-t', f'{total:.3f}', '-i', bed]
    filters.append(f'[{idx}:a]volume={BED_VOL + 0.2}[bed]'); mixins.append('[bed]'); idx += 1
    t_cursor = 0.0
    for (kind, _src, _dur, _cues), (clip, has_audio) in zip(SEGS, clips):
        d_real = probe(clip)
        if has_audio:
            inputs += ['-i', clip]
            delay_ms = int(t_cursor * 1000)
            filters.append(f'[{idx}:a]adelay={delay_ms}|{delay_ms},volume=1.0[g{idx}]')
            mixins.append(f'[g{idx}]'); idx += 1
        t_cursor += d_real
else:
    # Headless clients initialise no audio device, so the capture is digital silence.
    # Rebuild the soundtrack from the game's own assets: the music this creative world
    # would be playing, plus the item-frame click at the decode moment.
    print('capture silent — building soundtrack from vanilla sound assets')
    inputs += ['-i', asset('music/game/clark')]
    filters.append(f'[{idx}:a]volume={BED_VOL},afade=t=in:d=2,'
                   f'apad,atrim=0:{total:.3f},afade=t=out:st={total-2.5:.3f}:d=2.5[bed]')
    mixins.append('[bed]'); idx += 1
    t_cursor, t_dec = 0.0, None
    for (kind, src, _dur, _cues), (clip, _a) in zip(SEGS, clips):
        if kind == 'game' and src == 'dec':
            t_dec = t_cursor
        t_cursor += probe(clip)
    if t_dec is not None:
        delay_ms = int((t_dec + 1.0) * 1000)
        inputs += ['-i', asset('entity/itemframe/add_item1')]
        filters.append(f'[{idx}:a]adelay={delay_ms}|{delay_ms},volume=0.9[click]')
        mixins.append('[click]'); idx += 1

for t_at, wav in cue_events:
    inputs += ['-i', wav]
    delay_ms = int(t_at * 1000)
    filters.append(f'[{idx}:a]adelay={delay_ms}|{delay_ms},volume=1.0[v{idx}]')
    mixins.append(f'[v{idx}]'); idx += 1

# normalize=0 keeps absolute levels, so a limiter guards the sum against clipping.
filters.append(f"{''.join(mixins)}amix=inputs={len(mixins)}:normalize=0,alimiter=limit=0.92[aout]")

sub_style = ('FontName=Ubuntu,Fontsize=16,PrimaryColour=&H00F4F7FF,OutlineColour=&HA0000000,'
             'BorderStyle=1,Outline=2,Shadow=0,MarginV=48')

# Final encode. Default is H.264 via NVENC: plays in every browser GitHub serves the
# wiki to (AV1 playback is still spotty, HEVC-in-MP4 spottier), and this machine's
# Turing GPU encodes it ~40× faster than software AV1. `--codec av1` keeps the
# SVT-AV1 path for when a modern GPU or smaller files matter more than speed.
codec = 'av1' if '--codec' in sys.argv and sys.argv[sys.argv.index('--codec') + 1] == 'av1' else 'h264'
if codec == 'av1':
    VCODEC = ['-c:v', 'libsvtav1', '-preset', '5', '-crf', '32']
else:
    has_nvenc = subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error',
        '-f', 'lavfi', '-i', 'testsrc2=size=256x256:rate=30', '-t', '0.2',
        '-c:v', 'h264_nvenc', '-f', 'null', '-'], capture_output=True).returncode == 0
    VCODEC = (['-c:v', 'h264_nvenc', '-preset', 'p6', '-rc', 'vbr', '-cq', '23',
               '-b:v', '0', '-maxrate', '12M', '-bufsize', '24M']
              if has_nvenc else ['-c:v', 'libx264', '-preset', 'slow', '-crf', '20'])

final = OUT / 'ep01-first-map-art.mp4'
run(*inputs, '-filter_complex', ';'.join(filters + [
        f"[0:v]subtitles={srt_path}:force_style='{sub_style}'[vout]"]),
    '-map', '[vout]', '-map', '[aout]',
    '-r', '30', *VCODEC, '-pix_fmt', 'yuv420p',
    '-c:a', 'aac', '-b:a', '160k', '-movflags', '+faststart', final)

print(f'{final}  ({probe(final):.1f}s, {final.stat().st_size/1e6:.1f} MB)'
      + ('' if VOICE else '  [no narration: espeak-ng not found]'))
print(f'{srt_path}  ({cue_n - 1} cues)')
