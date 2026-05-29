/**
 * Editor — root Preact component.
 *
 * Three-panel layout:
 *   [GuidePanel | Canvas | PalettePanel]
 *             [StatusBar]
 *
 * Owns the CompositionState, EditHistory, active tool, and tool parameters.
 * Wires Canvas pointer events to the active tool via refs so the canvas init
 * effect never captures stale state.
 */

import { h, type ComponentChildren } from 'preact';
import { useEffect, useRef, useState, useCallback } from 'preact/hooks';

import { MapCanvas }    from './Canvas.js';
import { EditHistory }  from './history.js';
import { PalettePanel } from './PalettePanel.js';
import { StatusBar }    from './StatusBar.js';
import {
  buildOklabLookup,
  requantizeGrid,
  mapBytesToImageData,
  DitherAlgo,
  MatchMetric,
  DEFAULT_REQ_PARAMS,
  type RequantizeParams,
  countDistinct,
  findReductionTarget,
  type Strategy,
} from '../quantize.js';
import { MC_PALETTE }  from '../palette.js';
import type { CompositionState } from '../payload-state.js';
import { emptyPayloadState, compositionFromState } from '../payload-state.js';
import { FilterType, applyFilter } from '../filters.js';
import { type PreprocessParams, DEFAULT_PREPROCESS, prepareSourceImage, applyPreprocess } from '../preprocess.js';
import {
  PALETTE_CHOICES, buildPaletteFlag, type PaletteRestriction,
} from '../palette-filters.js';

import { BrushTool }      from './tools/Brush.js';
import { FillTool }       from './tools/Fill.js';
import { RectSelectTool } from './tools/Select.js';
import { LassoTool }      from './tools/Lasso.js';
import { MagicWandTool }  from './tools/MagicWand.js';
import type { Tool, ToolContext } from './tools/Tool.js';
import {
  dilateSelMask, erodeSelMask,
  readPixel, writePixel, MAP_SIZE,
} from './tools/Tool.js';

// ─── Data / compression types ─────────────────────────────────────────────────

interface TileDataStat {
  tileCol:  number;
  tileRow:  number;
  bytes:    number;    // compressed pixel data size (actual zstd output)
  banners:  number;    // CJK banners needed (ceil((bytes+2)/84))
  pct:      number;    // 0–100, percent of BANNER capacity (5290 B)
  overflow: boolean;   // exceeds BANNER mode capacity
}

interface DataStats {
  tiles:      TileDataStat[];
  totalBytes: number;
  maxBanners: number;
}

/**
 * Build a per-pixel compression-cost map.
 *
 * Steps:
 *   1. Per-pixel 4-neighbor edge count (0–4): pixels where all neighbors match
 *      are free to compress; pixels with many differing neighbors are expensive.
 *   2. Separable box-blur (radius 8, i.e. a 17×17 window) to turn the raw edge
 *      map into a smooth regional density estimate.
 *   3. Gamma-correct (sqrt) and scale to 0–255 so subtle variations are visible.
 *
 * Result: 0 = very uniform region (cheap) → 255 = dense/noisy region (expensive).
 */
function buildDetailMap(comp: CompositionState): Uint8Array {
  const { gridCols, gridRows, frames, activeFrame } = comp;
  const gridW = gridCols * MAP_SIZE;
  const gridH = gridRows * MAP_SIZE;

  // ── Step 1: raw 4-neighbor edge count ──────────────────────────────────────
  const edges = new Float32Array(gridW * gridH);
  for (let tileRow = 0; tileRow < gridRows; tileRow++) {
    for (let tileCol = 0; tileCol < gridCols; tileCol++) {
      const ti    = tileRow * gridCols + tileCol;
      const frame = frames[ti]?.[activeFrame];
      if (!frame) continue;
      for (let ly = 0; ly < MAP_SIZE; ly++) {
        const gy = tileRow * MAP_SIZE + ly;
        for (let lx = 0; lx < MAP_SIZE; lx++) {
          const gx   = tileCol * MAP_SIZE + lx;
          const here = frame[lx + ly * MAP_SIZE];
          let   diff = 0;
          if (lx > 0            && frame[(lx-1) +  ly    * MAP_SIZE] !== here) diff++;
          if (lx < MAP_SIZE - 1 && frame[(lx+1) +  ly    * MAP_SIZE] !== here) diff++;
          if (ly > 0            && frame[ lx    + (ly-1) * MAP_SIZE] !== here) diff++;
          if (ly < MAP_SIZE - 1 && frame[ lx    + (ly+1) * MAP_SIZE] !== here) diff++;
          edges[gy * gridW + gx] = diff;
        }
      }
    }
  }

  // ── Step 2: separable box blur (radius 8) ──────────────────────────────────
  const R    = 8;
  const tmpH = new Float32Array(gridW * gridH);

  // Horizontal pass
  for (let y = 0; y < gridH; y++) {
    let sum = 0;
    for (let dx = 0; dx <= R && dx < gridW; dx++) sum += edges[y * gridW + dx];
    for (let x = 0; x < gridW; x++) {
      if (x + R < gridW)   sum += edges[y * gridW + x + R];
      if (x - R - 1 >= 0) sum -= edges[y * gridW + x - R - 1];
      const winW = Math.min(x + R, gridW - 1) - Math.max(x - R, 0) + 1;
      tmpH[y * gridW + x] = sum / winW;
    }
  }

  // Vertical pass
  const out = new Uint8Array(gridW * gridH);
  for (let x = 0; x < gridW; x++) {
    let sum = 0;
    for (let dy = 0; dy <= R && dy < gridH; dy++) sum += tmpH[dy * gridW + x];
    for (let y = 0; y < gridH; y++) {
      if (y + R < gridH)   sum += tmpH[(y + R) * gridW + x];
      if (y - R - 1 >= 0) sum -= tmpH[(y - R - 1) * gridW + x];
      const winH = Math.min(y + R, gridH - 1) - Math.max(y - R, 0) + 1;
      const avg  = sum / winH;  // 0–4 range
      // Gamma-correct (sqrt) then cap at avg=2.5 → 255 for typical map art range
      out[y * gridW + x] = Math.min(255, Math.round(Math.sqrt(avg / 2.5) * 255));
    }
  }

  return out;
}

// ─── Tool registry ────────────────────────────────────────────────────────────

const brushTool  = new BrushTool();
const fillTool   = new FillTool();
const rectSelect = new RectSelectTool();
const lasso      = new LassoTool();
const magicWand  = new MagicWandTool();

const ALL_TOOLS: Tool[] = [brushTool, fillTool, rectSelect, lasso, magicWand];
const TOOL_MAP = new Map(ALL_TOOLS.map(t => [t.id, t]));

// ─── Default empty composition ────────────────────────────────────────────────

function makeEmptyComp(cols = 1, rows = 1): CompositionState {
  const ps        = emptyPayloadState(cols, rows);
  const n         = cols * rows;
  const pixelData = Array.from({ length: n }, () => [new Uint8Array(128 * 128)]) as Uint8Array[][];
  return compositionFromState(ps, pixelData);
}

const OKLAB = buildOklabLookup();

// ─── Metric / algo / filter / strategy cycle helpers ─────────────────────────

const METRIC_CYCLE: Array<typeof MatchMetric[keyof typeof MatchMetric]> =
  ['OKLAB', 'CHROMA_FIRST', 'LUMA_FIRST', 'HUE_ONLY', 'RGB'];
const METRIC_LABEL: Record<string, string> = {
  OKLAB: 'OKLab', CHROMA_FIRST: 'Chr+', LUMA_FIRST: 'Lum+', HUE_ONLY: 'Hue', RGB: 'RGB',
};

const DITHER_CYCLE: DitherAlgo[] = ['NONE', 'FS', 'ATKINSON', 'SIERRA', 'SIERRA2', 'SIERRA_LITE', 'SHIAU_FAN', 'JJN', 'STUCKI', 'BAYER'];
const DITHER_LABEL: Record<string, string> = {
  NONE: 'None', FS: 'FS', ATKINSON: 'Atk',
  SIERRA: 'Sierra', SIERRA2: 'Sierra2', SIERRA_LITE: 'SierraL',
  SHIAU_FAN: 'Shiau', JJN: 'JJN', STUCKI: 'Stucki',
  BAYER: 'Bayer',
};
const ED_ALGOS = new Set<DitherAlgo>(['FS', 'ATKINSON', 'SIERRA', 'SIERRA2', 'SIERRA_LITE', 'SHIAU_FAN', 'JJN', 'STUCKI']);

const FILTER_CYCLE: FilterType[] = ['SMOOTH', 'MEDIAN', 'SHARPEN', 'POSTERIZE'];
const FILTER_LABEL: Record<string, string> = {
  SMOOTH: 'Smooth', MEDIAN: 'Median', SHARPEN: 'Sharpen', POSTERIZE: 'Poster',
};

const STRATEGY_CYCLE: Strategy[] = ['RAREST', 'CLOSEST', 'WEIGHTED'];
const STRATEGY_LABEL: Record<string, string> = {
  RAREST: 'Rarest', CLOSEST: 'Closest', WEIGHTED: 'Weighted',
};

// ─── Heatmap LUT builder ──────────────────────────────────────────────────────

function buildHeatmapLut(comp: CompositionState): Uint32Array {
  const freq = new Int32Array(256);
  for (const tf of comp.frames) {
    const f = tf[comp.activeFrame];
    if (!f) continue;
    for (let i = 0; i < f.length; i++) freq[f[i] & 0xFF]++;
  }
  let minF = Infinity, maxF = 0;
  for (let c = 1; c < 256; c++) {
    if (freq[c] > 0) { if (freq[c] < minF) minF = freq[c]; if (freq[c] > maxF) maxF = freq[c]; }
  }
  const lut = new Uint32Array(256);
  for (let c = 1; c < 256; c++) {
    if (!freq[c]) { lut[c] = 0x222222; continue; }
    // t=0 → rarest (expensive, red)   t=1 → commonest (cheap, blue)
    const t = maxF === minF ? 0.5 : (freq[c] - minF) / (maxF - minF);
    const h = (240 * t) / 360; // hue 0=red, 0.667=blue
    const s = 0.9, l = 0.45;
    const cv = (1 - Math.abs(2*l - 1)) * s;
    const x  = cv * (1 - Math.abs((h * 6) % 2 - 1));
    const m  = l - cv/2;
    let r=0, g=0, b=0;
    const h6 = h * 6;
    if      (h6 < 1) { r=cv; g=x;  b=0;  }
    else if (h6 < 2) { r=x;  g=cv; b=0;  }
    else if (h6 < 3) { r=0;  g=cv; b=x;  }
    else if (h6 < 4) { r=0;  g=x;  b=cv; }
    else if (h6 < 5) { r=x;  g=0;  b=cv; }
    else             { r=cv; g=0;  b=x;  }
    lut[c] = (Math.round((r+m)*255) << 16) | (Math.round((g+m)*255) << 8) | Math.round((b+m)*255);
  }
  return lut;
}

// ─── Editor props ─────────────────────────────────────────────────────────────

export interface EditorProps {
  initialComp?:        CompositionState;
  sourceBitmap?:       ImageBitmap | null;
  /** Crop mode used at import — applied when re-quantizing from the source bitmap. */
  importCropMode?:     'scale' | 'center';
  /** Preprocessing params used at import — applied when re-quantizing from the source bitmap. */
  importPre?:          PreprocessParams;
  /**
   * Per-animation-frame source bitmaps (from multi-frame GIF import).
   * sourceFrames[i] is the original bitmap for animation frame i.
   * When present, requantize from source uses sourceFrames[activeFrame]
   * instead of the single sourceBitmap.
   */
  sourceFrames?:       ImageBitmap[] | null;
  showGridLines?:      boolean;
  /** Base font size in px — slider controlled by App toolbar. */
  uiFontSize?:         number;
  /** If provided, initialises the requantize params on mount. */
  initialReqParams?:   RequantizeParams;
  /** Called after every composition mutation so the parent can track the latest state. */
  onCompChange?:       (comp: CompositionState) => void;
  /** Called when the user wants to re-link a source image (e.g. after session restore). */
  onRelinkSource?:     () => void;
}

// ─── Editor ───────────────────────────────────────────────────────────────────

