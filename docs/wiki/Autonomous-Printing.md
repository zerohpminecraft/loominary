# Autonomous printing

`/loominary walk print` turns carpet placement into a spectator sport: your player walks along the platform area and places every carpet from the Litematica placement, band by band, hands-free — including inventory restocks from chests you set up in advance.

## Setup

1. Install **[IceTank's litematica-printer](https://github.com/IceTank/litematica-printer)** alongside Litematica. Loominary drives it via its toggle — you never configure the printer directly.
2. Load and position your carpet schematic as a Litematica **placement** ([placement guide](In-Game-Placement)).
3. Stock chests near the platform with the carpets the schematic needs. `/loominary carpets catalogue` scans and remembers which chest holds which color.
4. Stand at the starting corner of the platform.

## Printing

```
/loominary walk print
```

The mod:

- walks you forward in a duty cycle tuned to the printer's placement rate,
- prints the band of carpet columns around you as you move (`/loominary walk print <width>` overrides the band width),
- turns at the end of each pass and starts the next band,
- **restocks automatically** — `/loominary carpets fill` behavior kicks in at the catalogued chests when a color runs out,
- recovers missed cells: any gap left behind (lag, collisions) is revisited before the band is considered done,
- stops itself when the placement is complete.

Progress reports arrive in chat per band. Stop anytime with `/loominary walk print stop` — or `/loominary stop`, the global kill-switch for every Loominary automation (walk, print, fill, catalogue).

## Tuning

| Command | Effect |
|---|---|
| `/loominary walk` | plain duty-cycle auto-walk (no printing) — toggle |
| `/loominary walk <on> <off>` | set walk/pause tick timings for the duty cycle |
| `/loominary walk print <width>` | print with a specific band width (1–64) |
| `/loominary walk printer on\|off` | verbose printer debug logging |

## Notes

- The printer places blocks as fast as the server accepts them; pacing adapts rather than pushing into rejection.
- If you alt-tab, printing continues — the walker doesn't need window focus.
- Check your server's rules regarding automation before using this on shared servers.
