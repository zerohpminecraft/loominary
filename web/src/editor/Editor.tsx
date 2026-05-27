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
  scaleImage,
  DitherAlgo,
  MatchMetric,
  DEFAULT_REQ_PARAMS,
  type RequantizeParams,
  countDistinct,
  reduceColorsInPlace,
  type Strategy,
} from '../quantize.js';
import { MC_PALETTE }  from '../palette.js';
import type { CompositionState } from '../payload-state.js';
import { emptyPayloadState, compositionFromState } from '../payload-state.js';
import { FilterType, applyFilter } from '../filters.js';

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

function resizeComposition(comp: CompositionState, newCols: number, newRows: number): CompositionState {
  const frameCount     = Math.max(...comp.frames.map(t => t.length), 1);
  const newFrames:      Uint8Array[][] = [];
  const newFrameDelays: number[][]     = [];

  for (let row = 0; row < newRows; row++) {
    for (let col = 0; col < newCols; col++) {
      const oldTi = row * comp.gridCols + col;
      if (col < comp.gridCols && row < comp.gridRows && comp.frames[oldTi]) {
        newFrames.push(comp.frames[oldTi]);
        newFrameDelays.push(comp.frameDelays[oldTi] ?? new Array(comp.frames[oldTi].length).fill(100));
      } else {
        newFrames.push(Array.from({ length: frameCount }, () => new Uint8Array(128 * 128)));
        newFrameDelays.push(new Array(frameCount).fill(100));
      }
    }
  }

  return { ...comp, gridCols: newCols, gridRows: newRows, frames: newFrames, frameDelays: newFrameDelays };
}

const OKLAB = buildOklabLookup();

// ─── Metric / algo / filter / strategy cycle helpers ─────────────────────────

const METRIC_CYCLE: Array<typeof MatchMetric[keyof typeof MatchMetric]> =
  ['OKLAB', 'CHROMA_FIRST', 'LUMA_FIRST', 'HUE_ONLY', 'RGB'];
const METRIC_LABEL: Record<string, string> = {
  OKLAB: 'OKLab', CHROMA_FIRST: 'Chr+', LUMA_FIRST: 'Lum+', HUE_ONLY: 'Hue', RGB: 'RGB',
};

const DITHER_CYCLE: DitherAlgo[] = ['NONE', 'FS', 'ATKINSON', 'BAYER'];
const DITHER_LABEL: Record<string, string> = {
  NONE: 'None', FS: 'FS', ATKINSON: 'Atk', BAYER: 'Bayer',
};

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
  initialComp?:           CompositionState;
  gridCols?:              number;
  gridRows?:              number;
  sourceBitmap?:          ImageBitmap | null;
  /** Called whenever the user changes a quantize param so App can use it for reimport. */
  onImportParamsChange?:  (p: import('../quantize.js').QuantizeParams) => void;
}

// ─── Editor ───────────────────────────────────────────────────────────────────

