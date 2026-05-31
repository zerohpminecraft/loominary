/**
 * ExportPage — Step 3 of the wizard.
 *
 * Left:  codec selector · metadata · password · export actions · mux section.
 * Right: animated GIF preview (or static frame) · per-tile data stats.
 */

import { h, Fragment } from 'preact';
import { useState, useEffect, useRef, useCallback } from 'preact/hooks';
import { SchematicViewer3D } from '../SchematicViewer3D.js';

import type { CompositionState }         from '../payload-state.js';
import { downloadState, emptyTileData }  from '../payload-state.js';
import { encodeComposition }              from '../encode.js';
import type { CodecMode }                from '../codec-mode.js';
import {
  CAPACITY, CODEC_LABEL, CODEC_HINT, NEEDS_CARPET, DEFAULT_CODEC,
} from '../codec-mode.js';
import { mapBytesToImageData }           from '../quantize.js';
import {
  budgetStatus, computeMuxAllocation,
  type MuxAllocation,
} from '../mux.js';
import { compress }                      from '../compression.js';
import {
  toBytesV2, toBytesAnimated, crc32,
  FLAG_ALL_SHADES, FLAG_ANIMATED, FLAG_MUX, FLAG_DELTA_FRAMES, FLAG_SPARSE_FRAMES,
} from '../manifest.js';

// ─── Codec display order ──────────────────────────────────────────────────────

const CODEC_ORDER: CodecMode[] = [
  'CARPET_BANNERS_SHADE',
  'CARPET_SHADE_BANNERS',
  'CARPET_BANNERS',
  'CARPET_SHADE',
  'CARPET',
  'BANNER',
];

// ─── Channel constants ────────────────────────────────────────────────────────

const LOOM_HDR     = 16;
const CARPET_MAX   = 8_192;
const SHADE_MAX    = 2_016;
const OVERFLOW_MAX = 5_290;

// ─── Types ────────────────────────────────────────────────────────────────────

interface TileManifest {
  version:     number;
  headerBytes: number;
  flags:       number;
  colorCrc32:  number;
  username:    string | null;
  title:       string | null;
  nonce:       number;
  frameCount:  number;
  loopCount:   number;
  frameDelays: number[];
}

interface TileStat {
  ti:             number;
  tileCol:        number;
  tileRow:        number;
  compressedBytes:number;
  compressedData: Uint8Array;
  manifest:       TileManifest;
}

interface ChannelBreakdown {
  carpetBytes:    number;
  shadeBytes:     number;
  overflowBytes:  number;
  overflowBanners:number;
  bannerCount:    number;
  fits:           boolean;
}

function breakdown(bytes: number, codec: CodecMode): ChannelBreakdown {
  const fits = bytes <= CAPACITY[codec];
  if (codec === 'BANNER') {
    return { carpetBytes:0, shadeBytes:0, overflowBytes:0, overflowBanners:0,
             bannerCount: Math.ceil((bytes + 2) / 84), fits };
  }
  const cargo      = LOOM_HDR + bytes;
  const carpetBytes = Math.max(0, Math.min(cargo, CARPET_MAX) - LOOM_HDR);
  let shadeBytes = 0, overflowBytes = 0, overflowBanners = 0;

  if (codec === 'CARPET') {
    // no shade, no overflow
  } else if (codec === 'CARPET_SHADE') {
    if (cargo > CARPET_MAX) shadeBytes = Math.min(cargo - CARPET_MAX, SHADE_MAX);
  } else if (codec === 'CARPET_BANNERS') {
    if (cargo > CARPET_MAX) overflowBytes = Math.min(cargo - CARPET_MAX, OVERFLOW_MAX);
    if (overflowBytes) overflowBanners = Math.ceil((overflowBytes + 2) / 84);
  } else if (codec === 'CARPET_BANNERS_SHADE') {
    if (cargo > CARPET_MAX)                    overflowBytes = Math.min(cargo - CARPET_MAX, OVERFLOW_MAX);
    if (cargo > CARPET_MAX + OVERFLOW_MAX)     shadeBytes    = Math.min(cargo - CARPET_MAX - OVERFLOW_MAX, SHADE_MAX);
    if (overflowBytes) overflowBanners = Math.ceil((overflowBytes + 2) / 84);
  } else if (codec === 'CARPET_SHADE_BANNERS') {
    if (cargo > CARPET_MAX)               shadeBytes    = Math.min(cargo - CARPET_MAX, SHADE_MAX);
    if (cargo > CARPET_MAX + SHADE_MAX)   overflowBytes = Math.min(cargo - CARPET_MAX - SHADE_MAX, OVERFLOW_MAX);
    if (overflowBytes) overflowBanners = Math.ceil((overflowBytes + 2) / 84);
  }
  return { carpetBytes, shadeBytes, overflowBytes, overflowBanners, bannerCount:0, fits };
}

// ─── ZIP builder (STORE method — no external deps) ───────────────────────────

interface ZipEntry { name: string; data: Uint8Array }

function buildZip(files: ZipEntry[]): Uint8Array {
  const enc = new TextEncoder();

  const crcTable = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let j = 0; j < 8; j++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    crcTable[i] = c >>> 0;
  }
  function fileCrc32(data: Uint8Array): number {
    let c = 0xFFFFFFFF;
    for (let i = 0; i < data.length; i++) c = (crcTable[(c ^ data[i]) & 0xFF] ^ (c >>> 8)) >>> 0;
    return (c ^ 0xFFFFFFFF) >>> 0;
  }

  function w16(v: DataView, o: number, n: number) { v.setUint16(o, n, true); }
  function w32(v: DataView, o: number, n: number) { v.setUint32(o, n, true); }

  type LocalMeta = { nameBytes: Uint8Array; crc: number; size: number; localOffset: number };
  const metas: LocalMeta[] = [];
  const parts: Uint8Array[] = [];
  let pos = 0;

  for (const { name, data } of files) {
    const nameBytes = enc.encode(name);
    const crc = fileCrc32(data);
    const hdr = new Uint8Array(30 + nameBytes.length);
    const v   = new DataView(hdr.buffer);
    w32(v, 0,  0x04034b50);
    w16(v, 4,  20); w16(v, 6, 0); w16(v, 8, 0);
    w16(v, 10, 0);  w16(v, 12, 0);
    w32(v, 14, crc); w32(v, 18, data.length); w32(v, 22, data.length);
    w16(v, 26, nameBytes.length); w16(v, 28, 0);
    hdr.set(nameBytes, 30);
    metas.push({ nameBytes, crc, size: data.length, localOffset: pos });
    parts.push(hdr, data);
    pos += hdr.length + data.length;
  }

  const cdStart = pos;
  for (const { nameBytes, crc, size, localOffset } of metas) {
    const cd = new Uint8Array(46 + nameBytes.length);
    const v  = new DataView(cd.buffer);
    w32(v, 0,  0x02014b50);
    w16(v, 4,  20); w16(v, 6, 20); w16(v, 8, 0); w16(v, 10, 0);
    w16(v, 12, 0);  w16(v, 14, 0);
    w32(v, 16, crc); w32(v, 20, size); w32(v, 24, size);
    w16(v, 28, nameBytes.length); w16(v, 30, 0); w16(v, 32, 0);
    w16(v, 34, 0); w16(v, 36, 0); w32(v, 38, 0); w32(v, 42, localOffset);
    cd.set(nameBytes, 46);
    parts.push(cd);
    pos += cd.length;
  }

  const cdSize = pos - cdStart;
  const eocd = new Uint8Array(22);
  const ev   = new DataView(eocd.buffer);
  w32(ev, 0, 0x06054b50); w16(ev, 4, 0); w16(ev, 6, 0);
  w16(ev, 8, files.length); w16(ev, 10, files.length);
  w32(ev, 12, cdSize); w32(ev, 16, cdStart); w16(ev, 20, 0);
  parts.push(eocd);

  const total = parts.reduce((s, p) => s + p.length, 0);
  const out   = new Uint8Array(total);
  let off = 0;
  for (const p of parts) { out.set(p, off); off += p.length; }
  return out;
}

