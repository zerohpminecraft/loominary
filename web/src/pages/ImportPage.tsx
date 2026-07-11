/**
 * ImportPage — Step 1 of the wizard.
 *
 * Left panel: image file, grid, adjustments, palette, quantization settings.
 * Right panel: zoomable/pannable live preview canvas.
 *
 * Calls onProceed when the user clicks "Proceed to Editor →".
 */

import { h } from 'preact';
import { useState, useCallback, useEffect, useRef } from 'preact/hooks';

import {
  requantizeGrid,
  mapBytesToImageData,
  DEFAULT_REQ_PARAMS,
  type RequantizeParams,
  type DitherAlgo as DitherAlgoType,
  type MatchMetric as MatchMetricType,
} from '../quantize.js';
import {
  compositionFromState,
  emptyPayloadState,
  type CompositionState,
} from '../payload-state.js';
import {
  type PreprocessParams,
  DEFAULT_PREPROCESS,
  prepareSourceImage,
} from '../preprocess.js';
import { rgbToOklab } from '../oklab.js';
import { MC_PALETTE }  from '../palette.js';
import { updateCarpetTables } from '../carpet.js';
import { decodeGifFrames } from '../gif-decode.js';
import {
  PALETTE_CHOICES,
  buildPaletteFlag,
  paletteMeansAllShades,
  type PaletteRestriction,
} from '../palette-filters.js';
import {
  loadSessionMetas, deleteSession, clearAllSessions,
  savedAgoLabel,
  type SessionMeta,
} from '../persistence.js';
import { OriginalPreviewPanel } from '../OriginalPreview.js';

// ─── Match quality ────────────────────────────────────────────────────────────

interface MatchQuality {
  accuratePct: number;   // % of pixels with ΔE ≤ 0.05
  avgDelta:    number;   // mean OKLab ΔE across all opaque pixels
}

/**
 * Palette coverage score: for each source pixel, find the nearest entry in
 * `palette` (pure OKLab nearest-neighbour, no dithering, no chroma boost) and
 * measure the ΔE.  Reports what fraction of pixels are within ΔE ≤ 0.05.
 *
 * This measures palette suitability, not algorithm quality — it answers
 * "how well can this palette represent the source image's colours?"
 * The score is stable across dithering/boost changes; it only improves when
 * the palette itself is a better fit (different palette restriction, different
 * preprocessing).
 */