export function Editor({
  initialComp, sourceBitmap,
  importCropMode = 'scale',
  importPre      = DEFAULT_PREPROCESS,
  sourceFrames   = null,
  showGridLines  = true,
  uiFontSize     = 14,
  initialReqParams,
  onCompChange,
  onRelinkSource,
}: EditorProps) {
  const canvasElRef = useRef<HTMLCanvasElement>(null);
  const canvasRef   = useRef<MapCanvas | null>(null);
  const historyRef  = useRef(new EditHistory());

  // ── Sidebar widths ─────────────────────────────────────────────────────────
  const [leftW,  setLeftW]  = useState(304);
  const [rightW, setRightW] = useState(336);

  // ── Composition ────────────────────────────────────────────────────────────
  const [comp, setComp] = useState<CompositionState>(() => initialComp ?? makeEmptyComp());
  const compRef = useRef(comp);
  const onCompChangeRef = useRef(onCompChange);
  useEffect(() => { onCompChangeRef.current = onCompChange; }, [onCompChange]);

  const syncComp = useCallback((next: CompositionState) => {
    compRef.current = next;
    setComp(next);
    canvasRef.current?.setComposition(next);
    onCompChangeRef.current?.(next);
  }, []);

  useEffect(() => {
    if (initialComp) syncComp(initialComp);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialComp]);


  // ── Tool state ──────────────────────────────────────────────────────────────
  const [activeToolId, _setActiveToolId] = useState('brush');
  const activeToolIdRef = useRef('brush');
  function setActiveToolId(id: string) {
    activeToolIdRef.current = id;
    _setActiveToolId(id);
  }

  const [activeColor, _setActiveColor] = useState<number>(60);
  const activeColorRef = useRef<number>(60);
  function setActiveColor(c: number) {
    activeColorRef.current = c;
    _setActiveColor(c);
    // Keep fill preview tint in sync when the user picks a new colour.
    if (canvasRef.current) {
      canvasRef.current.fillPreviewColor = c;
      if (canvasRef.current.fillPreview) canvasRef.current.markDirty();
    }
  }

  // Keyboard modifier state (updated in keydown/keyup so tools can read it via context)
  const ctrlHeldRef  = useRef(false);
  const shiftHeldRef = useRef(false);

  const [brushRadius, _setBrushRadius] = useState(0);
  const brushRadiusRef = useRef(0);
  function setBrushRadius(fn: ((r: number) => number) | number) {
    const next = typeof fn === 'function' ? fn(brushRadiusRef.current) : fn;
    brushRadiusRef.current = next;
    _setBrushRadius(next);
    if (canvasRef.current) { canvasRef.current.brushRadius = next; }
  }

  const [brushShape, _setBrushShape] = useState<'circle' | 'square'>('circle');
  const brushShapeRef = useRef<'circle' | 'square'>('circle');
  function setBrushShape(s: 'circle' | 'square') { brushShapeRef.current = s; _setBrushShape(s); }

  const [fillTol, _setFillTol] = useState(0.15);
  const fillTolRef = useRef(0.15);
  function setFillTol(fn: number | ((t: number) => number)) {
    const next = typeof fn === 'function' ? fn(fillTolRef.current) : fn;
    fillTolRef.current = next;
    _setFillTol(next);
  }

  const [wandTol, _setWandTol] = useState(0.15);
  const wandTolRef = useRef(0.15);
  function setWandTol(fn: number | ((t: number) => number)) {
    const next = typeof fn === 'function' ? fn(wandTolRef.current) : fn;
    wandTolRef.current = next;
    _setWandTol(next);
  }

  const [selMask, _setSelMask] = useState<Uint8Array | null>(null);
  const selMaskRef = useRef<Uint8Array | null>(null);
  function setSelMask(m: Uint8Array | null) {
    selMaskRef.current = m;
    _setSelMask(m);
    if (canvasRef.current) { canvasRef.current.selMask = m; canvasRef.current.markDirty(); }
  }

  // ── Requantize params ───────────────────────────────────────────────────────
  const [reqAlgo,          setReqAlgo]          = useState<DitherAlgo>('NONE');
  const [reqMetric,        setReqMetric]        = useState<typeof MatchMetric[keyof typeof MatchMetric]>(DEFAULT_REQ_PARAMS.metric);
  const [reqFsStr,         setReqFsStr]         = useState(DEFAULT_REQ_PARAMS.fsStrength);
  const [reqAtkStr,        setReqAtkStr]        = useState(DEFAULT_REQ_PARAMS.atkStrength);
  const [reqSierraStr,     setReqSierraStr]     = useState(DEFAULT_REQ_PARAMS.sierraStrength);
  const [reqSierra2Str,    setReqSierra2Str]    = useState(DEFAULT_REQ_PARAMS.sierra2Strength);
  const [reqSierraLiteStr, setReqSierraLiteStr] = useState(DEFAULT_REQ_PARAMS.sierraLiteStrength);
  const [reqShiauFanStr,   setReqShiauFanStr]   = useState(DEFAULT_REQ_PARAMS.shiauFanStrength);
  const [reqJjnStr,        setReqJjnStr]        = useState(DEFAULT_REQ_PARAMS.jjnStrength);
  const [reqStuckiStr,     setReqStuckiStr]     = useState(DEFAULT_REQ_PARAMS.stuckiStrength);
  const [reqSerpentine,    setReqSerpentine]    = useState(DEFAULT_REQ_PARAMS.serpentine);
  const [reqBayer,         setReqBayer]         = useState(DEFAULT_REQ_PARAMS.bayerScale);
  const [reqBayerSize,     setReqBayerSize]     = useState<2|4|8|16>(DEFAULT_REQ_PARAMS.bayerSize);
  const [reqChroma,        setReqChroma]        = useState(DEFAULT_REQ_PARAMS.chromaBoost);
  // 'auto' = use comp.allShades  |  'tile' = restrict to existing tile colours
  // any PaletteRestriction = use that specific palette
  const [reqPaletteMode,   setReqPaletteMode]   = useState<PaletteRestriction | 'auto' | 'tile'>('auto');
  const [reqGreyThresh,    setReqGreyThresh]     = useState(40);
  const [reqRunning,       setReqRunning]       = useState(false);
  const [reqStatus,        setReqStatus]        = useState<string | null>(null);
  const [reqProgress,    _setReqProgress]       = useState<{ done: number; total: number } | null>(null);
  const reqProgressRef     = useRef<{ done: number; total: number } | null>(null);
  function setReqProgress(v: typeof reqProgressRef.current) { reqProgressRef.current = v; _setReqProgress(v); }
  const [reqAllFrames,     setReqAllFrames]     = useState(false);
  const reqAllFramesRef  = useRef(false);
  useEffect(() => { reqAllFramesRef.current = reqAllFrames; }, [reqAllFrames]);
  const [reqUseSource,   _setReqUseSource]   = useState<'source' | 'pixels'>('source');
  const reqUseSourceRef  = useRef<'source' | 'pixels'>('source');
  function setReqUseSource(v: 'source' | 'pixels') { reqUseSourceRef.current = v; _setReqUseSource(v); }
  const [reqQueuePalette, _setReqQueuePalette] = useState(false);
  const reqQueuePaletteRef = useRef(false);
  function setReqQueuePalette(v: boolean) { reqQueuePaletteRef.current = v; _setReqQueuePalette(v); }

  // ── Image adjustments (applied to source before quantization) ───────────────
  // Initialised from the import-time preprocessing so values carry over.
  const [reqBrightness, _setReqBrightness] = useState(importPre.brightness);
  const [reqContrast,   _setReqContrast]   = useState(importPre.contrast);
  const [reqSaturation, _setReqSaturation] = useState(importPre.saturation);
  const reqBrightnessRef = useRef(importPre.brightness);
  const reqContrastRef   = useRef(importPre.contrast);
  const reqSaturationRef = useRef(importPre.saturation);
  function setReqBrightness(v: number) { reqBrightnessRef.current = v; _setReqBrightness(v); }
  function setReqContrast  (v: number) { reqContrastRef.current   = v; _setReqContrast(v);   }
  function setReqSaturation(v: number) { reqSaturationRef.current = v; _setReqSaturation(v); }
  // Re-initialise when a new image is loaded.
  useEffect(() => {
    setReqBrightness(importPre.brightness);
    setReqContrast(importPre.contrast);
    setReqSaturation(importPre.saturation);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [importPre]);

  const reqAlgoRef          = useRef(reqAlgo);
  const reqMetricRef        = useRef(reqMetric);
  const reqFsStrRef         = useRef(reqFsStr);
  const reqAtkStrRef        = useRef(reqAtkStr);
  const reqSierraStrRef     = useRef(reqSierraStr);
  const reqSierra2StrRef    = useRef(reqSierra2Str);
  const reqSierraLiteStrRef = useRef(reqSierraLiteStr);
  const reqShiauFanStrRef   = useRef(reqShiauFanStr);
  const reqJjnStrRef        = useRef(reqJjnStr);
  const reqStuckiStrRef     = useRef(reqStuckiStr);
  const reqSerpentineRef    = useRef(reqSerpentine);
  const reqBayerRef         = useRef(reqBayer);
  const reqBayerSizeRef     = useRef(reqBayerSize);
  const reqChromaRef        = useRef(reqChroma);
  const reqPaletteModeRef   = useRef<PaletteRestriction | 'auto' | 'tile'>('auto');
  const reqGreyThreshRef    = useRef(40);
  useEffect(() => { reqAlgoRef.current          = reqAlgo;          }, [reqAlgo]);
  useEffect(() => { reqMetricRef.current        = reqMetric;        }, [reqMetric]);
  useEffect(() => { reqFsStrRef.current         = reqFsStr;         }, [reqFsStr]);
  useEffect(() => { reqAtkStrRef.current        = reqAtkStr;        }, [reqAtkStr]);
  useEffect(() => { reqSierraStrRef.current     = reqSierraStr;     }, [reqSierraStr]);
  useEffect(() => { reqSierra2StrRef.current    = reqSierra2Str;    }, [reqSierra2Str]);
  useEffect(() => { reqSierraLiteStrRef.current = reqSierraLiteStr; }, [reqSierraLiteStr]);
  useEffect(() => { reqShiauFanStrRef.current   = reqShiauFanStr;   }, [reqShiauFanStr]);
  useEffect(() => { reqJjnStrRef.current        = reqJjnStr;        }, [reqJjnStr]);
  useEffect(() => { reqStuckiStrRef.current     = reqStuckiStr;     }, [reqStuckiStr]);
  useEffect(() => { reqSerpentineRef.current    = reqSerpentine;    }, [reqSerpentine]);
  useEffect(() => { reqBayerRef.current         = reqBayer;         }, [reqBayer]);
  useEffect(() => { reqBayerSizeRef.current     = reqBayerSize;     }, [reqBayerSize]);
  useEffect(() => { reqChromaRef.current        = reqChroma;        }, [reqChroma]);
  useEffect(() => { reqPaletteModeRef.current   = reqPaletteMode;   }, [reqPaletteMode]);
  useEffect(() => { reqGreyThreshRef.current    = reqGreyThresh;    }, [reqGreyThresh]);

  // ── Initialise from importReqParams if provided (on mount only) ────────────
  useEffect(() => {
    if (!initialReqParams) return;
    setReqAlgo(initialReqParams.dither);
    setReqMetric(initialReqParams.metric);
    setReqFsStr(initialReqParams.fsStrength);
    setReqAtkStr(initialReqParams.atkStrength);
    setReqBayer(initialReqParams.bayerScale);
    setReqChroma(initialReqParams.chromaBoost);
    setReqPaletteMode(initialReqParams.tilePalette ? 'tile' : 'auto');
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const sourceBitmapRef   = useRef<ImageBitmap | null>(sourceBitmap ?? null);
  const importCropModeRef = useRef(importCropMode);
  const importPreRef      = useRef(importPre);
  const sourceFramesRef   = useRef<ImageBitmap[] | null>(sourceFrames ?? null);
  useEffect(() => { sourceBitmapRef.current   = sourceBitmap ?? null;   }, [sourceBitmap]);
  useEffect(() => { importCropModeRef.current = importCropMode;         }, [importCropMode]);
  useEffect(() => { importPreRef.current      = importPre;              }, [importPre]);
  useEffect(() => { sourceFramesRef.current   = sourceFrames ?? null;   }, [sourceFrames]);


  // ── Requantize preview ──────────────────────────────────────────────────────
  const [previewComp, _setPreviewComp] = useState<CompositionState | null>(null);
  const previewCompRef = useRef<CompositionState | null>(null);
  function setPreviewComp(c: CompositionState | null) {
    previewCompRef.current = c;
    _setPreviewComp(c);
    if (canvasRef.current) { canvasRef.current.previewComp = c; canvasRef.current.markDirty(); }
  }

  // ── Clipboard / paste mode ──────────────────────────────────────────────────
  const clipWRef   = useRef(0);
  const clipHRef   = useRef(0);
  const clipPixRef = useRef<Uint8Array | null>(null);
  const clipMskRef = useRef<Uint8Array | null>(null);

  const [inPasteMode, _setInPasteMode] = useState(false);
  const inPasteModeRef = useRef(false);
  function setInPasteMode(v: boolean) {
    inPasteModeRef.current = v;
    _setInPasteMode(v);
    if (!v && canvasRef.current) { canvasRef.current.pasteGhost = null; canvasRef.current.markDirty(); }
  }

  // ── Merge queue ─────────────────────────────────────────────────────────────
  const mergeQueueRef = useRef<Set<number>>(new Set());
  const [mergeQueueVer, setMergeQueueVer] = useState(0); // bumped to trigger re-render
  const [mergeScope, _setMergeScope] = useState<'frame' | 'all'>('frame');
  const mergeScopeRef = useRef<'frame' | 'all'>('frame');
  function setMergeScope(s: 'frame' | 'all') { mergeScopeRef.current = s; _setMergeScope(s); }

  // ── Filter ──────────────────────────────────────────────────────────────────
  const [filterType, _setFilterType] = useState<FilterType>('SMOOTH');
  const filterTypeRef = useRef<FilterType>('SMOOTH');
  function setFilterType(ft: FilterType) { filterTypeRef.current = ft; _setFilterType(ft); }

  const [filterStrength, _setFilterStrength] = useState(1.0);
  const filterStrengthRef = useRef(1.0);
  function setFilterStrength(s: number) { filterStrengthRef.current = s; _setFilterStrength(s); }

  const [filterScope, _setFilterScope] = useState<'frame' | 'all'>('frame');
  const filterScopeRef = useRef<'frame' | 'all'>('frame');
  function setFilterScope(s: 'frame' | 'all') { filterScopeRef.current = s; _setFilterScope(s); }

  // ── Reduction strategy ──────────────────────────────────────────────────────
  const [reductionStrategy, _setReductionStrategy] = useState<Strategy>('RAREST');
  const reductionStrategyRef = useRef<Strategy>('RAREST');
  function setReductionStrategy(s: Strategy) { reductionStrategyRef.current = s; _setReductionStrategy(s); }

  const [reduceScope, _setReduceScope] = useState<'frame' | 'all'>('frame');
  const reduceScopeRef = useRef<'frame' | 'all'>('frame');
  function setReduceScope(s: 'frame' | 'all') { reduceScopeRef.current = s; _setReduceScope(s); }

  // ── Animation playback ──────────────────────────────────────────────────────
  const [playing,  _setPlaying]  = useState(false);
  const playingRef = useRef(false);
  function setPlaying(v: boolean) { playingRef.current = v; _setPlaying(v); }
  const playTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // ── Heatmap ─────────────────────────────────────────────────────────────────
  const [heatmapOn, setHeatmapOn] = useState(false);
  const heatmapOnRef = useRef(false);

  // ── Compression data view ───────────────────────────────────────────────────
  const [detailMapOn,     setDetailMapOn]     = useState(false);
  const detailMapOnRef  = useRef(false);
  const [dataStats,       setDataStats]       = useState<DataStats | null>(null);
  const [dataComputing,   setDataComputing]   = useState(false);
  const [hoveredStatTile, setHoveredStatTile] = useState<number | null>(null);
  const [statsOpen,       _setStatsOpen]      = useState(true);
  const statsOpenRef = useRef(true);
  function setStatsOpen(v: boolean) { statsOpenRef.current = v; _setStatsOpen(v); }

  // ── Grid lines visibility (driven by App toolbar) ───────────────────────────
  useEffect(() => {
    if (!canvasRef.current) return;
    canvasRef.current.showGridLines = showGridLines;
    canvasRef.current.markDirty();
  }, [showGridLines]);

  // ── Status / display state ──────────────────────────────────────────────────
  const [cursorGx,   setCursorGx]   = useState(-1);
  const [cursorGy,   setCursorGy]   = useState(-1);
  const [hoverColor, setHoverColor] = useState(0);
  const [scale,      setScale]      = useState(4);
  const [undoState,  setUndoState]  = useState({ canUndo: false, canRedo: false });
  const [statusMsg,  setStatusMsg]  = useState<string | null>(null);

  // ── Heatmap effect: rebuild LUT and push to canvas ──────────────────────────
  useEffect(() => {
    heatmapOnRef.current = heatmapOn;
    const c = canvasRef.current;
    if (!c) return;
    if (heatmapOn) {
      c.heatmapLut = buildHeatmapLut(compRef.current);
    } else {
      c.heatmapLut = null;
    }
    c.markDirty();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [heatmapOn, comp.frames, comp.activeFrame]);

  // ── Playback engine ─────────────────────────────────────────────────────────
  // Uses recursive setTimeout so each frame's own delay governs the next tick.
  // Frame advances bypass syncComp to avoid triggering auto-save every tick.
  useEffect(() => {
    if (!playing) {
      if (playTimerRef.current) { clearTimeout(playTimerRef.current); playTimerRef.current = null; }
      return;
    }
    function tick() {
      const c = compRef.current;
      const maxF = Math.max(...c.frames.map(t => t.length), 1);
      if (maxF <= 1) { setPlaying(false); return; }
      const delay = c.frameDelays[0]?.[c.activeFrame] ?? 100;
      playTimerRef.current = setTimeout(() => {
        if (!playingRef.current) return;
        const nextF = (compRef.current.activeFrame + 1) % Math.max(...compRef.current.frames.map(t => t.length), 1);
        const next = { ...compRef.current, activeFrame: nextF };
        compRef.current = next;
        setComp(next);
        canvasRef.current?.setComposition(next);
        tick();
      }, delay);
    }
    tick();
    return () => { if (playTimerRef.current) { clearTimeout(playTimerRef.current); playTimerRef.current = null; } };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [playing]);

  // ── Compression detail map ─────────────────────────────────────────────────
  useEffect(() => {
    detailMapOnRef.current = detailMapOn;
    const c = canvasRef.current;
    if (!c) return;
    if (detailMapOn) {
      c.setCompressionMap(buildDetailMap(compRef.current));
    } else {
      c.setCompressionMap(null);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detailMapOn, comp]);

  // Auto-recompute stats when the composition changes, but only while the stats
  // panel is "open" (user has run Compute at least once).
  // Use a ref for the "panel open" flag to avoid stale-closure issues, and
  // watch `comp` (not comp.frames) because frames are mutated in place so the
  // array reference never changes.
  useEffect(() => {
    if (canvasRef.current) canvasRef.current.hoveredTileTi = hoveredStatTile;
  }, [hoveredStatTile]);

  const computeDataStatsRef  = useRef<() => Promise<void>>(async () => {});
  const autoRecomputeTimer   = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Trigger initial computation on mount (panel defaults open).
  useEffect(() => { void computeDataStatsRef.current(); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  // Debounced auto-recompute when comp changes, only while panel is open.
  useEffect(() => {
    if (!statsOpenRef.current) return;
    if (autoRecomputeTimer.current) clearTimeout(autoRecomputeTimer.current);
    autoRecomputeTimer.current = setTimeout(() => {
      void computeDataStatsRef.current();
    }, 1500);
    return () => { if (autoRecomputeTimer.current) clearTimeout(autoRecomputeTimer.current); };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [comp]);

  const computeDataStats = useCallback(async () => {
    if (dataComputing) return;
    const c = compRef.current;
    setDataComputing(true);
    try {
      const { compress } = await import('../compression.js');
      const tiles: TileDataStat[] = [];
      for (let ti = 0; ti < c.frames.length; ti++) {
        const frame      = c.frames[ti]?.[c.activeFrame] ?? new Uint8Array(MAP_SIZE * MAP_SIZE);
        const compressed = await compress(frame);
        const bytes      = compressed.length;
        // CJK banner count: each banner holds 84 bytes; 2-byte length header overhead
        const banners    = Math.ceil((bytes + 2) / 84);
        const pct        = bytes / 5290 * 100;
        tiles.push({ tileCol: ti % c.gridCols, tileRow: Math.floor(ti / c.gridCols), bytes, banners, pct, overflow: bytes > 5290 });
      }
      setDataStats({
        tiles,
        totalBytes:  tiles.reduce((s, t) => s + t.bytes, 0),
        maxBanners:  Math.max(...tiles.map(t => t.banners)),
      });
    } catch (err) {
      console.warn('[Loominary] computeDataStats error:', err);
    } finally {
      setDataComputing(false);
    }
  }, [dataComputing]);
  useEffect(() => { computeDataStatsRef.current = computeDataStats; }, [computeDataStats]);

  // ── ToolContext ─────────────────────────────────────────────────────────────
  const getCtx = useRef((): ToolContext => ({
    comp:          compRef.current,
    canvas:        canvasRef.current!,
    history:       historyRef.current,
    oklabLookup:   OKLAB,
    activeColor:   activeColorRef.current,
    brushRadius:   brushRadiusRef.current,
    brushShape:    brushShapeRef.current,
    fillTolerance: fillTolRef.current,
    wandTolerance: wandTolRef.current,
    ctrlHeld:      ctrlHeldRef.current,
    shiftHeld:     shiftHeldRef.current,
    setColor:      (b: number) => setActiveColor(b),
    setSelMask,
    getSelMask:    () => selMaskRef.current,
  })).current;

  // ── Tool switching ──────────────────────────────────────────────────────────
  function doSwitchTool(id: string) {
    const next = TOOL_MAP.get(id);
    if (!next) return;
    const ctx = getCtx();
    TOOL_MAP.get(activeToolIdRef.current)?.deactivate?.(ctx);
    setActiveToolId(id);
    next.activate?.(ctx);
  }

  // ── Clipboard helpers ───────────────────────────────────────────────────────
  function doCopySelection(): boolean {
    const sel = selMaskRef.current;
    if (!sel) { setStatusMsg('No selection — use S / L / W first'); return false; }
    const c = compRef.current;
    const gw = c.gridCols * MAP_SIZE, gh = c.gridRows * MAP_SIZE;
    let x0 = gw, y0 = gh, x1 = -1, y1 = -1;
    for (let gy = 0; gy < gh; gy++)
      for (let gx = 0; gx < gw; gx++)
        if (sel[gy * gw + gx]) {
          if (gx < x0) x0 = gx; if (gy < y0) y0 = gy;
          if (gx > x1) x1 = gx; if (gy > y1) y1 = gy;
        }
    if (x1 < 0) { setStatusMsg('Selection is empty'); return false; }
    const cw = x1-x0+1, ch = y1-y0+1;
    const pix  = new Uint8Array(cw * ch);
    const mask = new Uint8Array(cw * ch);
    for (let gy = y0; gy <= y1; gy++)
      for (let gx = x0; gx <= x1; gx++) {
        const ci = (gy-y0)*cw + (gx-x0);
        if (sel[gy*gw+gx]) { pix[ci] = readPixel(c, gx, gy); mask[ci] = 1; }
      }
    clipWRef.current = cw; clipHRef.current = ch;
    clipPixRef.current = pix; clipMskRef.current = mask;
    setStatusMsg(`Copied ${cw}×${ch} — Ctrl+V to paste`);
    return true;
  }

  function doCutSelection(): void {
    if (!doCopySelection()) return;
    const c   = compRef.current;
    const sel = selMaskRef.current!;
    const gw  = c.gridCols * MAP_SIZE, gh = c.gridRows * MAP_SIZE;
    historyRef.current.snapshot(c.frames);
    for (let gy = 0; gy < gh; gy++)
      for (let gx = 0; gx < gw; gx++)
        if (sel[gy*gw+gx]) writePixel(c, gx, gy, 0, null);
    syncComp({ ...c });
    setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });
    setStatusMsg(`Cut — Ctrl+V to paste`);
  }

  function startPaste(): void {
    if (!clipPixRef.current) { setStatusMsg('Nothing to paste — copy a selection first (Ctrl+C)'); return; }
    setInPasteMode(true);
    if (canvasRef.current) {
      canvasRef.current.pasteGhost = {
        pixels: clipPixRef.current!,
        mask:   clipMskRef.current!,
        w:      clipWRef.current,
        h:      clipHRef.current,
      };
      canvasRef.current.markDirty();
    }
    setStatusMsg('Paste — click to stamp · Enter to stamp at cursor · Esc to cancel');
  }

  function commitPasteAt(gx: number, gy: number): void {
    if (!clipPixRef.current || !clipMskRef.current) return;
    const c  = compRef.current;
    const cw = clipWRef.current, ch = clipHRef.current;
    const ox = gx - Math.floor(cw / 2);
    const oy = gy - Math.floor(ch / 2);
    historyRef.current.snapshot(c.frames);
    const newSel = new Uint8Array(c.gridCols * MAP_SIZE * c.gridRows * MAP_SIZE);
    let placed = 0;
    for (let py = 0; py < ch; py++) {
      for (let px = 0; px < cw; px++) {
        const ci = py * cw + px;
        if (!clipMskRef.current[ci]) continue;
        const tgx = ox + px, tgy = oy + py;
        if (writePixel(c, tgx, tgy, clipPixRef.current[ci], null)) {
          newSel[tgy * c.gridCols * MAP_SIZE + tgx] = 1;
          placed++;
        }
      }
    }
    syncComp({ ...c });
    setSelMask(placed > 0 ? newSel : selMaskRef.current);
    setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });
    setInPasteMode(false);
    setStatusMsg(`Pasted ${placed} pixel${placed !== 1 ? 's' : ''}`);
  }

  // ── Merge helpers ───────────────────────────────────────────────────────────
  function toggleMergeColor(color: number): void {
    if (color === 0) return;
    const q = mergeQueueRef.current;
    if (q.has(color)) q.delete(color); else q.add(color);
    setMergeQueueVer(v => v + 1);
    if (q.size > 0) setStatusMsg(`Merge queue: ${q.size} color${q.size>1?'s':''} — C commits → active color`);
    else setStatusMsg('Removed from merge queue');
  }

  function commitMerge(): void {
    const q = mergeQueueRef.current;
    if (q.size === 0) { setStatusMsg('Queue empty — Ctrl+click swatches or canvas pixels to add'); return; }
    const target  = activeColorRef.current;
    const sources = new Set(q); sources.delete(target);
    if (sources.size === 0) { setStatusMsg('Queued colors match target — nothing to merge'); return; }
    const c   = compRef.current;
    const sel = selMaskRef.current;
    const gw  = c.gridCols * MAP_SIZE;
    historyRef.current.snapshot(c.frames);
    let replaced = 0;
    for (let ti = 0; ti < c.frames.length; ti++) {
      const tileCol = ti % c.gridCols;
      const tileRow = Math.floor(ti / c.gridCols);
      const tileFrames = c.frames[ti];
      const frameIdxs = mergeScopeRef.current === 'all'
        ? tileFrames.map((_, fi) => fi)
        : [c.activeFrame];
      for (const fi of frameIdxs) {
        const frame = tileFrames[fi]; if (!frame) continue;
        for (let ly = 0; ly < MAP_SIZE; ly++) {
          for (let lx = 0; lx < MAP_SIZE; lx++) {
            if (sel) {
              const gx = tileCol * MAP_SIZE + lx;
              const gy = tileRow * MAP_SIZE + ly;
              if (!sel[gy * gw + gx]) continue;
            }
            const idx   = lx + ly * MAP_SIZE;
            const color = frame[idx] & 0xFF;
            if (sources.has(color)) { frame[idx] = target; replaced++; }
          }
        }
      }
    }
    mergeQueueRef.current.clear();
    setMergeQueueVer(v => v + 1);
    syncComp({ ...c });
    setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });
    const scopeLbl = mergeScopeRef.current === 'all' ? ' (all frames)' : '';
    setStatusMsg(`Merged ${sources.size} color${sources.size>1?'s':''} → byte ${target}: ${replaced} px${scopeLbl}`);
  }

  // ── Reduce ──────────────────────────────────────────────────────────────────
  function applyReduction(): void {
    const c    = compRef.current;
    const sel  = selMaskRef.current;
    const gw   = c.gridCols * MAP_SIZE;
    const allF = reduceScopeRef.current === 'all';
    const maxF = Math.max(...c.frames.map(t => t.length), 1);
    const frameIdxs = allF ? Array.from({ length: maxF }, (_, i) => i) : [c.activeFrame];

    // Build frequency table across the scoped frames.
    const freq = new Int32Array(256);
    for (let ti = 0; ti < c.frames.length; ti++) {
      const tileCol = ti % c.gridCols;
      const tileRow = Math.floor(ti / c.gridCols);
      for (const fi of frameIdxs) {
        const frame = c.frames[ti]?.[fi];
        if (!frame) continue;
        for (let i = 0; i < frame.length; i++) {
          if (!frame[i]) continue;
          if (sel) {
            const lx = i % MAP_SIZE, ly = (i / MAP_SIZE) | 0;
            if (!sel[(tileRow * MAP_SIZE + ly) * gw + tileCol * MAP_SIZE + lx]) continue;
          }
          freq[frame[i]]++;
        }
      }
    }

    const [victim, survivor] = findReductionTarget(freq, OKLAB, reductionStrategyRef.current);
    if (victim === -1) { setStatusMsg('Already at minimum (1 color or less)'); return; }

    historyRef.current.snapshot(c.frames);

    let pixelsChanged = 0;
    for (let ti = 0; ti < c.frames.length; ti++) {
      const tileCol = ti % c.gridCols;
      const tileRow = Math.floor(ti / c.gridCols);
      for (const fi of frameIdxs) {
        const frame = c.frames[ti]?.[fi];
        if (!frame) continue;
        for (let i = 0; i < frame.length; i++) {
          if (frame[i] !== victim) continue;
          if (sel) {
            const lx = i % MAP_SIZE, ly = (i / MAP_SIZE) | 0;
            if (!sel[(tileRow * MAP_SIZE + ly) * gw + tileCol * MAP_SIZE + lx]) continue;
          }
          frame[i] = survivor;
          pixelsChanged++;
        }
      }
    }

    syncComp({ ...c });
    setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });
    setStatusMsg(`Reduced color ${victim} → ${survivor} (${pixelsChanged} px, ${reductionStrategyRef.current}${allF ? ' · all frames' : ''})`);
  }

  // ── Filter ──────────────────────────────────────────────────────────────────
  async function applyEditorFilter(): Promise<void> {
    if (reqRunning) return;
    const c    = compRef.current;
    const allF = filterScopeRef.current === 'all';
    const maxF = Math.max(...c.frames.map(t => t.length), 1);
    const frameIdxs = allF ? Array.from({ length: maxF }, (_, i) => i) : [c.activeFrame];
    setStatusMsg(`Applying ${filterTypeRef.current}…`);
    try {
      const { customPalette: filterCp, tilePalette: filterTp } = buildReqPalette();
      const params: RequantizeParams = {
        ...DEFAULT_REQ_PARAMS,
        dither:          'NONE',
        metric:          reqMetricRef.current,
        legalOnly:       !c.allShades,
        customPalette:   filterCp,
        tilePalette:     filterTp,
        chromaBoost:     reqChromaRef.current,
        useCustomDither: false,
        ditherMask:      null,
      };
      historyRef.current.snapshot(c.frames);
      let newFrames = c.frames.map(tf => [...tf]);
      for (const fi of frameIdxs) {
        const source   = mapBytesToImageData(c.frames, fi, c.gridCols, c.gridRows);
        const filtered = applyFilter(source, { type: filterTypeRef.current, strength: filterStrengthRef.current });
        const newTiles = requantizeGrid(filtered, c, selMaskRef.current, params);
        newFrames = newFrames.map((tf, ti) => tf.map((f, fj) => fj === fi ? newTiles[ti] : f));
      }
      syncComp({ ...c, frames: newFrames });
      setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });
      setStatusMsg(`Filter: ${filterTypeRef.current} applied${allF ? ` (${frameIdxs.length} frames)` : ''}`);
    } catch (err) {
      setStatusMsg(`Filter error: ${err}`);
    }
  }

  // ── Frame management ────────────────────────────────────────────────────────
  function dropCurrentFrame(): void {
    const c = compRef.current;
    const maxF = Math.max(...c.frames.map(t => t.length), 1);
    if (maxF <= 1) { setStatusMsg('Only 1 frame — cannot drop'); return; }
    historyRef.current.snapshot(c.frames);
    const fi = c.activeFrame;
    const newFrames = c.frames.map(tf => {
      if (tf.length <= 1) return tf;
      const next = [...tf]; next.splice(fi, 1); return next;
    });
    const newDelays = c.frameDelays.map(dd => {
      if (dd.length <= 1) return dd;
      const next = [...dd]; next.splice(fi, 1); return next;
    });
    const newActive = Math.min(fi, maxF - 2);
    syncComp({ ...c, frames: newFrames, frameDelays: newDelays, activeFrame: newActive });
    setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });
    setStatusMsg(`Dropped frame ${fi + 1}`);
  }

  function cloneCurrentFrame(): void {
    const c = compRef.current;
    historyRef.current.snapshot(c.frames);
    const fi = c.activeFrame;
    const newFrames = c.frames.map(tf => {
      const next  = [...tf];
      const clone = (tf[fi] ?? new Uint8Array(MAP_SIZE * MAP_SIZE)).slice() as Uint8Array;
      next.splice(fi + 1, 0, clone);
      return next;
    });
    const newDelays = c.frameDelays.map(dd => {
      const next = [...dd];
      next.splice(fi + 1, 0, dd[fi] ?? 100);
      return next;
    });
    syncComp({ ...c, frames: newFrames, frameDelays: newDelays, activeFrame: fi + 1 });
    setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });
    setStatusMsg(`Cloned frame ${fi + 1} → frame ${fi + 2}`);
  }

  function addBlankFrame(): void {
    const c = compRef.current;
    historyRef.current.snapshot(c.frames);
    const fi = c.activeFrame;
    const newFrames = c.frames.map(tf => {
      const next = [...tf];
      next.splice(fi + 1, 0, new Uint8Array(MAP_SIZE * MAP_SIZE));
      return next;
    });
    const newDelays = c.frameDelays.map(dd => {
      const next = [...dd];
      next.splice(fi + 1, 0, 100);
      return next;
    });
    syncComp({ ...c, frames: newFrames, frameDelays: newDelays, activeFrame: fi + 1 });
    setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });
    setStatusMsg(`Added blank frame ${fi + 2}`);
  }

  function adjustFrameDelay(delta: number): void {
    const c = compRef.current;
    const fi = c.activeFrame;
    const newDelays = c.frameDelays.map(dd => {
      const next = [...dd];
      next[fi] = Math.max(10, Math.min(10000, (dd[fi] ?? 100) + delta));
      return next;
    });
    syncComp({ ...c, frameDelays: newDelays });
    const delay = newDelays[0]?.[fi] ?? 100;
    setStatusMsg(`Frame ${fi+1} delay: ${delay} ms`);
  }

  function setFrameDelay(ms: number): void {
    const c = compRef.current;
    const fi = c.activeFrame;
    const clamped = Math.max(10, Math.min(10000, ms));
    const newDelays = c.frameDelays.map(dd => {
      const next = [...dd];
      next[fi] = clamped;
      return next;
    });
    syncComp({ ...c, frameDelays: newDelays });
  }

  function applyDelayToAll(ms: number): void {
    const c = compRef.current;
    const clamped = Math.max(10, Math.min(10000, ms));
    const newDelays = c.frameDelays.map(dd => dd.map(() => clamped));
    syncComp({ ...c, frameDelays: newDelays });
    setStatusMsg(`All frame delays set to ${clamped} ms`);
  }

  function applyStride(n: number): void {
    if (n < 2) return;
    const c    = compRef.current;
    const maxF = Math.max(...c.frames.map(t => t.length), 1);
    if (maxF <= 1) { setStatusMsg('Only 1 frame — nothing to stride'); return; }
    historyRef.current.snapshot(c.frames);

    const newFrames = c.frames.map(tf => {
      if (tf.length <= 1) return tf;
      const kept: Uint8Array[] = [];
      const keptCount = Math.ceil(tf.length / n);
      for (let j = 0; j < keptCount; j++) kept.push(tf[j * n]);
      return kept;
    });

    const newDelays = c.frameDelays.map(dd => {
      if (dd.length <= 1) return dd;
      const kept: number[] = [];
      const keptCount = Math.ceil(dd.length / n);
      for (let j = 0; j < keptCount; j++) {
        const src = j * n;
        let acc = 0;
        for (let k = src; k < Math.min(src + n, dd.length); k++) acc += dd[k];
        kept.push(acc);
      }
      return kept;
    });

    const newCount  = Math.ceil(maxF / n);
    const newActive = Math.min(c.activeFrame, newCount - 1);
    syncComp({ ...c, frames: newFrames, frameDelays: newDelays, activeFrame: newActive });
    setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });
    setStatusMsg(`Stride ${n}: ${maxF} → ${newCount} frames`);
  }

  function applySkip(n: number): void {
    if (n < 2) return;
    const c    = compRef.current;
    const maxF = Math.max(...c.frames.map(t => t.length), 1);
    if (maxF <= 1) { setStatusMsg('Only 1 frame — nothing to skip'); return; }
    historyRef.current.snapshot(c.frames);

    const newFrames = c.frames.map(tf => {
      if (tf.length <= 1) return tf;
      const kept: Uint8Array[] = [];
      for (let j = 0; j < tf.length; j++) if ((j + 1) % n !== 0) kept.push(tf[j]);
      if (kept.length === 0) kept.push(tf[0]);
      return kept;
    });

    const newDelays = c.frameDelays.map(dd => {
      if (dd.length <= 1) return dd;
      const kept: number[] = [];
      let pending = 0;
      for (let j = 0; j < dd.length; j++) {
        if ((j + 1) % n === 0) {
          pending += dd[j];
        } else {
          kept.push((dd[j] ?? 100) + pending);
          pending = 0;
        }
      }
      if (pending > 0 && kept.length > 0) kept[kept.length - 1] += pending;
      if (kept.length === 0) kept.push(dd[0] ?? 100);
      return kept;
    });

    const newCount  = Math.max(...newFrames.map(tf => tf.length), 1);
    const newActive = Math.min(c.activeFrame, newCount - 1);
    syncComp({ ...c, frames: newFrames, frameDelays: newDelays, activeFrame: newActive });
    setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });
    setStatusMsg(`Skip ${n}: ${maxF} → ${newCount} frames`);
  }

  function moveFrame(dir: -1 | 1): void {
    const c = compRef.current;
    const fi  = c.activeFrame;
    const maxF = Math.max(...c.frames.map(t => t.length), 1);
    const fi2 = fi + dir;
    if (fi2 < 0 || fi2 >= maxF) return;
    historyRef.current.snapshot(c.frames);
    const newFrames = c.frames.map(tf => {
      const next = [...tf];
      [next[fi], next[fi2]] = [next[fi2], next[fi]];
      return next;
    });
    const newDelays = c.frameDelays.map(dd => {
      const next = [...dd];
      [next[fi], next[fi2]] = [next[fi2], next[fi]];
      return next;
    });
    syncComp({ ...c, frames: newFrames, frameDelays: newDelays, activeFrame: fi2 });
    setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });
  }

  // ── Requantize ──────────────────────────────────────────────────────────────
  // Derive { customPalette, tilePalette } from the current palette-mode setting.
  // Queue-palette override (reqQueuePalette) takes precedence.
  function buildReqPalette(): { customPalette: Uint8Array | undefined; tilePalette: boolean } {
    if (reqQueuePaletteRef.current && mergeQueueRef.current.size > 0) {
      const cp = new Uint8Array(256);
      for (const col of mergeQueueRef.current) cp[col] = 1;
      return { customPalette: cp, tilePalette: false };
    }
    const mode = reqPaletteModeRef.current;
    if (mode === 'tile') return { customPalette: undefined, tilePalette: true };
    if (mode === 'auto') return { customPalette: undefined, tilePalette: false };
    return {
      customPalette: buildPaletteFlag(mode, { greyThreshold: reqGreyThreshRef.current }),
      tilePalette: false,
    };
  }

  const doRequantize = useCallback(async () => {
    if (reqRunning) return;
    // Clear any existing preview
    if (previewCompRef.current) { setPreviewComp(null); }
    setReqRunning(true);
    setReqStatus('⏳ Computing…');

    const c = compRef.current;
    const { gridCols, gridRows, frames, activeFrame, allShades } = c;

    try {
      const useSourceImg = reqUseSourceRef.current === 'source'
        && (!!sourceBitmapRef.current || !!sourceFramesRef.current);
      const reqPre: PreprocessParams = {
        brightness: reqBrightnessRef.current,
        contrast:   reqContrastRef.current,
        saturation: reqSaturationRef.current,
      };

      // Helper: build source ImageData for a given animation frame index.
      async function buildSource(fi: number): Promise<ImageData> {
        if (useSourceImg) {
          // Prefer per-frame source bitmap (from GIF import), fall back to single bitmap.
          const bmp = sourceFramesRef.current?.[fi] ?? sourceBitmapRef.current;
          if (bmp) {
            return prepareSourceImage(bmp, gridCols * MAP_SIZE, gridRows * MAP_SIZE,
              importCropModeRef.current, reqPre);
          }
        }
        const raw = mapBytesToImageData(frames, fi, gridCols, gridRows);
        return applyPreprocess(raw, reqPre);
      }

      let source: ImageData;
      source = await buildSource(activeFrame);

      const { customPalette, tilePalette: tp } = buildReqPalette();

      const params: RequantizeParams = {
        legalOnly:          !allShades,
        customPalette,
        dither:             reqAlgoRef.current,
        metric:             reqMetricRef.current,
        fsStrength:         reqFsStrRef.current,
        atkStrength:        reqAtkStrRef.current,
        sierraStrength:     reqSierraStrRef.current,
        sierra2Strength:    reqSierra2StrRef.current,
        sierraLiteStrength: reqSierraLiteStrRef.current,
        shiauFanStrength:   reqShiauFanStrRef.current,
        jjnStrength:        reqJjnStrRef.current,
        stuckiStrength:     reqStuckiStrRef.current,
        serpentine:         reqSerpentineRef.current,
        bayerScale:         reqBayerRef.current,
        bayerSize:          reqBayerSizeRef.current,
        chromaBoost:        reqChromaRef.current,
        tilePalette:        tp,
        useCustomDither:    false,
        ditherMask:         null,
      };

      const maxF = Math.max(...frames.map(t => t.length), 1);
      const allF = reqAllFramesRef.current && maxF > 1;

      // Build per-frame results (one requantize pass per animation frame if "all frames").
      let builtFrames = frames.map(tf => [...tf]); // shallow copy
      const framesToProcess = allF ? Array.from({ length: maxF }, (_, i) => i) : [activeFrame];
      if (allF) setReqProgress({ done: 0, total: maxF });

      for (const fi of framesToProcess) {
        const src    = await buildSource(fi);
        // Temporarily present the composition as if activeFrame=fi so requantizeGrid
        // reads the right tile data for selection masking.
        const cForFi = fi === activeFrame ? c : { ...c, activeFrame: fi };
        const tiles  = requantizeGrid(src, cForFi, selMaskRef.current, params);
        builtFrames  = builtFrames.map((tf, ti) => {
          const next = [...tf];
          next[fi] = tiles[ti] ?? next[fi];
          return next;
        });
        if (allF) { setReqStatus(`⏳ Frame ${fi + 1}/${maxF}`); setReqProgress({ done: fi + 1, total: maxF }); }
      }
      if (allF) setReqProgress(null);

      const newFrames = builtFrames;
      const preview: CompositionState = { ...c, frames: newFrames };
      setPreviewComp(preview);
      const srcNote = useSourceImg ? '' : ' (pixels)';
      const framesNote = allF ? ` · all ${maxF} frames` : '';
      void framesNote; // used below
      const modeLabel = reqPaletteModeRef.current === 'auto' ? '' :
        reqPaletteModeRef.current === 'tile' ? ' · tile palette' :
        reqPaletteModeRef.current === 'greyscale' ? ' · greyscale' :
        ' · ' + reqPaletteModeRef.current;
      const palNote = (reqQueuePaletteRef.current && mergeQueueRef.current.size > 0)
        ? ` · ${mergeQueueRef.current.size}c queue`
        : modeLabel;
      const allFramesNote = allF ? ` · all ${maxF} frames` : '';
      setReqStatus(`🔍 Preview${srcNote}${palNote}${allFramesNote} — Enter/Y: commit  ·  Esc: cancel`);
    } catch (err) {
      setReqStatus(`Error: ${err}`);
      setTimeout(() => setReqStatus(null), 3500);
    } finally {
      setReqRunning(false);
      setReqProgress(null);
    }
  }, [reqRunning]);

  const doRequantizeRef = useRef(doRequantize);
  useEffect(() => { doRequantizeRef.current = doRequantize; }, [doRequantize]);


  // ── Auto-refresh preview when req params change ──────────────────────────────
  // If a preview is already showing, re-run requantize immediately so the
  // user can see the effect of dither / metric / chroma changes live.
  useEffect(() => {
    if (!previewCompRef.current) return;
    void doRequantizeRef.current();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reqAlgo, reqMetric, reqChroma, reqFsStr, reqAtkStr,
      reqSierraStr, reqSierra2Str, reqSierraLiteStr, reqShiauFanStr, reqJjnStr, reqStuckiStr,
      reqSerpentine, reqBayer, reqBayerSize,
      reqPaletteMode, reqGreyThresh,
      reqUseSource, reqQueuePalette, mergeQueueVer,
      reqBrightness, reqContrast, reqSaturation]);

  // ── Canvas init ─────────────────────────────────────────────────────────────
  useEffect(() => {
    const el = canvasElRef.current!;

    const canvas = new MapCanvas(el, {
      onPixelEvent: (gx, gy, button, buttons, e) => {
        // Paste mode: left-click stamps, right-click cancels
        if (inPasteModeRef.current) {
          if (button === 0) commitPasteAt(gx, gy);
          else if (button === 2) setInPasteMode(false);
          return;
        }

        // Any interaction cancels the requantize preview
        if (previewCompRef.current && button !== -1) {
          setPreviewComp(null);
          setReqStatus(null);
        }

        // Ctrl+click: toggle color in merge queue
        if ((e.ctrlKey || e.metaKey) && button === 0) {
          const color = readPixel(compRef.current, gx, gy);
          if (color > 0) toggleMergeColor(color);
          return;
        }

        const ctx  = getCtx();
        const tool = TOOL_MAP.get(activeToolIdRef.current) ?? brushTool;
        tool.onPointerEvent?.(gx, gy, button, buttons, ctx);
        setComp({ ...compRef.current });
        setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });

        // Rebuild heatmap after paint operations
        if (heatmapOnRef.current && canvasRef.current) {
          canvasRef.current.heatmapLut = buildHeatmapLut(compRef.current);
        }
      },
      onPointerUp: () => {
        TOOL_MAP.get(activeToolIdRef.current)?.onPointerUp?.(getCtx());
      },
      onPointerHover: (gx, gy) => {
        setCursorGx(gx); setCursorGy(gy);
        const c  = compRef.current;
        const tx = (gx / MAP_SIZE) | 0;
        const ty = (gy / MAP_SIZE) | 0;
        const ti = ty * c.gridCols + tx;
        const frame = c.frames[ti]?.[c.activeFrame];
        setHoverColor(frame ? frame[(gx % MAP_SIZE) + (gy % MAP_SIZE) * MAP_SIZE] : 0);
        setScale(canvas.scale);
        const toolId = activeToolIdRef.current;
        if (toolId === 'wand') {
          magicWand.updateHover(gx, gy, getCtx(), ctrlHeldRef.current);
        } else if (toolId === 'fill') {
          fillTool.updateHover(gx, gy, getCtx());
        }
      },
      onWheel: (e) => {
        // Shift+scroll: adjust the active tool's primary parameter.
        const delta  = e.deltaY < 0 ? 1 : -1;
        const toolId = activeToolIdRef.current;
        switch (toolId) {
          case 'brush': {
            setBrushRadius(r => Math.max(0, Math.min(64, r + delta)));
            break;
          }
          case 'wand': {
            const step = e.ctrlKey ? 0.005 : 0.025;
            const sc   = e.ctrlKey ? 200   : 40;
            setWandTol(t => Math.round(Math.max(0, Math.min(0.5, t + delta * step)) * sc) / sc);
            // Recompute hover preview at current cursor position with updated tolerance.
            magicWand.invalidateHoverCache();
            magicWand.updateHover(canvas.brushX, canvas.brushY, getCtx(), ctrlHeldRef.current);
            break;
          }
          case 'fill': {
            const step  = e.ctrlKey ? 0.005 : 0.025;
            const sc    = e.ctrlKey ? 200   : 40;
            setFillTol(t => Math.round(Math.max(0, Math.min(0.5, t + delta * step)) * sc) / sc);
            // Recompute fill preview at current cursor position with updated tolerance.
            fillTool.invalidateHoverCache();
            fillTool.updateHover(canvas.brushX, canvas.brushY, getCtx());
            break;
          }
        }
      },
    });

    canvasRef.current = canvas;
    canvas.setComposition(compRef.current);
    canvas.selMask     = selMaskRef.current;
    canvas.brushRadius = brushRadiusRef.current;
    canvas.brushShape  = brushShapeRef.current;

    const ro = new ResizeObserver(() => {
      canvas.resize();
      canvas.fitToView(compRef.current);
      setScale(canvas.scale);
    });
    ro.observe(el);
    canvas.resize();
    canvas.fitToView(compRef.current);
    canvas.start();

    return () => { canvas.stop(); ro.disconnect(); };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── Keyboard shortcuts ──────────────────────────────────────────────────────
  useEffect(() => {
    // Track modifier key state so tools can read it via ToolContext.
    // Also re-run the wand hover when Ctrl toggles (changes preview mode).
    const onKeyUp = (e: KeyboardEvent) => {
      if (e.key === 'Control' || e.key === 'Meta') {
        ctrlHeldRef.current = false;
        // If wand is active, switch back to blue (add) preview.
        if (activeToolIdRef.current === 'wand' && canvasRef.current) {
          magicWand.invalidateHoverCache();
          magicWand.updateHover(canvasRef.current.brushX, canvasRef.current.brushY, getCtx(), false);
        }
      }
      if (e.key === 'Shift') shiftHeldRef.current = false;
    };
    window.addEventListener('keyup', onKeyUp);

    const onKey = (e: KeyboardEvent) => {
      const tag       = (document.activeElement as HTMLElement)?.tagName ?? '';
      const inputType = ((document.activeElement as HTMLInputElement)?.type ?? '').toLowerCase();
      const ctrl      = e.ctrlKey || e.metaKey;
      // Only suppress single-key shortcuts when the user is actively typing text.
      // Range sliders, checkboxes, radio buttons, selects, and buttons should NOT
      // block shortcuts — only text/number/password/email/url/search inputs do.
      const isTextEntry = tag === 'TEXTAREA'
        || (tag === 'INPUT' && ['text','number','email','password','search','url'].includes(inputType));
      if (isTextEntry && !ctrl) return;

      // Update modifier refs (used by tools via ToolContext).
      ctrlHeldRef.current  = ctrl;
      shiftHeldRef.current = e.shiftKey;

      // When Ctrl is pressed while wand is active, switch to orange (subtract) preview.
      if ((e.key === 'Control' || e.key === 'Meta') && activeToolIdRef.current === 'wand' && canvasRef.current) {
        magicWand.invalidateHoverCache();
        magicWand.updateHover(canvasRef.current.brushX, canvasRef.current.brushY, getCtx(), true);
        return;
      }

      const shift = e.shiftKey;

      // ── Ctrl combos ──────────────────────────────────────────────────────
      if (ctrl && !shift && e.key === 'z') {
        e.preventDefault();
        const h = historyRef.current;
        const res = h.undo(compRef.current.frames);
        if (res) {
          syncComp({ ...compRef.current, frames: res });
          setUndoState({ canUndo: h.canUndo, canRedo: h.canRedo });
          setPreviewComp(null); setReqStatus(null);
        }
        return;
      }
      if (ctrl && shift && (e.key === 'z' || e.key === 'Z')) {
        e.preventDefault();
        const h = historyRef.current;
        const res = h.redo(compRef.current.frames);
        if (res) { syncComp({ ...compRef.current, frames: res }); setUndoState({ canUndo: h.canUndo, canRedo: h.canRedo }); }
        return;
      }
      if (ctrl && e.key === 'a') {
        e.preventDefault();
        const { gridCols, gridRows } = compRef.current;
        setSelMask(new Uint8Array(gridCols * MAP_SIZE * gridRows * MAP_SIZE).fill(1));
        return;
      }
      if (ctrl && e.key === 'd') {
        e.preventDefault();
        setSelMask(null);
        return;
      }
      if (ctrl && e.key === 'i') {
        e.preventDefault();
        const { gridCols, gridRows } = compRef.current;
        const size = gridCols * MAP_SIZE * gridRows * MAP_SIZE;
        const cur  = selMaskRef.current;
        const inv  = new Uint8Array(size);
        let any    = false;
        for (let i = 0; i < size; i++) {
          inv[i] = cur ? (cur[i] ? 0 : 1) : 1;
          if (inv[i]) any = true;
        }
        setSelMask(any ? inv : null);
        return;
      }
      if (ctrl && e.key === 'c') { e.preventDefault(); doCopySelection(); return; }
      if (ctrl && e.key === 'x') { e.preventDefault(); doCutSelection();  return; }
      if (ctrl && e.key === 'v') { e.preventDefault(); startPaste();      return; }
      // Let Ctrl+[ and Ctrl+] (frame nav) fall through.
      if (ctrl && e.key !== '[' && e.key !== ']') return;

      // ── Non-Ctrl ─────────────────────────────────────────────────────────
      const c  = compRef.current;
      const gw = c.gridCols * MAP_SIZE;
      const gh = c.gridRows * MAP_SIZE;
      const maxFrames = Math.max(...c.frames.map(t => t.length), 1);

      switch (e.key) {

        // ─ Play/pause animation (Space) ──────────────────────────────────
        case ' ':
          e.preventDefault();
          setPlaying(p => !p);
          break;

        // ─ Escape ────────────────────────────────────────────────────────
        case 'Escape':
          e.preventDefault();
          if (inPasteModeRef.current) { setInPasteMode(false); break; }
          if (previewCompRef.current) { setPreviewComp(null); setReqStatus(null); setStatusMsg('Preview cancelled'); break; }
          if (activeToolIdRef.current === 'lasso' && lasso.hasPath()) { lasso.deactivate(getCtx()); break; }
          if (selMaskRef.current) { setSelMask(null); break; }
          break;

        // ─ Commit requantize preview / paste ─────────────────────────────
        case 'Enter': {
          e.preventDefault();
          if (previewCompRef.current) {
            historyRef.current.snapshot(compRef.current.frames);
            syncComp(previewCompRef.current);
            setPreviewComp(null);
            setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });
            setReqStatus(null);
            setStatusMsg('Requantize applied ✓');
          } else if (inPasteModeRef.current) {
            const cv = canvasRef.current;
            if (cv) commitPasteAt(cv.brushX, cv.brushY);
          }
          break;
        }

        case 'y': case 'Y':
          if (!ctrl && previewCompRef.current) {
            historyRef.current.snapshot(compRef.current.frames);
            syncComp(previewCompRef.current);
            setPreviewComp(null);
            setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });
            setReqStatus(null);
            setStatusMsg('Requantize applied ✓');
          }
          break;

        // ─ Tool keys ─────────────────────────────────────────────────────
        case 'b': case 'B': doSwitchTool('brush');  break;
        case 'f': case 'F': doSwitchTool('fill');   break;
        case 's': case 'S': doSwitchTool('select'); break;
        case 'l': case 'L': doSwitchTool('lasso');  break;
        case 'w': case 'W': doSwitchTool('wand');   break;

        // ─ Brush shape / radius ───────────────────────────────────────────
        case 'x': case 'X':
          setBrushShape(brushShapeRef.current === 'circle' ? 'square' : 'circle');
          break;
        case '[':
          if (!ctrl) setBrushRadius(r => Math.max(0, r - 1));
          else {
            e.preventDefault();
            syncComp({ ...c, activeFrame: (c.activeFrame - 1 + maxFrames) % maxFrames });
          }
          break;
        case ']':
          if (!ctrl) setBrushRadius(r => Math.min(64, r + 1));
          else {
            e.preventDefault();
            syncComp({ ...c, activeFrame: (c.activeFrame + 1) % maxFrames });
          }
          break;

        // ─ Selection grow/shrink / tolerance ─────────────────────────────
        case '=': case '+': {
          if (selMaskRef.current) {
            e.preventDefault();
            const n = shift ? 5 : 1;
            let mask = selMaskRef.current;
            for (let i = 0; i < n; i++) mask = dilateSelMask(mask, gw, gh);
            setSelMask(mask);
          } else if (activeToolIdRef.current === 'fill') {
            const step  = ctrl ? 0.005 : 0.025;
            const sc    = ctrl ? 200   : 40;
            setFillTol(t => Math.round(Math.min(0.5, t + step) * sc) / sc);
          } else if (activeToolIdRef.current === 'wand') {
            const step = ctrl ? 0.005 : 0.025;
            const sc   = ctrl ? 200   : 40;
            setWandTol(t => Math.round(Math.min(0.5, t + step) * sc) / sc);
          }
          break;
        }
        case '-': {
          if (selMaskRef.current) {
            e.preventDefault();
            const n = shift ? 5 : 1;
            let mask = selMaskRef.current;
            for (let i = 0; i < n; i++) mask = erodeSelMask(mask, gw, gh);
            setSelMask(mask);
          } else if (activeToolIdRef.current === 'fill') {
            const step  = ctrl ? 0.005 : 0.025;
            const sc    = ctrl ? 200   : 40;
            setFillTol(t => Math.round(Math.max(0, t - step) * sc) / sc);
          } else if (activeToolIdRef.current === 'wand') {
            const step = ctrl ? 0.005 : 0.025;
            const sc   = ctrl ? 200   : 40;
            setWandTol(t => Math.round(Math.max(0, t - step) * sc) / sc);
          }
          break;
        }

        // ─ Delete: clear selection → transparent, or drop frame ──────────
        case 'Delete': case 'Backspace': {
          if (selMaskRef.current) {
            e.preventDefault();
            historyRef.current.snapshot(c.frames);
            let cleared = 0;
            const sel = selMaskRef.current!;
            for (let gy = 0; gy < gh; gy++)
              for (let gx = 0; gx < gw; gx++)
                if (sel[gy * gw + gx]) { writePixel(c, gx, gy, 0, null); cleared++; }
            if (cleared > 0) {
              syncComp({ ...c });
              setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });
              setStatusMsg(`Cleared ${cleared} selected pixel${cleared!==1?'s':''}`);
            }
          } else if (maxFrames > 1) {
            e.preventDefault();
            dropCurrentFrame();
          }
          break;
        }

        // ─ Frame navigation (<  >) and delay adjustment (,  .) ───────────
        case ',':
          e.preventDefault();
          if (shift && maxFrames > 1) {
            // Shift+, = < = previous frame
            syncComp({ ...c, activeFrame: (c.activeFrame - 1 + maxFrames) % maxFrames });
          } else if (!shift && maxFrames > 1) {
            adjustFrameDelay(-10);
          }
          break;
        case '.':
          e.preventDefault();
          if (shift && maxFrames > 1) {
            // Shift+. = > = next frame
            syncComp({ ...c, activeFrame: (c.activeFrame + 1) % maxFrames });
          } else if (!shift && maxFrames > 1) {
            adjustFrameDelay(10);
          }
          break;
        case '<':   // explicit in case browser reports shifted key
          if (maxFrames > 1) { e.preventDefault(); syncComp({ ...c, activeFrame: (c.activeFrame - 1 + maxFrames) % maxFrames }); }
          break;
        case '>':
          if (maxFrames > 1) { e.preventDefault(); syncComp({ ...c, activeFrame: (c.activeFrame + 1) % maxFrames }); }
          break;

        // ─ Requantize ─────────────────────────────────────────────────────
        case 'r': case 'R':
          e.preventDefault();
          void doRequantizeRef.current();
          break;

        // ─ Cycle dither algo (D) ──────────────────────────────────────────
        case 'd': case 'D':
          setReqAlgo(a => {
            const next = DITHER_CYCLE[(DITHER_CYCLE.indexOf(a) + 1) % DITHER_CYCLE.length];
            setStatusMsg(`Dither: ${DITHER_LABEL[next]}`);
            return next;
          });
          break;

        // ─ Cycle match metric (Q) ─────────────────────────────────────────
        case 'q': case 'Q':
          setReqMetric(m => {
            const next = METRIC_CYCLE[(METRIC_CYCLE.indexOf(m) + 1) % METRIC_CYCLE.length];
            setStatusMsg(`Metric: ${METRIC_LABEL[next]}`);
            return next;
          });
          break;

        // ─ Chroma boost (N = +0.25, Shift+N = -0.25) ─────────────────────
        case 'n': case 'N': {
          const chromaStep = 0.25;
          setReqChroma(v => {
            const next = Math.round(
              Math.max(0.25, Math.min(4.0, v + (shift ? -chromaStep : chromaStep))) / chromaStep,
            ) * chromaStep;
            setStatusMsg(`Chroma boost: ${next.toFixed(2)}×`);
            return next;
          });
          break;
        }

        // ─ Toggle heatmap (H) ─────────────────────────────────────────────
        case 'h': case 'H':
          setHeatmapOn(on => {
            const next = !on;
            setStatusMsg(next ? 'Heatmap: red=rarest, blue=most common' : 'Heatmap off');
            return next;
          });
          break;

        // ─ Toggle detail / compression map (Z) ───────────────────────────
        case 'z': case 'Z':
          if (!ctrl) {
            setDetailMapOn(on => {
              const next = !on;
              setStatusMsg(next ? 'Detail map on — bright = expensive to compress' : 'Detail map off');
              return next;
            });
          }
          break;

        // ─ Toggle dither mask overlay (M) ─────────────────────────────────
        case 'm': case 'M':
          if (canvasRef.current) {
            canvasRef.current.showDitherMask = !canvasRef.current.showDitherMask;
            canvasRef.current.markDirty();
          }
          break;

        // ─ Color merge (C = commit, V = cycle scope) ──────────────────────
        case 'c': case 'C':
          if (!ctrl) {
            if (mergeQueueRef.current.size > 0) commitMerge();
            else setStatusMsg('Queue empty — Ctrl+click swatches or canvas pixels');
          }
          break;
        case 'v': case 'V':
          if (!ctrl) {
            setMergeScope(mergeScopeRef.current === 'frame' ? 'all' : 'frame');
            setStatusMsg(`Merge scope: ${mergeScopeRef.current === 'frame' ? 'frame → all frames' : 'all frames → frame'}`);
          }
          break;

        // ─ Apply filter (P), cycle filter (Shift+P) ───────────────────────
        case 'p': case 'P':
          if (shift) {
            const nextFilter = FILTER_CYCLE[(FILTER_CYCLE.indexOf(filterTypeRef.current) + 1) % FILTER_CYCLE.length];
            setFilterType(nextFilter);
            setStatusMsg(`Filter: ${FILTER_LABEL[nextFilter]} (P to apply)`);
          } else {
            void applyEditorFilter();
          }
          break;

        // ─ Color reduce (K), cycle strategy (Shift+K) ────────────────────
        case 'k': case 'K':
          if (shift) {
            const nextStrat = STRATEGY_CYCLE[(STRATEGY_CYCLE.indexOf(reductionStrategyRef.current) + 1) % STRATEGY_CYCLE.length];
            setReductionStrategy(nextStrat);
            setStatusMsg(`Reduce strategy: ${STRATEGY_LABEL[nextStrat]}`);
          } else {
            applyReduction();
          }
          break;

        // ─ Frame navigation (,/.) handled above; Ctrl+[/] also above ─────

      }
    };

    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('keyup', onKeyUp);
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Auto-clear status message
  useEffect(() => {
    if (!statusMsg) return;
    const t = setTimeout(() => setStatusMsg(null), 2500);
    return () => clearTimeout(t);
  }, [statusMsg]);

  // ─── Computed display values ──────────────────────────────────────────────────
  const TOOL_DEFS = [
    { id:'brush',  label:'🖌 Brush',  key:'B', hint:'right-click: pick color' },
    { id:'fill',   label:'🪣 Fill',   key:'F', hint:'right-click: pick · scroll: tolerance' },
    { id:'select', label:'▭ Select', key:'S', hint:'drag: marquee · Ctrl: subtract' },
    { id:'lasso',  label:'⬡ Lasso',  key:'L', hint:'drag: freehand · click: add vertex · dbl-click: close polygon · Ctrl: subtract' },
    { id:'wand',   label:'✦ Wand',   key:'W', hint:'click: add · Ctrl: subtract · scroll: tol' },
  ] as const;

  const activeTool   = TOOL_MAP.get(activeToolId) ?? brushTool;
  const maxFrames    = Math.max(...comp.frames.map(t => t.length), 1);
  const frameDelay   = comp.frameDelays[0]?.[comp.activeFrame] ?? 100;
  // Distinct color count across all tiles for the active frame.
  // The IIFE runs on every render; the Uint8Array scan is O(tiles × 16384) so
  // we guard with useMemo to skip repeats when only non-pixel state changes.
  const distinctCount = (() => {
    const seen = new Uint8Array(256);
    const af = comp.activeFrame;
    for (const tf of comp.frames) {
      const f = tf[af]; if (!f) continue;
      for (let i = 0; i < f.length; i++) if (f[i]) seen[f[i]] = 1;
    }
    let n = 0;
    for (let c = 1; c < 256; c++) if (seen[c]) n++;
    return n;
  })();

  const mergeQueueSnapshot = new Set(mergeQueueRef.current); // for rendering (mergeQueueVer drives re-render)
  void mergeQueueVer; // suppress unused warning

  // ─── Render ───────────────────────────────────────────────────────────────────
  return (
    <div style={{ ...ROOT_STYLE, fontSize: uiFontSize }}>
      {/* ── Main row ── */}
      <div style={{ display:'flex', flex:1, overflow:'hidden' }}>
      {/* Left sidebar */}
      <div style={{ ...LEFT_PANEL, width: leftW, minWidth: 80, maxWidth: 500 }}>

        {/* Tool list */}
        {TOOL_DEFS.map(({ id, label, key, hint }) => (
          <button key={id} onClick={() => doSwitchTool(id)} title={`${label} (${key}) — ${hint}`}
            style={id === activeToolId ? TOOL_BTN_ON : TOOL_BTN_OFF}>
            <span style={{ fontSize:'0.71em', color: id === activeToolId ? '#8cf' : '#555' }}>{key} — {label}</span>
          </button>
        ))}

        <div style={DIVIDER} />

        {/* Brush params */}
        {activeToolId === 'brush' && (
          <>
            <Param label={`Radius: ${brushRadius}  ([ ] or Shift+scroll)`}>
              <input type="range" min={0} max={32} value={brushRadius}
                onInput={(e) => setBrushRadius(+(e.target as HTMLInputElement).value)}
                style={{ width:'100%' }} />
            </Param>
            <Param label="Shape (X)">
              <div style={{ display:'flex', gap:4 }}>
                {(['circle','square'] as const).map(s => (
                  <button key={s} onClick={() => setBrushShape(s)}
                    style={s === brushShape ? SHAPE_ON : SHAPE_OFF}>
                    {s === 'circle' ? '◯' : '□'}
                  </button>
                ))}
              </div>
            </Param>
          </>
        )}

        {/* Magic wand tolerance */}
        {activeToolId === 'wand' && (
          <Param label={`Tolerance: ${wandTol.toFixed(3)}  (= - or Shift+scroll)`}>
            <input type="range" min={0} max={0.5} step={0.005} value={wandTol}
              onInput={(e) => setWandTol(+(e.target as HTMLInputElement).value)}
              style={{ width:'100%' }} />
            <span style={{ fontSize:'0.71em', color:'#555' }}>click: add · right-click: subtract</span>
          </Param>
        )}

        {/* Fill tolerance */}
        {activeToolId === 'fill' && (
          <Param label={`Tolerance: ${fillTol.toFixed(3)}  (= - or Shift+scroll)`}>
            <input type="range" min={0} max={0.5} step={0.005} value={fillTol}
              onInput={(e) => setFillTol(+(e.target as HTMLInputElement).value)}
              style={{ width:'100%' }} />
          </Param>
        )}

        {/* Selection ops */}
        {selMask && (
          <>
            <div style={DIVIDER} />
            <button style={SEL_BTN} onClick={() => setSelMask(null)}>✕ Desel (Esc)</button>
            <button style={SEL_BTN} onClick={() => setSelMask(dilateSelMask(selMask, comp.gridCols*MAP_SIZE, comp.gridRows*MAP_SIZE))}>+ Grow (=)</button>
            <button style={SEL_BTN} onClick={() => setSelMask(erodeSelMask(selMask, comp.gridCols*MAP_SIZE, comp.gridRows*MAP_SIZE))}>− Shrink (-)</button>
            <button style={SEL_BTN} onClick={() => {
              const size = comp.gridCols*MAP_SIZE*comp.gridRows*MAP_SIZE;
              const inv  = new Uint8Array(size);
              let any    = false;
              for (let i = 0; i < size; i++) { inv[i] = selMask[i] ? 0 : 1; if (inv[i]) any = true; }
              setSelMask(any ? inv : null);
            }}>⇄ Invert (Ctrl+I)</button>
            <button style={SEL_BTN} onClick={doCopySelection}>⎘ Copy (Ctrl+C)</button>
            <button style={SEL_BTN} onClick={doCutSelection}>✂ Cut (Ctrl+X)</button>
          </>
        )}

        {/* Paste ghost hint */}
        {clipPixRef.current && !inPasteMode && (
          <button style={SEL_BTN} onClick={startPaste}>⎘ Paste (Ctrl+V)</button>
        )}

        <div style={DIVIDER} />

        {/* ── Requantize section ──────────────────────────────────────────── */}
        <div style={SECTION_LABEL}>Requantize (R)</div>

        <div style={MICRO_LABEL}>Dither  (D to cycle)</div>
        <div style={{ ...CHIP_ROW, flexWrap: 'wrap' }}>
          {DITHER_CYCLE.map(a => (
            <ChipBtn key={a} active={reqAlgo === a} onClick={() => setReqAlgo(a)}>
              {DITHER_LABEL[a]}
            </ChipBtn>
          ))}
        </div>

        {/* Per-algo strength — shown for every error-diffusion algo */}
        {reqAlgo === 'FS' && (
          <Param label={`FS strength: ${reqFsStr.toFixed(1)}`}>
            <input type="range" min={0.1} max={1} step={0.1} value={reqFsStr}
              onInput={(e) => setReqFsStr(+(e.target as HTMLInputElement).value)}
              style={{ width:'100%' }} />
          </Param>
        )}
        {reqAlgo === 'ATKINSON' && (
          <Param label={`Atk strength: ${reqAtkStr.toFixed(1)}`}>
            <input type="range" min={0.1} max={1} step={0.1} value={reqAtkStr}
              onInput={(e) => setReqAtkStr(+(e.target as HTMLInputElement).value)}
              style={{ width:'100%' }} />
          </Param>
        )}
        {reqAlgo === 'SIERRA' && (
          <Param label={`Sierra strength: ${reqSierraStr.toFixed(1)}`}>
            <input type="range" min={0.1} max={1} step={0.1} value={reqSierraStr}
              onInput={(e) => setReqSierraStr(+(e.target as HTMLInputElement).value)}
              style={{ width:'100%' }} />
          </Param>
        )}
        {reqAlgo === 'SIERRA2' && (
          <Param label={`Sierra2 strength: ${reqSierra2Str.toFixed(1)}`}>
            <input type="range" min={0.1} max={1} step={0.1} value={reqSierra2Str}
              onInput={(e) => setReqSierra2Str(+(e.target as HTMLInputElement).value)}
              style={{ width:'100%' }} />
          </Param>
        )}
        {reqAlgo === 'SIERRA_LITE' && (
          <Param label={`SierraL strength: ${reqSierraLiteStr.toFixed(1)}`}>
            <input type="range" min={0.1} max={1} step={0.1} value={reqSierraLiteStr}
              onInput={(e) => setReqSierraLiteStr(+(e.target as HTMLInputElement).value)}
              style={{ width:'100%' }} />
          </Param>
        )}
        {reqAlgo === 'SHIAU_FAN' && (
          <Param label={`Shiau-Fan strength: ${reqShiauFanStr.toFixed(1)}`}>
            <input type="range" min={0.1} max={1} step={0.1} value={reqShiauFanStr}
              onInput={(e) => setReqShiauFanStr(+(e.target as HTMLInputElement).value)}
              style={{ width:'100%' }} />
          </Param>
        )}
        {reqAlgo === 'JJN' && (
          <Param label={`JJN strength: ${reqJjnStr.toFixed(1)}`}>
            <input type="range" min={0.1} max={1} step={0.1} value={reqJjnStr}
              onInput={(e) => setReqJjnStr(+(e.target as HTMLInputElement).value)}
              style={{ width:'100%' }} />
          </Param>
        )}
        {reqAlgo === 'STUCKI' && (
          <Param label={`Stucki strength: ${reqStuckiStr.toFixed(1)}`}>
            <input type="range" min={0.1} max={1} step={0.1} value={reqStuckiStr}
              onInput={(e) => setReqStuckiStr(+(e.target as HTMLInputElement).value)}
              style={{ width:'100%' }} />
          </Param>
        )}

        {/* Serpentine scanning — all ED algos */}
        {ED_ALGOS.has(reqAlgo) && (
          <label style={TOGGLE_ROW}>
            <input type="checkbox" checked={reqSerpentine}
              onChange={e => setReqSerpentine((e.target as HTMLInputElement).checked)} />
            <span style={{ fontSize:'0.79em' }}>Serpentine scan</span>
          </label>
        )}

        {/* Bayer options */}
        {reqAlgo === 'BAYER' && (
          <>
            <Param label={`Bayer scale: ${reqBayer.toFixed(2)}`}>
              <input type="range" min={0.02} max={0.20} step={0.02} value={reqBayer}
                onInput={(e) => setReqBayer(+(e.target as HTMLInputElement).value)}
                style={{ width:'100%' }} />
            </Param>
            <div style={{ fontSize:'0.79em', color:'#888', marginTop:2 }}>Matrix size</div>
            <div style={CHIP_ROW}>
              {([2,4,8,16] as const).map(sz => (
                <ChipBtn key={sz} active={reqBayerSize === sz} onClick={() => setReqBayerSize(sz)}>
                  {sz}×{sz}
                </ChipBtn>
              ))}
            </div>
          </>
        )}

        <div style={MICRO_LABEL}>Metric  (Q to cycle)</div>
        <div style={CHIP_ROW}>
          {METRIC_CYCLE.map(m => (
            <ChipBtn key={m} active={reqMetric === m} onClick={() => setReqMetric(m)}>
              {METRIC_LABEL[m]}
            </ChipBtn>
          ))}
        </div>

        <Param label={`Chroma: ${reqChroma.toFixed(2)}×  (N / Shift+N)`}>
          <input type="range" min={0.25} max={4} step={0.05} value={reqChroma}
            onInput={(e) => setReqChroma(+(e.target as HTMLInputElement).value)}
            style={{ width:'100%' }} />
        </Param>

        <div style={MICRO_LABEL}>Palette</div>
        <select
          value={reqPaletteMode}
          onChange={e => setReqPaletteMode((e.target as HTMLSelectElement).value as typeof reqPaletteMode)}
          style={{
            width: '100%', background: '#252525', border: '1px solid #444',
            borderRadius: 3, color: '#ccc', fontSize: '0.82em', padding: '3px 5px',
            marginBottom: 2, cursor: 'pointer',
          }}
        >
          <option value="auto">Auto (respects All Shades)</option>
          <option value="tile">Tile colors</option>
          {PALETTE_CHOICES.map(c => (
            <option key={c.id} value={c.id}>{c.label}</option>
          ))}
        </select>

        {reqPaletteMode === 'greyscale' && (
          <Param label={`Chroma threshold: ${reqGreyThresh}`}>
            <input type="range" min={5} max={120} step={5} value={reqGreyThresh}
              onInput={e => setReqGreyThresh(+(e.target as HTMLInputElement).value)}
              style={{ width: '100%' }} />
          </Param>
        )}

        {/* Source image vs current pixels */}
        {sourceBitmap && (
          <>
            <div style={MICRO_LABEL}>Source</div>
            <div style={CHIP_ROW}>
              <ChipBtn active={reqUseSource === 'source'} onClick={() => setReqUseSource('source')}>
                ↺ Source image
              </ChipBtn>
              <ChipBtn active={reqUseSource === 'pixels'} onClick={() => setReqUseSource('pixels')}>
                ✎ Current pixels
              </ChipBtn>
            </div>
          </>
        )}

        {/* Image adjustments */}
        <div style={MICRO_LABEL}>Image Adjustments</div>
        {([ ['Brightness', reqBrightness, setReqBrightness],
            ['Contrast',   reqContrast,   setReqContrast],
            ['Saturation', reqSaturation, setReqSaturation],
          ] as [string, number, (v: number) => void][]).map(([label, val, set]) => (
          <div key={label} style={{ marginBottom: 3 }}>
            <div style={{ display:'flex', justifyContent:'space-between', fontSize:'0.71em', color:'#888' }}>
              <span>{label}</span>
              <span style={{ color: val === 1 ? '#555' : '#aaa' }}>{val.toFixed(2)}</span>
            </div>
            <input type="range" min={0} max={2} step={0.05} value={val}
              onInput={e => set(+(e.target as HTMLInputElement).value)}
              style={{ width:'100%' }} />
          </div>
        ))}
        {(reqBrightness !== 1 || reqContrast !== 1 || reqSaturation !== 1) && (
          <button style={{ ...SEL_BTN, fontSize:'0.71em', marginBottom:3 }}
            onClick={() => { setReqBrightness(1); setReqContrast(1); setReqSaturation(1); }}>
            Reset adjustments
          </button>
        )}

        {/* Apply to all animation frames */}
        {maxFrames > 1 && (
          <label style={TOGGLE_ROW}>
            <input type="checkbox" checked={reqAllFrames}
              onChange={e => setReqAllFrames((e.target as HTMLInputElement).checked)} />
            <span style={{ fontSize:'0.79em' }}>All {maxFrames} frames</span>
          </label>
        )}

        {/* Override with queued colors (takes precedence over palette selector) */}
        {mergeQueueSnapshot.size > 0 && (
          <label style={TOGGLE_ROW}>
            <input type="checkbox" checked={reqQueuePalette}
              onChange={e => setReqQueuePalette((e.target as HTMLInputElement).checked)} />
            <span style={{ fontSize:'0.79em' }}>
              Override: {mergeQueueSnapshot.size} queued color{mergeQueueSnapshot.size > 1 ? 's' : ''}
            </span>
          </label>
        )}

        {/* Preview mode controls */}
        {previewComp ? (
          <div style={{ marginTop:4, display:'flex', gap:3 }}>
            <button style={{ ...SEL_BTN, flex:1, textAlign:'center', color:'#7f7', borderColor:'#3a6' }}
              onClick={() => {
                historyRef.current.snapshot(compRef.current.frames);
                syncComp(previewComp);
                setPreviewComp(null);
                setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });
                setReqStatus(null); setStatusMsg('Requantize applied ✓');
              }}>
              ✓ Commit
            </button>
            <button style={{ ...SEL_BTN, flex:1, textAlign:'center', color:'#f77', borderColor:'#633' }}
              onClick={() => { setPreviewComp(null); setReqStatus(null); }}>
              ✕ Cancel
            </button>
          </div>
        ) : (
          <button
            onClick={() => void doRequantize()}
            disabled={reqRunning}
            style={{ ...SEL_BTN, marginTop:4, textAlign:'center', color: reqRunning ? '#555' : '#8cf' }}
          >
            {reqRunning ? 'Computing…' : 'Apply (R)'}
          </button>
        )}

        {reqProgress && (
          <div style={{ marginTop:3 }}>
            <div style={{ height:3, background:'#252525', borderRadius:2, overflow:'hidden' }}>
              <div style={{
                height:'100%',
                width:`${Math.round(reqProgress.done / reqProgress.total * 100)}%`,
                background:'#4a9eff', borderRadius:2, transition:'width 0.1s ease',
              }} />
            </div>
          </div>
        )}
        {reqStatus && (
          <div style={{ fontSize:'0.71em', color: reqStatus.startsWith('Error') ? '#f77' : reqStatus.startsWith('🔍') ? '#5cf' : '#7f7', marginTop:2 }}>
            {reqStatus}
          </div>
        )}
        {!sourceBitmap && (
          <div style={{ marginTop:2, display:'flex', alignItems:'center', gap:5, flexWrap:'wrap' }}>
            <span style={{ fontSize:'0.71em', color:'#555', fontStyle:'italic' }}>
              No source image
            </span>
            {onRelinkSource && (
              <button onClick={onRelinkSource} style={{
                background:'transparent', border:'1px solid #444', borderRadius:3,
                color:'#888', cursor:'pointer', fontSize:'0.68em', padding:'1px 5px',
              }} title="Re-link the original source file so requantize from source works">
                Re-link file…
              </button>
            )}
          </div>
        )}

        <div style={DIVIDER} />

        {/* ── Filter section ──────────────────────────────────────────────── */}
        <div style={SECTION_LABEL}>Filter (P)</div>
        <div style={CHIP_ROW}>
          {FILTER_CYCLE.map(ft => (
            <ChipBtn key={ft} active={filterType === ft} onClick={() => setFilterType(ft)}>
              {FILTER_LABEL[ft]}
            </ChipBtn>
          ))}
        </div>
        {(filterType === 'SMOOTH' || filterType === 'SHARPEN') && (
          <Param label={`Strength: ${filterStrength.toFixed(1)}`}>
            <input type="range" min={0.5} max={3} step={0.5} value={filterStrength}
              onInput={(e) => setFilterStrength(+(e.target as HTMLInputElement).value)}
              style={{ width:'100%' }} />
          </Param>
        )}
        {filterType === 'POSTERIZE' && (
          <Param label={`Levels: ${Math.round(filterStrength)}`}>
            <input type="range" min={2} max={8} step={1} value={Math.round(filterStrength)}
              onInput={(e) => setFilterStrength(+(e.target as HTMLInputElement).value)}
              style={{ width:'100%' }} />
          </Param>
        )}
        <label style={TOGGLE_ROW}>
          <input type="checkbox" checked={filterScope === 'all'}
            onChange={e => setFilterScope((e.target as HTMLInputElement).checked ? 'all' : 'frame')} />
          <span style={{ fontSize:'0.71em' }}>All frames</span>
        </label>
        <button style={{ ...SEL_BTN, marginTop:2 }} onClick={() => void applyEditorFilter()}>
          Apply (P)
        </button>
        <div style={{ fontSize:'0.71em', color:'#555', marginTop:1 }}>Shift+P: cycle type</div>

        <div style={DIVIDER} />

        {/* ── Color reduce section ────────────────────────────────────────── */}
        <div style={SECTION_LABEL}>Reduce (K)</div>
        <div style={CHIP_ROW}>
          {STRATEGY_CYCLE.map(s => (
            <ChipBtn key={s} active={reductionStrategy === s} onClick={() => setReductionStrategy(s)}>
              {STRATEGY_LABEL[s]}
            </ChipBtn>
          ))}
        </div>
        <label style={TOGGLE_ROW}>
          <input type="checkbox" checked={reduceScope === 'all'}
            onChange={e => setReduceScope((e.target as HTMLInputElement).checked ? 'all' : 'frame')} />
          <span style={{ fontSize:'0.71em' }}>All frames</span>
        </label>
        <button style={{ ...SEL_BTN, marginTop:2 }} onClick={applyReduction}>
          Reduce 1 color (K)
        </button>
        <div style={{ fontSize:'0.71em', color:'#555', marginTop:1 }}>Shift+K: cycle strategy · {distinctCount} distinct</div>

        <div style={DIVIDER} />

        {/* ── Merge section ───────────────────────────────────────────────── */}
        <div style={SECTION_LABEL}>Color Merge</div>
        <div style={{ fontSize:'0.71em', color:'#666', lineHeight:1.3 }}>
          Ctrl+click canvas or palette to queue; C commits → active color
        </div>
        {mergeQueueSnapshot.size > 0 && (
          <>
            <div style={{ fontSize:'0.71em', color:'#f93', marginTop:2 }}>
              {mergeQueueSnapshot.size} color{mergeQueueSnapshot.size>1?'s':''} queued
            </div>
            <div style={{ display:'flex', flexWrap:'wrap', gap:2, marginTop:2 }}>
              {Array.from(mergeQueueSnapshot).map(c => {
                const rgb = MC_PALETTE[c] ?? 0;
                const r = (rgb>>16)&0xff, g = (rgb>>8)&0xff, b = rgb&0xff;
                return (
                  <div key={c} onClick={() => toggleMergeColor(c)}
                    title={`Byte ${c} — click to remove`}
                    style={{ width:16, height:16, background:`rgb(${r},${g},${b})`, border:'1px solid #f93', cursor:'pointer', borderRadius:2 }} />
                );
              })}
            </div>
            <label style={TOGGLE_ROW}>
              <input type="checkbox" checked={mergeScope === 'all'}
                onChange={e => setMergeScope((e.target as HTMLInputElement).checked ? 'all' : 'frame')} />
              <span style={{ fontSize:'0.71em' }}>All frames (V to toggle)</span>
            </label>
            <div style={{ display:'flex', gap:3, marginTop:2 }}>
              <button style={{ ...SEL_BTN, flex:1, textAlign:'center', color:'#f93' }}
                onClick={commitMerge}>C: Commit</button>
              <button style={{ ...SEL_BTN, flex:1, textAlign:'center' }}
                onClick={() => { mergeQueueRef.current.clear(); setMergeQueueVer(v=>v+1); }}>Clear</button>
            </div>
          </>
        )}

        <div style={DIVIDER} />

        {/* ── Heatmap / overlays ───────────────────────────────────────────── */}
        <label style={TOGGLE_ROW}>
          <input type="checkbox" checked={heatmapOn}
            onChange={e => setHeatmapOn((e.target as HTMLInputElement).checked)} />
          <span style={{ fontSize:'0.79em' }}>Heatmap (H)</span>
        </label>
        <div style={{ fontSize:'0.71em', color:'#555' }}>red=rarest · blue=common</div>

        {/* Detail map overlay */}
        <label style={{ ...TOGGLE_ROW, marginTop:6 }}>
          <input type="checkbox" checked={detailMapOn}
            onChange={e => setDetailMapOn((e.target as HTMLInputElement).checked)} />
          <span style={{ fontSize:'0.79em' }}>Detail map (Z)</span>
        </label>
        <div style={{ fontSize:'0.68em', color:'#555' }}>transparent=cheap · bright red=expensive</div>

        {/* ── Frame controls ───────────────────────────────────────────────── */}
        {maxFrames > 1 && (
          <>
            <div style={DIVIDER} />
            <div style={SECTION_LABEL}>
              Frames: {comp.activeFrame + 1} / {maxFrames}  {frameDelay}ms
            </div>
            <div style={{ display:'flex', gap:3, flexWrap:'wrap' }}>
              <button style={SEL_BTN} onClick={() => syncComp({ ...comp, activeFrame: Math.max(0, comp.activeFrame - 1) })}>← Prev</button>
              <button style={SEL_BTN} onClick={() => syncComp({ ...comp, activeFrame: Math.min(maxFrames-1, comp.activeFrame+1) })}>Next →</button>
            </div>
            <div style={{ display:'flex', gap:3, flexWrap:'wrap', marginTop:2 }}>
              <button style={SEL_BTN} onClick={cloneCurrentFrame} title="Clone current frame">⎘ Clone</button>
              <button style={SEL_BTN} onClick={addBlankFrame}     title="Add blank frame">+ Blank</button>
              <button style={{ ...SEL_BTN, color:'#f77' }} onClick={dropCurrentFrame} title="Delete current frame (Del)">✕ Drop</button>
            </div>
            <div style={{ fontSize:'0.71em', color:'#555', marginTop:1 }}>
              , / .  adjust delay (±10ms, Shift=±100ms)
            </div>
          </>
        )}
        {maxFrames === 1 && (
          <>
            <div style={DIVIDER} />
            <div style={{ display:'flex', gap:3 }}>
              <button style={SEL_BTN} onClick={cloneCurrentFrame} title="Clone to start animation">⎘ Clone frame</button>
              <button style={SEL_BTN} onClick={addBlankFrame}     title="Add blank frame">+ Blank</button>
            </div>
          </>
        )}

      </div>

      <ResizeHandle onDrag={dx => setLeftW(w => Math.max(80, Math.min(500, w + dx)))} />

      {/* Canvas */}
      <div style={CANVAS_AREA}>
        <canvas ref={canvasElRef} style={{ display:'block', width:'100%', height:'100%' }} />

        {/* Status overlay for key-triggered messages */}
        {statusMsg && !reqStatus && (
          <div style={STATUS_OVERLAY}>{statusMsg}</div>
        )}

        {/* Requantize status — shown in a distinct color, persists until acted on */}
        {reqStatus && (
          <div style={{
            ...STATUS_OVERLAY,
            background: reqStatus.startsWith('🔍') ? 'rgba(0,40,70,0.88)' : 'rgba(0,0,0,0.75)',
            color: reqStatus.startsWith('🔍') ? '#5cf' : reqStatus.startsWith('Error') ? '#f77' : '#ccc',
          }}>
            {reqStatus}
          </div>
        )}

        {/* Paste mode banner */}
        {inPasteMode && (
          <div style={{ ...STATUS_OVERLAY, background:'rgba(40,0,80,0.88)', color:'#caf' }}>
            📋 PASTE — click to stamp · Enter · Esc to cancel
          </div>
        )}
      </div>

      {/* ── Stats sidebar ── */}
      {statsOpen ? (
        <div style={{
          width: 160, flexShrink: 0,
          background: '#181818', borderLeft: '1px solid #2a2a2a',
          display: 'flex', flexDirection: 'column', overflow: 'hidden',
          fontSize: uiFontSize,
        }}>
          {/* Header */}
          <div style={{
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            padding: '3px 6px 3px 8px', borderBottom: '1px solid #2a2a2a', flexShrink: 0,
          }}>
            <span style={{ fontSize: '0.71em', color: '#666', fontWeight: 'bold' }}>
              Stats{dataComputing ? ' ⏳' : ''}
            </span>
            <button onClick={() => setStatsOpen(false)} style={{
              background: 'none', border: 'none', color: '#444', cursor: 'pointer',
              fontSize: '0.79em', lineHeight: 1, padding: '0 2px',
            }} title="Collapse stats">×</button>
          </div>

          {/* Content */}
          <div style={{ flex: 1, overflowY: 'auto', padding: '4px 6px' }}>
            {dataStats ? (() => {
              const { tiles, totalBytes, maxBanners } = dataStats;
              return (
                <>
                  {tiles.map(t => {
                    const ti       = t.tileRow * comp.gridCols + t.tileCol;
                    const label    = comp.gridCols * comp.gridRows > 1 ? `(${t.tileRow},${t.tileCol})` : 'Tile';
                    const barPct   = Math.min(100, t.pct);
                    const barColor = t.overflow ? '#f55' : t.pct > 85 ? '#f93' : '#4af';
                    const isHov    = hoveredStatTile === ti;
                    return (
                      <div key={`${t.tileRow}_${t.tileCol}`}
                        style={{ marginBottom: 5, borderRadius: 2, background: isHov ? '#222' : 'transparent', padding: '1px 2px', cursor: 'default' }}
                        onMouseEnter={() => setHoveredStatTile(ti)}
                        onMouseLeave={() => setHoveredStatTile(null)}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.71em',
                          color: t.overflow ? '#f77' : isHov ? '#ccc' : '#888', marginBottom: 2 }}>
                          <span>{label}</span>
                          <span style={{ color: t.overflow ? '#f77' : '#555' }}>
                            {t.overflow ? '⚠ ' : ''}{(t.bytes / 1024).toFixed(1)}K
                          </span>
                        </div>
                        <div style={{ height: 3, background: '#2a2a2a', borderRadius: 2, overflow: 'hidden' }}>
                          <div style={{ height: '100%', width: `${barPct}%`, background: barColor, borderRadius: 2 }} />
                        </div>
                        <div style={{ fontSize: '0.63em', color: '#444', marginTop: 1 }}>
                          {t.banners} ban · {t.pct.toFixed(0)}%
                        </div>
                      </div>
                    );
                  })}
                  <div style={{ fontSize: '0.68em', color: '#555', borderTop: '1px solid #2a2a2a', paddingTop: 4, marginTop: 2 }}>
                    {(totalBytes / 1024).toFixed(1)} KB total · max {maxBanners} ban
                  </div>
                  <div style={{ fontSize: '0.58em', color: '#333', marginTop: 2, lineHeight: 1.4 }}>
                    cap 5,290 B / 63 ban
                  </div>
                </>
              );
            })() : (
              <div style={{ fontSize: '0.68em', color: '#333', marginTop: 6 }}>
                {dataComputing ? 'Computing…' : 'Loading…'}
              </div>
            )}
          </div>
        </div>
      ) : (
        <div
          onClick={() => { setStatsOpen(true); void computeDataStats(); }}
          title="Show stats"
          style={{
            width: 16, flexShrink: 0, background: '#181818',
            borderLeft: '1px solid #2a2a2a', cursor: 'pointer',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
          <span style={{ fontSize: '0.58em', color: '#333', writingMode: 'vertical-rl' }}>Stats</span>
        </div>
      )}

      <ResizeHandle onDrag={dx => setRightW(w => Math.max(80, Math.min(500, w - dx)))} />

      {/* Palette */}
      <PalettePanel
        comp={comp}
        activeColor={activeColor}
        selMask={selMask}
        mergeQueue={mergeQueueSnapshot}
        distinctCount={distinctCount}
        onColorPick={setActiveColor}
        onCtrlClick={toggleMergeColor}
        onColorHover={(color) => {
          if (canvasRef.current) canvasRef.current.hoveredPaletteColor = color;
        }}
        onShiftClick={(mapByte) => {
          const sel = selMaskRef.current;
          if (!sel || mapByte === 0) return;
          const c  = compRef.current;
          const gw = c.gridCols * MAP_SIZE;
          const newMask = sel.slice();
          let changed   = false;
          for (let ti = 0; ti < c.frames.length; ti++) {
            const tileCol = ti % c.gridCols;
            const tileRow = Math.floor(ti / c.gridCols);
            const frame   = c.frames[ti]?.[c.activeFrame];
            if (!frame) continue;
            for (let ly = 0; ly < MAP_SIZE; ly++) {
              for (let lx = 0; lx < MAP_SIZE; lx++) {
                if ((frame[lx + ly * MAP_SIZE] & 0xFF) === mapByte) {
                  const idx = (tileRow * MAP_SIZE + ly) * gw + (tileCol * MAP_SIZE + lx);
                  if (newMask[idx]) { newMask[idx] = 0; changed = true; }
                }
              }
            }
          }
          if (changed) setSelMask(newMask);
        }}
        panelWidth={rightW}
      />

      </div>{/* end main row */}

      {/* ── Frame timeline ── */}
      <FrameTimeline
        comp={comp}
        maxFrames={maxFrames}
        playing={playing}
        onPlayPause={() => setPlaying(!playingRef.current)}
        onPrevFrame={() => {
          if (playingRef.current) setPlaying(false);
          const c = compRef.current;
          const maxF = Math.max(...c.frames.map(t => t.length), 1);
          syncComp({ ...c, activeFrame: (c.activeFrame - 1 + maxF) % maxF });
        }}
        onNextFrame={() => {
          if (playingRef.current) setPlaying(false);
          const c = compRef.current;
          const maxF = Math.max(...c.frames.map(t => t.length), 1);
          syncComp({ ...c, activeFrame: (c.activeFrame + 1) % maxF });
        }}
        onFrameClick={fi => {
          if (playingRef.current) setPlaying(false);
          syncComp({ ...compRef.current, activeFrame: fi });
        }}
        onDelayChange={setFrameDelay}
        onApplyToAll={applyDelayToAll}
        onClone={() => { if (playing) setPlaying(false); cloneCurrentFrame(); }}
        onBlank ={() => { if (playing) setPlaying(false); addBlankFrame(); }}
        onDrop  ={() => { if (playing) setPlaying(false); dropCurrentFrame(); }}
        onMove   ={moveFrame}
        onStride ={applyStride}
        onSkip   ={applySkip}
      />

      {/* Status bar */}
      <StatusBar
        comp={comp}
        cursorGx={cursorGx}
        cursorGy={cursorGy}
        hoverColor={hoverColor}
        activeColor={activeColor}
        activeTool={activeTool.name}
        scale={scale}
        canUndo={undoState.canUndo}
        canRedo={undoState.canRedo}
        maxFrames={maxFrames}
        frameDelay={frameDelay}
        distinctCount={distinctCount}
        inPreview={!!previewComp}
        mergeQueueSize={mergeQueueSnapshot.size}
      />
    </div>
  );
}

