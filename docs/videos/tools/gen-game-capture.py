#!/usr/bin/env python3
"""Emits the in-game capture step scripts (DocsDriver JSON) for the video series.

  python3 docs/videos/tools/gen-game-capture.py ep03   → docs/tools/game-ep03.json

A wall of animated map arts is built by, for each art: placing an item frame,
scanning a map, attaching it, then `loominary load <name>` + `loominary preview`
(frameCount>1 registers an AnimatedMapState, so the framed map plays). The
placement itself is the "mounting" footage; camera dollies afterward give the
cold-open reveal, the showcase pan, and close-ups. Marker screenshots (mk-*) mark
the cut points; scripts/game-video.sh turns their mtimes into markers.txt offsets.
"""
import json, sys

Z_WALL = -6
Z_STAND = -3
Y = -60          # player feet; frames land at y=-59

# (x, state-name, tiles)  — single-tile arts spaced 2 apart, the 2-wide art last.
ARTS = [
    (-6, 'ball',   1),
    (-4, 'plasma', 1),
    (-2, 'rings',  1),
    (0,  'heavy',  1),
    (2,  'srgb',   1),
    (4,  'wide',   2),   # tiles at x=4 and x=5
]

def face(x, pitch=3):
    return {"tp": [x + 0.5, Y, Z_STAND, 180, pitch]}

def place_frame_and_map(x):
    # Creative does NOT consume the map when it is attached to a frame, so the
    # same filled_map would otherwise be re-attached to every frame (all showing
    # one art). Clear the lingering filled_map first so each scan yields a fresh,
    # distinct map id. The already-framed maps are entities and are unaffected.
    return [
        {"cmd": "clear @s minecraft:filled_map"}, {"waitTicks": 2},
        face(x), {"select": "minecraft:item_frame"}, {"useBlock": True},
        {"select": "minecraft:map"}, {"useItem": True}, {"waitTicks": 6},
        {"select": "minecraft:filled_map"}, {"useEntity": True}, {"waitTicks": 4},
    ]

def art_steps(x, name, tiles):
    s = []
    if tiles == 1:
        s += place_frame_and_map(x)
        s += [{"cmd": f"loominary load {name}"}, {"waitTicks": 4}, face(x),
              {"cmd": "loominary preview"}, {"waitTicks": 6}]
    else:  # 2-wide: two adjacent frames, load once, preview tile 0 then tile 1
        s += place_frame_and_map(x)
        s += place_frame_and_map(x + 1)
        s += [{"cmd": f"loominary load {name}"}, {"waitTicks": 4},
              face(x), {"cmd": "loominary preview"}, {"waitTicks": 4},
              {"cmd": "loominary tile next"}, {"waitTicks": 3},
              face(x + 1), {"cmd": "loominary preview"}, {"waitTicks": 6}]
    return s

def dolly(x0, x1, z, y, pitch, n, settle=3):
    """A smooth camera truck from x0 to x1 at (z, y) looking south, n steps."""
    out = []
    for i in range(n + 1):
        t = i / n
        x = x0 + (x1 - x0) * t
        out.append({"tp": [round(x, 3), y, z, 180, pitch]})
        out.append({"waitTicks": settle})
    return out

def setup_wall():
    steps = []
    for c in ["gamerule doDaylightCycle false", "gamerule doWeatherCycle false",
              "time set noon", "weather clear",
              f"fill -8 -60 {Z_WALL} 8 -54 {Z_WALL} minecraft:smooth_stone",
              f"fill -8 -60 {Z_WALL-1} 8 -54 {Z_WALL-1} minecraft:stone_bricks",
              "clear", "give @s minecraft:item_frame 64", "give @s minecraft:map 64"]:
        steps.append({"cmd": c})
    steps.append({"waitTicks": 10})
    steps.append({"hud": False})
    return steps

def ep03():
    steps = setup_wall()

    # ── Mount the wall (this sequence is the "mounting" footage) ──
    steps.append({"screenshot": "mk-mount-a"})
    for (x, name, tiles) in ARTS:
        steps += art_steps(x, name, tiles)
    steps.append({"screenshot": "mk-mount-b"})

    # Let every animation settle/advance before the reveal.
    steps.append({"waitTicks": 20})

    # ── Cold open: pull back and pan the whole wall ──
    steps.append({"screenshot": "mk-cold-a"})
    steps += dolly(-7, 7, z=4.0, y=-57.5, pitch=6, n=36, settle=2)
    steps.append({"screenshot": "mk-cold-b"})

    # ── Showcase: a slower pan back the other way ──
    steps.append({"screenshot": "mk-show-a"})
    steps += dolly(7, -7, z=3.0, y=-58.0, pitch=4, n=48, settle=2)
    steps.append({"screenshot": "mk-show-b"})

    # ── Close-up: the 2-wide multi-tile art (tiles in sync) ──
    steps.append({"screenshot": "mk-wide-a"})
    steps.append({"tp": [4.9, -59.2, -2.2, 180, -1]})
    steps.append({"waitTicks": 90})
    steps.append({"screenshot": "mk-wide-b"})

    # ── Close-up: a single art playing (for the wall-clock/sync beat) ──
    steps.append({"screenshot": "mk-play-a"})
    steps.append({"tp": [-4.0, -59.2, -2.2, 180, -1]})
    steps.append({"waitTicks": 90})
    steps.append({"screenshot": "mk-play-b"})

    steps.append({"exit": True})
    return steps

def ep04():
    """A 2-wide animated art plus two blank donor maps beside it (the mux payoff:
    the donors look blank but carry overflow), and a multi-tile close-up."""
    steps = setup_wall()
    steps.append({"screenshot": "mk-place-a"})
    # The 2-wide animated art (tiles at x=-1 and x=0).
    steps += place_frame_and_map(-1)
    steps += place_frame_and_map(0)
    steps += [{"cmd": "loominary load wide"}, {"waitTicks": 4},
              face(-1), {"cmd": "loominary preview"}, {"waitTicks": 4},
              {"cmd": "loominary tile next"}, {"waitTicks": 3},
              face(0), {"cmd": "loominary preview"}, {"waitTicks": 6}]
    # Two blank donor maps beside it (scanned + framed, never previewed → blank).
    steps += place_frame_and_map(2)
    steps += place_frame_and_map(3)
    steps.append({"screenshot": "mk-place-b"})
    steps.append({"waitTicks": 20})
    # Wide shot: the art next to its blank donor maps.
    steps.append({"screenshot": "mk-donors-a"})
    steps.append({"tp": [1.0, -57.5, 4.0, 180, 6]})
    steps.append({"waitTicks": 80})
    steps.append({"screenshot": "mk-donors-b"})
    # Close-up on the multi-tile art (two tiles in lockstep).
    steps.append({"screenshot": "mk-grid-a"})
    steps.append({"tp": [-0.1, -59.2, -2.2, 180, -1]})
    steps.append({"waitTicks": 90})
    steps.append({"screenshot": "mk-grid-b"})
    steps.append({"exit": True})
    return steps

if __name__ == '__main__':
    which = sys.argv[1] if len(sys.argv) > 1 else 'ep03'
    steps = {'ep03': ep03, 'ep04': ep04}[which]()
    out = f'docs/tools/game-{which}.json'
    with open(out, 'w') as f:
        json.dump(steps, f, indent=1)
    print(f'wrote {out}  ({len(steps)} steps)')
