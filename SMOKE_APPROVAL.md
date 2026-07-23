# Loominary smoke-test release approval

Last run: 2026-07-23T20:58:59Z at rev `d665b49-dirty`

Watch each video and confirm the in-game behavior is correct, then change its
`- [ ]` to `- [x]`. A tick is dropped automatically when the revision changes,
so re-recorded footage must be re-reviewed.

- [ ] **import-basic** — PASS — rev `d665b49-dirty` — assertions: `PASS 3/3` — video: [import-basic.mp4](docs/videos/out/smoke/import-basic.mp4)
- [ ] **preview-map** — PASS — rev `d665b49-dirty` — assertions: `PASS 3/3` — video: [preview-map.mp4](docs/videos/out/smoke/preview-map.mp4)

Enforced by `scripts/smoke-approve.sh` — a release must not proceed until that
exits 0. Videos live under `docs/videos/out/smoke/` and are git-ignored; this manifest is
committed so sign-off is auditable.