// ─── FrameTimeline ────────────────────────────────────────────────────────────

interface FrameTimelineProps {
  comp:           CompositionState;
  maxFrames:      number;
  playing:        boolean;
  onPlayPause:    () => void;
  onPrevFrame:    () => void;   // uses compRef internally — never stale
  onNextFrame:    () => void;
  onFrameClick:   (fi: number) => void;
  onDelayChange:  (ms: number) => void;
  onApplyToAll:   (ms: number) => void;
  onClone:        () => void;
  onBlank:        () => void;
  onDrop:         () => void;
  onMove:         (dir: -1 | 1) => void;
  onStride:       (n: number) => void;
  onSkip:         (n: number) => void;
}

function FrameTimeline({
  comp, maxFrames, playing,
  onPlayPause, onPrevFrame, onNextFrame, onFrameClick,
  onDelayChange, onApplyToAll,
  onClone, onBlank, onDrop, onMove, onStride, onSkip,
}: FrameTimelineProps) {
  const fi    = comp.activeFrame;
  const delay = comp.frameDelays[0]?.[fi] ?? 100;
  const [thinN, setThinN] = useState(2);

  // ── Thumbnail dimensions ────────────────────────────────────────────────────
  const THUMB_H = 44;
  const THUMB_W = Math.max(28, Math.min(88, Math.round(THUMB_H * comp.gridCols / comp.gridRows)));

  // ── Thumbnail generation ─────────────────────────────────────────────────────
  // Draw directly to per-frame <canvas> elements — no blob encoding, no URLs.
  const thumbCanvasRefs = useRef<Array<HTMLCanvasElement | null>>([]);
  const thumbGenIdRef   = useRef(0);
  const latestCompRef2  = useRef(comp);
  latestCompRef2.current = comp;

  useEffect(() => {
    // Skip regeneration while playing — thumbnails update 500 ms after playback stops.
    if (playing) return;
    const genId = ++thumbGenIdRef.current;
    const timer = setTimeout(() => {
      // Run as an async generator so we can yield between frames and avoid
      // blocking the main thread (mapBytesToImageData + GPU uploads per frame
      // can total seconds for large animated GIFs).
      async function generate() {
        const c   = latestCompRef2.current;
        const nF  = Math.max(...c.frames.map(t => t.length), 1);
        const tw  = Math.max(28, Math.min(88, Math.round(THUMB_H * c.gridCols / c.gridRows)));
        // Shared full-size OffscreenCanvas reused for each frame.
        const src  = new OffscreenCanvas(c.gridCols * 128, c.gridRows * 128);
        const sctx = src.getContext('2d')!;
        for (let f = 0; f < nF; f++) {
          if (thumbGenIdRef.current !== genId) return;
          const thumbEl = thumbCanvasRefs.current[f];
          if (!thumbEl) continue;
          const imgData = mapBytesToImageData(c.frames, f, c.gridCols, c.gridRows);
          sctx.putImageData(imgData, 0, 0);
          thumbEl.width  = tw;
          thumbEl.height = THUMB_H;
          const dctx = thumbEl.getContext('2d')!;
          dctx.imageSmoothingEnabled = false;
          dctx.drawImage(src, 0, 0, tw, THUMB_H);
          // Yield every 4 frames so the browser can dispatch pending events
          // and the GPU can flush uploads before we queue more.
          if ((f & 3) === 3) await new Promise<void>(r => setTimeout(r, 0));
        }
      }
      void generate();
    }, 500);
    return () => { clearTimeout(timer); };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [comp, playing]);

  // ── Auto-scroll active chip into view ────────────────────────────────────────
  const stripRef    = useRef<HTMLDivElement>(null);
  const chipW       = THUMB_W + 2; // chip + gap

  useEffect(() => {
    const strip = stripRef.current;
    if (!strip) return;
    const target = fi * chipW - strip.clientWidth / 2 + chipW / 2;
    strip.scrollLeft = Math.max(0, target);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fi]);

  // ── Scrub-drag ───────────────────────────────────────────────────────────────
  const isDraggingRef = useRef(false);

  function getFrameAtPointer(clientX: number): number {
    const strip = stripRef.current;
    if (!strip) return -1;
    const rect = strip.getBoundingClientRect();
    const x    = clientX - rect.left + strip.scrollLeft;
    return Math.max(0, Math.min(maxFrames - 1, Math.floor(x / chipW)));
  }

  function handleStripPointerDown(e: PointerEvent) {
    if (e.button !== 0) return;
    e.preventDefault(); // keep keyboard focus where it was (prevents browser Ctrl+I / Page-Info)
    isDraggingRef.current = true;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    const f = getFrameAtPointer(e.clientX);
    if (f >= 0) onFrameClick(f);
  }

  function handleStripPointerMove(e: PointerEvent) {
    if (!isDraggingRef.current) return;
    const f = getFrameAtPointer(e.clientX);
    if (f >= 0 && f !== fi) onFrameClick(f);
  }

  function handleStripPointerUp() {
    isDraggingRef.current = false;
  }

  // ── Render ───────────────────────────────────────────────────────────────────
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 6,
      background: '#161616', borderTop: '1px solid #2a2a2a',
      padding: '3px 8px', flexShrink: 0, flexWrap: 'wrap', minHeight: THUMB_H + 10,
    }}>
      {/* Playback controls */}
      <div style={{ display:'flex', gap:2, flexShrink:0 }}>
        <TBtn title="Previous frame  (< or Ctrl+[)" onClick={onPrevFrame} disabled={maxFrames <= 1}>⏮</TBtn>
        <TBtn title={playing ? 'Pause (Space)' : 'Play (Space)'} onClick={onPlayPause} disabled={maxFrames <= 1}
          style={{ color: playing ? '#fc6' : undefined }}>{playing ? '⏸' : '▶'}</TBtn>
        <TBtn title="Next frame  (> or Ctrl+])" onClick={onNextFrame} disabled={maxFrames <= 1}>⏭</TBtn>
      </div>

      {/* Frame counter */}
      <span style={{ fontSize: 11, color: '#888', flexShrink: 0, minWidth: 56, textAlign: 'center' }}>
        {maxFrames > 1 ? `${fi + 1} / ${maxFrames}` : '1 frame'}
      </span>

      {/* Delay input */}
      {maxFrames > 1 && (
        <div style={{ display:'flex', alignItems:'center', gap:3, flexShrink:0 }}>
          <span style={{ fontSize:10, color:'#666' }}>ms</span>
          <input
            type="number" min={10} max={10000} step={10} value={delay}
            onChange={e => onDelayChange(+(e.target as HTMLInputElement).value)}
            style={{
              width: 58, background:'#252525', border:'1px solid #444',
              borderRadius:3, color:'#ccc', fontSize:11, padding:'2px 4px', textAlign:'center',
            }}
            title="Frame delay in milliseconds"
          />
          <TBtn title="Apply this delay to all frames" onClick={() => onApplyToAll(delay)} style={{ fontSize:9 }}>all</TBtn>
        </div>
      )}

      {/* Scrollable frame strip — click or drag to scrub */}
      <div
        ref={stripRef}
        onPointerDown={handleStripPointerDown}
        onPointerMove={handleStripPointerMove}
        onPointerUp={handleStripPointerUp}
        onPointerCancel={handleStripPointerUp}
        style={{
          display:'flex', gap:2, overflowX:'auto', flex:1, alignItems:'center',
          scrollbarWidth:'thin', minWidth:0, cursor:'pointer', userSelect:'none',
        }}
      >
        {Array.from({ length: maxFrames }, (_, i) => {
          const isActive = i === fi;
          return (
            <div key={i}
              title={`Frame ${i + 1}  (${comp.frameDelays[0]?.[i] ?? 100} ms)`}
              style={{
                width: THUMB_W, height: THUMB_H, flexShrink: 0,
                border: `1px solid ${isActive ? '#4a9eff' : '#2a2a2a'}`,
                borderRadius: 3, overflow: 'hidden', position: 'relative',
                background: '#111',
                boxShadow: isActive ? '0 0 0 1px #4a9eff' : 'none',
              }}
            >
              {/* Canvas element drawn to imperatively by the thumbnail effect */}
              <canvas
                ref={el => { thumbCanvasRefs.current[i] = el; }}
                style={{ display:'block', width:'100%', height:'100%', imageRendering:'pixelated' }}
              />
              {/* Frame-number badge */}
              <div style={{
                position:'absolute', bottom:1, right:2, fontSize:8, lineHeight:1,
                color: isActive ? '#8cf' : '#555',
                textShadow:'0 0 3px #000,0 0 3px #000',
                pointerEvents:'none',
              }}>
                {i + 1}
              </div>
              {/* Active underline */}
              {isActive && (
                <div style={{
                  position:'absolute', bottom:0, left:0, right:0, height:2,
                  background:'#4a9eff', pointerEvents:'none',
                }} />
              )}
            </div>
          );
        })}
      </div>

      {/* Frame operations */}
      <div style={{ display:'flex', gap:2, flexShrink:0 }}>
        {maxFrames > 1 && <>
          <TBtn title="Move frame left"  onClick={() => onMove(-1)} disabled={fi === 0}>◀</TBtn>
          <TBtn title="Move frame right" onClick={() => onMove(1)}  disabled={fi === maxFrames - 1}>▶</TBtn>
        </>}
        <TBtn title="Clone current frame (add copy after)"  onClick={onClone}>⎘</TBtn>
        <TBtn title="Add blank frame after current"         onClick={onBlank}>+</TBtn>
        {maxFrames > 1 && (
          <TBtn title="Delete current frame" onClick={onDrop} style={{ color:'#f77' }}>✕</TBtn>
        )}
      </div>

      {/* Stride / Skip — frame thinning */}
      {maxFrames > 1 && (
        <div style={{ display:'flex', alignItems:'center', gap:3, flexShrink:0, borderLeft:'1px solid #2a2a2a', paddingLeft:6 }}>
          <span style={{ fontSize:10, color:'#555' }}>n=</span>
          <input
            type="number" min={2} max={maxFrames} step={1} value={thinN}
            onChange={e => setThinN(Math.max(2, Math.min(maxFrames, +(e.target as HTMLInputElement).value | 0)))}
            style={{
              width:42, background:'#252525', border:'1px solid #444',
              borderRadius:3, color:'#ccc', fontSize:11, padding:'2px 4px', textAlign:'center',
            }}
            title="Thinning factor n for Stride / Skip"
          />
          <TBtn
            title={`Stride ${thinN}: keep every ${thinN}th frame (0, ${thinN}, ${2*thinN}…), accumulate delays`}
            onClick={() => onStride(thinN)}
          >
            Stride
          </TBtn>
          <TBtn
            title={`Skip ${thinN}: drop every ${thinN}th frame (${thinN-1}, ${2*thinN-1}…), merge delays`}
            onClick={() => onSkip(thinN)}
          >
            Skip
          </TBtn>
        </div>
      )}
    </div>
  );
}