// ─── README content ───────────────────────────────────────────────────────────

const README = `Loominary Export Package
=========================

Files in this archive
---------------------
  loominary_state.json         Copy to: <game dir>/config/loominary_state.json
  loominary_carpet*.litematic  Carpet platform schematic(s) — carpet codecs only
  preview.png / preview.gif    Rendered preview of the map art
  README.txt                   This file

How to install in-game (carpet codecs)
---------------------------------------
1. Copy loominary_state.json to your Minecraft game config directory:
     Windows : %appdata%\\.minecraft\\config\\loominary_state.json
     Linux   : ~/.minecraft/config/loominary_state.json
     macOS   : ~/Library/Application Support/minecraft/config/loominary_state.json

2. Copy the loominary_carpet*.litematic file(s) to your Litematica schematics folder:
     Windows : %appdata%\\.minecraft\\schematics\\
     Linux   : ~/.minecraft/schematics/
     macOS   : ~/Library/Application Support/minecraft/schematics/

3. In-game, load the schematic with Litematica (M key) and place the carpet platform
   at the correct location.  Each .litematic is a 128x128 grid of colored carpet blocks
   matching the image — place it flat on the ground where your map hangs.

4. Once the carpet platform is placed, the Loominary mod writes the LOOM channel data
   automatically.  The mod reads the state JSON to know which tile to encode.

5. Lock the map in a cartography table and hang it in an item frame.
   The image appears as the mod decodes the carpet channel.

For multi-tile grids, repeat steps 2-5 for each .litematic file.
Files are named  loominary_carpet_r<row>_c<col>.litematic.

Banner codec (no carpet platform needed)
----------------------------------------
If you exported with the banner codec there are no .litematic files.
The full workflow:

1. Copy loominary_state.json to your config directory (see path above).

2. In-game, open an anvil with unnamed banners and empty bundles in your
   inventory.  The mod reads ACTIVE_CHUNKS from the state JSON and renames
   one banner per tick automatically.  You need 1 XP level per banner and
   up to 63 banners per map tile.

3. Place the renamed banners anywhere inside the 128x128 overworld area
   that the map represents (the map's grid tile).  They do not need to
   stay near the item frame — once the decorations are registered the
   map can hang anywhere.

4. Hold the target map and right-click each banner (or run
   /loominary click to automate this).  The server records each banner's
   name as a decoration on the map — this is the only server interaction.

5. Lock the map in a cartography table, then place it in an item frame
   wherever you like.  Any player running Loominary within 32 blocks
   will see your image rendered client-side.  The banner pins are
   suppressed automatically so they don't clutter the image.

For multi-tile grids, use /loominary tile next to step through tiles
and repeat from step 2 for each one.

More info: https://github.com/ZeroHPMinecraft/loominary
`;

// ─── Props ────────────────────────────────────────────────────────────────────

export interface ExportPageProps {
  comp:       CompositionState;
  onBack:     () => void;
  uiFontSize?: number;
}

// ─── ExportPage ───────────────────────────────────────────────────────────────

const AUTHOR_LS_KEY = 'loominary_author';