function computeMatchQuality(
  src:     ImageData,
  palette: Uint8Array,   // PaletteFlag: 1 = entry is in palette
  cols:    number,
  rows:    number,
): MatchQuality {
  // Pre-compute OKLab for every palette entry once.
  const entries: [number, number, number][] = [];
  for (let c = 1; c < 256; c++) {
    if (!palette[c]) continue;
    const rgb = MC_PALETTE[c];
    entries.push(rgbToOklab((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff));
  }
  if (!entries.length) return { accuratePct: 0, avgDelta: 1 };

  const GOOD_SQ = 0.05 * 0.05;  // use squared distance in inner loop, sqrt only for winner
  let good = 0, total = 0, sumDelta = 0;
  const W = cols * 128;

  for (let gy = 0; gy < rows * 128; gy++) {
    for (let gx = 0; gx < W; gx++) {
      const si = (gy * W + gx) * 4;
      if (src.data[si + 3] < 128) continue;
      const [sL, sa, sb] = rgbToOklab(src.data[si], src.data[si + 1], src.data[si + 2]);
      let bestSq = Infinity;
      for (const [qL, qa, qb] of entries) {
        const dSq = (sL - qL) ** 2 + (sa - qa) ** 2 + (sb - qb) ** 2;
        if (dSq < bestSq) bestSq = dSq;
      }
      sumDelta += Math.sqrt(bestSq);
      total++;
      if (bestSq <= GOOD_SQ) good++;
    }
  }

  return {
    accuratePct: total > 0 ? (good / total) * 100 : 0,
    avgDelta:    total > 0 ? sumDelta / total      : 0,
  };
}

// ─── bestGridSize ────────────────────────────────────────────────────────────

function bestGridSize(w: number, h: number, maxDim = 8): [number, number] {
  const ratio = w / h;
  let bestCols = 1, bestRows = 1, bestScore = Infinity;
  for (let cols = 1; cols <= maxDim; cols++) {
    for (let rows = 1; rows <= maxDim; rows++) {
      const gridRatio = cols / rows;
      const maxR  = Math.max(ratio, gridRatio);
      const diff  = Math.abs(ratio - gridRatio) / maxR;
      const score = diff * 100 + cols * rows * 0.1;
      if (score < bestScore) { bestScore = score; bestCols = cols; bestRows = rows; }
    }
  }
  return [bestCols, bestRows];
}

// ─── computePreview ─────────────────────────────────────────────────────────

async function computePreview(
  bmp:            ImageBitmap,
  cols:           number,
  rows:           number,
  cropMode:       'scale' | 'center',
  pre:            PreprocessParams,
  palRestriction: PaletteRestriction,
  reqP:           RequantizeParams,
  greyThreshold:  number,
): Promise<{ tiles: Uint8Array[]; quality: MatchQuality }> {
  const img = await prepareSourceImage(bmp, cols * 128, rows * 128, cropMode, pre);
  const dummyComp: CompositionState = {
    gridCols:      cols,
    gridRows:      rows,
    frames:        Array.from({ length: cols * rows }, () => [new Uint8Array(128 * 128)]) as Uint8Array[][],
    frameDelays:   Array.from({ length: cols * rows }, () => [100]) as number[][],
    activeFrame:   0,
    sourceFilename: null,
    title:         null,
    author:        null,
    allShades:     paletteMeansAllShades(palRestriction),
    codecMode:     'BANNER' as const,
  };

  const paletteFlag = buildPaletteFlag(palRestriction, { greyThreshold });
  const tiles = requantizeGrid(img, dummyComp, null, {
    ...reqP,
    customPalette: paletteFlag,
  });

  // Score = palette coverage: nearest-neighbour ΔE against the palette using
  // the preprocessed source (no chroma boost, no quantized tiles).  This
  // measures how well the chosen palette can represent the source image's
  // colours, independent of dithering or chroma boost settings.
  return { tiles, quality: computeMatchQuality(img, paletteFlag, cols, rows) };
}

// ─── State JSON import ────────────────────────────────────────────────────────

function b64ToBytes(s: string): Uint8Array {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/**
 * Reconstruct per-tile pixel data from a PayloadState.
 * Works for all codec modes: carpet (carpetCompressedB64), banner (CJK chunks),
 * and muxed variants.  Art tiles are the first gridCols×gridRows entries;
 * donor-only tiles are skipped.
 */
async function decodeStateToComposition(
  ps: import('../payload-state.js').PayloadState,
  onProgress?: (done: number, total: number) => void,
): Promise<CompositionState> {
  const { decompress }     = await import('../compression.js');
  const { fromBytes: parseMf, extractFrames, av1, av1Lossy, av1Composite, av1StreamOffset } = await import('../manifest.js');
  const { decodeLosslessMono, decodeLossyColor } = await import('../av1/codec.js');
  const { cropTile }       = await import('../encode.js');
  const { assembleChunks } = await import('../cjk-codec.js');

  const artCount = ps.columns * ps.rows;
  const pixelData: Uint8Array[][] = [];
  const BLANK = () => [new Uint8Array(128 * 128)];

  // First pass: decompress every tile and parse its manifest.
  const parsed: ({ raw: Uint8Array; mf: import('../manifest.js').Manifest } | null)[] = [];
  for (let ti = 0; ti < artCount; ti++) {
    const tile = ps.tiles[ti];
    let compressed: Uint8Array | null = null;
    if (tile?.carpetCompressedB64) {
      // Carpet / LOOM modes: full payload stored as base64 zstd.
      compressed = b64ToBytes(tile.carpetCompressedB64);
    } else if (tile && tile.chunks.length > 0) {
      // Banner mode: CJK-encoded chunks contain the compressed payload.
      const cjk = tile.chunks.filter(c => c.length > 2 && c.charCodeAt(2) >= 0x4E00);
      if (cjk.length > 0) {
        try { compressed = assembleChunks(cjk); } catch { /* fall through to blank */ }
      }
    } else if (tile?.muxCargoB64) {
      // Muxed donor with cargo but no carpet field — try the cargo segment.
      compressed = b64ToBytes(tile.muxCargoB64);
    }
    if (!compressed) { parsed.push(null); continue; }
    try {
      const raw = await decompress(compressed);
      parsed.push({ raw, mf: parseMf(raw) });
    } catch {
      parsed.push(null);
    }
  }

  // Composite payloads: every tile carries a segment of ONE composition-wide lossy stream;
  // concatenate them in tile-index order and decode once at full composition size.
  if (parsed.some(p => p != null && av1Composite(p.mf))) {
    const segs = parsed.map(p => p != null && av1Composite(p.mf) ? p.raw.subarray(av1StreamOffset(p.mf)) : null);
    if (segs.every(s => s != null)) {
      try {
        const stream = new Uint8Array(segs.reduce((n, s) => n + s!.length, 0));
        let off = 0;
        for (const s of segs) { stream.set(s!, off); off += s!.length; }
        const frames = await decodeLossyColor(stream, 0, parsed[0]!.mf.frameCount,
          undefined, { width: ps.columns * 128, height: ps.rows * 128 });
        for (let ti = 0; ti < artCount; ti++) {
          pixelData.push(frames.map(fr => cropTile(fr, ti % ps.columns, Math.floor(ti / ps.columns), ps.columns)));
          onProgress?.(ti + 1, artCount);
        }
        return compositionFromState(ps, pixelData);
      } catch { /* fall through to per-tile decode (yields blanks for composite tiles) */ }
    }
  }

  for (let ti = 0; ti < artCount; ti++) {
    const p = parsed[ti];
    if (!p) { pixelData.push(BLANK()); onProgress?.(ti + 1, artCount); continue; }
    try {
      const { raw, mf } = p;
      const frames = av1Composite(mf)
        ? BLANK() // a lone composite segment can't be decoded without its siblings
        : av1Lossy(mf)
        ? await decodeLossyColor(raw, av1StreamOffset(mf), mf.frameCount)
        : av1(mf)
        ? await decodeLosslessMono(raw, av1StreamOffset(mf), mf.frameCount)
        : extractFrames(raw, mf);
      pixelData.push(frames.length > 0 ? frames : BLANK());
    } catch {
      pixelData.push(BLANK());
    }

    onProgress?.(ti + 1, artCount);
  }

  return compositionFromState(ps, pixelData);
}

// ─── Props ────────────────────────────────────────────────────────────────────

export interface ImportPageProps {
  onProceed: (comp: CompositionState, bitmap: ImageBitmap, reqParams: RequantizeParams, cropMode: 'scale' | 'center', pre: PreprocessParams, sourceFrames?: ImageBitmap[] | null, sourceFile?: File | null) => void;
  /** Called when the user imports a state JSON directly — no source bitmap available. */
  onProceedFromState?: (comp: CompositionState) => void;
  onLoadSession?: (id: string) => void;
  uiFontSize?: number;
}

// ─── Chip button ─────────────────────────────────────────────────────────────

function Chip({ active, onClick, children }: {
  active: boolean; onClick: () => void; children: preact.ComponentChildren;
}) {
  return (
    <button onClick={onClick} style={{
      flex: 1, background: active ? '#1b3556' : 'transparent',
      border: `1px solid ${active ? '#4a9eff' : '#444'}`,
      borderRadius: 3, color: active ? '#8cf' : '#888',
      cursor: 'pointer', fontSize: '0.58em', padding: '3px 0',
      textAlign: 'center', whiteSpace: 'nowrap',
    }}>
      {children}
    </button>
  );
}

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

// ─── Worker pool ─────────────────────────────────────────────────────────────
//
// Distributes per-frame quantization across N Web Workers (one per logical
// CPU core, capped at the number of frames).  Results are collected in frame
// order so callers can treat the output like a simple map().
//
// Large buffers are transferred zero-copy in both directions:
//   main → worker: imageData.data.buffer (transferred; original ImageData neutered)
//   worker → main: tile ArrayBuffer[]    (transferred; worker is done with them)

interface QuantizeWorkerResult { frameIndex: number; tileBuffers: ArrayBuffer[] }

async function runQuantizePool(
  preparedImages: ImageData[],
  gridCols:       number,
  gridRows:       number,
  reqParams:      RequantizeParams,
  onProgress:     (done: number) => void,
): Promise<Uint8Array[][]> {
  const total = preparedImages.length;
  if (total === 0) return [];

  const concurrency = Math.min(total, navigator.hardwareConcurrency || 4);
  const ordered     = new Array<Uint8Array[]>(total);

  return new Promise((resolve, reject) => {
    let nextJob  = 0;
    let finished = 0;
    const workers: Worker[] = [];

    function terminateAll() { workers.forEach(w => w.terminate()); }

    function dispatch(worker: Worker) {
      if (nextJob >= total) return;
      const i         = nextJob++;
      const imageData = preparedImages[i];
      const buf       = imageData.data.buffer as ArrayBuffer;
      worker.postMessage(
        { frameIndex: i, imageBuffer: buf,
          width: imageData.width, height: imageData.height,
          gridCols, gridRows, reqParams },
        { transfer: [buf] },
      );
    }

    for (let i = 0; i < concurrency; i++) {
      const w = new Worker(
        new URL('../quantize-worker.ts', import.meta.url),
        { type: 'module' },
      );
      w.onmessage = ({ data }: MessageEvent<QuantizeWorkerResult>) => {
        const { frameIndex, tileBuffers } = data;
        ordered[frameIndex] = tileBuffers.map(b => new Uint8Array(b));
        finished++;
        onProgress(finished);
        if (finished === total) { terminateAll(); resolve(ordered); }
        else dispatch(w);
      };
      w.onerror = (e) => { terminateAll(); reject(new Error(e.message ?? 'Worker error')); };
      workers.push(w);
      dispatch(w);
    }
  });
}

// ─── Constants ────────────────────────────────────────────────────────────────

const DITHER_CYCLE: DitherAlgoType[] = ['NONE', 'FS', 'ATKINSON', 'SIERRA', 'SIERRA2', 'SIERRA_LITE', 'SHIAU_FAN', 'JJN', 'STUCKI', 'BAYER'];
const DITHER_LABEL: Record<string, string> = {
  NONE: 'None', FS: 'FS', ATKINSON: 'Atk',
  SIERRA: 'Sierra', SIERRA2: 'Sierra2', SIERRA_LITE: 'SierraL',
  SHIAU_FAN: 'Shiau', JJN: 'JJN', STUCKI: 'Stucki',
  BAYER: 'Bayer',
};
const ED_ALGOS_SET = new Set<DitherAlgoType>(['FS', 'ATKINSON', 'SIERRA', 'SIERRA2', 'SIERRA_LITE', 'SHIAU_FAN', 'JJN', 'STUCKI']);

const METRIC_CYCLE: MatchMetricType[] = ['OKLAB', 'CHROMA_FIRST', 'LUMA_FIRST', 'HUE_ONLY', 'RGB'];
const METRIC_LABEL: Record<string, string> = {
  OKLAB: 'OKLab', CHROMA_FIRST: 'Chr+', LUMA_FIRST: 'Lum+', HUE_ONLY: 'Hue', RGB: 'RGB',
};

// ─── ImportPage ───────────────────────────────────────────────────────────────

export function ImportPage({ onProceed, onProceedFromState, onLoadSession, uiFontSize = 19 }: ImportPageProps) {
  // ── Session history ────────────────────────────────────────────────────────
  const [sessionMetas, setSessionMetas] = useState<SessionMeta[]>([]);
  const [historyOpen,  setHistoryOpen]  = useState(true);

  useEffect(() => {
    loadSessionMetas().then(setSessionMetas);
  }, []);

  function handleDeleteSession(id: string) {
    void deleteSession(id).then(() =>
      loadSessionMetas().then(setSessionMetas)
    );
  }

  function handleClearHistory() {
    void clearAllSessions().then(() => setSessionMetas([]));
  }

  // ── Image state ────────────────────────────────────────────────────────────
  const [sourceBitmap, setSourceBitmap] = useState<ImageBitmap | null>(null);
  const [filename,     setFilename]     = useState<string | null>(null);
  const [imgDims,      setImgDims]      = useState<[number, number] | null>(null);
  const [dragging,     setDragging]     = useState(false);

  // ── Grid ───────────────────────────────────────────────────────────────────
  // null = "auto" (computed from image dims when image is loaded).
  // Once the user manually types a value it becomes locked and won't be
  // overwritten by subsequent image loads.
  const [colsManual, setColsManual] = useState<number | null>(null);
  const [rowsManual, setRowsManual] = useState<number | null>(null);
  const [autoGrid,   setAutoGrid]   = useState<[number, number] | null>(null);
  const [cropMode, setCropMode] = useState<'scale' | 'center'>('center');
  const [isGif,      setIsGif]      = useState(false);

  // ── Image adjustments ──────────────────────────────────────────────────────
  const [pre, setPre] = useState<PreprocessParams>(DEFAULT_PREPROCESS);

  // ── Palette ────────────────────────────────────────────────────────────────
  const [palRestriction, setPalRestriction] = useState<PaletteRestriction>('legal');
  const [greyThreshold,  setGreyThreshold]  = useState(40);
  const [matchQuality,   setMatchQuality]   = useState<MatchQuality | null>(null);

  // ── Quantization ──────────────────────────────────────────────────────────
  const [dither,          setDither]          = useState<DitherAlgoType>(DEFAULT_REQ_PARAMS.dither);
  const [metric,          setMetric]          = useState<MatchMetricType>(DEFAULT_REQ_PARAMS.metric);
  const [chromaBoost,     setChromaBoost]     = useState(DEFAULT_REQ_PARAMS.chromaBoost);
  const [fsStrength,      setFsStrength]      = useState(DEFAULT_REQ_PARAMS.fsStrength);
  const [atkStrength,     setAtkStrength]     = useState(DEFAULT_REQ_PARAMS.atkStrength);
  const [sierraStr,       setSierraStr]       = useState(DEFAULT_REQ_PARAMS.sierraStrength);
  const [sierra2Str,      setSierra2Str]      = useState(DEFAULT_REQ_PARAMS.sierra2Strength);
  const [sierraLiteStr,   setSierraLiteStr]   = useState(DEFAULT_REQ_PARAMS.sierraLiteStrength);
  const [shiauFanStr,     setShiauFanStr]     = useState(DEFAULT_REQ_PARAMS.shiauFanStrength);
  const [jjnStr,          setJjnStr]          = useState(DEFAULT_REQ_PARAMS.jjnStrength);
  const [stuckiStr,       setStuckiStr]       = useState(DEFAULT_REQ_PARAMS.stuckiStrength);
  const [serpentine,      setSerpentine]      = useState(DEFAULT_REQ_PARAMS.serpentine);
  const [bayerScale,      setBayerScale]      = useState(DEFAULT_REQ_PARAMS.bayerScale);
  const [bayerSize,       setBayerSize]       = useState<2|4|8|16>(DEFAULT_REQ_PARAMS.bayerSize);
  const [tilePalette,     setTilePalette]     = useState(DEFAULT_REQ_PARAMS.tilePalette);

  // ── Status ─────────────────────────────────────────────────────────────────
  const [previewStatus,   setPreviewStatus]   = useState<'idle' | 'computing' | 'done' | string>('idle');
  const [computing,       setComputing]       = useState(false);
  const [proceedProgress, setProceedProgress] = useState(0);
  const [proceedTotal,    setProceedTotal]    = useState(0);

  // ── State JSON import ──────────────────────────────────────────────────────
  const [stateImporting, setStateImporting] = useState(false);
  const [stateImportMsg, setStateImportMsg] = useState<string | null>(null);
  const stateFileRef = useRef<HTMLInputElement | null>(null);

  const handleStateFileInput = useCallback(async (e: Event) => {
    const file = (e.target as HTMLInputElement).files?.[0];
    (e.target as HTMLInputElement).value = '';
    if (!file) return;
    setStateImporting(true);
    setStateImportMsg(`Reading ${file.name}…`);
    try {
      const text = await file.text();
      const ps   = JSON.parse(text) as import('../payload-state.js').PayloadState;
      if (!ps.columns || !ps.rows || !Array.isArray(ps.tiles)) {
        throw new Error('Not a valid loominary_state.json file');
      }
      const total = ps.columns * ps.rows;
      setStateImportMsg(`Decoding 0 / ${total} tiles…`);
      const comp = await decodeStateToComposition(ps, (done, t) =>
        setStateImportMsg(`Decoding ${done} / ${t} tiles…`)
      );
      setStateImportMsg(null);
      setStateImporting(false);
      onProceedFromState?.(comp);
    } catch (err) {
      setStateImportMsg(`Error: ${err instanceof Error ? err.message : err}`);
      setStateImporting(false);
    }
  }, [onProceedFromState]);

  // ── Original image sidebar ────────────────────────────────────────────────
  const [origOpen, setOrigOpen] = useState(true);

  // ── Canvas refs ────────────────────────────────────────────────────────────
  const canvasRef           = useRef<HTMLCanvasElement>(null);
  const previewOffscreenRef = useRef<OffscreenCanvas | null>(null);
  const debounceRef         = useRef<ReturnType<typeof setTimeout> | null>(null);
  const latestBmpRef        = useRef<ImageBitmap | null>(null);
  const latestFileRef       = useRef<File | null>(null);
  const prevDimsRef         = useRef<[number, number]>([0, 0]);

  // ── Zoom / pan state (mutable refs to avoid re-renders during interaction) ─
  const zoomRef       = useRef(4);
  const panXRef       = useRef(0);
  const panYRef       = useRef(0);
  const [zoomDisplay, setZoomDisplay] = useState(4);
  const isPanningRef  = useRef(false);
  const panLastRef    = useRef({ x: 0, y: 0 });

  // ── Render helpers ─────────────────────────────────────────────────────────

  const redraw = useCallback(() => {
    const canvas = canvasRef.current;
    const osc    = previewOffscreenRef.current;
    if (!canvas) return;

    const dpr = window.devicePixelRatio || 1;
    const cw  = canvas.clientWidth;
    const ch  = canvas.clientHeight;
    const pw  = Math.round(cw * dpr);
    const ph  = Math.round(ch * dpr);
    if (canvas.width !== pw || canvas.height !== ph) {
      canvas.width  = pw;
      canvas.height = ph;
    }

    const ctx = canvas.getContext('2d')!;
    ctx.fillStyle = '#111111';
    ctx.fillRect(0, 0, pw, ph);
    if (!osc) return;

    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(
      osc,
      Math.round(panXRef.current * dpr),
      Math.round(panYRef.current * dpr),
      Math.round(osc.width  * zoomRef.current * dpr),
      Math.round(osc.height * zoomRef.current * dpr),
    );
  }, []);

  const fitToView = useCallback(() => {
    const canvas = canvasRef.current;
    const osc    = previewOffscreenRef.current;
    if (!canvas || !osc) return;
    const cw = canvas.clientWidth  || 400;
    const ch = canvas.clientHeight || 400;
    const z  = Math.min(cw / osc.width, ch / osc.height) * 0.9;
    zoomRef.current  = Math.max(0.5, Math.min(32, z));
    panXRef.current  = (cw - osc.width  * zoomRef.current) / 2;
    panYRef.current  = (ch - osc.height * zoomRef.current) / 2;
    setZoomDisplay(parseFloat(zoomRef.current.toFixed(1)));
    redraw();
  }, [redraw]);

  const applyZoom = useCallback((factor: number, cx?: number, cy?: number) => {
    const oldZ = zoomRef.current;
    const newZ = Math.max(0.5, Math.min(32, oldZ * factor));
    if (newZ === oldZ) return;
    if (cx !== undefined && cy !== undefined) {
      panXRef.current = cx - (cx - panXRef.current) * newZ / oldZ;
      panYRef.current = cy - (cy - panYRef.current) * newZ / oldZ;
    }
    zoomRef.current = newZ;
    setZoomDisplay(parseFloat(newZ.toFixed(1)));
    redraw();
  }, [redraw]);

  // ── Canvas events ──────────────────────────────────────────────────────────

  const onCanvasWheel = useCallback((e: WheelEvent) => {
    e.preventDefault();
    applyZoom(e.deltaY < 0 ? 1.25 : 0.8, e.offsetX, e.offsetY);
  }, [applyZoom]);

  const onCanvasPointerDown = useCallback((e: PointerEvent) => {
    if (!previewOffscreenRef.current) return;
    isPanningRef.current = true;
    panLastRef.current   = { x: e.clientX, y: e.clientY };
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
    (e.target as HTMLElement).style.cursor = 'grabbing';
  }, []);

  const onCanvasPointerMove = useCallback((e: PointerEvent) => {
    if (!isPanningRef.current) return;
    panXRef.current += e.clientX - panLastRef.current.x;
    panYRef.current += e.clientY - panLastRef.current.y;
    panLastRef.current = { x: e.clientX, y: e.clientY };
    redraw();
  }, [redraw]);

  const onCanvasPointerUp = useCallback((e: PointerEvent) => {
    isPanningRef.current = false;
    (e.target as HTMLElement).style.cursor = previewOffscreenRef.current ? 'grab' : 'default';
  }, []);

  // ── Wire wheel listener (non-passive) and ResizeObserver ──────────────────
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    canvas.addEventListener('wheel', onCanvasWheel, { passive: false });
    const ro = new ResizeObserver(() => redraw());
    ro.observe(canvas);
    return () => { canvas.removeEventListener('wheel', onCanvasWheel); ro.disconnect(); };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── Image loading ──────────────────────────────────────────────────────────

  const loadImage = useCallback(async (file: File) => {
    try {
      const bmp = await createImageBitmap(file);
      latestBmpRef.current  = bmp;
      latestFileRef.current = file;
      setSourceBitmap(bmp);
      setFilename(file.name);
      setImgDims([bmp.width, bmp.height]);
      // Update auto-grid from image dims without overriding any manual user choice.
      setAutoGrid(bestGridSize(bmp.width, bmp.height));
      setCropMode(bmp.width !== bmp.height ? 'center' : 'scale');
      setIsGif(file.type === 'image/gif');
    } catch (err) {
      setPreviewStatus(`Error loading image: ${err}`);
    }
  }, []);

  const handleFileInput = useCallback((e: Event) => {
    const file = (e.target as HTMLInputElement).files?.[0];
    if (file) void loadImage(file);
    (e.target as HTMLInputElement).value = '';
  }, [loadImage]);

  const handleDragOver  = useCallback((e: DragEvent) => { e.preventDefault(); setDragging(true);  }, []);
  const handleDragLeave = useCallback(() => setDragging(false), []);
  const handleDrop      = useCallback((e: DragEvent) => {
    e.preventDefault(); setDragging(false);
    const file = e.dataTransfer?.files[0];
    if (file?.type.startsWith('image/')) void loadImage(file);
  }, [loadImage]);

  // ── Build RequantizeParams ─────────────────────────────────────────────────

  const buildReqParams = useCallback((): RequantizeParams => ({
    legalOnly:          palRestriction !== 'all',
    dither, metric,
    fsStrength, atkStrength,
    sierraStrength:     sierraStr,
    sierra2Strength:    sierra2Str,
    sierraLiteStrength: sierraLiteStr,
    shiauFanStrength:   shiauFanStr,
    jjnStrength:        jjnStr,
    stuckiStrength:     stuckiStr,
    serpentine,
    bayerScale, bayerSize,
    chromaBoost, tilePalette,
    useCustomDither: false, ditherMask: null,
  }), [palRestriction, dither, metric, fsStrength, atkStrength,
       sierraStr, sierra2Str, sierraLiteStr, shiauFanStr, jjnStr, stuckiStr,
       serpentine, bayerScale, bayerSize, chromaBoost, tilePalette]);

  // ── Effective grid dimensions ─────────────────────────────────────────────
  // colsManual/rowsManual are null until the user explicitly sets them; fall
  // back to autoGrid (computed from the loaded image) or 1 as a hard default.
  const effectiveCols = colsManual ?? autoGrid?.[0] ?? 1;
  const effectiveRows = rowsManual ?? autoGrid?.[1] ?? 1;

  // ── Auto-preview with debounce ────────────────────────────────────────────

  useEffect(() => {
    if (!sourceBitmap) return;
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(async () => {
      const bmp = latestBmpRef.current;
      if (!bmp) return;
      setComputing(true);
      setPreviewStatus('computing');
      try {
        const reqP = buildReqParams();
        const { tiles, quality } = await computePreview(bmp, effectiveCols, effectiveRows, cropMode, pre, palRestriction, reqP, greyThreshold);
        setMatchQuality(quality);
        const imgData = mapBytesToImageData(tiles.map(t => [t]), 0, effectiveCols, effectiveRows);

        const osc  = new OffscreenCanvas(imgData.width, imgData.height);
        const octx = osc.getContext('2d')!;
        octx.putImageData(imgData, 0, 0);
        previewOffscreenRef.current = osc;

        const prevDims = prevDimsRef.current;
        if (prevDims[0] !== imgData.width || prevDims[1] !== imgData.height) {
          prevDimsRef.current = [imgData.width, imgData.height];
          fitToView();
        } else {
          redraw();
        }

        setPreviewStatus('done');
      } catch (err) {
        setPreviewStatus(`Error: ${err}`);
      } finally {
        setComputing(false);
      }
    }, 600);
    return () => { if (debounceRef.current) clearTimeout(debounceRef.current); };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sourceBitmap, effectiveCols, effectiveRows, cropMode, pre, palRestriction, greyThreshold, dither, metric, chromaBoost,
      fsStrength, atkStrength, sierraStr, sierra2Str, sierraLiteStr, shiauFanStr, jjnStr, stuckiStr,
      serpentine, bayerScale, bayerSize, tilePalette]);

  // ── Proceed ───────────────────────────────────────────────────────────────

  const handleProceed = useCallback(async () => {
    const bmp  = latestBmpRef.current;
    const file = latestFileRef.current;
    if (!bmp) return;
    setComputing(true);
    setPreviewStatus('computing');
    setProceedProgress(0);
    setProceedTotal(0);
    try {
      const reqP        = buildReqParams();
      const paletteFlag = buildPaletteFlag(palRestriction, { greyThreshold });

      // Snapshot effective dims at proceed time so they're stable for the whole run.
      const eCols = colsManual ?? autoGrid?.[0] ?? 1;
      const eRows = rowsManual ?? autoGrid?.[1] ?? 1;

      // Try to decode all GIF frames if the source is an animated GIF.
      let gifFrames: import('../gif-decode.js').GifFrame[] | null = null;
      if (file && file.type === 'image/gif') {
        try {
          const decoded = await decodeGifFrames(file);
          if (decoded.length > 1) gifFrames = decoded;
        } catch { /* fall through to single-frame */ }
      }

      const ps     = emptyPayloadState(eCols, eRows);
      ps.allShades = paletteMeansAllShades(palRestriction);
      ps.sourceFilename = filename;

      if (gifFrames) {
        const total = gifFrames.length;
        setProceedTotal(total);

        // Phase 1: prepare all source images in parallel (canvas / GPU-accelerated).
        // Firing them all at once lets the browser overlap rasterisation work.
        setPreviewStatus(`Preparing ${total} frames…`);
        const preparedImages = await Promise.all(
          gifFrames.map(({ bitmap: fb }) =>
            prepareSourceImage(fb, eCols * 128, eRows * 128, cropMode, pre)
          )
        );

        // Phase 2: quantize all frames in parallel using a Web Worker pool
        // (one worker per logical CPU core, capped at frame count).
        // Each ImageData buffer is transferred zero-copy to its worker.
        setPreviewStatus(`Quantizing 0 of ${total}…`);
        setProceedProgress(0);
        const perFrameTiles = await runQuantizePool(
          preparedImages, eCols, eRows,
          { ...reqP, customPalette: paletteFlag },
          (done) => {
            setProceedProgress(done);
            setPreviewStatus(`Quantizing ${done} of ${total}…`);
          },
        );

        const delays = gifFrames.map(f => f.delayMs);

        // Reshape: [animFrame][tile] → [tile][animFrame]
        const framesByTile: Uint8Array[][] = Array.from(
          { length: eCols * eRows },
          (_, ti) => perFrameTiles.map(ft => ft[ti]),
        );
        const frameDelays = Array.from({ length: eCols * eRows }, () => [...delays]);
        const comp        = { ...compositionFromState(ps, framesByTile), frameDelays };
        const sourceBitmaps = gifFrames.map(f => f.bitmap);
        onProceed(comp, sourceBitmaps[0], reqP, cropMode, pre, sourceBitmaps, latestFileRef.current);
      } else {
        const { tiles } = await computePreview(bmp, eCols, eRows, cropMode, pre, palRestriction, reqP, greyThreshold);
        onProceed(compositionFromState(ps, tiles.map(t => [t])), bmp, reqP, cropMode, pre, null, latestFileRef.current);
      }
    } catch (err) {
      setPreviewStatus(`Error: ${err}`);
    } finally {
      setComputing(false);
      setProceedProgress(0);
      setProceedTotal(0);
    }
  }, [buildReqParams, colsManual, rowsManual, autoGrid, cropMode, pre, palRestriction, greyThreshold, onProceed]);

  // ── Status display ─────────────────────────────────────────────────────────

  const statusText  =
    previewStatus === 'idle'      ? 'Select an image to begin' :
    previewStatus === 'computing' ? 'Computing…'               :
    previewStatus === 'done'      ? 'Scroll to zoom · drag to pan' :
    previewStatus;
  const statusColor =
    previewStatus.startsWith('Error') ? '#f77' :
    previewStatus === 'computing'     ? '#888' :
    previewStatus === 'done'          ? '#556' : '#888';

  // ─── Render ────────────────────────────────────────────────────────────────
  return (
    <div style={{ display: 'flex', height: '100%', overflow: 'hidden', fontSize: Math.round(uiFontSize * 1.25) }}>

      {/* ── Left settings panel ── */}
      <div style={{
        width: 360, minWidth: 280, flexShrink: 0,
        background: '#1e1e1e', borderRight: '1px solid #333',
        overflowY: 'auto', padding: '8px 12px',
        color: '#ccc',
        display: 'flex', flexDirection: 'column',
      }}>

        {/* ── Session history ── */}
        <div style={{ marginBottom: 10, border: '1px solid #333', borderRadius: 4 }}>
          <button
            onClick={() => setHistoryOpen(o => !o)}
            style={{
              width: '100%', textAlign: 'left', background: '#1e1e1e',
              border: 'none', borderBottom: historyOpen ? '1px solid #333' : 'none',
              color: '#aaa', cursor: 'pointer', fontSize: '0.58em',
              padding: '5px 8px', display: 'flex', alignItems: 'center', gap: 5,
            }}
          >
            <span style={{ color: '#5af' }}>{historyOpen ? '▾' : '▸'}</span>
            <span style={{ flex: 1, fontWeight: 'bold' }}>
              Recent sessions{sessionMetas.length > 0 ? ` (${sessionMetas.length})` : ''}
            </span>
            {historyOpen && sessionMetas.length > 0 && (
              <span
                onClick={e => { e.stopPropagation(); handleClearHistory(); }}
                title="Remove all history"
                style={{ color: '#666', fontSize: '0.9em', padding: '0 2px', lineHeight: 1 }}
              >Clear all</span>
            )}
          </button>
          {historyOpen && (
            <div style={{ maxHeight: 260, overflowY: 'auto' }}>
              {sessionMetas.length === 0 ? (
                <div style={{ padding: '8px 10px', fontSize: '0.53em', color: '#555', lineHeight: 1.5 }}>
                  No saved sessions yet. Proceed to the editor to save your first session.
                </div>
              ) : sessionMetas.map(s => (
                <SessionHistoryItem
                  key={s.id}
                  meta={s}
                  onLoad={() => onLoadSession?.(s.id)}
                  onDelete={() => handleDeleteSession(s.id)}
                />
              ))}
            </div>
          )}
        </div>

        <Step n={1} title="Image"
          hint="Load your source image — PNG, JPEG, WebP, or animated GIF. Drop a file below or browse to choose one. The Minecraft preview on the right updates live as you adjust settings." />
        <div
          onDragOver={handleDragOver} onDragLeave={handleDragLeave} onDrop={handleDrop}
          style={{
            border: `2px dashed ${dragging ? '#5af' : '#444'}`,
            borderRadius: 5, padding: '12px 8px', textAlign: 'center',
            color: dragging ? '#5af' : '#666', fontSize: '0.58em', marginBottom: 6,
            background: dragging ? '#1a2a3a' : 'transparent', transition: 'all 0.15s',
          }}
        >
          <div>Drag &amp; drop an image here</div>
          <div style={{ marginTop: 4, color: '#555' }}>or</div>
        </div>
        <label style={{ ...BTN_STYLE, textAlign: 'center', cursor: 'pointer', display: 'block', marginBottom: 6 }}>
          Choose File…
          <input type="file" accept="image/*" onChange={handleFileInput} style={{ display: 'none' }} />
        </label>
        {filename && (
          <div style={{ fontSize: '0.58em', color: '#888', wordBreak: 'break-all' }}>
            {filename}
            {imgDims && <span style={{ color: '#555' }}> ({imgDims[0]}×{imgDims[1]})</span>}
          </div>
        )}

        {/* ── State JSON import ── */}
        <div style={{ margin: '8px 0 4px', borderTop: '1px solid #2a2a2a', paddingTop: 8 }}>
          <label style={{
            ...BTN_STYLE, textAlign: 'center', cursor: stateImporting ? 'wait' : 'pointer',
            display: 'block', marginBottom: 4, opacity: stateImporting ? 0.5 : 1,
          }}>
            {stateImporting ? stateImportMsg : 'Import state JSON…'}
            <input
              ref={el => { if (el) stateFileRef.current = el; }}
              type="file" accept=".json,application/json"
              onChange={handleStateFileInput}
              disabled={stateImporting}
              style={{ display: 'none' }}
            />
          </label>
          {stateImportMsg && !stateImporting && (
            <div style={{ fontSize: '0.53em', color: '#f77', marginBottom: 4 }}>{stateImportMsg}</div>
          )}
          <div style={{ fontSize: '0.51em', color: '#444', lineHeight: 1.4 }}>
            Reload pixel data from a previously exported <span style={{ fontFamily: 'monospace' }}>loominary_state.json</span> to continue editing.
          </div>
        </div>

        {isGif && (
          <div style={{
            fontSize: '0.53em', color: '#888', lineHeight: 1.4,
            marginTop: 5, padding: '4px 7px',
            background: '#1a1a10', border: '1px solid #443', borderRadius: 3,
          }}>
            ℹ Animated GIFs only preview the first frame here. When you proceed,
            every frame will be quantized — large GIFs with many frames may take
            a moment to process.
          </div>
        )}

        <Step n={2} title="Grid &amp; Crop"
          hint="One map tile covers 128×128 pixels in-game. A 2×3 grid produces 6 tiles displayed side-by-side. Loominary suggests a grid from your image's aspect ratio — type to override. Scale stretches to fill every tile; Center crop trims the edges." />
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
          <label style={{ color: '#aaa' }}>Cols</label>
          <input type="number" min={1} max={128} value={effectiveCols} style={NUM_INPUT_STYLE}
            onInput={e => {
              const v = parseInt((e.target as HTMLInputElement).value);
              setColsManual(isNaN(v) ? null : Math.max(1, Math.min(128, v)));
            }} />
          <span style={{ color: '#555' }}>×</span>
          <label style={{ color: '#aaa' }}>Rows</label>
          <input type="number" min={1} max={128} value={effectiveRows} style={NUM_INPUT_STYLE}
            onInput={e => {
              const v = parseInt((e.target as HTMLInputElement).value);
              setRowsManual(isNaN(v) ? null : Math.max(1, Math.min(128, v)));
            }} />
          {(colsManual !== null || rowsManual !== null) && (
            <button
              onClick={() => { setColsManual(null); setRowsManual(null); }}
              title="Reset to auto (computed from image dimensions)"
              style={{
                background: 'none', border: '1px solid #555', borderRadius: 3,
                color: '#777', cursor: 'pointer', fontSize: '0.53em', padding: '2px 5px',
              }}
            >auto</button>
          )}
        </div>
        {colsManual === null && rowsManual === null && autoGrid && (
          <div style={{ fontSize: '0.53em', color: '#555', marginBottom: 4 }}>
            Auto-sized from image — type to override
          </div>
        )}

        <div style={{ display: 'flex', gap: 12, marginBottom: 4 }}>
          {(['scale', 'center'] as const).map(mode => (
            <label key={mode} style={{ display: 'flex', alignItems: 'center', gap: 4, cursor: 'pointer' }}>
              <input type="radio" name="cropMode" value={mode}
                checked={cropMode === mode} onChange={() => setCropMode(mode)} />
              <span style={{ color: '#bbb', fontSize: '0.58em' }}>
                {mode === 'scale' ? 'Scale to grid' : 'Center crop'}
              </span>
            </label>
          ))}
        </div>

        <Step n={3} title="Adjustments"
          hint="Pre-processing applied before colour matching. Boosting saturation often improves results — the Minecraft palette has a limited colour range, so vivid sources map better." />
        {(
          [
            { key: 'brightness', label: 'Brightness' },
            { key: 'contrast',   label: 'Contrast'   },
            { key: 'saturation', label: 'Saturation'  },
          ] as const
        ).map(({ key, label }) => (
          <div key={key} style={{ marginBottom: 5 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 2 }}>
              <span style={{ color: '#aaa', fontSize: '0.58em' }}>{label}</span>
              <span style={{ color: '#666', fontSize: '0.58em' }}>{pre[key].toFixed(2)}</span>
            </div>
            <input
              type="range" min={0} max={2} step={0.05} value={pre[key]}
              onInput={e => setPre(p => ({ ...p, [key]: parseFloat((e.target as HTMLInputElement).value) }))}
              style={{ width: '100%' }}
            />
          </div>
        ))}

        <Step n={4} title="Palette"
          hint="Restricts which Minecraft map colours are available. Legal = normally-placeable blocks (~186 colours). All Shades adds mod-exclusive shades for a larger palette. Carpet-only is required by carpet-channel codecs on the export screen." />
        {PALETTE_CHOICES.map(choice => (
          <label key={choice.id} style={{ display: 'flex', gap: 6, marginBottom: 5, cursor: 'pointer', alignItems: 'flex-start' }}>
            <input type="radio" name="palette" value={choice.id}
              checked={palRestriction === choice.id}
              onChange={() => setPalRestriction(choice.id)}
              style={{ marginTop: 2, flexShrink: 0 }} />
            <div>
              <div style={{ color: '#ccc', fontSize: '0.58em' }}>{choice.label}</div>
              <div style={{ color: '#666', fontSize: '0.53em', lineHeight: 1.3 }}>{choice.description}</div>
            </div>
          </label>
        ))}

        {/* Greyscale chroma threshold */}
        {palRestriction === 'greyscale' && (
          <SliderParam
            label={`Chroma threshold: ${greyThreshold}`}
            min={5} max={120} step={5}
            value={greyThreshold}
            onChange={setGreyThreshold}
          />
        )}

        {/* Match quality score */}
        {matchQuality !== null && (
          <div style={{
            marginTop: 6, padding: '5px 8px', borderRadius: 3,
            background: '#1a1a1a', border: '1px solid #333',
            fontSize: '0.53em', lineHeight: 1.5,
          }}>
            <div style={{
              color: matchQuality.accuratePct >= 75 ? '#7f7'
                   : matchQuality.accuratePct >= 50 ? '#fc6'
                   : '#f77',
              fontWeight: 'bold', marginBottom: 2,
            }}>
              Palette coverage: {matchQuality.accuratePct.toFixed(0)}%
            </div>
            <div style={{ color: '#666' }}>
              avg ΔE {matchQuality.avgDelta.toFixed(3)}
              {' · '}
              {matchQuality.accuratePct >= 75 ? 'good palette for this image'
               : matchQuality.accuratePct >= 50 ? 'moderate palette fit'
               : 'poor fit — try a broader palette'}
            </div>
            <div style={{ color: '#444', fontSize: '0.47em', marginTop: 2 }}>
              Measures palette suitability only — unaffected by dithering or chroma boost
            </div>
          </div>
        )}

        <Step n={5} title="Quantization"
          hint="Controls how pixels are colour-matched to the palette. Dithering spreads matching error across neighbouring pixels to reduce banding — Floyd–Steinberg (FS) is a solid starting point. Chroma boost pre-amplifies saturation before matching." />
        <div style={{ fontSize: '0.53em', color: '#666', marginBottom: 3 }}>Dither</div>
        <div style={{ display: 'flex', gap: 2, marginBottom: 6, flexWrap: 'wrap' }}>
          {DITHER_CYCLE.map(a => (
            <Chip key={a} active={dither === a} onClick={() => setDither(a)}>{DITHER_LABEL[a]}</Chip>
          ))}
        </div>
        {dither === 'FS'          && <SliderParam label={`FS strength: ${fsStrength.toFixed(1)}`}         min={0.1} max={1} step={0.1} value={fsStrength}    onChange={setFsStrength} />}
        {dither === 'ATKINSON'    && <SliderParam label={`Atk strength: ${atkStrength.toFixed(1)}`}       min={0.1} max={1} step={0.1} value={atkStrength}   onChange={setAtkStrength} />}
        {dither === 'SIERRA'      && <SliderParam label={`Sierra strength: ${sierraStr.toFixed(1)}`}      min={0.1} max={1} step={0.1} value={sierraStr}     onChange={setSierraStr} />}
        {dither === 'SIERRA2'     && <SliderParam label={`Sierra2 strength: ${sierra2Str.toFixed(1)}`}    min={0.1} max={1} step={0.1} value={sierra2Str}    onChange={setSierra2Str} />}
        {dither === 'SIERRA_LITE' && <SliderParam label={`SierraL strength: ${sierraLiteStr.toFixed(1)}`} min={0.1} max={1} step={0.1} value={sierraLiteStr} onChange={setSierraLiteStr} />}
        {dither === 'SHIAU_FAN'   && <SliderParam label={`Shiau-Fan strength: ${shiauFanStr.toFixed(1)}`} min={0.1} max={1} step={0.1} value={shiauFanStr}   onChange={setShiauFanStr} />}
        {dither === 'JJN'         && <SliderParam label={`JJN strength: ${jjnStr.toFixed(1)}`}            min={0.1} max={1} step={0.1} value={jjnStr}        onChange={setJjnStr} />}
        {dither === 'STUCKI'      && <SliderParam label={`Stucki strength: ${stuckiStr.toFixed(1)}`}      min={0.1} max={1} step={0.1} value={stuckiStr}     onChange={setStuckiStr} />}
        {ED_ALGOS_SET.has(dither) && (
          <label style={{ display: 'flex', alignItems: 'center', gap: 5, cursor: 'pointer', marginBottom: 4 }}>
            <input type="checkbox" checked={serpentine}
              onChange={e => setSerpentine((e.target as HTMLInputElement).checked)} />
            <span style={{ fontSize: '0.58em' }}>Serpentine scan</span>
          </label>
        )}
        {dither === 'BAYER' && (
          <>
            <SliderParam label={`Bayer scale: ${bayerScale.toFixed(2)}`} min={0.02} max={0.20} step={0.02} value={bayerScale} onChange={setBayerScale} />
            <div style={{ fontSize: '0.53em', color: '#666', marginBottom: 3 }}>Matrix size</div>
            <div style={{ display: 'flex', gap: 2, marginBottom: 6 }}>
              {([2, 4, 8, 16] as const).map(sz => (
                <Chip key={sz} active={bayerSize === sz} onClick={() => setBayerSize(sz)}>{sz}×{sz}</Chip>
              ))}
            </div>
          </>
        )}

        <div style={{ fontSize: '0.53em', color: '#666', marginBottom: 3 }}>Metric</div>
        <div style={{ display: 'flex', gap: 2, marginBottom: 4, flexWrap: 'wrap' }}>
          {METRIC_CYCLE.map(m => (
            <Chip key={m} active={metric === m} onClick={() => setMetric(m)}>{METRIC_LABEL[m]}</Chip>
          ))}
        </div>
        <MetricHelp metric={metric} />
        <SliderParam label={`Chroma boost: ${chromaBoost.toFixed(2)}×`} min={0.25} max={4.0} step={0.05}
          value={chromaBoost} onChange={setChromaBoost} />
        <label style={{ display: 'flex', alignItems: 'center', gap: 5, cursor: 'pointer', marginTop: 4 }}>
          <input type="checkbox" checked={tilePalette}
            onChange={e => setTilePalette((e.target as HTMLInputElement).checked)} />
          <span style={{ fontSize: '0.58em' }}>Tile palette</span>
        </label>

        {/* Carpet table override + Proceed */}
        <div style={{ flex: 1 }} />
        <CarpetDumpInput />
        <Step n={6} title="Proceed to Editor"
          hint="When the preview looks good, continue to the editor for per-tile fine-tuning and additional adjustments before export." />
        <button
          onClick={() => void handleProceed()}
          disabled={!sourceBitmap || computing}
          style={{
            ...BTN_STYLE, marginTop: 16, textAlign: 'center',
            padding: '7px 0', fontSize: '0.68em',
            background:   (!sourceBitmap || computing) ? '#252525' : '#1a3a1a',
            borderColor:  (!sourceBitmap || computing) ? '#444'    : '#3a7a3a',
            color:        (!sourceBitmap || computing) ? '#555'    : '#8f8',
            cursor:       (!sourceBitmap || computing) ? 'not-allowed' : 'pointer',
          }}
        >
          {computing && proceedTotal > 0
            ? `Quantizing frame ${proceedProgress} of ${proceedTotal}…`
            : computing ? 'Computing…' : 'Proceed to Editor →'}
        </button>

        {/* Progress bar — shown only during multi-frame GIF import */}
        {proceedTotal > 0 && (
          <div style={{ marginTop: 6 }}>
            <div style={{
              height: 4, borderRadius: 2, background: '#333', overflow: 'hidden',
            }}>
              <div style={{
                height: '100%', borderRadius: 2, background: '#3a7a3a',
                width: `${Math.round((proceedProgress / proceedTotal) * 100)}%`,
                transition: 'width 0.1s linear',
              }} />
            </div>
            <div style={{ fontSize: '0.53em', color: '#666', marginTop: 3 }}>
              {proceedProgress} / {proceedTotal} frames
            </div>
          </div>
        )}
      </div>

      {/* ── Original image preview panel ── */}
      <OriginalPreviewPanel
        bitmap={sourceBitmap}
        open={origOpen}
        onClose={() => setOrigOpen(false)}
        onOpen={() => setOrigOpen(true)}
      />

      {/* ── Right preview panel ── */}
      <div style={{ flex: 1, position: 'relative', overflow: 'hidden', background: '#111' }}>

        {/* Zoomable canvas */}
        <canvas
          ref={canvasRef}
          style={{
            position: 'absolute', inset: 0,
            width: '100%', height: '100%',
            cursor: sourceBitmap ? 'grab' : 'default',
            display: 'block',
          }}
          onPointerDown={onCanvasPointerDown as unknown as h.JSX.MouseEventHandler<HTMLCanvasElement>}
          onPointerMove={onCanvasPointerMove as unknown as h.JSX.MouseEventHandler<HTMLCanvasElement>}
          onPointerUp={onCanvasPointerUp   as unknown as h.JSX.MouseEventHandler<HTMLCanvasElement>}
        />

        {/* Empty-state icon */}
        {!sourceBitmap && (
          <div style={{
            position: 'absolute', inset: 0,
            display: 'flex', flexDirection: 'column',
            alignItems: 'center', justifyContent: 'center',
            pointerEvents: 'none', gap: 10,
          }}>
            <div style={{ fontSize: '2.5em', opacity: 0.15 }}>🖼</div>
            <div style={{ fontSize: '0.68em', color: '#444' }}>Select an image to begin</div>
          </div>
        )}

        {/* Status (bottom-left) */}
        <div style={{
          position: 'absolute', bottom: 8, left: 10,
          fontSize: '0.58em', color: statusColor, pointerEvents: 'none',
        }}>
          {previewStatus !== 'idle' && statusText}
        </div>

        {/* Zoom controls (bottom-right) */}
        {sourceBitmap && (
          <div style={{
            position: 'absolute', bottom: 8, right: 8,
            display: 'flex', alignItems: 'center', gap: 3,
          }}>
            <ZoomBtn onClick={() => applyZoom(1.25)}>+</ZoomBtn>
            <span style={{ fontSize: '0.58em', color: '#666', minWidth: 36, textAlign: 'center' }}>
              {zoomDisplay}×
            </span>
            <ZoomBtn onClick={() => applyZoom(0.8)}>−</ZoomBtn>
            <ZoomBtn onClick={fitToView} title="Fit to view">⊡</ZoomBtn>
          </div>
        )}
      </div>
    </div>
  );
}

// ─── MetricHelp ──────────────────────────────────────────────────────────────

const METRIC_INFO: Array<{ key: MatchMetricType; label: string; desc: string; when: string }> = [
  {
    key:   'OKLAB',
    label: 'OKLab',
    desc:  'Balanced perceptual distance — equal weight on lightness and colour.',
    when:  'Good default for most images.',
  },
  {
    key:   'CHROMA_FIRST',
    label: 'Chr+',
    desc:  '4× weight on hue/saturation vs lightness.',
    when:  'Saturated or vivid images where colour accuracy matters more than brightness.',
  },
  {
    key:   'LUMA_FIRST',
    label: 'Lum+',
    desc:  '4× weight on lightness vs hue.',
    when:  'Images where shading/contrast matters most — faces, landscapes, greyscale.',
  },
  {
    key:   'HUE_ONLY',
    label: 'Hue',
    desc:  'Matches only the colour-wheel angle; ignores brightness and saturation. Near-grey pixels fall back to OKLab.',
    when:  'Flat cartoon or pixel art where hue fidelity matters and brightness can vary.',
  },
  {
    key:   'RGB',
    label: 'RGB',
    desc:  'Euclidean distance in linear sRGB — how most colour pickers measure difference.',
    when:  'Images that look better when colour-matched in RGB rather than perceptual space.',
  },
];

function MetricHelp({ metric }: { metric: MatchMetricType }) {
  return (
    <div style={{
      marginBottom: 6,
      border: '1px solid #2a2a2a',
      borderRadius: 3,
      background: '#161616',
      fontSize: '0.53em',
      lineHeight: 1.45,
      // No overflow:hidden — it creates a BFC on flex items and collapses height in Firefox.
    }}>
      {METRIC_INFO.map((m, i) => {
        const active = m.key === metric;
        return (
          <div key={m.key} style={{
            padding: '4px 7px',
            background: active ? '#0d1f0d' : 'transparent',
            borderTop: i > 0 ? '1px solid #2a2a2a' : 'none',
          }}>
            <span style={{
              color: active ? '#7f7' : '#7a9aaa',
              fontWeight: active ? 'bold' : 'normal',
              marginRight: 5,
            }}>{m.label}</span>
            <span style={{ color: active ? '#9ab' : '#556070' }}>{m.desc}</span>
            {active && (
              <span style={{ color: '#5a9a5a', marginLeft: 4 }}>→ {m.when}</span>
            )}
          </div>
        );
      })}
    </div>
  );
}

// ─── SliderParam helper ───────────────────────────────────────────────────────

function SliderParam({ label, min, max, step, value, onChange }: {
  label: string; min: number; max: number; step: number; value: number; onChange: (v: number) => void;
}) {
  return (
    <div style={{ marginBottom: 5 }}>
      <span style={{ color: '#aaa', fontSize: '0.58em' }}>{label}</span>
      <input type="range" min={min} max={max} step={step} value={value}
        onInput={e => onChange(parseFloat((e.target as HTMLInputElement).value))}
        style={{ width: '100%', marginTop: 2 }} />
    </div>
  );
}

function ZoomBtn({ onClick, children, title }: {
  onClick: () => void; children: preact.ComponentChildren; title?: string;
}) {
  return (
    <button onClick={onClick} title={title} style={{
      background: '#1e1e1e', border: '1px solid #444', borderRadius: 3,
      color: '#888', cursor: 'pointer', fontSize: '0.68em',
      padding: '1px 6px', lineHeight: 1.5,
    }}>
      {children}
    </button>
  );
}

// ─── CarpetDumpInput ──────────────────────────────────────────────────────────

/**
 * Collapsible panel for pasting the output of `/loominary dumpcarpet`.
 * Parses the  CARPET_DUMP=[b0,b1,...,b15]  line and calls updateCarpetTables().
 */
function CarpetDumpInput() {
  const [open,   setOpen]   = useState(false);
  const [text,   setText]   = useState('');
  const [status, setStatus] = useState<string | null>(null);

  function apply() {
    const m = text.match(/CARPET_DUMP\s*=\s*\[([^\]]+)\]/);
    if (!m) { setStatus('Not found — paste the CARPET_DUMP=[...] line from carpet_dump.txt'); return; }
    const vals = m[1].split(',').map(s => parseInt(s.trim(), 10));
    if (vals.length !== 16 || vals.some(n => isNaN(n) || n < 0 || n > 255)) {
      setStatus('Expected exactly 16 byte values (0–255)'); return;
    }
    updateCarpetTables(vals);
    setStatus('✓ Carpet tables updated — carpet palette options now use game-verified values');
  }

  return (
    <div style={{ marginTop: 8, border: '1px solid #333', borderRadius: 4, overflow: 'hidden' }}>
      <button
        onClick={() => setOpen(o => !o)}
        style={{
          width: '100%', textAlign: 'left', background: '#1e1e1e',
          border: 'none', borderBottom: open ? '1px solid #333' : 'none',
          color: '#888', cursor: 'pointer', fontSize: '0.58em',
          padding: '5px 8px', display: 'flex', alignItems: 'center', gap: 5,
        }}
      >
        <span>{open ? '▾' : '▸'}</span>
        <span>Carpet colour override</span>
        <span style={{ color:'#555', fontSize: '0.53em' }}>/loominary dumpcarpet</span>
      </button>

      {open && (
        <div style={{ marginTop: 6 }}>
          <div style={{ fontSize: '0.53em', color: '#555', lineHeight: 1.5, marginBottom: 5 }}>
            In-game: <span style={{ color:'#7a7a7a', fontFamily:'monospace' }}>/loominary dumpcarpet</span><br/>
            Open <span style={{ color:'#7a7a7a', fontFamily:'monospace' }}>loominary_data/carpet_dump.txt</span>,
            paste the whole file or just the <span style={{ color:'#7a7a7a', fontFamily:'monospace' }}>CARPET_DUMP=[…]</span> line below.
          </div>
          <textarea
            value={text}
            onInput={e => { setText((e.target as HTMLTextAreaElement).value); setStatus(null); }}
            placeholder="CARPET_DUMP=[33,61,65,...]"
            style={{ width:'100%', height: 60, background:'#1a1a1a', border:'1px solid #333',
              borderRadius: 3, color:'#aaa', fontSize: '0.53em', padding: '4px 6px',
              fontFamily:'monospace', resize:'vertical' }}
          />
          <div style={{ display:'flex', gap: 6, marginTop: 4, alignItems:'center' }}>
            <button onClick={apply} style={{ ...BTN_STYLE, cursor:'pointer' }}>Apply</button>
            {status && (
              <span style={{ fontSize: '0.53em', color: status.startsWith('✓') ? '#7f7' : '#f77', flex:1 }}>
                {status}
              </span>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

// ─── SessionHistoryItem ───────────────────────────────────────────────────────

function SessionHistoryItem({ meta, onLoad, onDelete }: {
  meta:     SessionMeta;
  onLoad:   () => void;
  onDelete: () => void;
}) {
  const thumbRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = thumbRef.current;
    if (!canvas || !meta.thumbnailData) return;
    const imgData = mapBytesToImageData([[meta.thumbnailData]], 0, 1, 1);
    canvas.width  = imgData.width;
    canvas.height = imgData.height;
    canvas.getContext('2d')!.putImageData(imgData, 0, 0);
  }, [meta.thumbnailData]);

  const label = meta.title || meta.sourceFilename || 'untitled';
  const grid  = `${meta.gridCols}×${meta.gridRows}`;
  const frames = meta.totalFrames > meta.totalTiles
    ? ` · ${meta.totalFrames / meta.totalTiles}f` : '';

  return (
    <div
      onClick={onLoad}
      title="Restore this session"
      style={{
        display: 'flex', alignItems: 'center', gap: 8,
        padding: '5px 8px', cursor: 'pointer', borderBottom: '1px solid #222',
      }}
      onMouseEnter={e => { (e.currentTarget as HTMLElement).style.background = '#1e2a1e'; }}
      onMouseLeave={e => { (e.currentTarget as HTMLElement).style.background = ''; }}
    >
      {/* Thumbnail */}
      <div style={{
        width: 40, height: 40, flexShrink: 0,
        background: '#111', border: '1px solid #2a2a2a', borderRadius: 2,
        display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden',
      }}>
        {meta.thumbnailData
          ? <canvas ref={thumbRef} style={{ maxWidth: '100%', maxHeight: '100%', imageRendering: 'pixelated' }} />
          : <span style={{ fontSize: '0.5em', color: '#333' }}>?</span>}
      </div>

      {/* Info */}
      <div style={{ flex: 1, overflow: 'hidden' }}>
        <div style={{
          fontSize: '0.58em', color: '#ccc', overflow: 'hidden',
          textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        }}>{label}</div>
        <div style={{ fontSize: '0.52em', color: '#555', marginTop: 1 }}>
          {grid}{frames} · {savedAgoLabel(meta.savedAt)}
        </div>
      </div>

      {/* Delete */}
      <button
        onClick={e => { e.stopPropagation(); onDelete(); }}
        title="Remove from history"
        style={{
          background: 'none', border: 'none', color: '#444', cursor: 'pointer',
          fontSize: '0.75em', padding: '2px 4px', lineHeight: 1, flexShrink: 0,
        }}
      >✕</button>
    </div>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const BTN_STYLE: h.JSX.CSSProperties = {
  background: '#252525', border: '1px solid #444', borderRadius: 3,
  color: '#ccc', padding: '4px 10px', fontSize: '0.63em', fontFamily: 'system-ui, sans-serif',
};

const NUM_INPUT_STYLE: h.JSX.CSSProperties = {
  width: 44, background: '#252525', border: '1px solid #444',
  borderRadius: 3, color: '#ccc', fontSize: '0.63em',
  padding: '2px 4px', textAlign: 'center',
};