/** Compact icon button used in the FrameTimeline. */
function TBtn({ onClick, title, disabled, style, children }: {
  onClick: () => void; title?: string; disabled?: boolean;
  style?: h.JSX.CSSProperties; children: preact.ComponentChildren;
}) {
  return (
    <button onClick={onClick} title={title} disabled={disabled} style={{
      background:'#252525', border:'1px solid #333', borderRadius:3,
      color: disabled ? '#444' : '#888', cursor: disabled ? 'default' : 'pointer',
      fontSize:12, padding:'2px 5px', lineHeight:1.4,
      ...style,
    }}>
      {children}
    </button>
  );
}

// ─── Small helper components ──────────────────────────────────────────────────

function Param({ label, children }: { label: string; children: ComponentChildren }) {
  return (
    <div style={{ display:'flex', flexDirection:'column', gap:3, marginTop:3 }}>
      <span style={{ fontSize:'0.79em', color:'#888', lineHeight:1.2 }}>{label}</span>
      {children}
    </div>
  );
}

function ChipBtn({ active, onClick, children }: { active: boolean; onClick: () => void; children: ComponentChildren }) {
  return (
    <button onClick={onClick} style={{
      flex:         1,
      background:   active ? '#1b3556' : 'transparent',
      border:       `1px solid ${active ? '#4a9eff' : '#444'}`,
      borderRadius:  3,
      color:        active ? '#8cf' : '#888',
      cursor:       'pointer',
      fontSize:     '0.79em',
      padding:      '3px 0',
      textAlign:    'center',
    }}>
      {children}
    </button>
  );
}

