# Loominary smoke-test release approval

Last run: 2026-07-23T21:10:13Z — source fingerprint `2d0ceafe20a7`

Watch each video and confirm the in-game behavior is correct, then change its
`- [ ]` to `- [x]`. Ticking this file does not invalidate the sign-off, but a tick
is dropped as soon as the mod source, a scenario, or a smoke script changes, so
re-recorded footage must be re-reviewed.

- [ ] **import-basic** — PASS — fingerprint `2d0ceafe20a7` — assertions: `PASS 3/3` — video: [import-basic.mp4](docs/videos/out/smoke/import-basic.mp4)
- [ ] **preview-map** — PASS — fingerprint `2d0ceafe20a7` — assertions: `PASS 3/3` — video: [preview-map.mp4](docs/videos/out/smoke/preview-map.mp4)

Enforced by `scripts/smoke-approve.sh` — a release must not proceed until that
exits 0. Videos live under `docs/videos/out/smoke/` and are git-ignored; this manifest is
committed so sign-off is auditable.
