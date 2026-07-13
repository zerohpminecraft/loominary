#!/usr/bin/env python3
"""Assembles Episode 1 (docs/videos/ep01-first-map-art.md) from generated assets.

Inputs (produce first):
  docs/videos/out/raw/game.mkv + markers.txt   scripts/game-video.sh
  web/e2e/media/.pw-artifacts/**/video.webm    cd web && npm run broll
  docs/videos/out/card-*.png                   rendered by the cards step
  docs/wiki/assets/**                          the committed wiki stills

Output: docs/videos/out/ep01/ep01-first-map-art.mp4 (captions burned in, game audio)
        docs/videos/out/ep01/ep01-first-map-art.srt (sidecar, same cues)

Audio is real in-game audio: each game segment keeps its own sound, and a quiet
ambience bed (the recording's dedicated still segment) runs under everything.
There is no narration track; the episode script plays as captions.

  python3 docs/videos/tools/assemble-ep01.py
"""
import json, math, subprocess, sys
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

# ── Game recording markers ────────────────────────────────────────────────────
markers = {}
for line in (RAW / 'markers.txt').read_text().split('\n'):
    if line.strip():
        name, off = line.rsplit(' ', 1)
        markers[name] = float(off)

def game_window(seg, pad_in=0.3, pad_out=0.3):
    a, b = markers[f'{seg}-a'] + pad_in, markers[f'{seg}-b'] - pad_out
    return a, b - a

# ── Segment plan: (kind, source, seg-duration hint, caption cues) ─────────────
# kind: game (slice of game.mkv, keeps audio) | broll (slice, silent)
#       | card/still (image, silent). Cue text spreads across the segment.
wiz = broll('wizard'); wiz_d = probe(wiz)
adj = broll('adjustments'); adj_d = probe(adj)
tools = broll('editor-tools'); tools_d = probe(tools)

SEGS = [
    # For game segments the third field is a SPEED factor (the llvmpipe client ticks
    # slower than real time, so camera dollies are shot long and timelapsed here).
    ('game', 'reveal', 4.0, [
        "This is a Minecraft map. A completely vanilla map, on a completely vanilla server.",
        "It's showing a full-color image. Any friend with the same mod sees it too.",
    ]),
    ('card', CARDS / 'card-title.png', 6.5, [
        "The tool is Loominary: a client-side Fabric mod, plus a web editor that runs entirely in your browser.",
    ]),
    ('card', CARDS / 'card-install.png', 6.0, [
        "Install is the usual Fabric routine: Loominary, Fabric API, and Litematica into your mods folder.",
    ]),
    ('broll', (wiz, 0.0, min(11.0, wiz_d * 0.4)), None, [
        "Open the web editor and drop in your image.",
        "The preview is the exact result, quantized to Minecraft's real map palette.",
    ]),
    ('broll', (adj, 1.0, min(10.0, adj_d - 1)), None, [
        "The palette is muted, so a small saturation boost usually helps.",
        "Dithering hides the gaps in smooth gradients; sharp edges stay crisp.",
    ]),
    ('broll', (tools, 1.0, min(7.5, tools_d - 1)), None, [
        "Step two is a full pixel editor, if anything needs touching up.",
    ]),
    ('broll', (wiz, wiz_d * 0.62, min(9.0, wiz_d * 0.38)), None, [
        "Step three: export. Carpet colors carry most of the bytes; banners catch any overflow.",
        "Hit Export ZIP.",
    ]),
    ('card', CARDS / 'card-files.png', 7.0, [
        "Two files matter: the state JSON goes into your config folder, the litematic into your schematics folder.",
    ]),
    ('game', 'status', None, [
        "In game, /loominary status confirms the mod loaded it.",
    ]),
    ('game', 'plat', 4.5, [
        "Place the platform: a 128 by 128 sheet of carpet.",
        "There's a mod feature that walks out and places every carpet for you. That's another video.",
    ]),
    ('game', 'scan', None, [
        "Stand on the platform and use an empty map.",
        "That snapshot is the trick: the carpet colors just became data on the map.",
    ]),
    ('game', 'dec', None, [
        "Lock the map so it never redraws, then frame it.",
        "The mod reads the data back off the map, and paints the real image.",
        "Anyone with Loominary sees this. Anyone without sees an abstract carpet pattern.",
    ]),
    ('still', SHOTS_WEB / 'import-gif.png', 4.0, ["Animated GIFs."]),
    ('still', SHOTS_WEB / 'export-multitile.png', 4.0, ["Wall-sized murals."]),
    ('still', SHOTS_GAME / 'status-locked.png', 4.0, ["Password-locked art. All coming up in this series."]),
    ('card', CARDS / 'card-end.png', 6.0, [
        "Web editor and wiki linked below. Go make something.",
    ]),
]

# ── Build normalized clips ────────────────────────────────────────────────────
OUT.mkdir(parents=True, exist_ok=True); TMP.mkdir(exist_ok=True)
NORM_V = ['-r', '30', '-c:v', 'libx264', '-preset', 'medium', '-crf', '18', '-pix_fmt', 'yuv420p']
SCALE = 'scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=0x0b1020'