/** Draggable vertical divider between panels. */
function ResizeHandle({ onDrag }: { onDrag: (dx: number) => void }) {
  const onDragRef = useRef(onDrag);
  onDragRef.current = onDrag;

  const handleMouseDown = useCallback((e: MouseEvent) => {
    e.preventDefault();
    let lastX = e.clientX;
    const onMove = (ev: MouseEvent) => {
      const dx = ev.clientX - lastX;
      lastX    = ev.clientX;
      onDragRef.current(dx);
    };
    const onUp = () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup',   onUp);
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup',   onUp);
  }, []);

  return (
    <div
      onMouseDown={handleMouseDown}
      style={{
        width:       5,
        height:      '100%',
        cursor:      'ew-resize',
        background:  '#252525',
        flexShrink:   0,
        borderLeft:  '1px solid #333',
        borderRight: '1px solid #333',
        transition:  'background 0.1s',
        userSelect:  'none',
      }}
      onMouseEnter={e => { (e.target as HTMLElement).style.background = '#4a9eff55'; }}
      onMouseLeave={e => { (e.target as HTMLElement).style.background = '#252525'; }}
    />
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const ROOT_STYLE: h.JSX.CSSProperties = {
  display:       'flex',
  flexDirection: 'column',
  width:         '100%',
  height:        '100%',
  overflow:      'hidden',
  background:    '#1a1a1a',
  color:         '#ddd',
  fontFamily:    'system-ui, sans-serif',
  // fontSize applied inline with uiFontSize prop
};

const LEFT_PANEL: h.JSX.CSSProperties = {
  background:    '#1e1e1e',
  borderRight:   '1px solid #333',
  padding:        7,
  display:       'flex',
  flexDirection: 'column',
  gap:            3,
  overflowY:     'auto',
  flexShrink:     0,
};

const CANVAS_AREA: h.JSX.CSSProperties = {
  flex:     1,
  overflow: 'hidden',
  position: 'relative',
};

const BTN_BASE: h.JSX.CSSProperties = {
  background:    'transparent',
  border:        '1px solid transparent',
  borderRadius:   3,
  color:         '#ccc',
  cursor:        'pointer',
  padding:       '5px 6px',
  textAlign:     'left',
  fontSize:      '0.93em',
  lineHeight:     1.3,
};
const TOOL_BTN_OFF: h.JSX.CSSProperties = { ...BTN_BASE };
const TOOL_BTN_ON:  h.JSX.CSSProperties = { ...BTN_BASE, background:'#1b3556', borderColor:'#4a9eff', color:'#fff' };

const SHAPE_BASE: h.JSX.CSSProperties = {
  flex: 1, background:'transparent', border:'1px solid #444', borderRadius:3, color:'#ccc', cursor:'pointer', fontSize:'1.07em',
};
const SHAPE_OFF: h.JSX.CSSProperties = { ...SHAPE_BASE };
const SHAPE_ON:  h.JSX.CSSProperties = { ...SHAPE_BASE, background:'#1b3556', borderColor:'#4a9eff' };

const SEL_BTN: h.JSX.CSSProperties = {
  background:   '#252525',
  border:       '1px solid #444',
  borderRadius:  3,
  color:         '#bbb',
  cursor:        'pointer',
  padding:       '4px 7px',
  fontSize:      '0.86em',
  textAlign:     'left',
};

const DIVIDER: h.JSX.CSSProperties = {
  borderTop:  '1px solid #333',
  margin:     '5px 0',
  flexShrink:  0,
};

const SECTION_LABEL: h.JSX.CSSProperties = {
  fontSize:    '0.86em',
  color:       '#5af',
  fontWeight:  'bold',
  marginBottom: 2,
};

const MICRO_LABEL: h.JSX.CSSProperties = {
  fontSize: '0.79em',
  color:    '#666',
  marginTop: 3,
};

const CHIP_ROW: h.JSX.CSSProperties = {
  display: 'flex',
  gap:      2,
};

const TOGGLE_ROW: h.JSX.CSSProperties = {
  display:    'flex',
  alignItems: 'center',
  gap:         5,
  cursor:     'pointer',
  marginTop:   2,
};

const STATUS_OVERLAY: h.JSX.CSSProperties = {
  position:    'absolute',
  bottom:       8,
  left:        '50%',
  transform:   'translateX(-50%)',
  background:  'rgba(0,0,0,0.75)',
  color:       '#fff',
  fontSize:    '0.93em',
  padding:     '4px 12px',
  borderRadius: 4,
  pointerEvents: 'none',
  whiteSpace:  'nowrap',
};
