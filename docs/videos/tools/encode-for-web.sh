#!/usr/bin/env bash
#
# encode-for-web.sh — produce GitHub-friendly web encodes + poster stills from the
# FINISHED episode finals in docs/videos/out/epNN/. This does NOT touch the
# assemble-epNN.py producers; it operates purely on their finished mp4 output.
#
# Why this exists / what "web-friendly" means here:
#   GitHub only plays a video INLINE when the file is served from its own
#   user-attachments CDN (github.com/user-attachments/assets/<hash>), which is
#   minted by drag-dropping the file into an issue/PR/wiki editor. Hand-written
#   <video> tags are stripped by the wiki/markdown sanitizer, and relative or
#   raw.githubusercontent paths do NOT auto-embed. So the deliverables are:
#     1. small H.264 mp4s (< ~10 MB, the user-attachments inline-player cap) that
#        a human uploads once to get CDN URLs — these are the inline players;
#     2. poster stills committed to the wiki (docs/wiki/assets/video/) that act as
#        the graceful fallback: a clickable thumbnail linking to the video.
#
# Encoding target: H.264 High profile, yuv420p, capped to 720p, AAC, +faststart
# (moov atom at the front so the player can start before the whole file loads).
#
# Usage:  docs/videos/tools/encode-for-web.sh [epNN ...]
#         (no args = all episodes)
#
set -euo pipefail

# Repo root = two levels up from this script (docs/videos/tools/ -> repo root).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OUT_DIR="$ROOT/docs/videos/out/web"          # git-ignored (docs/videos/out/ is)
POSTER_DIR="$ROOT/docs/wiki/assets/video"    # TRACKED -> published to the wiki

# Encode knobs
CRF=27             # first-pass 720p CRF; higher = smaller. 27 keeps most eps ~5-9 MB.
PRESET=slow
MAXH=720           # default height cap (720p)
POSTER_T=3         # seconds into the video to grab the poster (past title-card fade-in)
POSTER_W=960       # poster width; height auto (keeps posters ~60-150 KB as JPG)
# Hard size cap. GitHub's inline-video attachment limit is 10 MB on a FREE plan
# (100 MB on paid). We keep every file < CAP_BYTES so it uploads inline on ANY plan.
CAP_BYTES=$(( 9500 * 1000 ))   # 9.5 MB, leaving headroom under the 10 MB cap
AUDIO_KBPS=96

# episode -> final-mp4-path (+ per-ep height override in HMAX)
declare -A FINAL TITLE HMAX
HMAX[ep05]=540     # long + heavy motion: 540p so a sub-10 MB budget still looks clean
FINAL[ep01]="ep01/ep01-first-map-art.mp4";        TITLE[ep01]="Your First Minecraft Map Art in 10 Minutes"
FINAL[ep02]="ep02/ep02-web-editor.mp4";           TITLE[ep02]="The Web Editor Deep-Dive"
FINAL[ep03]="ep03/ep03-animated-art.mp4";         TITLE[ep03]="Animated Map Art"
FINAL[ep04]="ep04/ep04-murals-and-mux.mp4";       TITLE[ep04]="Mux & Multi-Tile Art"
FINAL[ep05]="ep05/ep05-autonomous-printing.mp4";  TITLE[ep05]="Fully Autonomous Printing"
FINAL[ep06]="ep06/ep06-encryption-sharing.mp4";   TITLE[ep06]="Encrypted Map Art & Sharing"
FINAL[ep07]="ep07/ep07-banners.mp4";              TITLE[ep07]="The Banner Channel"
FINAL[ep08]="ep08/ep08-how-it-works.mp4";         TITLE[ep08]="Tips, Troubleshooting & How It Works"

EPS=("$@")
if [ "${#EPS[@]}" -eq 0 ]; then
  EPS=(ep01 ep02 ep03 ep04 ep05 ep06 ep07 ep08)
fi

mkdir -p "$OUT_DIR" "$POSTER_DIR"

printf '%-6s %-10s %-10s %s\n' "EP" "SRC" "WEB" "POSTER"
for ep in "${EPS[@]}"; do
  src="$ROOT/docs/videos/out/${FINAL[$ep]}"
  if [ ! -f "$src" ]; then
    echo "!! $ep: source not found: $src" >&2
    continue
  fi
  web="$OUT_DIR/${ep}-web.mp4"
  poster="$POSTER_DIR/${ep}.jpg"
  h="${HMAX[$ep]:-$MAXH}"
  vf="scale=-2:'min($h,ih)'"

  # --- pass 1: CRF encode (720p H.264 High, yuv420p, AAC, faststart) ---
  ffmpeg -nostdin -y -loglevel error -i "$src" \
    -vf "$vf" \
    -c:v libx264 -profile:v high -pix_fmt yuv420p -crf "$CRF" -preset "$PRESET" \
    -c:a aac -b:a "${AUDIO_KBPS}k" -ac 2 \
    -movflags +faststart \
    "$web"

  # --- size cap: if over CAP_BYTES, redo as 2-pass at a computed target bitrate ---
  bytes=$(stat -c%s "$web")
  if [ "$bytes" -gt "$CAP_BYTES" ]; then
    dur=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$src")
    # target total kbps for CAP_BYTES, minus audio, = video kbps
    vkbps=$(python3 -c "print(max(120, int($CAP_BYTES*8/1000/$dur) - $AUDIO_KBPS))")
    echo "   $ep over cap ($(numfmt --to=iec $bytes)); 2-pass @ ${vkbps}k video" >&2
    plog="$OUT_DIR/.${ep}-2pass"
    ffmpeg -nostdin -y -loglevel error -i "$src" -vf "$vf" \
      -c:v libx264 -profile:v high -pix_fmt yuv420p -b:v "${vkbps}k" \
      -preset "$PRESET" -pass 1 -passlogfile "$plog" -an -f mp4 /dev/null
    ffmpeg -nostdin -y -loglevel error -i "$src" -vf "$vf" \
      -c:v libx264 -profile:v high -pix_fmt yuv420p -b:v "${vkbps}k" \
      -preset "$PRESET" -pass 2 -passlogfile "$plog" \
      -c:a aac -b:a "${AUDIO_KBPS}k" -ac 2 -movflags +faststart "$web"
    rm -f "${plog}"-*.log "${plog}"-*.log.mbtree
  fi

  # --- poster still (fallback thumbnail) ---
  ffmpeg -nostdin -y -loglevel error -ss "$POSTER_T" -i "$src" \
    -frames:v 1 -vf "scale=${POSTER_W}:-2" -q:v 3 "$poster"

  srcsz=$(du -h "$src" | cut -f1)
  websz=$(du -h "$web" | cut -f1)
  postsz=$(du -h "$poster" | cut -f1)
  printf '%-6s %-10s %-10s %s\n' "$ep" "$srcsz" "$websz" "$postsz"
done

echo
echo "Web mp4s (git-ignored, upload these to user-attachments): $OUT_DIR"
echo "Posters (commit these):                                   $POSTER_DIR"