clips = []       # (path, duration, has_audio)
for i, (kind, src, dur, _cues) in enumerate(SEGS):
    clip = TMP / f'seg{i:02d}.mp4'
    if kind == 'game':
        start, d = game_window(src)
        speed = dur or 1.0
        if speed != 1.0:
            # Timelapse the shot; camera moves are silent, so drop audio (bed covers it).
            run('-ss', f'{start:.3f}', '-t', f'{d:.3f}', '-i', RAW / 'game.mkv',
                '-vf', f'{SCALE},setpts=PTS/{speed}', *NORM_V, '-an', clip)
            clips.append((clip, d / speed, False))
        else:
            run('-ss', f'{start:.3f}', '-t', f'{d:.3f}', '-i', RAW / 'game.mkv',
                '-vf', SCALE, *NORM_V, '-c:a', 'aac', '-b:a', '192k', clip)
            clips.append((clip, d, True))
    elif kind == 'broll':
        path, off, d = src
        run('-ss', f'{off:.3f}', '-t', f'{d:.3f}', '-i', path,
            '-vf', SCALE, *NORM_V, '-an', clip)
        clips.append((clip, d, False))
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
        clips.append((clip, dur, False))
    else:  # card — plain hold
        run('-loop', '1', '-t', f'{dur:.3f}', '-i', src, '-vf', SCALE, *NORM_V, '-an', clip)
        clips.append((clip, dur, False))

# ── Captions: spread each segment's cues over its measured duration ──────────
def fmt_ts(t):
    ms = int(round(t * 1000)); h, ms = divmod(ms, 3600000); m, ms = divmod(ms, 60000); s, ms = divmod(ms, 1000)
    return f'{h:02d}:{m:02d}:{s:02d},{ms:03d}'

srt, cue_n, t0 = [], 1, 0.0
for (kind, src, dur, cues), (clip, d, _a) in zip(SEGS, clips):
    d_real = probe(clip)
    weights = [max(len(c), 40) for c in cues]
    total_w = sum(weights)
    lead = min(0.4, d_real * 0.05)
    t = t0 + lead
    for cue, w in zip(cues, weights):
        cd = max(2.2, (d_real - 2 * lead) * w / total_w)
        end = min(t + cd, t0 + d_real - 0.1)
        srt.append(f'{cue_n}\n{fmt_ts(t)} --> {fmt_ts(end)}\n{cue}\n')
        cue_n += 1; t = end
    t0 += d_real

srt_path = OUT / 'ep01-first-map-art.srt'
srt_path.write_text('\n'.join(srt))

# ── Concat video; audio = ambience bed + game-segment sound in place ──────────
concat = TMP / 'concat.txt'
concat.write_text(''.join(f"file '{c}'\n" for c, _d, _a in clips))
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

inputs, filters, mixins = ['-i', silent], [], []
idx = 1
if captured_ok:
    # Real captured game audio: ambience bed + each game segment's own sound in place.
    bed = TMP / 'bed.wav'
    run('-ss', f'{amb_start:.3f}', '-t', f'{amb_d:.3f}', '-i', RAW / 'game.mkv',
        '-vn', '-af', 'afade=t=in:d=1,afade=t=out:st=' + f'{amb_d-1:.3f}' + ':d=1', bed)
    inputs += ['-stream_loop', '-1', '-t', f'{total:.3f}', '-i', bed]
    filters.append(f'[{idx}:a]volume=0.4[bed]'); mixins.append('[bed]'); idx += 1
    t_cursor = 0.0
    for (kind, _src, _dur, _cues), (clip, _d, has_audio) in zip(SEGS, clips):
        d_real = probe(clip)
        if has_audio:
            inputs += ['-i', clip]
            delay_ms = int(t_cursor * 1000)
            filters.append(f'[{idx}:a]adelay={delay_ms}|{delay_ms},volume=1.0[g{idx}]')
            mixins.append(f'[g{idx}]'); idx += 1
        t_cursor += d_real
else:
    # The headless client initialises no audio device (known Xvfb limitation), so the
    # capture is digital silence. Rebuild the soundtrack from the game's own assets:
    # the music this creative world would be playing, plus the item-frame click at the
    # decode moment. Same sounds, deterministic timing.
    print('capture silent — building soundtrack from vanilla sound assets')
    inputs += ['-i', asset('music/game/clark')]
    filters.append(f'[{idx}:a]volume=0.45,afade=t=in:d=2,'
                   f'apad,atrim=0:{total:.3f},afade=t=out:st={total-2.5:.3f}:d=2.5[bed]')
    mixins.append('[bed]'); idx += 1
    # Frame click when the scanned map goes into the item frame (start of the decode
    # segment; the useEntity fires ~1 s in).
    t_cursor, t_dec = 0.0, None
    for (kind, src, _dur, _cues), (clip, _d, _a) in zip(SEGS, clips):
        if kind == 'game' and src == 'dec':
            t_dec = t_cursor
        t_cursor += probe(clip)
    if t_dec is not None:
        delay_ms = int((t_dec + 1.0) * 1000)
        inputs += ['-i', asset('entity/itemframe/add_item1')]
        filters.append(f'[{idx}:a]adelay={delay_ms}|{delay_ms},volume=0.9[click]')
        mixins.append('[click]'); idx += 1
filters.append(f"{''.join(mixins)}amix=inputs={len(mixins)}:normalize=0[aout]")

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

print(f'{final}  ({probe(final):.1f}s, {final.stat().st_size/1e6:.1f} MB)')
print(f'{srt_path}  ({cue_n - 1} cues)')