export function Editor({
  initialComp, gridCols: propCols, gridRows: propRows, sourceBitmap,
  onImportParamsChange,
}: EditorProps) {
  const canvasElRef = useRef<HTMLCanvasElement>(null);
  const canvasRef   = useRef<MapCanvas | null>(null);
  const historyRef  = useRef(new EditHistory());

  // ── Composition ────────────────────────────────────────────────────────────
  const [comp, setComp] = useState<CompositionState>(() => initialComp ?? makeEmptyComp());
  const compRef = useRef(comp);
  const syncComp = useCallback((next: CompositionState) => {
    compRef.current = next;
    setComp(next);
    canvasRef.current?.setComposition(next);
  }, []);

  useEffect(() => {
    if (initialComp) syncComp(initialComp);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialComp]);

  useEffect(() => {
    if (propCols === undefined || propRows === undefined) return;
    const c = compRef.current;
    if (c.gridCols === propCols && c.gridRows === propRows) return;
    const resized = resizeComposition(c, propCols, propRows);
    syncComp(resized);
    setSelMask(null);
    if (canvasRef.current) {
      canvasRef.current.resize();
      canvasRef.current.fitToView(resized);
    }
    historyRef.current.clear();
    setUndoState({ canUndo: false, canRedo: false });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [propCols, propRows]);

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
  const [reqAlgo,    setReqAlgo]    = useState<DitherAlgo>('NONE');
  const [reqMetric,  setReqMetric]  = useState<typeof MatchMetric[keyof typeof MatchMetric]>(DEFAULT_REQ_PARAMS.metric);
  const [reqFsStr,   setReqFsStr]   = useState(DEFAULT_REQ_PARAMS.fsStrength);
  const [reqAtkStr,  setReqAtkStr]  = useState(DEFAULT_REQ_PARAMS.atkStrength);
  const [reqBayer,   setReqBayer]   = useState(DEFAULT_REQ_PARAMS.bayerScale);
  const [reqChroma,  setReqChroma]  = useState(DEFAULT_REQ_PARAMS.chromaBoost);
  const [reqTilePal, setReqTilePal] = useState(DEFAULT_REQ_PARAMS.tilePalette);
  const [reqRunning, setReqRunning] = useState(false);
  const [reqStatus,  setReqStatus]  = useState<string | null>(null);

  const reqAlgoRef    = useRef(reqAlgo);
  const reqMetricRef  = useRef(reqMetric);
  const reqFsStrRef   = useRef(reqFsStr);
  const reqAtkStrRef  = useRef(reqAtkStr);
  const reqBayerRef   = useRef(reqBayer);
  const reqChromaRef  = useRef(reqChroma);
  const reqTilePalRef = useRef(reqTilePal);
  useEffect(() => { reqAlgoRef.current    = reqAlgo;    }, [reqAlgo]);
  useEffect(() => { reqMetricRef.current  = reqMetric;  }, [reqMetric]);
  useEffect(() => { reqFsStrRef.current   = reqFsStr;   }, [reqFsStr]);
  useEffect(() => { reqAtkStrRef.current  = reqAtkStr;  }, [reqAtkStr]);
  useEffect(() => { reqBayerRef.current   = reqBayer;   }, [reqBayer]);
  useEffect(() => { reqChromaRef.current  = reqChroma;  }, [reqChroma]);
  useEffect(() => { reqTilePalRef.current = reqTilePal; }, [reqTilePal]);

  const sourceBitmapRef = useRef<ImageBitmap | null>(sourceBitmap ?? null);
  useEffect(() => { sourceBitmapRef.current = sourceBitmap ?? null; }, [sourceBitmap]);

  // ── Notify App of current import params so reimport respects user settings ──
  useEffect(() => {
    onImportParamsChange?.({
      legalOnly:     !compRef.current.allShades,
      targetColors:  0,
      dither:        reqAlgo,
      metric:        reqMetric,
      chromaBoost:   reqChroma,
      diffuseAmount: reqAlgo === 'ATKINSON' ? reqAtkStr : reqFsStr,
      bayerScale:    reqBayer,
    });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reqAlgo, reqMetric, reqChroma, reqFsStr, reqAtkStr, reqBayer]);

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

  // ── Reduction strategy ──────────────────────────────────────────────────────
  const [reductionStrategy, _setReductionStrategy] = useState<Strategy>('RAREST');
  const reductionStrategyRef = useRef<Strategy>('RAREST');
  function setReductionStrategy(s: Strategy) { reductionStrategyRef.current = s; _setReductionStrategy(s); }

  // ── Heatmap ─────────────────────────────────────────────────────────────────
  const [heatmapOn, setHeatmapOn] = useState(false);
  const heatmapOnRef = useRef(false);

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
    const c = compRef.current;
    historyRef.current.snapshot(c.frames);
    let totalRemoved = 0;
    for (const tf of c.frames) {
      const frame = tf[c.activeFrame]; if (!frame) continue;
      const d = countDistinct(frame); if (d <= 1) continue;
      const [removed] = reduceColorsInPlace(frame.slice() as Uint8Array, d - 1, OKLAB, reductionStrategyRef.current);
      // reduceColorsInPlace mutates in-place, so use the original
      reduceColorsInPlace(frame, d - 1, OKLAB, reductionStrategyRef.current);
      totalRemoved += removed;
    }
    syncComp({ ...c });
    setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });
    setStatusMsg(totalRemoved > 0
      ? `Reduced ${totalRemoved} color${totalRemoved>1?'s':''} (${reductionStrategyRef.current})`
      : 'Already at minimum (1 color or less)');
  }

  // ── Filter ──────────────────────────────────────────────────────────────────
  async function applyEditorFilter(): Promise<void> {
    if (reqRunning) return;
    const c = compRef.current;
    setStatusMsg(`Applying ${filterTypeRef.current}…`);
    try {
      // Build ImageData from current frame
      const source   = mapBytesToImageData(c.frames, c.activeFrame, c.gridCols, c.gridRows);
      const filtered = applyFilter(source, { type: filterTypeRef.current, strength: filterStrengthRef.current });

      // Re-quantize with NONE dither + current palette/metric settings
      const params: RequantizeParams = {
        ...DEFAULT_REQ_PARAMS,
        dither:          'NONE',
        metric:          reqMetricRef.current,
        legalOnly:       !c.allShades,
        tilePalette:     reqTilePalRef.current,
        chromaBoost:     reqChromaRef.current,
        useCustomDither: false,
        ditherMask:      null,
      };
      const newTiles = requantizeGrid(filtered, c, selMaskRef.current, params);
      historyRef.current.snapshot(c.frames);
      const newFrames = c.frames.map((tf, ti) =>
        tf.map((f, fi) => fi === c.activeFrame ? newTiles[ti] : f),
      );
      syncComp({ ...c, frames: newFrames });
      setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });
      setStatusMsg(`Filter: ${filterTypeRef.current} applied`);
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

  // ── Requantize ──────────────────────────────────────────────────────────────
  const doRequantize = useCallback(async () => {
    if (reqRunning) return;
    // Clear any existing preview
    if (previewCompRef.current) { setPreviewComp(null); }
    setReqRunning(true);
    setReqStatus('⏳ Computing…');

    const c = compRef.current;
    const { gridCols, gridRows, frames, activeFrame, allShades } = c;

    try {
      let source: ImageData;
      if (sourceBitmapRef.current) {
        source = await scaleImage(sourceBitmapRef.current, gridCols * MAP_SIZE, gridRows * MAP_SIZE);
      } else {
        source = mapBytesToImageData(frames, activeFrame, gridCols, gridRows);
      }

      const params: RequantizeParams = {
        legalOnly:       !allShades,
        dither:          reqAlgoRef.current,
        metric:          reqMetricRef.current,
        fsStrength:      reqFsStrRef.current,
        atkStrength:     reqAtkStrRef.current,
        bayerScale:      reqBayerRef.current,
        chromaBoost:     reqChromaRef.current,
        tilePalette:     reqTilePalRef.current,
        useCustomDither: false,
        ditherMask:      null,
      };

      const newTiles  = requantizeGrid(source, c, selMaskRef.current, params);
      const newFrames = frames.map((tileFrames, ti) =>
        tileFrames.map((f, fi) => fi === activeFrame ? newTiles[ti] : f),
      );

      // Show as preview — user must press Enter/Y to commit.
      const preview: CompositionState = { ...c, frames: newFrames };
      setPreviewComp(preview);
      setReqStatus(sourceBitmapRef.current
        ? '🔍 Preview — Enter/Y: commit  ·  Esc: cancel'
        : '🔍 Preview (from pixels) — Enter/Y: commit  ·  Esc: cancel');
    } catch (err) {
      setReqStatus(`Error: ${err}`);
      setTimeout(() => setReqStatus(null), 3500);
    } finally {
      setReqRunning(false);
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
  }, [reqAlgo, reqMetric, reqChroma, reqFsStr, reqAtkStr, reqBayer, reqTilePal]);

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
      const tag = (document.activeElement as HTMLElement)?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;

      // Update modifier refs (used by tools via ToolContext).
      ctrlHeldRef.current  = e.ctrlKey || e.metaKey;
      shiftHeldRef.current = e.shiftKey;

      // When Ctrl is pressed while wand is active, switch to orange (subtract) preview.
      if ((e.key === 'Control' || e.key === 'Meta') && activeToolIdRef.current === 'wand' && canvasRef.current) {
        magicWand.invalidateHoverCache();
        magicWand.updateHover(canvasRef.current.brushX, canvasRef.current.brushY, getCtx(), true);
        return;
      }

      const ctrl  = e.ctrlKey || e.metaKey;
      const shift = e.shiftKey;

      // ── Ctrl combos ──────────────────────────────────────────────────────
      if (ctrl && e.key === 'z') {
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
      if (ctrl && (e.key === 'y' || (shift && e.key === 'z'))) {
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

        // ─ Frame delay (,/.) — only when multi-frame ──────────────────────
        case ',':
          if (maxFrames > 1) { e.preventDefault(); adjustFrameDelay(shift ? -100 : -10); }
          break;
        case '.':
          if (maxFrames > 1) { e.preventDefault(); adjustFrameDelay(shift ? 100 : 10); }
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
    { id:'lasso',  label:'⬡ Lasso',  key:'L', hint:'dbl-click: close · Ctrl: subtract' },
    { id:'wand',   label:'✦ Wand',   key:'W', hint:'click: add · Ctrl: subtract · scroll: tol' },
  ] as const;

  const activeTool   = TOOL_MAP.get(activeToolId) ?? brushTool;
  const maxFrames    = Math.max(...comp.frames.map(t => t.length), 1);
  const frameDelay   = comp.frameDelays[0]?.[comp.activeFrame] ?? 100;
  // Compute distinct color count (for budget badge) — just for current frame
  const distinctCount = (() => {
    const seen = new Uint8Array(256);
    for (const tf of comp.frames) {
      const f = tf[comp.activeFrame]; if (!f) continue;
      for (let i = 0; i < f.length; i++) if (f[i]) seen[f[i]] = 1;
    }
    return Array.from(seen).slice(1).filter(Boolean).length;
  })();

  const mergeQueueSnapshot = new Set(mergeQueueRef.current); // for rendering (mergeQueueVer drives re-render)
  void mergeQueueVer; // suppress unused warning

  // ─── Render ───────────────────────────────────────────────────────────────────
  return (
    <div style={ROOT_STYLE}>
      {/* Left sidebar */}
      <div style={LEFT_PANEL}>

        {/* Tool list */}
        {TOOL_DEFS.map(({ id, label, key, hint }) => (
          <button key={id} onClick={() => doSwitchTool(id)} title={`${label} (${key}) — ${hint}`}
            style={id === activeToolId ? TOOL_BTN_ON : TOOL_BTN_OFF}>
            <span style={{ fontSize:10, color: id === activeToolId ? '#8cf' : '#555' }}>{key} — {label}</span>
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
            <span style={{ fontSize:10, color:'#555' }}>click: add · right-click: subtract</span>
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
        <div style={CHIP_ROW}>
          {DITHER_CYCLE.map(a => (
            <ChipBtn key={a} active={reqAlgo === a} onClick={() => setReqAlgo(a)}>
              {DITHER_LABEL[a]}
            </ChipBtn>
          ))}
        </div>

        {reqAlgo === 'FS' && (
          <Param label={`FS Error: ${reqFsStr.toFixed(1)}`}>
            <input type="range" min={0.1} max={1} step={0.1} value={reqFsStr}
              onInput={(e) => setReqFsStr(+(e.target as HTMLInputElement).value)}
              style={{ width:'100%' }} />
          </Param>
        )}
        {reqAlgo === 'ATKINSON' && (
          <Param label={`Atk Error: ${reqAtkStr.toFixed(1)}`}>
            <input type="range" min={0.1} max={1} step={0.1} value={reqAtkStr}
              onInput={(e) => setReqAtkStr(+(e.target as HTMLInputElement).value)}
              style={{ width:'100%' }} />
          </Param>
        )}
        {reqAlgo === 'BAYER' && (
          <Param label={`Bayer Scale: ${reqBayer.toFixed(2)}`}>
            <input type="range" min={0.02} max={0.20} step={0.02} value={reqBayer}
              onInput={(e) => setReqBayer(+(e.target as HTMLInputElement).value)}
              style={{ width:'100%' }} />
          </Param>
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

        <label style={TOGGLE_ROW}>
          <input type="checkbox" checked={reqTilePal}
            onChange={(e) => setReqTilePal((e.target as HTMLInputElement).checked)} />
          <span style={{ fontSize:11 }}>Tile palette</span>
        </label>

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

        {reqStatus && (
          <div style={{ fontSize:10, color: reqStatus.startsWith('Error') ? '#f77' : reqStatus.startsWith('🔍') ? '#5cf' : '#7f7', marginTop:2 }}>
            {reqStatus}
          </div>
        )}
        {!sourceBitmap && (
          <div style={{ fontSize:10, color:'#555', marginTop:2, fontStyle:'italic' }}>
            No source — from current pixels
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
        <button style={{ ...SEL_BTN, marginTop:2 }} onClick={() => void applyEditorFilter()}>
          Apply (P)
        </button>
        <div style={{ fontSize:10, color:'#555', marginTop:1 }}>Shift+P: cycle type</div>

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
        <button style={{ ...SEL_BTN, marginTop:2 }} onClick={applyReduction}>
          Reduce 1 color (K)
        </button>
        <div style={{ fontSize:10, color:'#555', marginTop:1 }}>Shift+K: cycle strategy · {distinctCount} distinct</div>

        <div style={DIVIDER} />

        {/* ── Merge section ───────────────────────────────────────────────── */}
        <div style={SECTION_LABEL}>Color Merge</div>
        <div style={{ fontSize:10, color:'#666', lineHeight:1.3 }}>
          Ctrl+click canvas or palette to queue; C commits → active color
        </div>
        {mergeQueueSnapshot.size > 0 && (
          <>
            <div style={{ fontSize:10, color:'#f93', marginTop:2 }}>
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
              <span style={{ fontSize:10 }}>All frames (V to toggle)</span>
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
          <span style={{ fontSize:11 }}>Heatmap (H)</span>
        </label>
        <div style={{ fontSize:10, color:'#555' }}>red=rarest · blue=common</div>

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
            <div style={{ fontSize:10, color:'#555', marginTop:1 }}>
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

      {/* Palette */}
      <PalettePanel
        comp={comp}
        activeColor={activeColor}
        selMask={selMask}
        mergeQueue={mergeQueueSnapshot}
        distinctCount={distinctCount}
        onColorPick={setActiveColor}
        onCtrlClick={toggleMergeColor}
      />

      {/* Status bar */}
      <div style={{ gridColumn:'1 / -1' }}>
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
    </div>
  );
}

// ─── Small helper components ──────────────────────────────────────────────────

function Param({ label, children }: { label: string; children: ComponentChildren }) {
  return (
    <div style={{ display:'flex', flexDirection:'column', gap:3, marginTop:3 }}>
      <span style={{ fontSize:11, color:'#888', lineHeight:1.2 }}>{label}</span>
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
      fontSize:      11,
      padding:      '3px 0',
      textAlign:    'center',
    }}>
      {children}
    </button>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const ROOT_STYLE: h.JSX.CSSProperties = {
  display:             'grid',
  gridTemplateColumns: '152px 1fr 168px',
  gridTemplateRows:    '1fr auto',
  width:               '100%',
  height:              '100%',
  overflow:            'hidden',
  background:          '#1a1a1a',
  color:               '#ddd',
  fontFamily:          'system-ui, sans-serif',
  fontSize:             14,
};

const LEFT_PANEL: h.JSX.CSSProperties = {
  gridColumn:    1,
  gridRow:       1,
  background:    '#1e1e1e',
  borderRight:   '1px solid #333',
  padding:        7,
  display:       'flex',
  flexDirection: 'column',
  gap:            3,
  overflowY:     'auto',
};

const CANVAS_AREA: h.JSX.CSSProperties = {
  gridColumn: 2,
  gridRow:    1,
  overflow:   'hidden',
  position:   'relative',
};

const BTN_BASE: h.JSX.CSSProperties = {
  background:    'transparent',
  border:        '1px solid transparent',
  borderRadius:   3,
  color:         '#ccc',
  cursor:        'pointer',
  padding:       '5px 6px',
  textAlign:     'left',
  fontSize:       13,
  lineHeight:     1.3,
};
const TOOL_BTN_OFF: h.JSX.CSSProperties = { ...BTN_BASE };
const TOOL_BTN_ON:  h.JSX.CSSProperties = { ...BTN_BASE, background:'#1b3556', borderColor:'#4a9eff', color:'#fff' };

const SHAPE_BASE: h.JSX.CSSProperties = {
  flex: 1, background:'transparent', border:'1px solid #444', borderRadius:3, color:'#ccc', cursor:'pointer', fontSize:15,
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
  fontSize:       12,
  textAlign:     'left',
};

const DIVIDER: h.JSX.CSSProperties = {
  borderTop:  '1px solid #333',
  margin:     '5px 0',
  flexShrink:  0,
};

const SECTION_LABEL: h.JSX.CSSProperties = {
  fontSize:    12,
  color:       '#5af',
  fontWeight:  'bold',
  marginBottom: 2,
};

const MICRO_LABEL: h.JSX.CSSProperties = {
  fontSize: 11,
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
  fontSize:     13,
  padding:     '4px 12px',
  borderRadius: 4,
  pointerEvents: 'none',
  whiteSpace:  'nowrap',
};
