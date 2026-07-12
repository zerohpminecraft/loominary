# Ep. 06 — Encrypted Map Art & Sharing

**Target length:** 5 min

## Packaging

- **Title:** Password-Locked Minecraft Map Art (AES-encrypted, in vanilla data)
- **Thumbnail text:** "🔒 WRONG PASSWORD" over the lock-screen map next to the real art
- **Description:**
  > Loominary can encrypt map art with AES-256-GCM — inside vanilla banner names and carpet colors. Who sees what, multiple passwords, and every way to share your art (files, world, remixing). Wiki: https://github.com/zerohpminecraft/loominary/wiki/Encryption-and-Sharing

## Setup checklist

- [ ] Two accounts/instances side by side (one with the password, one without)
- [ ] An export worth "protecting" (guild logo energy)

## Narration script + shot list

| Time | On screen | Narration |
|---|---|---|
| 0:00 | **manual:** split screen — same framed map: lock screen vs the art | "Same map. Same server. Left doesn't know the password. Right does." |
| 0:25 | **generated:** broll — export page password section | "On the export page, add a password — or several. Under the hood it's AES-256-GCM with a key derived from each password, and every password is its own slot: one for the guild, one for officers, revoke either by re-exporting without it." |
| 1:15 | **manual:** placing the encrypted art (fast recap) | "Build it exactly like any other art — the ciphertext travels in the same carpet colors and banner names. To players without the mod, it's indistinguishable from any Loominary map." |
| 1:50 | **manual:** the no-password account looking at the map; still `status-locked.png` | "With the mod but without the password, you get this: a lock screen. The mod knows something's there; it just can't read it." |
| 2:20 | **manual:** `/loominary password add hunter2`, art appears on next scan | "`/loominary password add`, and on the next scan — there it is. Passwords persist across sessions." |
| 2:50 | **generated:** broll — export ZIP contents; state JSON re-import | "Sharing, beyond the world itself: the export ZIP is the whole project. Hand someone the state JSON and schematics and they can rebuild your art anywhere — and the state JSON re-imports into the web editor, so they can remix it too. Every payload carries your author name and title." |
| 3:50 | **manual:** decode-toggle hotkey flipping raw/decoded view | "Party trick: the decode-toggle hotkey shows you exactly what the unmodded world sees. Good for checking what you're actually revealing." |
| 4:20 | end card | "One warning: passwords aren't recoverable — keep your web editor session saved. Next episode: taking art *out* of the world." |

## B-roll manifest

- generated: broll password-section + export-ZIP flows; stills `status-locked.png`, `export-overview.png`
- manual: split-screen comparison, placement recap, password-add reveal moment, decode-toggle flip
