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
} from '../quantize.js';
import type { CompositionState } from '../payload-state.js';
import { emptyPayloadState, compositionFromState } from '../payload-state.js';

import { BrushTool }       from './tools/Brush.js';
import { FillTool }        from './tools/Fill.js';
import { RectSelectTool }  from './tools/Select.js';
import { LassoTool }       from './tools/Lasso.js';
import { MagicWandTool }   from './tools/MagicWand.js';
import type { Tool, ToolContext } from './tools/Tool.js';
import { dilateSelMask, erodeSelMask, writePixel, MAP_SIZE } from './tools/Tool.js';

// ─── Tool registry ────────────────────────────────────────────────────────────

const brushTool   = new BrushTool();
const fillTool    = new FillTool();
const rectSelect  = new RectSelectTool();
const lasso       = new LassoTool();
const magicWand = new MagicWandTool();

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

// ─── Metric / algo cycle helpers ─────────────────────────────────────────────

const METRIC_CYCLE: Array<typeof MatchMetric[keyof typeof MatchMetric]> =
  ['OKLAB', 'CHROMA_FIRST', 'LUMA_FIRST', 'HUE_ONLY', 'RGB'];
const METRIC_LABEL: Record<string, string> = {
  OKLAB: 'OKLab', CHROMA_FIRST: 'Chr+', LUMA_FIRST: 'Lum+', HUE_ONLY: 'Hue', RGB: 'RGB',
};

const DITHER_CYCLE: DitherAlgo[] = ['NONE', 'FS', 'ATKINSON', 'BAYER'];
const DITHER_LABEL: Record<string, string> = {
  NONE: 'None', FS: 'FS', ATKINSON: 'Atk', BAYER: 'Bayer',
};

// ─── Editor props ─────────────────────────────────────────────────────────────

export interface EditorProps {
  initialComp?:  CompositionState;
  gridCols?:     number;
  gridRows?:     number;
  sourceBitmap?: ImageBitmap | null;
}

// ─── Editor ───────────────────────────────────────────────────────────────────

