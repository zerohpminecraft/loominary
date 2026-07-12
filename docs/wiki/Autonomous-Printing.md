# Autonomous printing

`/loominary walk print` turns carpet placement into a spectator sport: your player walks the platform area in a serpentine pattern and places every carpet from the Litematica placement — including restocking itself from chests and going back for anything it missed.

## Setup

1. Install **[IceTank's litematica-printer](https://github.com/IceTank/litematica-printer)** alongside Litematica. Loominary toggles the printer itself while printing; you never configure it directly.
2. Load and position your carpet schematic as a Litematica **placement** ([placement guide](In-Game-Placement)).
3. Stock chests near the platform with the needed carpets, then run `/loominary carpets catalogue` — it scans a 48-block radius (±6 vertically) and memorizes which chest holds which color, persisting the map to `loominary_chest_memory.json`.
4. Optionally `/loominary carpets balance` to pre-arrange your inventory to the schematic's material list.
5. Stand at the platform's starting corner and:

```
/loominary walk print
```

## What it does

- **Serpentine bands**: the platform is covered in bands of columns (**default width 5**; override with `/loominary walk print <width>`, 1–64). Rows alternate direction per band so the walk never backtracks.
- **Adaptive pacing**: it looks 5 blocks ahead and slows down for dense regions and color-swap-heavy patches rather than outrunning the printer — placements the server would reject never get attempted. Base rhythm comes from the duty-cycle walker (default 0.5 s forward / 1.0 s pause; tune with `/loominary walk <onTicks> <offTicks>`).
- **Self-restocking**: when a color runs out, it pathfinds (A*, up to 96 blocks) to the catalogued chest, refills, and returns — up to 3 attempts per restock before it asks for help.
- **Missed-cell recovery**: every 8 ticks it re-scans a 12-block radius for holes (lag, collisions, chunk hiccups), darts back to fix them, and will break up to 3 wrong-colored carpets per target to correct mistakes. A band isn't done until it's verified.
- **Completion**: it stops itself when the placement is complete and reports per-band progress in chat along the way. A watchdog caps any run at ~1 hour.

## Controls

| Command | Effect |
|---|---|
| `/loominary walk print [width]` | start (optionally set band width) |
| `/loominary walk print stop` | stop printing |
| `/loominary walk` | plain duty-cycle auto-walk toggle (no printing) |
| `/loominary walk <on> <off>` | walk/pause tick timings (e.g. `10 20` = 0.5 s / 1.0 s) |
| `/loominary walk printer on\|off` | verbose printer debug logging |
| `/loominary stop` | **the global kill switch** — halts print, walk, fill, catalogue |

## Notes

- Alt-tabbing is fine — the walker doesn't need window focus.
- The printer places as fast as the server accepts; the pacing adapts instead of spamming.
- Check your server's rules on automation before using this on shared servers.