function slugifyFilename(filename: string): string {
  return filename
    .replace(/\.[^.]+$/, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

export function ExportPage({ comp, onBack, uiFontSize = 19 }: ExportPageProps) {
  const defaultTitle = comp.title
    ?? (comp.sourceFilename ? slugifyFilename(comp.sourceFilename) : '');
  const defaultAuthor = comp.author
    ?? localStorage.getItem(AUTHOR_LS_KEY)
    ?? '';

  const [title,     setTitle]     = useState(defaultTitle);
  const [author,    setAuthor]    = useState(defaultAuthor);
  const [codec,     setCodec]     = useState<CodecMode>(comp.codecMode ?? DEFAULT_CODEC);
  const [nonce,     setNonce]     = useState(false);
  const [status,    setStatus]    = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);

  // Encryption
  const [encryptOn, setEncryptOn] = useState(false);
  const [pwInput,   setPwInput]   = useState('');
  const [passwords, setPasswords] = useState<string[]>([]);

  // Tile stats
  const [tileStats,      setTileStats]      = useState<TileStat[] | null>(null);
  const [statsComputing, setStatsComputing] = useState(false);
  const [statsProg,      setStatsProg]      = useState<{ done: number; total: number } | null>(null);
  const [exportProg,     setExportProg]     = useState<{ label: string; done: number; total: number } | null>(null);

  // Mux
  const [muxAlloc,    setMuxAlloc]    = useState<MuxAllocation | null>(null);
  const [muxCodec,    setMuxCodec]    = useState<CodecMode | null>(null);
  const [extraDonors, setExtraDonors] = useState(0);

  // 3D viewer resizable height
  const [viewerH,     setViewerH]     = useState(320);
  const viewerDragRef = useRef<{ y0: number; h0: number } | null>(null);

  // Persist author to localStorage whenever it's updated.
  useEffect(() => {
    const trimmed = author.trim();
    if (trimmed) localStorage.setItem(AUTHOR_LS_KEY, trimmed);
  }, [author]);

  // Refs for export metadata so computeStats can read them without closure-dep issues
  const exportMetaRef = useRef({ title, author, nonce });
  useEffect(() => { exportMetaRef.current = { title, author, nonce }; }, [title, author, nonce]);

  // Budget warning dialog
  const [budgetWarnLabel,  setBudgetWarnLabel]  = useState<string | null>(null);
  const pendingExportRef = useRef<(() => Promise<void>) | null>(null);

  // Preview
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [gifUrl,  setGifUrl]  = useState<string | null>(null);
  const [show3D,  setShow3D]  = useState(false);
  const [expandedTiles, setExpandedTiles] = useState<Set<number>>(() => new Set());

  // Per-tile 3D schematic preview (null = no tile selected)
  const [preview3DTile, setPreview3DTile] = useState<{
    ti: number; compressedData: Uint8Array; label: string;
  } | null>(null);

  const maxF       = Math.max(...comp.frames.map(t => t.length), 1);
  const isAnimated = maxF > 1;
  const multiTile  = comp.gridCols * comp.gridRows > 1;

  // Schematics encode banner chunks and are only meaningful when combined with
  // a carpet-codec state JSON (where the carpet channel carries the payload and
  // banners carry overflow/routing).  Banner-only mode doesn't use schematics —
  // the mod's anvil auto-fill handler places banners directly from the state JSON.
  const schematicsAvailable = codec !== 'BANNER';

  // Validation
  const titleMissing  = title.trim().length === 0;
  const authorMissing = author.trim().length === 0;
  const canExport     = !titleMissing && !authorMissing;

  // ── Animated GIF preview ──────────────────────────────────────────────────
  useEffect(() => {
    if (!isAnimated) { setGifUrl(null); return; }
    let url = '';
    import('../gif-encode.js').then(({ encodeAnimatedGif }) => {
      const bytes = encodeAnimatedGif(comp, 0);
      const blob  = new Blob([bytes.buffer as ArrayBuffer], { type: 'image/gif' });
      url = URL.createObjectURL(blob);
      setGifUrl(url);
    });
    return () => { if (url) URL.revokeObjectURL(url); };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAnimated]);

  // ── Static canvas preview ─────────────────────────────────────────────────
  useEffect(() => {
    if (isAnimated) return;
    const canvas = canvasRef.current; if (!canvas) return;
    const imgData = mapBytesToImageData(comp.frames, comp.activeFrame, comp.gridCols, comp.gridRows);
    canvas.width = imgData.width; canvas.height = imgData.height;
    canvas.getContext('2d')!.putImageData(imgData, 0, 0);
  }, [comp, isAnimated, show3D]);

  // ── Stats computation ─────────────────────────────────────────────────────
  const computeStats = useCallback(async () => {
    setStatsComputing(true);
    const total = comp.gridCols * comp.gridRows;
    setStatsProg({ done: 0, total });
    const { title: t, author: a, nonce: n } = exportMetaRef.current;
    try {
      const stats: TileStat[] = [];
      for (let ti = 0; ti < total; ti++) {
        const tileCol  = ti % comp.gridCols;
        const tileRow  = Math.floor(ti / comp.gridCols);
        const frames   = comp.frames[ti]      ?? [new Uint8Array(128 * 128)];
        const delays   = comp.frameDelays[ti] ?? new Array(frames.length).fill(100);
        const frame0   = frames[0] ?? new Uint8Array(128 * 128);
        let flags = 0;
        if (comp.allShades)    flags |= FLAG_ALL_SHADES;
        if (frames.length > 1) flags |= FLAG_ANIMATED;
        const mBytes = frames.length > 1
          ? toBytesAnimated(flags, comp.gridCols, comp.gridRows, tileCol, tileRow,
              crc32(frame0), a || null, t || null, n ? 1 : 0, frames.length, 0, delays)
          : toBytesV2(flags, comp.gridCols, comp.gridRows, tileCol, tileRow,
              crc32(frame0), a || null, t || null, n ? 1 : 0);
        const combined = new Uint8Array(mBytes.length + frames.length * 128 * 128);
        combined.set(mBytes, 0);
        for (let f = 0; f < frames.length; f++)
          combined.set(frames[f] ?? new Uint8Array(128*128), mBytes.length + f * 128*128);
        const compressed = await compress(combined);
        stats.push({
          ti, tileCol, tileRow,
          compressedBytes: compressed.length,
          compressedData:  compressed,
          manifest: {
            version:     mBytes[0],
            headerBytes: mBytes.length,
            flags,
            colorCrc32:  crc32(frame0),
            username:    a || null,
            title:       t || null,
            nonce:       n ? 1 : 0,
            frameCount:  frames.length,
            loopCount:   0,
            frameDelays: delays,
          },
        });
        setStatsProg({ done: ti + 1, total });
      }
      setTileStats(stats);
      setMuxAlloc(null);
      setMuxCodec(null);
      setExtraDonors(0);
    } finally {
      setStatsComputing(false);
      setStatsProg(null);
    }
  }, [comp]); // eslint-disable-line react-hooks/exhaustive-deps

  // Keep a stable ref so debounced triggers always call the latest version.
  const computeStatsRef = useRef(computeStats);
  useEffect(() => { computeStatsRef.current = computeStats; }, [computeStats]);

  useEffect(() => { void computeStats(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Debounced rebuild when export metadata changes (title/author/nonce affect manifest).
  const metaTriggerTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (metaTriggerTimer.current) clearTimeout(metaTriggerTimer.current);
    metaTriggerTimer.current = setTimeout(() => void computeStatsRef.current(), 600);
    return () => { if (metaTriggerTimer.current) clearTimeout(metaTriggerTimer.current); };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [title, author, nonce]);

  // When tileStats refreshes (title/author/nonce) or encryption settings change,
  // rebuild the active 3D preview tile with the correct (possibly encrypted) bytes.
  // Donor tiles are cleared — their cargo requires a full mux re-run.
  useEffect(() => {
    if (!preview3DTile || !tileStats) return;
    const artCount = comp.gridCols * comp.gridRows;
    if (preview3DTile.ti >= artCount) { setPreview3DTile(null); return; }
    const fresh = tileStats.find(s => s.ti === preview3DTile.ti);
    if (!fresh) return;

    let cancelled = false;
    void (async () => {
      let data: Uint8Array = fresh.compressedData;
      if (encryptOn && passwords.length > 0) {
        const { encrypt } = await import('../encryption.js');
        data = await encrypt(data, passwords, author.trim() || null, title.trim() || null);
      }
      if (!cancelled) setPreview3DTile(p => p ? { ...p, compressedData: data } : null);
    })();
    return () => { cancelled = true; };
  }, [tileStats, encryptOn, passwords]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── Derived budget info ───────────────────────────────────────────────────
  // Encryption adds a fixed overhead per tile: 4+1+12+256+1 header bytes + 76 bytes per
  // password slot + 16 bytes GCM tag = 290 + 76*N bytes.
  const encryptOverhead = encryptOn && passwords.length > 0 ? 290 + 76 * passwords.length : 0;
  const sizes      = tileStats?.map(s => s.compressedBytes) ?? [];
  const effectiveSizes = sizes.map(s => s + encryptOverhead);
  const { overBudget, budget } = budgetStatus(effectiveSizes, codec);

  const effectivelyOverBudget =
    overBudget.length > 0 && (!muxAlloc || muxAlloc.unresolved > 0);

  // ── Auto-mux: recompute whenever stats, codec, or encryption changes ──────
  useEffect(() => {
    if (!tileStats || statsComputing) return;
    const currentSizes = tileStats.map(s => s.compressedBytes + encryptOverhead);
    const { overBudget: ob } = budgetStatus(currentSizes, codec);
    if (ob.length === 0) {
      setMuxAlloc(null); setExtraDonors(0); setMuxCodec(null);
      return;
    }
    let extra = 0;
    let alloc = computeMuxAllocation(currentSizes, codec);
    while (alloc.unresolved > 0 && extra < 9999) {
      extra++;
      alloc = computeMuxAllocation([...currentSizes, ...Array(extra).fill(0)], codec);
    }
    setExtraDonors(extra);
    setMuxAlloc(alloc);
    setMuxCodec(codec);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tileStats, codec, statsComputing, encryptOverhead]);

  // ── Budget-aware export gate ──────────────────────────────────────────────
  function tryExport(label: string, action: () => Promise<void>) {
    if (tileStats && effectivelyOverBudget) {
      pendingExportRef.current = action;
      setBudgetWarnLabel(label);
    } else {
      void action();
    }
  }

  function confirmBudgetWarn() {
    const fn = pendingExportRef.current;
    pendingExportRef.current = null;
    setBudgetWarnLabel(null);
    if (fn) void fn();
  }

  // ── Password helpers ──────────────────────────────────────────────────────
  function addPassword() {
    const pw = pwInput.trim();
    if (!pw || passwords.includes(pw)) return;
    setPasswords(p => [...p, pw]);
    setPwInput('');
  }

  function fromB64(s: string): Uint8Array {
    const bin = atob(s); const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }
  function toB64(bytes: Uint8Array): string {
    let s = '';
    for (let i = 0; i < bytes.length; i += 8192) s += String.fromCharCode(...bytes.subarray(i, i+8192));
    return btoa(s);
  }

  // ── Shared: encode composition + optional encryption + optional mux ────────
  //
  // Blank donor tiles (extraDonors > 0) are appended to ps.tiles before the
  // mux-apply functions run.  They have isDonorOnly=true and empty chunks/
  // carpetCompressedB64, which is exactly what applyBannerMux /
  // applyCarpetMux expect for blank donors (the functions fill in routing
  // banners / muxCargoB64 automatically).
  async function encodeAndMux(nonceVal: number, localExtraDonors: number, localMuxAlloc: MuxAllocation | null, skipEncrypt = false) {
    const ps = await encodeComposition(comp, {
      title: title.trim(), author: author.trim(), nonce: nonceVal, whitelist: [], codecMode: codec,
    });

    if (!skipEncrypt && encryptOn && passwords.length > 0) {
      const { encrypt } = await import('../encryption.js');
      const encTotal = ps.tiles.length;
      setExportProg({ label: 'Encrypting', done: 0, total: encTotal });
      for (let ei = 0; ei < encTotal; ei++) {
        const tile = ps.tiles[ei];
        if (tile.carpetCompressedB64) {
          const raw = fromB64(tile.carpetCompressedB64);
          tile.carpetCompressedB64 = toB64(await encrypt(raw, passwords, author.trim() || null, title.trim() || null));
        }
        if (tile.chunks.length > 0) {
          const { assembleChunks: ac, buildChunks: bc } = await import('../cjk-codec.js');
          const cjk = tile.chunks.filter(c => c.length > 2 && c.charCodeAt(2) >= 0x4E00);
          if (cjk.length > 0) {
            const raw = ac(cjk);
            tile.chunks = bc(await encrypt(raw, passwords, author.trim() || null, title.trim() || null));
          }
        }
        setExportProg({ label: 'Encrypting', done: ei + 1, total: encTotal });
      }
      setExportProg(null);
    }

    if (localMuxAlloc) {
      for (let i = 0; i < localExtraDonors; i++) {
        const donor = emptyTileData();
        donor.isDonorOnly = true;
        ps.tiles.push(donor);
      }
      if (codec === 'BANNER') {
        const { applyBannerMux: mx } = await import('../mux.js');
        const result = mx(ps.tiles, localMuxAlloc, comp.gridCols);
        if (result.unresolved > 0) setStatus(`Mux: ${result.unresolved} unresolved tile(s)`);
      } else {
        const payloads = ps.tiles.map(t =>
          t.carpetCompressedB64 ? fromB64(t.carpetCompressedB64) : new Uint8Array(0)
        );
        const { applyCarpetMux: mx } = await import('../mux.js');
        const result = mx(ps.tiles, localMuxAlloc, payloads);
        if (result.unresolved > 0) setStatus(`Mux: ${result.unresolved} unresolved tile(s)`);
      }
    }

    return ps;
  }

  // ── Export all as ZIP ─────────────────────────────────────────────────────
  const handleZipExport = useCallback(async () => {
    if (!canExport) return;
    setExporting(true); setStatus('Building ZIP…');
    try {
      const files: ZipEntry[] = [];
      const nonceVal = nonce ? ((Math.random() * 0x100000000) >>> 0) : 0;
      const baseName = title.trim() || 'loominary';

      // 1 — State JSON (with mux/encryption if active)
      setStatus('Building ZIP… encoding state');
      const ps = await encodeAndMux(nonceVal, extraDonors, muxAlloc);
      files.push({
        name: 'loominary_state.json',
        data: new TextEncoder().encode(JSON.stringify(ps, null, 2)),
      });

      // 2 — Per-tile carpet platform schematics (carpet codecs only), including
      //     any blank donor tiles added by mux.  Art tiles use muxCargoB64 when
      //     muxed (receivers carry their own segment), falling back to
      //     carpetCompressedB64.  Donor tiles always use muxCargoB64.
      if (schematicsAvailable) {
        const { exportCarpetSchematic } = await import('../schematic.js');
        const artCount  = comp.gridCols * comp.gridRows;
        const multiTile = artCount > 1 || extraDonors > 0;
        const schTotal  = artCount + extraDonors;
        for (let ti = 0; ti < artCount; ti++) {
          const tileCol = ti % comp.gridCols, tileRow = Math.floor(ti / comp.gridCols);
          const tile    = ps.tiles[ti];
          const b64     = tile?.muxCargoB64 ?? tile?.carpetCompressedB64;
          if (!b64) continue;
          const suffix  = multiTile ? `_r${tileRow}_c${tileCol}` : '';
          const name    = `loominary_carpet${suffix}`;
          setStatus(`Building ZIP… schematic ${ti + 1}/${schTotal}`);
          files.push({ name: `${name}.litematic`, data: await exportCarpetSchematic(b64, name, author.trim() || 'Loominary', tileCol, tileRow, codec) });
        }
        for (let i = 0; i < extraDonors; i++) {
          const tile = ps.tiles[artCount + i];
          const b64  = tile?.muxCargoB64;
          if (!b64) continue;
          const name = `loominary_carpet_donor${extraDonors > 1 ? i + 1 : ''}`;
          setStatus(`Building ZIP… donor schematic ${i + 1}/${extraDonors}`);
          files.push({ name: `${name}.litematic`, data: await exportCarpetSchematic(b64, name, author.trim() || 'Loominary', 0, 0, codec) });
        }
      }

      // 3 — Preview: animated GIF for multi-frame compositions, 4× PNG otherwise
      if (isAnimated) {
        setStatus('Building ZIP… encoding GIF preview');
        const { encodeAnimatedGif } = await import('../gif-encode.js');
        files.push({ name: 'preview.gif', data: encodeAnimatedGif(comp, 0) });
      } else {
        setStatus('Building ZIP… rendering preview');
        const imgData = mapBytesToImageData(comp.frames, comp.activeFrame, comp.gridCols, comp.gridRows);
        const oscS = new OffscreenCanvas(imgData.width, imgData.height);
        oscS.getContext('2d')!.putImageData(imgData, 0, 0);
        const osc  = new OffscreenCanvas(imgData.width * 4, imgData.height * 4);
        const octx = osc.getContext('2d')!;
        octx.imageSmoothingEnabled = false;
        octx.drawImage(oscS, 0, 0, osc.width, osc.height);
        files.push({ name: 'preview.png', data: new Uint8Array(await (await osc.convertToBlob({ type: 'image/png' })).arrayBuffer()) });
      }

      // 4 — README
      files.push({ name: 'README.txt', data: new TextEncoder().encode(README) });

      setStatus('Building ZIP… compressing');
      const zipBytes = buildZip(files);
      const blob     = new Blob([zipBytes.buffer as ArrayBuffer], { type: 'application/zip' });
      const url      = URL.createObjectURL(blob);
      const a        = document.createElement('a'); a.href = url;
      a.download = `${baseName}_export.zip`; a.click();
      setTimeout(() => URL.revokeObjectURL(url), 5000);

      setStatus('ZIP exported ✓'); setTimeout(() => setStatus(null), 3000);
    } catch (err) { setStatus(`Error: ${err}`); setTimeout(() => setStatus(null), 4000); }
    finally { setExporting(false); setExportProg(null); }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [comp, title, author, codec, nonce, encryptOn, passwords, muxAlloc, extraDonors, schematicsAvailable, canExport]);

  // ─── Render ───────────────────────────────────────────────────────────────
  return (
    <div style={{ display:'flex', height:'100%', overflow:'hidden', background:'#111', color:'#ccc', fontSize: Math.round(uiFontSize * 1.25) }}>

      {/* ── Budget warning modal ── */}
      {budgetWarnLabel && (
        <div style={{
          position:'fixed', inset:0, background:'rgba(0,0,0,0.75)',
          display:'flex', alignItems:'center', justifyContent:'center', zIndex:1000,
        }}>
          <div style={{
            background:'#1e1e1e', border:'1px solid #f93', borderRadius:6,
            padding:'20px 24px', maxWidth:420, margin:20,
          }}>
            <div style={{ fontSize:'0.74em', color:'#f93', fontWeight:'bold', marginBottom:10 }}>
              ⚠ Over Budget — {budgetWarnLabel}
            </div>
            <div style={{ fontSize:'0.63em', color:'#ccc', lineHeight:1.6, marginBottom:16 }}>
              <b>{overBudget.length} tile{overBudget.length > 1 ? 's are' : ' is'}</b> over the{' '}
              <b>{CODEC_LABEL[codec]}</b> budget ({(budget / 1024).toFixed(1)} KB/tile).{' '}
              This export <b style={{ color:'#f77' }}>will not work in-game</b> until all tiles are
              within budget.
              <br /><br />
              To fix: compute Mux to redistribute overflow · switch to a higher-capacity codec ·
              reduce frame count or colour complexity in the editor.
            </div>
            <div style={{ display:'flex', gap:8, justifyContent:'flex-end' }}>
              <button onClick={() => { pendingExportRef.current = null; setBudgetWarnLabel(null); }}
                style={SM_BTN}>Cancel</button>
              <button onClick={confirmBudgetWarn}
                style={{ ...SM_BTN, background:'#2a1212', border:'1px solid #f55', color:'#f77' }}>
                Export anyway
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── Left: controls ── */}
      <div style={{
        width:380, minWidth:260, flexShrink:0, background:'#1e1e1e',
        borderRight:'1px solid #333', padding:'14px 12px',
        display:'flex', flexDirection:'column', gap:10, overflowY:'auto',
      }}>
        {/* ── Step 1: Codec ── */}
        <Step n={1} title="Codec"
          hint="How image data is stored in-game. Carpet codecs (default) encode as a 128×128 carpet schematic. More capacity, crisper images, but require the platform to be placed in-game first. The banner codec works on any server but holds less data per tile. The stats panel on the right shows whether your image fits each codec's budget." />
        {CODEC_ORDER.map(c => (
          <label key={c} style={{ display:'flex', alignItems:'flex-start', gap:6, marginBottom:5, cursor:'pointer' }}>
            <input type="radio" name="codec" value={c} checked={codec===c}
              onChange={() => setCodec(c)}
              style={{ marginTop:3, flexShrink:0 }} />
            <div>
              <div style={{ color:'#ccc', fontSize:'0.63em' }}>
                {CODEC_LABEL[c]}
                {c === DEFAULT_CODEC && <span style={{ color:'#666', marginLeft:5 }}>(default)</span>}
              </div>
              <div style={{ color:'#555', fontSize:'0.58em' }}>{CODEC_HINT[c]}</div>
              {tileStats && (() => {
                const ob = budgetStatus(effectiveSizes, c).overBudget.length, tot = tileStats.length;
                return <div style={{ fontSize:'0.53em', color: ob > 0 ? '#f93' : '#3a3', marginTop:1 }}>
                  {ob > 0 ? `⚠ ${ob}/${tot} tile${ob>1?'s':''} over budget` : `✓ all ${tot} fit`}
                </div>;
              })()}
            </div>
          </label>
        ))}
        {NEEDS_CARPET.has(codec) && (
          <div style={{ fontSize:'0.58em', color:'#555', marginTop:2, borderTop:'1px solid #2a2a2a', paddingTop:5 }}>
            Requires a carpet platform in-game.
          </div>
        )}

        {/* ── Step 2: Identify ── */}
        <Step n={2} title="Identify"
          hint="Metadata embedded in the encoded data. Title and author are required — they appear in the mod's in-game catalogue. Resalt embeds a random nonce so each export has unique compressed bytes, which is useful when re-submitting the same image." />
        <FieldRow label="Title" error={titleMissing ? 'Required' : undefined}>
          <input type="text" placeholder="Map title…" maxLength={64} value={title}
            onInput={e => setTitle((e.target as HTMLInputElement).value)}
            style={{ ...INPUT, border: `1px solid ${titleMissing ? '#f55' : '#444'}` }} />
        </FieldRow>
        <FieldRow label="Author" error={authorMissing ? 'Required' : undefined}>
          <input type="text" placeholder="Your username…" maxLength={16} value={author}
            onInput={e => setAuthor((e.target as HTMLInputElement).value)}
            style={{ ...INPUT, border: `1px solid ${authorMissing ? '#f55' : '#444'}` }} />
        </FieldRow>
        <label style={{ display:'flex', alignItems:'flex-start', gap:6, cursor:'pointer' }}
          title="Embeds a random 32-bit nonce so compressed bytes are unique per export.">
          <input type="checkbox" checked={nonce} onChange={e => setNonce((e.target as HTMLInputElement).checked)}
            style={{ marginTop:2 }} />
          <div>
            <div style={{ fontSize:'0.63em' }}>Resalt (nonce)</div>
            <div style={{ fontSize:'0.58em', color:'#555' }}>Randomises compressed bytes without changing the image.</div>
          </div>
        </label>

        {/* ── Step 3: Encrypt (optional) ── */}
        <Step n={3} title="Encrypt"
          hint="Optional — AES-256-GCM payload encryption with PBKDF2 key derivation. Any one password in the list can decode the map art. Adds ≈290 B + 76 B per password of overhead per tile; the budget stats on the right account for this automatically." />
        <label style={{ display:'flex', alignItems:'center', gap:6, cursor:'pointer', fontSize:'0.63em' }}>
          <input type="checkbox" checked={encryptOn} onChange={e => setEncryptOn((e.target as HTMLInputElement).checked)} />
          Encrypt output
        </label>
        {encryptOn && (
          <>
            <div style={{ display:'flex', gap:4, marginTop:6 }}>
              <input type="text" placeholder="Add password…" value={pwInput}
                onInput={e => setPwInput((e.target as HTMLInputElement).value)}
                onKeyDown={e => { if (e.key === 'Enter') addPassword(); }}
                style={{ ...INPUT, flex:1 }} />
              <button onClick={addPassword} style={SM_BTN}>Add</button>
            </div>
            {passwords.length === 0 && <div style={{ fontSize:'0.58em', color:'#f93', marginTop:2 }}>⚠ No passwords added.</div>}
            {passwords.map((pw, i) => (
              <div key={i} style={{ display:'flex', alignItems:'center', gap:4, fontSize:'0.58em', color:'#888', marginTop:2 }}>
                <span style={{ flex:1 }}>{'•'.repeat(Math.min(pw.length, 12))}</span>
                <button onClick={() => setPasswords(p => p.filter((_, j) => j !== i))} style={{ ...SM_BTN, color:'#f77' }}>✕</button>
              </div>
            ))}
          </>
        )}

        <div style={{ flex:1 }} />

        {/* ── Step 4: Export ── */}
        <Step n={4} title="Export"
          hint="Copy the State JSON to &lt;game dir&gt;/config/ — the mod loads it automatically. Carpet schematics (.litematic) mark where to place the carpet platform in-game. The ZIP bundles everything with a README." />

        {status && (
          <div style={{ fontSize:'0.63em', color: status.startsWith('Error') ? '#f77' : status.includes('✓') ? '#7f7' : '#aaa' }}>
            {status}
          </div>
        )}
        {exportProg && (
          <div>
            <div style={{ fontSize:'0.58em', color:'#aaa', marginBottom:3 }}>
              {exportProg.label} — tile {exportProg.done} / {exportProg.total}
            </div>
            <Bar value={exportProg.done} total={exportProg.total} />
          </div>
        )}
        {!canExport && <div style={{ fontSize:'0.58em', color:'#f55' }}>Fill in Title and Author before exporting.</div>}

        <button
          onClick={() => tryExport('Export ZIP', handleZipExport)}
          disabled={exporting || !canExport}
          style={{ ...EXPORT_BTN, opacity: canExport ? 1 : 0.5, cursor: canExport && !exporting ? 'pointer' : 'not-allowed' }}>
          {exporting ? 'Working…' : `⬇ Export ZIP${encryptOn && passwords.length > 0 ? ' (encrypted)' : ''}${muxAlloc ? ' (mux)' : ''}`}
        </button>

        <button onClick={onBack} style={{ ...BTN, color:'#888', textAlign:'center' }}>← Back to Editor</button>
      </div>

      {/* ── Right: preview + stats + mux ── */}
      <div style={{ flex:1, display:'flex', flexDirection:'column', overflow:'hidden', padding:14, gap:10 }}>

        {/* Preview */}
        <div style={{ display:'flex', flexDirection:'column', gap:4, flexShrink:0 }}>
          <div style={{ display:'flex', alignItems:'center', gap:8 }}>
            <div style={{ color:'#555', fontSize:'0.63em', flex:1 }}>
              {comp.gridCols}×{comp.gridRows} tile{comp.gridCols*comp.gridRows!==1?'s':''}
              {comp.title ? ` — "${comp.title}"` : ''}
              {isAnimated && ` · ${maxF} frames`}
            </div>
            <div style={{ display:'flex', gap:2 }}>
              {(['2D', '3D'] as const).map(v => (
                <button key={v} onClick={() => setShow3D(v === '3D')} style={{
                  background:   (v === '3D') === show3D ? '#1b3556' : '#252525',
                  border:       `1px solid ${(v === '3D') === show3D ? '#4a9eff' : '#444'}`,
                  borderRadius:  3,
                  color:        (v === '3D') === show3D ? '#8cf' : '#666',
                  cursor:       'pointer',
                  fontSize:     '0.58em',
                  padding:      '2px 8px',
                }}>{v}</button>
              ))}
            </div>
          </div>

          {show3D ? (
            <>
              <div style={{ position:'relative', height:viewerH, width:'100%', border:'1px solid #333', borderRadius:3, overflow:'hidden' }}>
                <SchematicViewer3D codec={codec} tilePreview={preview3DTile} />
                {preview3DTile && (
                  <button onClick={() => setPreview3DTile(null)} title="Back to tile selection"
                    style={{ position:'absolute', top:6, left:8, background:'rgba(0,0,0,0.6)',
                      border:'1px solid #444', borderRadius:3, color:'#aaa', cursor:'pointer',
                      fontSize:'0.55em', padding:'2px 6px' }}>
                    ← clear
                  </button>
                )}
              </div>
              {/* Vertical resize handle */}
              <div
                style={{ height:5, cursor:'ns-resize', background:'#1a1a1a',
                  borderBottom:'1px solid #2a2a2a', flexShrink:0, userSelect:'none' }}
                onPointerDown={e => {
                  (e.target as HTMLElement).setPointerCapture(e.pointerId);
                  viewerDragRef.current = { y0: e.clientY, h0: viewerH };
                }}
                onPointerMove={e => {
                  if (!viewerDragRef.current) return;
                  setViewerH(Math.max(120, Math.min(720,
                    viewerDragRef.current.h0 + e.clientY - viewerDragRef.current.y0)));
                }}
                onPointerUp={() => { viewerDragRef.current = null; }}
              />
            </>
          ) : (
            <div style={{ display:'flex', flexDirection:'column', alignItems:'center', gap:4 }}>
              {isAnimated && gifUrl
                ? <img src={gifUrl} style={{ maxWidth:'100%', maxHeight:200, imageRendering:'pixelated', border:'1px solid #333' }} />
                : <canvas ref={canvasRef} style={{ maxWidth:'100%', maxHeight:200, imageRendering:'pixelated', border:'1px solid #333' }} />
              }
              {isAnimated && !gifUrl && <div style={{ fontSize:'0.58em', color:'#666' }}>Generating preview…</div>}
            </div>
          )}
        </div>

        {/* Data stats */}
        <div style={{ flex:1, overflowY:'auto', display:'flex', flexDirection:'column', gap:10 }}>
          <div style={{ fontSize:'0.63em', color:'#5af', fontWeight:'bold' }}>
            Payload — {CODEC_LABEL[codec]}
            <span style={{ fontWeight:'normal', color:'#555', marginLeft:8 }}>budget {(budget/1024).toFixed(1)} KB/tile</span>
          </div>

          {statsComputing && (
            <div>
              <div style={{ fontSize:'0.63em', color:'#666', marginBottom:4 }}>
                {statsProg ? `Compressing tile ${statsProg.done} / ${statsProg.total}…` : 'Starting…'}
              </div>
              <Bar value={statsProg?.done ?? 0} total={statsProg?.total ?? 1} />
            </div>
          )}

          {tileStats && !statsComputing && (
            <table style={{ width:'100%', borderCollapse:'collapse', fontSize:'0.58em' }}>
              <thead>
                <tr style={{ color:'#666', borderBottom:'1px solid #2a2a2a' }}>
                  <th style={TH}>Tile</th>
                  <th style={{ ...TH, textAlign:'right' }}>Compressed</th>
                  {codec !== 'BANNER' && <th style={{ ...TH, textAlign:'right' }}>Carpet</th>}
                  {(codec==='CARPET_SHADE'||codec==='CARPET_BANNERS_SHADE'||codec==='CARPET_SHADE_BANNERS') && (
                    <th style={{ ...TH, textAlign:'right' }}>Shade</th>
                  )}
                  {(codec==='CARPET_BANNERS'||codec==='CARPET_BANNERS_SHADE'||codec==='CARPET_SHADE_BANNERS') && (
                    <th style={{ ...TH, textAlign:'right' }}>Banners</th>
                  )}
                  {codec==='BANNER' && <th style={{ ...TH, textAlign:'right' }}>Banners</th>}
                  <th style={{ ...TH, textAlign:'right' }}>%</th>
                  <th style={TH}></th>
                </tr>
              </thead>
              <tbody>
                {tileStats.map(s => {
                  const effBytes  = s.compressedBytes + encryptOverhead;
                  const bd        = breakdown(effBytes, codec);
                  const pct       = effBytes / budget * 100;
                  const mxRole    = muxAlloc?.roles.find(r => r.ti === s.ti);
                  const expanded  = expandedTiles.has(s.ti);
                  const tileLabel = multiTile ? `(${s.tileRow},${s.tileCol})` : 'tile';
                  return (
                    <Fragment key={s.ti}>
                      <tr
                        onClick={() => setExpandedTiles(prev => {
                          const n = new Set(prev);
                          n.has(s.ti) ? n.delete(s.ti) : n.add(s.ti);
                          return n;
                        })}
                        style={{ borderBottom: expanded ? 'none' : '1px solid #1e1e1e',
                          color: !bd.fits ? '#f93' : '#aaa', cursor:'pointer' }}>
                        <td style={{ padding:'3px 6px 3px 0', userSelect:'none' }}>
                          <span style={{ fontSize:'0.75em', marginRight:4, opacity:0.5 }}>{expanded ? '▾' : '▸'}</span>
                          {tileLabel}
                          {mxRole && mxRole.role !== 'normal' && (
                            <span style={{ marginLeft:4, fontSize:9,
                              color: mxRole.role==='receiver' ? '#fa0' : '#5af',
                              background:'#222', borderRadius:2, padding:'1px 3px' }}>
                              {mxRole.role}
                            </span>
                          )}
                        </td>
                        <td style={{ textAlign:'right', padding:'3px 6px' }}>
                          {(effBytes/1024).toFixed(1)} KB
                          {encryptOverhead > 0 && <span style={{ color:'#666', fontSize:'0.85em' }}> 🔒</span>}
                        </td>
                        {codec !== 'BANNER' && (
                          <td style={{ textAlign:'right', padding:'3px 6px', color:'#5af' }}>
                            {(bd.carpetBytes/1024).toFixed(1)} KB
                          </td>
                        )}
                        {(codec==='CARPET_SHADE'||codec==='CARPET_BANNERS_SHADE'||codec==='CARPET_SHADE_BANNERS') && (
                          <td style={{ textAlign:'right', padding:'3px 6px', color:'#a8f' }}>
                            {bd.shadeBytes > 0 ? `${bd.shadeBytes} B` : '—'}
                          </td>
                        )}
                        {(codec==='CARPET_BANNERS'||codec==='CARPET_BANNERS_SHADE'||codec==='CARPET_SHADE_BANNERS') && (
                          <td style={{ textAlign:'right', padding:'3px 6px', color:'#fa8' }}>
                            {bd.overflowBanners > 0 ? bd.overflowBanners : '—'}
                          </td>
                        )}
                        {codec==='BANNER' && (
                          <td style={{ textAlign:'right', padding:'3px 6px', color:'#fa8' }}>{bd.bannerCount}</td>
                        )}
                        <td style={{ textAlign:'right', padding:'3px 6px' }}>{pct.toFixed(0)}%</td>
                        <td style={{ padding:'3px 0 3px 4px', whiteSpace:'nowrap' }}>
                          {bd.fits ? '✓' : '✗'}
                          {codec !== 'BANNER' && (
                            <button
                              onClick={e => {
                                e.stopPropagation();
                                setPreview3DTile(preview3DTile?.ti === s.ti ? null : {
                                  ti: s.ti,
                                  compressedData: s.compressedData,
                                  label: tileLabel,
                                });
                                setShow3D(true);
                              }}
                              title="Preview this tile's schematic in 3D"
                              style={{
                                marginLeft:4, background:'none',
                                border:`1px solid ${preview3DTile?.ti === s.ti ? '#4a9eff' : '#333'}`,
                                borderRadius:2,
                                color: preview3DTile?.ti === s.ti ? '#8cf' : '#555',
                                cursor:'pointer', fontSize:'0.85em', padding:'0 2px', lineHeight:1.3,
                              }}
                            >👁</button>
                          )}
                        </td>
                      </tr>
                      {expanded && (
                        <tr style={{ borderBottom:'1px solid #1e1e1e' }}>
                          <td colSpan={10} style={{ padding:'0 0 6px 18px', background:'#131313' }}>
                            <ManifestDetail m={s.manifest} />
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  );
                })}
              </tbody>
              <tfoot>
                <tr style={{ color:'#555', borderTop:'1px solid #2a2a2a' }}>
                  <td colSpan={2} style={{ padding:'4px 6px 0 0', fontSize:'0.58em' }}>
                    Total: {(effectiveSizes.reduce((a,b)=>a+b,0)/1024).toFixed(1)} KB
                    {encryptOverhead > 0 && ` (incl. ${(encryptOverhead/1024).toFixed(1)} KB enc. overhead/tile)`}
                  </td>
                  <td colSpan={10} style={{ textAlign:'right', fontSize:'0.58em', padding:'4px 0 0',
                    color: overBudget.length > 0 ? '#f93' : '#3a3' }}>
                    {overBudget.length === 0
                      ? `All ${tileStats.length} tile${tileStats.length>1?'s':''} fit`
                      : `${overBudget.length}/${tileStats.length} tile${overBudget.length>1?'s':''} over budget`}
                  </td>
                </tr>
              </tfoot>
            </table>
          )}

          {/* ── Mux section ── */}
          {tileStats && muxAlloc && (
            <div style={{ background:'#161616', border:'1px solid #2a2a2a', borderRadius:4, padding:'8px 10px' }}>
              <div style={{ display:'flex', alignItems:'center', gap:8, marginBottom:6, flexWrap:'wrap' }}>
                <div style={{ fontSize:'0.63em', color:'#5af', fontWeight:'bold' }}>Mux</div>
                <div style={{ fontSize:'0.58em', color: muxAlloc.unresolved > 0 ? '#f55' : '#3a3' }}>
                  {muxAlloc.unresolved > 0
                    ? `⚠ ${muxAlloc.unresolved} tile(s) unresolved`
                    : '✓ auto-applied on export'}
                </div>
                {extraDonors > 0 && (
                  <div style={{ fontSize:'0.53em', color:'#5af', background:'#001633', border:'1px solid #15385a', borderRadius:2, padding:'1px 5px' }}>
                    +{extraDonors} blank donor{extraDonors>1?'s':''}
                  </div>
                )}
              </div>

              <table style={{ width:'100%', borderCollapse:'collapse', fontSize:'0.58em', marginBottom:4 }}>
                <thead>
                  <tr style={{ color:'#666', borderBottom:'1px solid #2a2a2a' }}>
                    <th style={TH}>Tile</th>
                    <th style={{ ...TH, textAlign:'center' }}>Role</th>
                    <th style={{ ...TH, textAlign:'right' }}>Own</th>
                    <th style={{ ...TH, textAlign:'right' }}>Guest</th>
                    <th style={TH}>Routes to/from</th>
                    <th style={TH}></th>
                  </tr>
                </thead>
                <tbody>
                  {muxAlloc.roles.map(r => {
                    const artLen      = tileStats?.length ?? 0;
                    const isBlank     = r.ti >= artLen;
                    const rowCol      = isBlank ? null : { row: Math.floor(r.ti/comp.gridCols), col: r.ti%comp.gridCols };
                    const tileLabel   = isBlank ? `blank ${r.ti - artLen + 1}` : multiTile ? `(${rowCol!.row},${rowCol!.col})` : 'tile';
                    const artData = isBlank ? null : tileStats?.[r.ti]?.compressedData ?? null;
                    const isActive = preview3DTile?.ti === r.ti;
                    return (
                      <tr key={r.ti} style={{ borderBottom:'1px solid #1a1a1a',
                        color: isBlank ? '#5af' : r.role==='receiver' ? '#fa0' : r.role==='donor' ? '#5af' : '#666' }}>
                        <td style={{ padding:'3px 6px 3px 0' }}>
                          {tileLabel}
                          {isBlank && <span style={{ marginLeft:4, fontSize:9, color:'#5af', background:'#00122a', borderRadius:2, padding:'1px 3px' }}>auto</span>}
                        </td>
                        <td style={{ textAlign:'center', padding:'3px 6px' }}>
                          <span style={{ background: r.role==='receiver'?'#332200':r.role==='donor'?'#001633':'#1a1a1a', borderRadius:2, padding:'1px 5px', fontSize:'0.53em' }}>
                            {r.role}
                          </span>
                        </td>
                        <td style={{ textAlign:'right', padding:'3px 6px' }}>{r.ownBytes>0?`${(r.ownBytes/1024).toFixed(1)}K`:'—'}</td>
                        <td style={{ textAlign:'right', padding:'3px 6px' }}>{r.guestBytes>0?`${(r.guestBytes/1024).toFixed(1)}K`:'—'}</td>
                        <td style={{ padding:'3px 0 3px 6px', fontSize:'0.53em', color:'#555' }}>
                          {r.role==='receiver' && r.donors.length>0 && `→ ${r.donors.map(d=>d.donorTi>=artLen?`blank ${d.donorTi-artLen+1}`:multiTile?`(${Math.floor(d.donorTi/comp.gridCols)},${d.donorTi%comp.gridCols})`:'donor').join(', ')}`}
                          {r.role==='donor' && r.guests.length>0 && `← ${r.guests.map(g=>g.rxTi>=artLen?`blank ${g.rxTi-artLen+1}`:multiTile?`(${Math.floor(g.rxTi/comp.gridCols)},${g.rxTi%comp.gridCols})`:'rx').join(', ')}`}
                        </td>
                        <td style={{ padding:'3px 0 3px 2px' }}>
                          {codec !== 'BANNER' && (
                            <button
                              onClick={() => {
                                setShow3D(true);
                                if (isActive) { setPreview3DTile(null); return; }
                                if (artData) {
                                  // Art tile: show its own payload immediately
                                  setPreview3DTile({ ti: r.ti, compressedData: artData, label: tileLabel });
                                } else {
                                  // Blank donor: run encode+mux (with encryption if active) to get the routed cargo
                                  setStatus('Computing donor schematic…');
                                  void encodeAndMux(0, extraDonors, muxAlloc).then(ps => {
                                    const tile = ps.tiles[r.ti];
                                    const b64  = tile?.muxCargoB64 ?? tile?.carpetCompressedB64;
                                    if (b64) {
                                      setPreview3DTile({ ti: r.ti, compressedData: fromB64(b64), label: tileLabel });
                                      setStatus(null);
                                    } else {
                                      setStatus('No cargo for this donor');
                                    }
                                  }).catch(e => setStatus(`Preview error: ${e}`));
                                }
                              }}
                              title="Preview schematic in 3D"
                              style={{ background:'none', border:`1px solid ${isActive?'#4a9eff':'#333'}`,
                                borderRadius:2, color:isActive?'#8cf':'#555',
                                cursor:'pointer', fontSize:'0.85em', padding:'0 2px', lineHeight:1.3 }}>
                              👁
                            </button>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>

              {muxAlloc.unresolved > 0 && (
                <div style={{ fontSize:'0.58em', color:'#f55', marginTop:4 }}>
                  ⚠ {muxAlloc.unresolved} tile(s) could not be resolved — switch to a higher-capacity codec or reduce payload size.
                </div>
              )}
            </div>
          )}

          {tileStats && !statsComputing && (
            <div style={{ fontSize:'0.58em', color:'#3a3a3a', lineHeight:1.5 }}>
              Channels: carpet 8 176 B · shade 2 016 B · overflow 5 290 B (63 banners)
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// ─── Progress bar ─────────────────────────────────────────────────────────────

function Bar({ value, total, color = '#4a9eff' }: { value: number; total: number; color?: string }) {
  const pct = total > 0 ? Math.round(value / total * 100) : 0;
  return (
    <div style={{ height:4, background:'#2a2a2a', borderRadius:2, overflow:'hidden' }}>
      <div style={{ height:'100%', width:`${pct}%`, background:color, borderRadius:2, transition:'width 0.12s ease' }} />
    </div>
  );
}

// ─── Small helpers ────────────────────────────────────────────────────────────

function Step({ n, title, hint }: { n: number; title: string; hint: string }) {
  return (
    <div style={{ marginTop: n === 1 ? 4 : 18, marginBottom: 7 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{
          display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
          width: 20, height: 20, borderRadius: '50%',
          background: '#0d2040', border: '1px solid #1a4880',
          color: '#5af', fontSize: '0.52em', fontWeight: 'bold', flexShrink: 0,
        }}>{n}</span>
        <span style={{ color: '#8cf', fontSize: '0.63em', fontWeight: 'bold' }}>{title}</span>
      </div>
      <div style={{ fontSize: '0.51em', color: '#4d6070', lineHeight: 1.55, marginTop: 4, paddingLeft: 28 }}>
        {hint}
      </div>
    </div>
  );
}

function FieldRow({ label, error, children }: { label: string; error?: string; children: preact.ComponentChildren }) {
  return (
    <label style={{ display:'flex', flexDirection:'column', marginBottom:5 }}>
      <div style={{ display:'flex', justifyContent:'space-between', marginBottom:2 }}>
        <span style={{ color:'#aaa', fontSize:'0.58em' }}>{label}</span>
        {error && <span style={{ color:'#f55', fontSize:'0.53em' }}>{error}</span>}
      </div>
      {children}
    </label>
  );
}

// ─── Manifest detail panel ────────────────────────────────────────────────────

function hex8(n: number)  { return '0x' + n.toString(16).padStart(2, '0').toUpperCase(); }
function hex32(n: number) { return '0x' + (n >>> 0).toString(16).padStart(8, '0').toUpperCase(); }

function flagNames(flags: number): string {
  const parts: string[] = [];
  if (flags & FLAG_ALL_SHADES)    parts.push('ALL_SHADES');
  if (flags & FLAG_ANIMATED)      parts.push('ANIMATED');
  if (flags & FLAG_MUX)           parts.push('MUX');
  if (flags & FLAG_DELTA_FRAMES)  parts.push('DELTA_FRAMES');
  if (flags & FLAG_SPARSE_FRAMES) parts.push('SPARSE_FRAMES');
  return parts.length ? parts.join(' | ') : '—';
}

function ManifestDetail({ m }: { m: TileManifest }) {
  const KV_STYLE: h.JSX.CSSProperties = {
    fontFamily: 'monospace', fontSize: '0.90em', lineHeight: 1.9,
    display: 'grid', gridTemplateColumns: 'max-content 1fr',
    columnGap: 12, rowGap: 0,
  };
  const K: h.JSX.CSSProperties = { color: '#4a6070', userSelect: 'none' };
  const V: h.JSX.CSSProperties = { color: '#8ab' };
  const rows: [string, string][] = [
    ['version',    String(m.version)],
    ['headerSize', `${m.headerBytes} B`],
    ['flags',      `${hex8(m.flags)}  (${flagNames(m.flags)})`],
    ['colorCrc32', hex32(m.colorCrc32)],
    ['author',     m.username ? `"${m.username}"` : 'null'],
    ['title',      m.title    ? `"${m.title}"`    : 'null'],
    ['nonce',      hex32(m.nonce)],
  ];
  if (m.frameCount > 1) {
    rows.push(['frameCount',  String(m.frameCount)]);
    rows.push(['loopCount',   String(m.loopCount)]);
    const uniq = [...new Set(m.frameDelays)];
    rows.push(['frameDelays', uniq.length === 1
      ? `${uniq[0]} ms (all frames)`
      : `[${m.frameDelays.join(', ')}] ms`]);
  }
  return (
    <div style={{ ...KV_STYLE, padding: '6px 0 2px' }}>
      {rows.map(([k, v]) => (
        <Fragment key={k}>
          <span style={K}>{k}</span>
          <span style={V}>{v}</span>
        </Fragment>
      ))}
    </div>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const INPUT: h.JSX.CSSProperties = {
  background:'#252525', border:'1px solid #444', borderRadius:3, color:'#ccc', fontSize:'0.63em', padding:'4px 6px',
};
const SM_BTN: h.JSX.CSSProperties = {
  background:'#252525', border:'1px solid #444', borderRadius:3, color:'#ccc', cursor:'pointer', fontSize:'0.58em', padding:'3px 8px',
};
const BTN: h.JSX.CSSProperties = {
  background:'#252525', border:'1px solid #444', borderRadius:3, color:'#ccc', padding:'5px 10px', fontSize:'0.63em', cursor:'pointer',
};
const EXPORT_BTN: h.JSX.CSSProperties = {
  background:'#1a2a1a', border:'1px solid #3a6a3a', borderRadius:3, color:'#8cf',
  padding:'7px 10px', fontSize:'0.68em', textAlign:'center', width:'100%',
};
const TH: h.JSX.CSSProperties = { textAlign:'left', padding:'2px 6px 4px 0', fontWeight:'normal' };
