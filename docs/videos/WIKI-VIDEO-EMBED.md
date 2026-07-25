# Embedding the episode videos in the GitHub wiki

This is a maintainer runbook. It is **not** synced to the wiki (only `docs/wiki/`
is). It explains how the finished episode videos are made web-playable and how to
get them showing **inline on the GitHub wiki** without committing any video
binary to git.

## TL;DR of the mechanism (why it has to be done this way)

GitHub renders a video player inline **only when the video is served from
GitHub's own attachment CDN** — a `https://github.com/user-attachments/assets/<hash>`
URL (older form: `user-images.githubusercontent.com`). Everything else fails:

| Approach | Inline player on GitHub? |
|---|---|
| Bare `user-attachments` URL on its own line | **Yes** — auto-embeds as a `<video>` player |
| `<video src="…user-attachments…">` (explicit tag) | **Yes** — `<video>`/`<source>` survive the sanitizer *only* for a GitHub-hosted `src` |
| `<video src="…">` with any non-GitHub / placeholder src | No — sanitizer strips the tag (nested `<a><img>` fallback remains) |
| Relative path to a committed `.mp4` (`assets/…/ep01.mp4`) | No — downloads / does nothing |
| `raw.githubusercontent.com/…mp4` | No — served as `application/octet-stream`, downloads |
| **Release asset** `…/releases/download/…mp4` | No reliable inline playback — treated as a download, not a CDN video host |
| YouTube / Vimeo `<iframe>` | No — `<iframe>` is stripped (and out of scope anyway) |

So: user-attachments is the one GitHub-native mechanism that yields inline
playback, and its URLs are created by **uploading the file through the web UI**,
never by a git commit. That is why the mp4s stay out of git and only the small
poster stills are committed.

Sources: GitHub docs "Attaching files"
(https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/attaching-files),
community discussion #133813
(https://github.com/orgs/community/discussions/133813),
"HTML tags you can use on GitHub"
(https://gist.github.com/seanh/13a93686bf4c2cb16e658b3cf96807f2).

### Size cap

GitHub's video-attachment limit is **10 MB on a free plan**, 100 MB on paid.
`encode-for-web.sh` keeps every file **under 9.5 MB** so it uploads inline on any
plan. Formats accepted: `.mp4`, `.mov`, `.webm`; H.264 is recommended for
cross-browser playback (that is what we produce).

## Step 1 — build the web encodes + posters

```bash
docs/videos/tools/encode-for-web.sh          # all eight; or pass ep01 ep05 …
```

Outputs:
- `docs/videos/out/web/epNN-web.mp4` — 720p (ep05 540p) H.264 High / yuv420p /
  AAC / `+faststart`, each < 9.5 MB. **Git-ignored** (`docs/videos/out/` is) — these
  are upload inputs, not committed.
- `docs/wiki/assets/video/epNN.jpg` — poster still (~20–90 KB each). **Tracked**;
  these are the fallback thumbnails and are safe to commit (images, ~0.5 MB total).

## Step 2 — upload the mp4s and collect their URLs

You need a GitHub UI that accepts drag-and-drop uploads. Either works:

- **The wiki page editor** (keeps everything in one place):
  `https://github.com/zerohpminecraft/loominary/wiki/Video-Series/_edit`
- **A throwaway issue** (do not submit it): open a new issue, use the comment box.

For each episode:
1. Drag `docs/videos/out/web/epNN-web.mp4` into the box.
2. Wait for the upload to finish; GitHub inserts a
   `https://github.com/user-attachments/assets/<hash>` URL.
3. Copy that URL.

## Step 3 — fill the tokens

In **`docs/wiki/Video-Series.md`** replace both occurrences of each
`VIDEO_URL_EPnn` token (the `<video src>` and the fallback `<a href>`/watch link)
with that episode's URL. Save. Because the wiki is auto-synced from `docs/wiki/`
(`.github/workflows/wiki.yml`), committing the filled page to `master` republishes
the wiki with working inline players.

If you edited directly in the wiki web editor in step 2, mirror the same URLs back
into `docs/wiki/Video-Series.md` so the tracked source of truth doesn't get
overwritten on the next sync.

### Fallback behavior (already built into the page)

Each block is a `<video>` with a nested `<a><img>` poster. When the token is
filled with a GitHub URL, GitHub shows the player. In any renderer that strips
`<video>` (including GitHub while the token is still a placeholder), the nested
poster thumbnail shows and links to the video. So the page never looks broken,
filled or not.
