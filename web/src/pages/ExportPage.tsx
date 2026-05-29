/**
 * ExportPage — Step 3 of the wizard.
 *
 * Left:  codec selector · metadata · password · export actions · mux section.
 * Right: animated GIF preview (or static frame) · per-tile data stats.
 */

import { h } from 'preact';
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
  FLAG_ALL_SHADES, FLAG_ANIMATED,
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

interface TileStat {
  ti:             number;
  tileCol:        number;
  tileRow:        number;
  compressedBytes:number;
  compressedData: Uint8Array;
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
  preview.png                  Rendered preview of the map art
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

Banner codec note
-----------------
If you exported with the banner codec, there are no .litematic files.
Banners are placed automatically by the mod from the state JSON — no schematic needed.
Simply copy loominary_state.json and approach the blank banners in-game.

More info: https://github.com/ZeroHPMinecraft/loominary
`;

// ─── Props ────────────────────────────────────────────────────────────────────

export interface ExportPageProps {
  comp:       CompositionState;
  onBack:     () => void;
  uiFontSize?: number;
}

// ─── ExportPage ───────────────────────────────────────────────────────────────

export function ExportPage({ comp, onBack, uiFontSize = 19 }: ExportPageProps) {
  const [title,     setTitle]     = useState(comp.title ?? '');
  const [author,    setAuthor]    = useState(comp.author ?? '');
  const [codec,     setCodec]     = useState<CodecMode>(comp.codecMode ?? DEFAULT_CODEC);
  const [nonce,     setNonce]     = useState(false);
  const [status,    setStatus]    = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);
  const [pngScale,  setPngScale]  = useState<1|2|4|8>(4);
  const [gifLoop,   setGifLoop]   = useState<0|1>(0);

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
  const [muxApplied,  setMuxApplied]  = useState(false);
  const [muxCodec,    setMuxCodec]    = useState<CodecMode | null>(null);
  // Blank donor tiles auto-added by "Compute Mux" when existing tiles can't absorb overflow.
  // Reset to 0 whenever stats are recomputed (composition changed).
  const [extraDonors, setExtraDonors] = useState(0);

  // Budget warning dialog
  const [budgetWarnLabel,  setBudgetWarnLabel]  = useState<string | null>(null);
  const pendingExportRef = useRef<(() => Promise<void>) | null>(null);

  // Preview
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [gifUrl,  setGifUrl]  = useState<string | null>(null);
  const [show3D,  setShow3D]  = useState(false);

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
              crc32(frame0), null, null, 0, frames.length, 0, delays)
          : toBytesV2(flags, comp.gridCols, comp.gridRows, tileCol, tileRow,
              crc32(frame0), null, null, 0);
        const combined = new Uint8Array(mBytes.length + frames.length * 128 * 128);
        combined.set(mBytes, 0);
        for (let f = 0; f < frames.length; f++)
          combined.set(frames[f] ?? new Uint8Array(128*128), mBytes.length + f * 128*128);
        const compressed = await compress(combined);
        stats.push({ ti, tileCol, tileRow, compressedBytes: compressed.length, compressedData: compressed });
        setStatsProg({ done: ti + 1, total });
      }
      setTileStats(stats);
      // Composition changed → mux allocation is stale; clear so user recomputes.
      setMuxAlloc(null);
      setMuxCodec(null);
      setMuxApplied(false);
      setExtraDonors(0);
    } finally {
      setStatsComputing(false);
      setStatsProg(null);
    }
  }, [comp]);
  useEffect(() => { void computeStats(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // ── Derived budget info ───────────────────────────────────────────────────
  const sizes      = tileStats?.map(s => s.compressedBytes) ?? [];
  const { overBudget, budget } = budgetStatus(sizes, codec);

  const effectivelyOverBudget =
    overBudget.length > 0 &&
    (!muxApplied || !muxAlloc || muxAlloc.unresolved > 0);

  // ── Mux computation ───────────────────────────────────────────────────────
  //
  // Blank donor tiles are appended automatically when existing tiles can't
  // absorb all the overflow.  On recompute they're always recalculated from
  // scratch (old count cleared, new count derived from the algorithm's needs).
  // Users never see these tiles in the editor — they only appear in the export.
  //
  // We add blank donors one at a time rather than using the donorsNeeded()
  // estimate.  The estimate uses budget−BYTES_PER_BANNER as spare-per-donor,
  // but the actual donorSpareCalc overhead differs by codec (CARPET uses
  // LOOM_GUEST_DESC = 10 B per guest, BANNER uses 84 B).  Adding one at a time
  // guarantees convergence regardless of codec-specific capacity nuances.
  function handleComputeMux() {
    if (!tileStats) return;

    let extra = 0;
    let alloc = computeMuxAllocation(sizes, codec);

    while (alloc.unresolved > 0 && extra < 64) {
      extra++;
      alloc = computeMuxAllocation([...sizes, ...Array(extra).fill(0)], codec);
    }

    setExtraDonors(extra);
    setMuxAlloc(alloc);
    setMuxCodec(codec);
    setMuxApplied(false);
  }

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
  async function encodeAndMux(nonceVal: number, localExtraDonors: number, localMuxApplied: boolean, localMuxAlloc: MuxAllocation | null) {
    const ps = await encodeComposition(comp, {
      title: title.trim(), author: author.trim(), nonce: nonceVal, whitelist: [], codecMode: codec,
    });

    if (encryptOn && passwords.length > 0) {
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

    if (localMuxApplied && localMuxAlloc) {
      // Append auto-added blank donor tiles before applying mux so the
      // allocation indices align with ps.tiles indices.
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

  // ── Export State JSON ─────────────────────────────────────────────────────
  const handleExport = useCallback(async () => {
    if (!canExport) return;
    setExporting(true); setStatus('Encoding…');
    try {
      const nonceVal = nonce ? ((Math.random() * 0x100000000) >>> 0) : 0;
      const ps = await encodeAndMux(nonceVal, extraDonors, muxApplied, muxAlloc);
      downloadState(ps);
      setStatus('Exported ✓'); setTimeout(() => setStatus(null), 2500);
    } catch (err) {
      setStatus(`Error: ${err}`); setTimeout(() => setStatus(null), 4000);
    } finally { setExporting(false); setExportProg(null); }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [comp, title, author, codec, nonce, encryptOn, passwords, muxApplied, muxAlloc, extraDonors, canExport]);

  // ── Export PNG ────────────────────────────────────────────────────────────
  const handlePngExport = useCallback(async () => {
    if (!canExport) return;
    setExporting(true); setStatus('Rendering PNG…');
    try {
      const imgData = mapBytesToImageData(comp.frames, comp.activeFrame, comp.gridCols, comp.gridRows);
      const sw = imgData.width * pngScale, sh = imgData.height * pngScale;
      const src = new OffscreenCanvas(imgData.width, imgData.height);
      src.getContext('2d')!.putImageData(imgData, 0, 0);
      const dst = new OffscreenCanvas(sw, sh);
      const dctx = dst.getContext('2d')!; dctx.imageSmoothingEnabled = false;
      dctx.drawImage(src, 0, 0, sw, sh);
      const blob = await dst.convertToBlob({ type: 'image/png' });
      const url  = URL.createObjectURL(blob);
      const a    = document.createElement('a'); a.href = url;
      a.download = (title.trim() || 'loominary') + (pngScale > 1 ? `_${pngScale}x` : '') + '.png';
      a.click(); setTimeout(() => URL.revokeObjectURL(url), 5000);
      setStatus('PNG exported ✓'); setTimeout(() => setStatus(null), 2500);
    } catch (err) { setStatus(`Error: ${err}`); setTimeout(() => setStatus(null), 4000); }
    finally { setExporting(false); }
  }, [comp, pngScale, title, canExport]);

  // ── Export GIF ────────────────────────────────────────────────────────────
  const handleGifExport = useCallback(async () => {
    if (!canExport) return;
    setExporting(true); setStatus('Encoding GIF…');
    try {
      const { encodeAnimatedGif } = await import('../gif-encode.js');
      const bytes = encodeAnimatedGif(comp, gifLoop);
      const blob  = new Blob([bytes.buffer as ArrayBuffer], { type: 'image/gif' });
      const url   = URL.createObjectURL(blob);
      const a = document.createElement('a'); a.href = url;
      a.download = (title.trim() || 'loominary') + '.gif';
      a.click(); setTimeout(() => URL.revokeObjectURL(url), 5000);
      setStatus('GIF exported ✓'); setTimeout(() => setStatus(null), 2500);
    } catch (err) { setStatus(`Error: ${err}`); setTimeout(() => setStatus(null), 4000); }
    finally { setExporting(false); }
  }, [comp, gifLoop, title, canExport]);

  // ── Export Carpet Schematic ───────────────────────────────────────────────
  // Carpet schematics encode the physical 128×128 carpet-block platform that
  // must be placed in-game before the mod writes the LOOM channel data.
  // Only relevant for carpet codecs — banner data is carried by the state JSON.
  const handleSchematicExport = useCallback(async () => {
    if (!canExport || !schematicsAvailable) return;
    setExporting(true); setStatus('Building carpet schematic…');
    try {
      const { exportCarpetSchematic, downloadLitematic } = await import('../schematic.js');
      const { encodeComposition } = await import('../encode.js');
      const ps        = await encodeComposition(comp, {
        title: title.trim(), author: author.trim(), nonce: 0, whitelist: [], codecMode: codec,
      });
      const tileCount = comp.gridCols * comp.gridRows;
      for (let ti = 0; ti < tileCount; ti++) {
        const tileCol  = ti % comp.gridCols, tileRow = Math.floor(ti / comp.gridCols);
        const tile     = ps.tiles[ti];
        if (!tile?.carpetCompressedB64) { setStatus(`Tile (${tileRow},${tileCol}) has no carpet data`); continue; }
        const suffix   = tileCount > 1 ? `_r${tileRow}_c${tileCol}` : '';
        const name     = `loominary_carpet${suffix}`;
        downloadLitematic(
          await exportCarpetSchematic(tile.carpetCompressedB64, name, author.trim() || 'Loominary', tileCol, tileRow, codec),
          `${name}.litematic`,
        );
      }
      setStatus(`Carpet schematic${tileCount > 1 ? 's' : ''} exported ✓`); setTimeout(() => setStatus(null), 2500);
    } catch (err) { setStatus(`Error: ${err}`); setTimeout(() => setStatus(null), 4000); }
    finally { setExporting(false); }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [comp, title, author, codec, canExport, schematicsAvailable]);

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
      const ps = await encodeAndMux(nonceVal, extraDonors, muxApplied, muxAlloc);
      files.push({
        name: 'loominary_state.json',
        data: new TextEncoder().encode(JSON.stringify(ps, null, 2)),
      });

      // 2 — Per-tile carpet platform schematics (carpet codecs only).
      //     Encodes the LOOM zstd payload as carpet nibbles — mirrors the Java
      //     /loominary export command exactly.  Not relevant for banner-only codec.
      if (schematicsAvailable) {
        const { exportCarpetSchematic } = await import('../schematic.js');
        const tileCount = comp.gridCols * comp.gridRows;
        for (let ti = 0; ti < tileCount; ti++) {
          const tileCol  = ti % comp.gridCols, tileRow = Math.floor(ti / comp.gridCols);
          const tile     = ps.tiles[ti];
          if (!tile?.carpetCompressedB64) continue;
          const suffix   = tileCount > 1 ? `_r${tileRow}_c${tileCol}` : '';
          const name     = `loominary_carpet${suffix}`;
          setStatus(`Building ZIP… carpet schematic ${ti + 1}/${tileCount}`);
          const schData  = await exportCarpetSchematic(tile.carpetCompressedB64, name, author.trim() || 'Loominary', tileCol, tileRow, codec);
          files.push({ name: `${name}.litematic`, data: schData });
        }
      }

      // 3 — 4× PNG preview
      setStatus('Building ZIP… rendering preview');
      const imgData = mapBytesToImageData(comp.frames, comp.activeFrame, comp.gridCols, comp.gridRows);
      const oscS = new OffscreenCanvas(imgData.width, imgData.height);
      oscS.getContext('2d')!.putImageData(imgData, 0, 0);
      const osc  = new OffscreenCanvas(imgData.width * 4, imgData.height * 4);
      const octx = osc.getContext('2d')!;
      octx.imageSmoothingEnabled = false;
      octx.drawImage(oscS, 0, 0, osc.width, osc.height);
      files.push({ name: 'preview.png', data: new Uint8Array(await (await osc.convertToBlob({ type: 'image/png' })).arrayBuffer()) });

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
  }, [comp, title, author, codec, nonce, encryptOn, passwords, muxApplied, muxAlloc, extraDonors, schematicsAvailable, canExport]);

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
        <div style={{ fontSize:'0.79em', color:'#5af', fontWeight:'bold', marginBottom:2 }}>Export</div>

        {/* ── Codec ── */}
        <Section label="Codec">
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
                  const ob = budgetStatus(sizes, c).overBudget.length, tot = tileStats.length;
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
        </Section>

        {/* ── Metadata ── */}
        <Section label="Metadata (required)">
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
        </Section>

        {/* ── Encryption ── */}
        <Section label="Encryption">
          <label style={{ display:'flex', alignItems:'center', gap:6, cursor:'pointer', fontSize:'0.63em' }}>
            <input type="checkbox" checked={encryptOn} onChange={e => setEncryptOn((e.target as HTMLInputElement).checked)} />
            Encrypt output
          </label>
          {encryptOn && (
            <>
              <div style={{ fontSize:'0.58em', color:'#555', lineHeight:1.4, marginTop:2 }}>
                AES-256-GCM · PBKDF2-SHA256 (100 000 iterations per password).
              </div>
              <div style={{ display:'flex', gap:4, marginTop:4 }}>
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
        </Section>

        <div style={{ flex:1 }} />

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

        {/* ── Export buttons ── */}
        <button
          onClick={() => tryExport('State JSON', handleExport)}
          disabled={exporting || !canExport}
          style={{ ...EXPORT_BTN, opacity: canExport ? 1 : 0.5, cursor: canExport && !exporting ? 'pointer' : 'not-allowed' }}>
          {exporting ? 'Working…' : `⬇ State JSON${encryptOn && passwords.length > 0 ? ' (encrypted)' : ''}${muxApplied ? ' (mux)' : ''}`}
        </button>

        <div title={!schematicsAvailable ? 'Carpet platform schematics are only generated for carpet codecs — banner codec places banners automatically via the state JSON, no schematic needed' : 'Exports a 128×128 carpet-block platform schematic for each tile'}>
          <button
            onClick={() => tryExport('Schematic', handleSchematicExport)}
            disabled={exporting || !canExport || !schematicsAvailable}
            style={{
              ...EXPORT_BTN, width:'100%',
              opacity: (canExport && schematicsAvailable) ? 1 : 0.35,
              cursor: (canExport && schematicsAvailable && !exporting) ? 'pointer' : 'not-allowed',
            }}>
            ⬇ Carpet Platform Schematic{comp.gridCols * comp.gridRows > 1 ? 's' : ''}
            {!schematicsAvailable && <span style={{ fontSize:'0.53em', marginLeft:5, color:'#777' }}>(carpet codecs only)</span>}
          </button>
        </div>

        <button
          onClick={() => tryExport('Export ZIP', handleZipExport)}
          disabled={exporting || !canExport}
          style={{ ...EXPORT_BTN, opacity: canExport ? 1 : 0.5, cursor: canExport && !exporting ? 'pointer' : 'not-allowed' }}
          title="ZIP: state JSON + schematics (if Banner) + PNG preview + README">
          ⬇ Export all as ZIP
        </button>

        {isAnimated ? (
          <div style={{ display:'flex', flexDirection:'column', gap:4 }}>
            <button onClick={() => void handleGifExport()} disabled={exporting || !canExport}
              style={{ ...EXPORT_BTN, opacity: canExport ? 1 : 0.5, cursor: canExport && !exporting ? 'pointer' : 'not-allowed' }}>
              {exporting ? 'Encoding…' : `⬇ GIF (${maxF} frames)`}
            </button>
            <label style={{ display:'flex', alignItems:'center', gap:5, fontSize:'0.58em', color:'#888', cursor:'pointer' }}>
              <input type="checkbox" checked={gifLoop===1} onChange={e => setGifLoop((e.target as HTMLInputElement).checked ? 1 : 0)} />
              Play once (no loop)
            </label>
          </div>
        ) : (
          <div style={{ display:'flex', alignItems:'center', gap:6 }}>
            <button onClick={() => void handlePngExport()} disabled={exporting || !canExport}
              style={{ ...EXPORT_BTN, flex:1, opacity: canExport ? 1 : 0.5, cursor: canExport && !exporting ? 'pointer' : 'not-allowed' }}>
              ⬇ PNG
            </button>
            <div style={{ display:'flex', gap:2 }}>
              {([1,2,4,8] as const).map(s => (
                <button key={s} onClick={() => setPngScale(s)} style={{
                  background: pngScale===s ? '#1b3556' : '#252525',
                  border: `1px solid ${pngScale===s ? '#4a9eff' : '#444'}`,
                  borderRadius:3, color: pngScale===s ? '#8cf' : '#888',
                  cursor:'pointer', fontSize:'0.58em', padding:'3px 5px',
                }}>{s}×</button>
              ))}
            </div>
          </div>
        )}

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
            <div style={{ height:320, width:'100%', border:'1px solid #333', borderRadius:3, overflow:'hidden' }}>
              <SchematicViewer3D comp={comp} />
            </div>
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
                  const bd  = breakdown(s.compressedBytes, codec);
                  const pct = s.compressedBytes / budget * 100;
                  const mxRole = muxAlloc?.roles.find(r => r.ti === s.ti);
                  return (
                    <tr key={s.ti} style={{ borderBottom:'1px solid #1e1e1e', color: !bd.fits ? '#f93' : '#aaa' }}>
                      <td style={{ padding:'3px 6px 3px 0' }}>
                        {multiTile ? `(${s.tileRow},${s.tileCol})` : 'tile'}
                        {mxRole && mxRole.role !== 'normal' && (
                          <span style={{ marginLeft:4, fontSize:9,
                            color: mxRole.role==='receiver' ? '#fa0' : '#5af',
                            background:'#222', borderRadius:2, padding:'1px 3px' }}>
                            {mxRole.role}
                          </span>
                        )}
                      </td>
                      <td style={{ textAlign:'right', padding:'3px 6px' }}>{(s.compressedBytes/1024).toFixed(1)} KB</td>
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
                      <td style={{ padding:'3px 0 3px 4px' }}>{bd.fits ? '✓' : '✗'}</td>
                    </tr>
                  );
                })}
              </tbody>
              <tfoot>
                <tr style={{ color:'#555', borderTop:'1px solid #2a2a2a' }}>
                  <td colSpan={2} style={{ padding:'4px 6px 0 0', fontSize:'0.58em' }}>
                    Total: {(sizes.reduce((a,b)=>a+b,0)/1024).toFixed(1)} KB
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
          {tileStats && multiTile && (
            <div style={{ background:'#161616', border:'1px solid #2a2a2a', borderRadius:4, padding:'8px 10px' }}>
              <div style={{ display:'flex', alignItems:'center', gap:8, marginBottom:6, flexWrap:'wrap' }}>
                <div style={{ fontSize:'0.63em', color:'#5af', fontWeight:'bold' }}>Mux</div>

                {overBudget.length > 0 && (
                  <div style={{ fontSize:'0.58em', color:'#f93' }}>
                    ⚠ {overBudget.length} tile{overBudget.length>1?'s':''} over budget
                  </div>
                )}
                {overBudget.length === 0 && (
                  <div style={{ fontSize:'0.58em', color:'#666' }}>All tiles fit — mux available for rebalancing</div>
                )}

                {/* Staleness badge */}
                {muxAlloc && muxCodec && muxCodec !== codec && (
                  <div style={{ fontSize:'0.53em', color:'#fa8', background:'#2a1800', border:'1px solid #663', borderRadius:2, padding:'1px 5px' }}>
                    ↺ computed for {CODEC_LABEL[muxCodec]} — recompute for {CODEC_LABEL[codec]}
                  </div>
                )}

                {/* Extra donors badge */}
                {muxAlloc && extraDonors > 0 && (
                  <div style={{ fontSize:'0.53em', color:'#5af', background:'#001633', border:'1px solid #15385a', borderRadius:2, padding:'1px 5px' }}>
                    + {extraDonors} blank donor tile{extraDonors>1?'s':''} auto-added
                  </div>
                )}

                <button onClick={handleComputeMux} style={{ ...SM_BTN, marginLeft:'auto' }}>
                  {muxAlloc ? '↺ Recompute' : '▶ Compute Mux'}
                </button>
              </div>

              <div style={{ fontSize:'0.58em', color:'#666', lineHeight:1.5, marginBottom:6 }}>
                <b style={{ color:'#888' }}>What mux does:</b> Over-budget tiles become <em>receivers</em> — their overflow
                bytes are redistributed into <em>donor</em> tiles with spare capacity. If the existing grid has insufficient
                donor capacity, blank donor tiles are appended automatically. These donor tiles only appear in the exported
                state JSON and are never shown in the editor.
              </div>

              {muxAlloc && (
                <>
                  <table style={{ width:'100%', borderCollapse:'collapse', fontSize:'0.58em', marginBottom:6 }}>
                    <thead>
                      <tr style={{ color:'#666', borderBottom:'1px solid #2a2a2a' }}>
                        <th style={TH}>Tile</th>
                        <th style={{ ...TH, textAlign:'center' }}>Role</th>
                        <th style={{ ...TH, textAlign:'right' }}>Own bytes</th>
                        <th style={{ ...TH, textAlign:'right' }}>Guest bytes</th>
                        <th style={TH}>Details</th>
                      </tr>
                    </thead>
                    <tbody>
                      {muxAlloc.roles.map(r => {
                        const isBlankDonor = r.ti >= (tileStats?.length ?? 0);
                        const tileLabel = isBlankDonor
                          ? `blank ${r.ti - (tileStats?.length ?? 0) + 1}`
                          : multiTile ? `(${Math.floor(r.ti/comp.gridCols)},${r.ti%comp.gridCols})` : 'tile';
                        return (
                          <tr key={r.ti} style={{ borderBottom:'1px solid #1a1a1a',
                            color: isBlankDonor ? '#5af' : r.role==='receiver' ? '#fa0' : r.role==='donor' ? '#5af' : '#666' }}>
                            <td style={{ padding:'3px 6px 3px 0' }}>
                              {tileLabel}
                              {isBlankDonor && (
                                <span style={{ marginLeft:4, fontSize:9, color:'#5af',
                                  background:'#00122a', borderRadius:2, padding:'1px 3px' }}>
                                  auto
                                </span>
                              )}
                            </td>
                            <td style={{ textAlign:'center', padding:'3px 6px' }}>
                              <span style={{
                                background: r.role==='receiver' ? '#332200' : r.role==='donor' ? '#001633' : '#1a1a1a',
                                borderRadius:2, padding:'1px 5px', fontSize:'0.53em',
                              }}>
                                {r.role}
                              </span>
                            </td>
                            <td style={{ textAlign:'right', padding:'3px 6px' }}>
                              {r.ownBytes > 0 ? `${(r.ownBytes/1024).toFixed(1)} KB` : '—'}
                            </td>
                            <td style={{ textAlign:'right', padding:'3px 6px' }}>
                              {r.guestBytes > 0 ? `${(r.guestBytes/1024).toFixed(1)} KB` : '—'}
                            </td>
                            <td style={{ padding:'3px 0 3px 6px', fontSize:'0.53em', color:'#555' }}>
                              {r.role === 'receiver' && r.donors.length > 0 && (() => {
                                const artLen = tileStats?.length ?? 0;
                                return `→ ${r.donors.map(d => d.donorTi >= artLen ? `blank ${d.donorTi - artLen + 1}` : multiTile ? `(${Math.floor(d.donorTi/comp.gridCols)},${d.donorTi%comp.gridCols})` : 'donor').join(', ')}`;
                              })()}
                              {r.role === 'donor' && r.guests.length > 0 && (() => {
                                const artLen = tileStats?.length ?? 0;
                                return `← ${r.guests.map(g => g.rxTi >= artLen ? `blank ${g.rxTi - artLen + 1}` : multiTile ? `(${Math.floor(g.rxTi/comp.gridCols)},${g.rxTi%comp.gridCols})` : 'rx').join(', ')}`;
                              })()}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>

                  {muxAlloc.unresolved > 0 && (
                    <div style={{ fontSize:'0.58em', color:'#f55', marginBottom:4 }}>
                      ⚠ {muxAlloc.unresolved} tile(s) still unresolved — tile payloads may be too large for any donor configuration.
                      Try switching to a higher-capacity codec or reducing payload size.
                    </div>
                  )}

                  {codec !== 'BANNER' && (
                    <div style={{ fontSize:'0.58em', color:'#888', marginBottom:4 }}>
                      Carpet mode: mux routing stored in state JSON. The Java mod re-encodes the
                      LOOM carpet channel at place time using muxCargoB64.
                    </div>
                  )}

                  <div style={{ display:'flex', gap:6, alignItems:'center' }}>
                    <button
                      onClick={() => setMuxApplied(a => !a)}
                      style={{ ...SM_BTN,
                        color: muxApplied ? '#7f7' : '#ccc',
                        border: `1px solid ${muxApplied ? '#3a6a3a' : '#444'}`,
                        background: muxApplied ? '#1a2a1a' : '#252525' }}>
                      {muxApplied ? '✓ Mux applied on export' : 'Apply mux on export'}
                    </button>
                    {muxApplied && muxAlloc.unresolved === 0 && (
                      <span style={{ fontSize:'0.53em', color:'#3a3' }}>✓ Fully resolved</span>
                    )}
                    {muxApplied && muxAlloc.unresolved > 0 && (
                      <span style={{ fontSize:'0.53em', color:'#f93' }}>⚠ Partially resolved</span>
                    )}
                  </div>
                </>
              )}

              {!muxAlloc && (
                <div style={{ fontSize:'0.58em', color:'#555' }}>
                  Click <b>Compute Mux</b> to see which tiles would be receivers and donors.
                  Blank donor tiles will be added automatically if needed.
                </div>
              )}

              {overBudget.length > 0 && !muxAlloc && (
                <div style={{ marginTop:6, fontSize:'0.58em', color:'#666', lineHeight:1.5,
                  borderTop:'1px solid #2a2a2a', paddingTop:6 }}>
                  <b style={{ color:'#888' }}>To reduce payload size without mux:</b> thin frames with Stride/Skip ·
                  reduce colour count (K) · apply Requantize with a restricted palette ·
                  switch to a higher-capacity codec.
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

function Section({ label, children }: { label: string; children: preact.ComponentChildren }) {
  return (
    <div>
      <div style={{ fontSize:'0.58em', color:'#666', marginBottom:5 }}>{label}</div>
      {children}
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