export function Editor({ initialComp, gridCols: propCols, gridRows: propRows, sourceBitmap }: EditorProps) {
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
  function setActiveColor(c: number) { activeColorRef.current = c; _setActiveColor(c); }

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

  const [selMask, _setSelMask] = useState<Uint8Array | null>(null);
  const selMaskRef = useRef<Uint8Array | null>(null);
  function setSelMask(m: Uint8Array | null) {
    selMaskRef.current = m;
    _setSelMask(m);
    if (canvasRef.current) { canvasRef.current.selMask = m; canvasRef.current.markDirty(); }
  }

  // ── Requantize params ───────────────────────────────────────────────────────
  const [reqAlgo,       setReqAlgo]       = useState<DitherAlgo>(DEFAULT_REQ_PARAMS.dither);
  const [reqMetric,     setReqMetric]     = useState<typeof MatchMetric[keyof typeof MatchMetric]>(DEFAULT_REQ_PARAMS.metric);
  const [reqFsStr,      setReqFsStr]      = useState(DEFAULT_REQ_PARAMS.fsStrength);
  const [reqAtkStr,     setReqAtkStr]     = useState(DEFAULT_REQ_PARAMS.atkStrength);
  const [reqBayer,      setReqBayer]      = useState(DEFAULT_REQ_PARAMS.bayerScale);
  const [reqChroma,     setReqChroma]     = useState(DEFAULT_REQ_PARAMS.chromaBoost);
  const [reqTilePal,    setReqTilePal]    = useState(DEFAULT_REQ_PARAMS.tilePalette);
  // Custom dither mask removed (dither brush tool removed)
  const [reqRunning,    setReqRunning]    = useState(false);
  const [reqStatus,     setReqStatus]     = useState<string | null>(null);

  const reqAlgoRef       = useRef(reqAlgo);
  const reqMetricRef     = useRef(reqMetric);
  const reqFsStrRef      = useRef(reqFsStr);
  const reqAtkStrRef     = useRef(reqAtkStr);
  const reqBayerRef      = useRef(reqBayer);
  const reqChromaRef     = useRef(reqChroma);
  const reqTilePalRef    = useRef(reqTilePal);
  useEffect(() => { reqAlgoRef.current       = reqAlgo;       }, [reqAlgo]);
  useEffect(() => { reqMetricRef.current     = reqMetric;     }, [reqMetric]);
  useEffect(() => { reqFsStrRef.current      = reqFsStr;      }, [reqFsStr]);
  useEffect(() => { reqAtkStrRef.current     = reqAtkStr;     }, [reqAtkStr]);
  useEffect(() => { reqBayerRef.current      = reqBayer;      }, [reqBayer]);
  useEffect(() => { reqChromaRef.current     = reqChroma;     }, [reqChroma]);
  useEffect(() => { reqTilePalRef.current    = reqTilePal;    }, [reqTilePal]);

  const sourceBitmapRef = useRef<ImageBitmap | null>(sourceBitmap ?? null);
  useEffect(() => { sourceBitmapRef.current = sourceBitmap ?? null; }, [sourceBitmap]);

  // ── Status / display state ──────────────────────────────────────────────────
  const [cursorGx,   setCursorGx]   = useState(-1);
  const [cursorGy,   setCursorGy]   = useState(-1);
  const [hoverColor, setHoverColor] = useState(0);
  const [scale,      setScale]      = useState(4);
  const [undoState,  setUndoState]  = useState({ canUndo: false, canRedo: false });
  const [statusMsg,  setStatusMsg]  = useState<string | null>(null);

  const [wandTol, _setWandTol] = useState(0.15);
  const wandTolRef = useRef(0.15);
  function setWandTol(fn: number | ((t: number) => number)) {
    const next = typeof fn === 'function' ? fn(wandTolRef.current) : fn;
    wandTolRef.current = next;
    _setWandTol(next);
  }

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

  // ── Requantize ──────────────────────────────────────────────────────────────
  const doRequantize = useCallback(async () => {
    if (reqRunning) return;
    setReqRunning(true);
    setReqStatus('Computing…');

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

      const newTiles = requantizeGrid(source, c, selMaskRef.current, params);

      historyRef.current.snapshot(frames);
      setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });

      const newFrames: Uint8Array[][] = frames.map((tileFrames, ti) =>
        tileFrames.map((f, fi) => fi === activeFrame ? newTiles[ti] : f),
      );

      syncComp({ ...c, frames: newFrames });
      setReqStatus(sourceBitmapRef.current ? 'Done ✓' : 'Done ✓ (from pixels)');
    } catch (err) {
      setReqStatus(`Error: ${err}`);
    } finally {
      setReqRunning(false);
      setTimeout(() => setReqStatus(null), 3000);
    }
  }, [reqRunning, syncComp]);

  // Keep requantize in a ref so the keyboard effect ([] deps) can call it stably.
  const doRequantizeRef = useRef(doRequantize);
  useEffect(() => { doRequantizeRef.current = doRequantize; }, [doRequantize]);

  // ── Canvas init ─────────────────────────────────────────────────────────────
  useEffect(() => {
    const el = canvasElRef.current!;

    const canvas = new MapCanvas(el, {
      onPixelEvent: (gx, gy, button, buttons, _e) => {
        const ctx  = getCtx();
        const tool = TOOL_MAP.get(activeToolIdRef.current) ?? brushTool;
        tool.onPointerEvent?.(gx, gy, button, buttons, ctx);
        setComp({ ...compRef.current });
        setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });
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
        if (activeToolIdRef.current === 'wand') {
          magicWand.updateHover(gx, gy, getCtx());
        }
      },
      onWheel: (e) => {
        // Shift+scroll: adjust the active tool's primary parameter.
        const delta = e.deltaY < 0 ? 1 : -1;
        const toolId = activeToolIdRef.current;
        switch (toolId) {
          case 'brush': {
            setBrushRadius(r => Math.max(0, Math.min(64, r + delta)));
            break;
          }
          case 'wand': {
            const step  = e.ctrlKey ? 0.005 : 0.025;
            const sc    = e.ctrlKey ? 200   : 40;
            setWandTol(t => Math.round(Math.max(0, Math.min(0.5, t + delta * step)) * sc) / sc);
            break;
          }
          case 'fill': {
            const step  = e.ctrlKey ? 0.005 : 0.025;
            const scale = e.ctrlKey ? 200   : 40;
            setFillTol(t => Math.round(Math.max(0, Math.min(0.5, t + delta * step)) * scale) / scale);
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
    const onKey = (e: KeyboardEvent) => {
      const tag = (document.activeElement as HTMLElement)?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;

      const ctrl  = e.ctrlKey || e.metaKey;
      const shift = e.shiftKey;

      // ── Ctrl combos ──────────────────────────────────────────────────────
      if (ctrl && e.key === 'z') {
        e.preventDefault();
        const h = historyRef.current;
        const res = h.undo(compRef.current.frames);
        if (res) { syncComp({ ...compRef.current, frames: res }); setUndoState({ canUndo: h.canUndo, canRedo: h.canRedo }); }
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
      // Let Ctrl+[ and Ctrl+] (frame nav) fall through to the switch below.
      // Block all other Ctrl combos.
      if (ctrl && e.key !== '[' && e.key !== ']') return;

      // ── Non-Ctrl / frame nav ─────────────────────────────────────────────
      const c  = compRef.current;
      const gw = c.gridCols * MAP_SIZE;
      const gh = c.gridRows * MAP_SIZE;

      switch (e.key) {

        // ─ Escape ────────────────────────────────────────────────────────
        case 'Escape':
          e.preventDefault();
          if (activeToolIdRef.current === 'lasso' && lasso.hasPath()) {
            const ctx = getCtx();
            lasso.deactivate(ctx);
            // Re-activates cleanly — lasso has no activate() but deactivate() clears the path
          } else if (selMaskRef.current) {
            setSelMask(null);
          }
          break;

        // ─ Tool keys ─────────────────────────────────────────────────────
        case 'b': case 'B': doSwitchTool('brush');       break;
        case 'f': case 'F': doSwitchTool('fill');        break;
        case 's': case 'S': doSwitchTool('select');      break;
        case 'l': case 'L': doSwitchTool('lasso');       break;
        case 'w': case 'W': doSwitchTool('wand');       break;

        // ─ Brush shape / radius ───────────────────────────────────────────
        case 'x': case 'X':
          setBrushShape(brushShapeRef.current === 'circle' ? 'square' : 'circle');
          break;
        case '[':
          if (!ctrl) setBrushRadius(r => Math.max(0, r - 1));
          else {
            // Ctrl+[ = prev frame
            e.preventDefault();
            const maxF = Math.max(...c.frames.map(t => t.length), 1);
            syncComp({ ...c, activeFrame: (c.activeFrame - 1 + maxF) % maxF });
          }
          break;
        case ']':
          if (!ctrl) setBrushRadius(r => Math.min(64, r + 1));
          else {
            // Ctrl+] = next frame
            e.preventDefault();
            const maxF = Math.max(...c.frames.map(t => t.length), 1);
            syncComp({ ...c, activeFrame: (c.activeFrame + 1) % maxF });
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
            const scale = ctrl ? 200   : 40;
            setFillTol(t => Math.round(Math.min(0.5, t + step) * scale) / scale);
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
            const scale = ctrl ? 200   : 40;
            setFillTol(t => Math.round(Math.max(0, t - step) * scale) / scale);
          } else if (activeToolIdRef.current === 'wand') {
            const step = ctrl ? 0.005 : 0.025;
            const sc   = ctrl ? 200   : 40;
            setWandTol(t => Math.round(Math.max(0, t - step) * sc) / sc);
          }
          break;
        }

        // ─ Delete: clear selection to transparent ─────────────────────────
        case 'Delete': case 'Backspace': {
          const sel = selMaskRef.current;
          if (!sel) break;
          e.preventDefault();
          historyRef.current.snapshot(c.frames);
          let cleared = 0;
          for (let gy2 = 0; gy2 < gh; gy2++) {
            for (let gx2 = 0; gx2 < gw; gx2++) {
              if (sel[gy2 * gw + gx2]) {
                writePixel(c, gx2, gy2, 0, null);
                cleared++;
              }
            }
          }
          if (cleared > 0) {
            syncComp({ ...c });
            setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });
          }
          break;
        }

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

        // ─ Toggle dither mask overlay (M) ─────────────────────────────────
        case 'm': case 'M':
          if (canvasRef.current) {
            canvasRef.current.showDitherMask = !canvasRef.current.showDitherMask;
            canvasRef.current.markDirty();
          }
          break;

        // ─ Frame navigation (,/.) ─────────────────────────────────────────
        case ',': {
          syncComp({ ...c, activeFrame: Math.max(0, c.activeFrame - 1) });
          break;
        }
        case '.': {
          const maxF = Math.max(...c.frames.map(t => t.length), 1);
          syncComp({ ...c, activeFrame: Math.min(maxF - 1, c.activeFrame + 1) });
          break;
        }
      }
    };

    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []); // stable — all mutable state accessed through refs, doRequantize via doRequantizeRef

  // Auto-clear status message
  useEffect(() => {
    if (!statusMsg) return;
    const t = setTimeout(() => setStatusMsg(null), 2000);
    return () => clearTimeout(t);
  }, [statusMsg]);

  // ─── Tool guide list ─────────────────────────────────────────────────────────
  const TOOL_DEFS = [
    { id:'brush',       label:'Brush',    key:'B', hint:'right-click: pick' },
    { id:'fill',        label:'Fill',     key:'F', hint:'right-click: pick' },
    { id:'select',      label:'Select',   key:'S', hint:'drag: marquee' },
    { id:'lasso',       label:'Lasso',    key:'L', hint:'dbl-click: close' },
    { id:'wand',        label:'Wand',     key:'W', hint:'click: add, right: subtract' },
  ] as const;

  const activeTool = TOOL_MAP.get(activeToolId) ?? brushTool;

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

        {/* Brush / dither brush params */}
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

        {activeToolId === 'wand' && (
          <Param label={`Tolerance: ${wandTol.toFixed(3)}  (= - or Shift+scroll)`}>
            <input type="range" min={0} max={0.5} step={0.005} value={wandTol}
              onInput={(e) => setWandTol(+(e.target as HTMLInputElement).value)}
              style={{ width:'100%' }} />
            <span style={{ fontSize:10, color:'#555' }}>click: add · right-click: subtract</span>
          </Param>
        )}

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
            <button style={SEL_BTN} onClick={() => {
              let mask = selMask;
              for (let i = 0; i < 1; i++) mask = dilateSelMask(mask, gw, gh);
              setSelMask(mask);
            }}>+ Grow (=)</button>
            <button style={SEL_BTN} onClick={() => {
              let mask = selMask;
              for (let i = 0; i < 1; i++) mask = erodeSelMask(mask, gw, gh);
              setSelMask(mask);
            }}>− Shrink (-)</button>
            <button style={SEL_BTN} onClick={() => {
              // Invert
              const size = gw * gh;
              const inv  = new Uint8Array(size);
              let any    = false;
              for (let i = 0; i < size; i++) { inv[i] = selMask[i] ? 0 : 1; if (inv[i]) any = true; }
              setSelMask(any ? inv : null);
            }}>⇄ Invert (Ctrl+I)</button>
          </>
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

        <button
          onClick={() => void doRequantize()}
          disabled={reqRunning}
          style={{ ...SEL_BTN, marginTop:4, textAlign:'center', color: reqRunning ? '#555' : '#8cf' }}
        >
          {reqRunning ? 'Running…' : 'Apply (R)'}
        </button>

        {reqStatus && (
          <div style={{ fontSize:10, color: reqStatus.startsWith('Error') ? '#f77' : '#7f7', marginTop:2 }}>
            {reqStatus}
          </div>
        )}
        {!sourceBitmap && (
          <div style={{ fontSize:10, color:'#555', marginTop:2, fontStyle:'italic' }}>
            No source — from current pixels
          </div>
        )}
      </div>

      {/* Canvas */}
      <div style={CANVAS_AREA}>
        <canvas ref={canvasElRef} style={{ display:'block', width:'100%', height:'100%' }} />
        {/* Status overlay for key-triggered messages */}
        {statusMsg && (
          <div style={STATUS_OVERLAY}>{statusMsg}</div>
        )}
      </div>

      {/* Palette */}
      <PalettePanel
        comp={comp}
        activeColor={activeColor}
        selMask={selMask}
        onColorPick={setActiveColor}
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
        />
      </div>
    </div>
  );
}

