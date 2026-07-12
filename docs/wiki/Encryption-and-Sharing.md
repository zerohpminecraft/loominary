# Encryption & sharing

## Sharing your art

Everything Loominary decodes lives in vanilla data (map NBT and world blocks), so sharing is built-in:

- **In-world**: anyone running Loominary within 32 blocks of your framed, locked map sees the art. Nothing to send them.
- **As files**: hand someone your export ZIP — they copy `loominary_state.json` to their `config/`, place the schematics, and can reproduce the entire build themselves. The state JSON also **re-imports into the web editor**, so others can remix your work.
- **Archiving from the world**: see [Archiving map art](Stealing-Map-Art) for capturing existing framed art.

Every payload embeds its **title** and **author** (set on the export page), plus a CRC32 integrity check.

## Password-protecting your art

On the [export page](Web-Editor-Export), add one or more passwords to encrypt the payload:

- Encryption is **AES-256-GCM** with keys derived per password via PBKDF2. Each password is an independent key slot — you can give different groups different passwords and revoke by re-exporting without a slot. Overhead is small (≈290 bytes + 76 per password slot per tile).
- Viewers need the mod **and** a matching password:

  ```
  /loominary password add <password>
  ```

  Passwords persist across sessions (`/loominary password list`, `remove <pw>`, `clear`).
- Without a matching password, the map shows a lock screen instead of the art:

  ![PASSWORD REQUIRED status screen](assets/game/status-locked.png)

  Adding the right password makes the art appear on the next scan — no rebuilding needed.

To everyone without the mod, an encrypted map is indistinguishable from a normal carpet-colored map. There is no plaintext fallback: the payload itself is ciphertext.

## Notes

- Passwords are not recoverable — if all holders lose it, re-export from your saved web-editor session and rebuild the banners (carpet stays the same only if the payload bytes match, so expect to re-place).
- Encryption covers the image data and metadata. The fact that *something* Loominary-encoded exists is visible to any mod user (that's what the lock screen is).
