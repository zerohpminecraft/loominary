# Ep. 06: Encrypted Map Art & Sharing

**Target length:** ~5 min · **Audience:** eps. 1–5 viewers; the "lock it down and share it" one

## Packaging

- **Title:** Password-Locked Minecraft Map Art (AES-encrypted, in vanilla data)
- **Thumbnail text:** "🔒 WRONG PASSWORD" over the padlock lock-screen map
- **Description:**
  > Loominary can encrypt map art with AES-256-GCM, hidden inside ordinary banner names and carpet colors. Add one password or several, gate who can read it, and share the whole project as a file. Wiki: https://github.com/zerohpminecraft/loominary/wiki/Encryption-and-Sharing

## Setup checklist (for the shoot)

- [ ] A single 1.21.4 client with Loominary (one-account, sequential reveal: locked padlock first, then add the password and rescan)
- [ ] An exported piece to lock, placed and scannable
- [ ] The export page open in the web editor for the password section

## Narration script + shot list

Cues are narration AND burned captions, verbatim. Register: dry and plain, concise and factual, no in-group asides. Burned captions are one row at a time. No em-dashes in cues.

| Time | On screen | Narration |
|---|---|---|
| 0:00 | **manual/game (REAL):** a framed map showing the padlock lock screen | "This map is locked. Same server, same kind of blocks as any other art, but without the password it stays a padlock." |
| 0:25 | **generated:** broll, export page password section | "On the export page you add a password, or several. Under the hood it is AES-256-GCM, and each password unlocks the same art through its own slot. Give one to your guild and another to officers, and remove either by exporting again without it. To push that change to art you have already placed, you re-export from your original project and replace it, so always keep that unlocked original." |
| 1:15 | **manual/game (REAL):** placing the encrypted art (fast recap of the print) | "You build it exactly like any other art. The ciphertext rides the same carpet colors and banner names, so to players without the mod it is indistinguishable from any Loominary map." |
| 1:50 | **manual/game (REAL):** the framed map still showing the padlock; the action bar shows title, author, and the encrypted tag | "With the mod but no password, you get this. A padlock, plus the title and author in plain text, but not the art. The mod knows something is there. It just cannot read it." |
| 2:20 | **manual/game (REAL):** typing `/loominary password add`, the art appears on the next scan | "Run loominary password add, and on the next scan, there it is. Your passwords are saved and carry across sessions." |
| 2:50 | **generated:** broll, export ZIP contents; state file re-import into the editor | "Sharing goes past the world itself. The export ZIP is the whole project. Hand someone the state file and the schematics and they can rebuild it anywhere, and that same state file re-imports into the web editor so they can remix it. Every payload carries your author name and title when you set them." |
| 3:50 | **manual/game (REAL):** the decode-toggle hotkey flipping a map between decoded art and the raw carpet view | "There is also a decode toggle. Bind it in the controls menu, and it flips every map between the real art and exactly what the unmodded world sees, so you can check what you are actually revealing." |
| 4:20 | **generated:** end card (`ep06-end`) | "Two cautions. If you forget a password there is no reset, so keep your web editor project saved. And your stored passwords sit in a local config file in plain text, so treat your game folder as trusted. Next up: hiding a whole image in nothing but banner names." |

## Cards

- **ep06 end card (`ep06-end`):** two-line safety recap (no password reset, keep your project; passwords stored locally in plain text) and a tease for the finale (how it works). Series-consistent style.

## B-roll manifest

- manual/game (REAL): the locked padlock map, the placement recap, the `password add` reveal moment, the decode-toggle flip.
- generated: broll of the export-page password section and the export-ZIP + state re-import flow (`web/e2e/broll.spec.ts`, `-g ep06`); end card via `record-cards.mjs`.

## Production notes

- **Register:** dry and plain, no in-group asides. Burned captions one row at a time (`wrap_chunks`). No em-dashes in cues. No "mural."
- **Capture approach (decided): one account, sequential** (per [[feedback_real_capture]]). The headless harness (`DocsDriver` + `scripts/capture-ep05.sh` style wrapper) places an **encrypted** LOOM platform, scans it into a framed map that paints the padlock lock screen, then runs `/loominary password add <pw>` and rescans so the art appears. The decode-toggle beat binds `key.loominary.decode_toggle` and calls `MapBannerDecoder.toggle()` to flip raw/decoded. No second account.
- **Fact-check (verified against current code, 2026-07-15):**
  - Encryption is **AES-256-GCM** (256-bit key, 128-bit tag). Envelope scheme: a random 256-bit data key encrypts the payload, and that data key is wrapped once per password slot. Key derivation is **PBKDF2-HMAC-SHA256, 100,000 iterations, 16-byte random salt per slot** (`MapEncryption.java`; web parity `web/src/encryption.ts`). Narration keeps it to "AES-256-GCM" + "each password is its own slot" (accurate without over-specifying).
  - **Multiple passwords / slots** are real; "revoke" means re-export without that slot and re-place, not a live revoke of already-placed art.
  - **Lock screen** = `PlaceholderArt.locked()` gold padlock + "PASSWORD REQUIRED"; it also shows the plaintext author/title and an `§8[enc]` tag (envelope header is plaintext), so "knows something is there, can't read it" is exact.
  - **Command tree:** `/loominary password [list|add <pw>|remove <pw>|clear|encrypt <pw>|encrypt off]`. Passwords persist in `config/loominary_passwords.json` (**stored in cleartext** — hence the honest caution in the end card). `add` clears the fail-cache and forces a rescan next tick.
  - **Decode toggle** keybinding `key.loominary.decode_toggle` ("Toggle Loominary decoding (raw/decoded)") is **unbound by default** — narration says "bind it in the controls menu," never "press F-x."
  - **Export ZIP** = `loominary_state.json` + carpet `.litematic` schematic(s) + preview + README; the state JSON re-imports in the web editor. Author (≤16 B) and title (≤64 B) are optional, hence "when you set them."
  - **Recovery:** no user-facing password reset; narration says "there is no reset" (accurate) and does not claim absolute unrecoverability.
- **DECtalk pronunciations:** "AES-256-GCM" → read as "A E S two fifty six, G C M"; "loominary password add" spelled as words. Copy/adapt `assemble-ep05.py` → `assemble-ep06.py` (keep one-row captions, `gamevid` kind + cards).