// Helpers used in render (need access to comp dims for selection buttons)
// Defined outside so they're stable — use current comp from closures that get fresh values
function _dilate(mask: Uint8Array, gw: number, gh: number) {
  return dilateSelMask(mask, gw, gh);
}
function _erode(mask: Uint8Array, gw: number, gh: number) {
  return erodeSelMask(mask, gw, gh);
}
// Note: gw/gh are computed inline in the render from compRef values via
// the selMask buttons' onClick closures which close over comp state.
const gw = 0; const gh = 0; // silence TS — overridden inline
void _dilate; void _erode; void gw; void gh;

// ─── Small helper components ──────────────────────────────────────────────────

function Param({ label, children }: { label: string; children: ComponentChildren }) {
  return (
    <div style={{ display:'flex', flexDirection:'column', gap:3, marginTop:2 }}>
      <span style={{ fontSize:10, color:'#888', lineHeight:1.2 }}>{label}</span>
      {children}
    </div>
  );
}

function ChipBtn({ active, onClick, children }: { active: boolean; onClick: () => void; children: ComponentChildren }) {
  return (
    <button onClick={onClick} style={{
      flex:        1,
      background:  active ? '#1b3556' : 'transparent',
      border:      `1px solid ${active ? '#4a9eff' : '#444'}`,
      borderRadius: 3,
      color:       active ? '#8cf' : '#888',
      cursor:      'pointer',
      fontSize:     10,
      padding:     '2px 0',
      textAlign:   'center',
    }}>
      {children}
    </button>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const ROOT_STYLE: h.JSX.CSSProperties = {
  display:             'grid',
  gridTemplateColumns: '128px 1fr 160px',
  gridTemplateRows:    '1fr auto',
  width:               '100%',
  height:              '100%',
  overflow:            'hidden',
  background:          '#1a1a1a',
  color:               '#ddd',
  fontFamily:          'system-ui, sans-serif',
  fontSize:             13,
};

const LEFT_PANEL: h.JSX.CSSProperties = {
  gridColumn:    1,
  gridRow:       1,
  background:    '#1e1e1e',
  borderRight:   '1px solid #333',
  padding:        6,
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
  padding:       '4px 6px',
  textAlign:     'left',
  fontSize:       12,
  lineHeight:     1.3,
};
const TOOL_BTN_OFF: h.JSX.CSSProperties = { ...BTN_BASE };
const TOOL_BTN_ON:  h.JSX.CSSProperties = { ...BTN_BASE, background:'#1b3556', borderColor:'#4a9eff', color:'#fff' };

const SHAPE_BASE: h.JSX.CSSProperties = {
  flex: 1, background:'transparent', border:'1px solid #444', borderRadius:3, color:'#ccc', cursor:'pointer', fontSize:14,
};
const SHAPE_OFF: h.JSX.CSSProperties = { ...SHAPE_BASE };
const SHAPE_ON:  h.JSX.CSSProperties = { ...SHAPE_BASE, background:'#1b3556', borderColor:'#4a9eff' };

const SEL_BTN: h.JSX.CSSProperties = {
  background:   '#252525',
  border:       '1px solid #444',
  borderRadius:  3,
  color:         '#bbb',
  cursor:        'pointer',
  padding:       '3px 6px',
  fontSize:       10,
  textAlign:     'left',
};

const DIVIDER: h.JSX.CSSProperties = {
  borderTop:  '1px solid #333',
  margin:     '4px 0',
  flexShrink:  0,
};

const SECTION_LABEL: h.JSX.CSSProperties = {
  fontSize:    11,
  color:       '#5af',
  fontWeight:  'bold',
  marginBottom: 2,
};

const MICRO_LABEL: h.JSX.CSSProperties = {
  fontSize: 10,
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
  fontSize:     12,
  padding:     '3px 10px',
  borderRadius: 4,
  pointerEvents: 'none',
  whiteSpace:  'nowrap',
};
