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
import { EyedropperTool }  from './tools/Eyedropper.js';
import { RectSelectTool }  from './tools/Select.js';
import { LassoTool }       from './tools/Lasso.js';
import { MagicWandTool }   from './tools/MagicWand.js';
import { DitherBrushTool } from './tools/DitherBrush.js';
import type { Tool, ToolContext } from './tools/Tool.js';
import { dilateSelMask, erodeSelMask, MAP_SIZE } from './tools/Tool.js';

// ─── Tool registry ────────────────────────────────────────────────────────────

const brushTool   = new BrushTool();
const fillTool    = new FillTool();
const eyedropper  = new EyedropperTool();
const rectSelect  = new RectSelectTool();
const lasso       = new LassoTool();
const magicWand   = new MagicWandTool();
const ditherBrush = new DitherBrushTool();

const ALL_TOOLS: Tool[] = [brushTool, fillTool, eyedropper, rectSelect, lasso, magicWand, ditherBrush];
const TOOL_MAP = new Map(ALL_TOOLS.map(t => [t.id, t]));

// ─── Default empty composition ────────────────────────────────────────────────

function makeEmptyComp(cols = 1, rows = 1): CompositionState {
  const ps        = emptyPayloadState(cols, rows);
  const n         = cols * rows;
  const pixelData = Array.from({ length: n }, () => [new Uint8Array(128 * 128)]) as Uint8Array[][];
  return compositionFromState(ps, pixelData);
}

/**
 * Resize a composition to new grid dimensions.
 * Existing tiles are preserved in place; new tiles are blank.
 * Shrinking drops tiles that fall outside the new bounds.
 */
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

// Pre-built OKLab lookup — computed once.
const OKLAB = buildOklabLookup();

// ─── Editor props ─────────────────────────────────────────────────────────────

export interface EditorProps {
  initialComp?:  CompositionState;
  /** When changed by the parent toolbar, the composition is resized immediately. */
  gridCols?:     number;
  gridRows?:     number;
  /** Source bitmap kept in App for re-quantize; null when no image has been imported. */
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
  function setFillTol(v: number) { fillTolRef.current = v; _setFillTol(v); }

  const [wandTol, _setWandTol] = useState(0.15);
  const wandTolRef = useRef(0.15);
  function setWandTol(v: number) { wandTolRef.current = v; _setWandTol(v); }

  const [selMask, _setSelMask] = useState<Uint8Array | null>(null);
  const selMaskRef = useRef<Uint8Array | null>(null);
  function setSelMask(m: Uint8Array | null) {
    selMaskRef.current = m;
    _setSelMask(m);
    if (canvasRef.current) { canvasRef.current.selMask = m; canvasRef.current.markDirty(); }
  }

  // ── Requantize params ───────────────────────────────────────────────────────
  const [reqAlgo,       setReqAlgo]       = useState<typeof DitherAlgo[keyof typeof DitherAlgo]>(DEFAULT_REQ_PARAMS.dither);
  const [reqMetric,     setReqMetric]     = useState<typeof MatchMetric[keyof typeof MatchMetric]>(DEFAULT_REQ_PARAMS.metric);
  const [reqFsStr,      setReqFsStr]      = useState(DEFAULT_REQ_PARAMS.fsStrength);
  const [reqAtkStr,     setReqAtkStr]     = useState(DEFAULT_REQ_PARAMS.atkStrength);
  const [reqBayer,      setReqBayer]      = useState(DEFAULT_REQ_PARAMS.bayerScale);
  const [reqChroma,     setReqChroma]     = useState(DEFAULT_REQ_PARAMS.chromaBoost);
  const [reqTilePal,    setReqTilePal]    = useState(DEFAULT_REQ_PARAMS.tilePalette);
  const [reqCustomMask, setReqCustomMask] = useState(DEFAULT_REQ_PARAMS.useCustomDither);
  const [reqRunning,    setReqRunning]    = useState(false);
  const [reqStatus,     setReqStatus]     = useState<string | null>(null);

  // Refs for the R key handler (reads current values without stale closure).
  const reqAlgoRef       = useRef(reqAlgo);
  const reqMetricRef     = useRef(reqMetric);
  const reqFsStrRef      = useRef(reqFsStr);
  const reqAtkStrRef     = useRef(reqAtkStr);
  const reqBayerRef      = useRef(reqBayer);
  const reqChromaRef     = useRef(reqChroma);
  const reqTilePalRef    = useRef(reqTilePal);
  const reqCustomMaskRef = useRef(reqCustomMask);
  // Keep refs in sync.
  useEffect(() => { reqAlgoRef.current       = reqAlgo;       }, [reqAlgo]);
  useEffect(() => { reqMetricRef.current     = reqMetric;     }, [reqMetric]);
  useEffect(() => { reqFsStrRef.current      = reqFsStr;      }, [reqFsStr]);
  useEffect(() => { reqAtkStrRef.current     = reqAtkStr;     }, [reqAtkStr]);
  useEffect(() => { reqBayerRef.current      = reqBayer;      }, [reqBayer]);
  useEffect(() => { reqChromaRef.current     = reqChroma;     }, [reqChroma]);
  useEffect(() => { reqTilePalRef.current    = reqTilePal;    }, [reqTilePal]);
  useEffect(() => { reqCustomMaskRef.current = reqCustomMask; }, [reqCustomMask]);

  // Source bitmap ref — updated from prop each render.
  const sourceBitmapRef = useRef<ImageBitmap | null>(sourceBitmap ?? null);
  useEffect(() => { sourceBitmapRef.current = sourceBitmap ?? null; }, [sourceBitmap]);

  // ── Status bar state ────────────────────────────────────────────────────────
  const [cursorGx,   setCursorGx]   = useState(-1);
  const [cursorGy,   setCursorGy]   = useState(-1);
  const [hoverColor, setHoverColor] = useState(0);
  const [scale,      setScale]      = useState(4);
  const [undoState,  setUndoState]  = useState({ canUndo: false, canRedo: false });

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
    setColor: (b: number) => {
      setActiveColor(b);
      if (activeToolIdRef.current === 'eyedropper') {
        const returnId = eyedropper.returnToToolId ?? 'brush';
        eyedropper.returnToToolId = null;
        doSwitchTool(returnId);
      }
    },
    setSelMask,
    getSelMask: () => selMaskRef.current,
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
      // Obtain source ImageData.
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
        useCustomDither: reqCustomMaskRef.current && !!canvasRef.current?.ditherMask,
        ditherMask:      reqCustomMaskRef.current ? (canvasRef.current?.ditherMask ?? null) : null,
      };

      const newTiles = requantizeGrid(source, c, selMaskRef.current, params);

      // Snapshot before writing.
      historyRef.current.snapshot(frames);
      setUndoState({ canUndo: historyRef.current.canUndo, canRedo: historyRef.current.canRedo });

      // Write new tile data back into frames (replaces activeFrame for each tile).
      const newFrames: Uint8Array[][] = frames.map((tileFrames, ti) => {
        const updated = tileFrames.map((f, fi) => fi === activeFrame ? newTiles[ti] : f);
        return updated;
      });

      syncComp({ ...c, frames: newFrames });
      setReqStatus(sourceBitmapRef.current ? 'Done ✓' : 'Done ✓ (from current pixels)');
    } catch (err) {
      setReqStatus(`Error: ${err}`);
    } finally {
      setReqRunning(false);
      setTimeout(() => setReqStatus(null), 3000);
    }
  }, [reqRunning, syncComp]);

  // ── Canvas init ─────────────────────────────────────────────────────────────
  useEffect(() => {
    const el = canvasElRef.current!;

    const canvas = new MapCanvas(el, {
      onPixelEvent: (gx, gy, button, buttons, _e) => {
        const ctx  = getCtx();
        const tool = TOOL_MAP.get(activeToolIdRef.current) ?? brushTool;
        tool.onPointerEvent?.(gx, gy, button, buttons, ctx);
        setComp({ ...compRef.current });
        setUndoState({
          canUndo: historyRef.current.canUndo,
          canRedo: historyRef.current.canRedo,
        });
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
        if (activeToolIdRef.current === 'wand') {
          magicWand.updateHover(gx, gy, getCtx());
        }
        setScale(canvas.scale);
      },
      onWheel: (e) => {
        const delta = e.deltaY < 0 ? 1 : -1;
        TOOL_MAP.get(activeToolIdRef.current)?.onWheel?.(delta, getCtx());
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

      const ctrl = e.ctrlKey || e.metaKey;

      if (ctrl && e.key === 'z') {
        e.preventDefault();
        const h   = historyRef.current;
        const res = h.undo(compRef.current.frames);
        if (res) {
          syncComp({ ...compRef.current, frames: res });
          setUndoState({ canUndo: h.canUndo, canRedo: h.canRedo });
        }
        return;
      }
      if (ctrl && (e.key === 'y' || (e.shiftKey && e.key === 'z'))) {
        e.preventDefault();
        const h   = historyRef.current;
        const res = h.redo(compRef.current.frames);
        if (res) {
          syncComp({ ...compRef.current, frames: res });
          setUndoState({ canUndo: h.canUndo, canRedo: h.canRedo });
        }
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
      if (ctrl) return;

      const c  = compRef.current;
      const gw = c.gridCols * MAP_SIZE, gh = c.gridRows * MAP_SIZE;

      switch (e.key) {
        case '=': case '+':
          if (selMaskRef.current) setSelMask(dilateSelMask(selMaskRef.current, gw, gh));
          break;
        case '-':
          if (selMaskRef.current) setSelMask(erodeSelMask(selMaskRef.current, gw, gh));
          break;
        case '[': setBrushRadius(r => Math.max(0, r - 1)); break;
        case ']': setBrushRadius(r => Math.min(64, r + 1)); break;
        case 'b': case 'B': doSwitchTool('brush');       break;
        case 'f': case 'F': doSwitchTool('fill');        break;
        case 'e': case 'E':
          eyedropper.returnToToolId = activeToolIdRef.current;
          doSwitchTool('eyedropper');
          break;
        case 's': case 'S': doSwitchTool('select');      break;
        case 'l': case 'L': doSwitchTool('lasso');       break;
        case 'w': case 'W': doSwitchTool('wand');        break;
        case 't': case 'T': doSwitchTool('ditherbrush'); break;
        case 'r': case 'R':
          e.preventDefault();
          void doRequantize();
          break;
        case 'm': case 'M':
          if (canvasRef.current) {
            canvasRef.current.showDitherMask = !canvasRef.current.showDitherMask;
            canvasRef.current.markDirty();
          }
          break;
        case ',':
          syncComp({ ...compRef.current, activeFrame: Math.max(0, compRef.current.activeFrame - 1) });
          break;
        case '.': {
          const maxF = Math.max(...compRef.current.frames.map(t => t.length), 1);
          syncComp({ ...compRef.current, activeFrame: Math.min(maxF - 1, compRef.current.activeFrame + 1) });
          break;
        }
      }
    };

    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [doRequantize]);

  // ─── Dither brush strength display ───────────────────────────────────────────
  const [ditherStrDisplay, setDitherStrDisplay] = useState(ditherBrush.strength);
  useEffect(() => {
    const interval = setInterval(() => {
      setDitherStrDisplay(ditherBrush.strength);
    }, 200);
    return () => clearInterval(interval);
  }, []);

  // ─── Tool guide list ─────────────────────────────────────────────────────────
  const TOOL_DEFS = [
    { id:'brush',       label:'Brush',    key:'B' },
    { id:'fill',        label:'Fill',     key:'F' },
    { id:'eyedropper',  label:'Pick',     key:'E' },
    { id:'select',      label:'Select',   key:'S' },
    { id:'lasso',       label:'Lasso',    key:'L' },
    { id:'wand',        label:'Wand',     key:'W' },
    { id:'ditherbrush', label:'Dither',   key:'T' },
  ] as const;

  const activeTool = TOOL_MAP.get(activeToolId) ?? brushTool;
  const hasDitherMask = !!canvasRef.current?.ditherMask;

  // ─── Render ───────────────────────────────────────────────────────────────────
  return (
    <div style={ROOT_STYLE}>
      {/* Left sidebar */}
      <div style={LEFT_PANEL}>

        {/* Tool list */}
        {TOOL_DEFS.map(({ id, label, key }) => (
          <button key={id} onClick={() => doSwitchTool(id)} title={`${label} (${key})`}
            style={id === activeToolId ? TOOL_BTN_ON : TOOL_BTN_OFF}>
            <span style={{ fontSize:10, color: id === activeToolId ? '#8cf' : '#555' }}>{key}</span>
            <span>{label}</span>
          </button>
        ))}

        <div style={DIVIDER} />

        {/* Tool-specific params */}
        {(activeToolId === 'brush' || activeToolId === 'ditherbrush') && (
          <>
            <Param label={`Radius: ${brushRadius}`}>
              <input type="range" min={0} max={32} value={brushRadius}
                onInput={(e) => setBrushRadius(+(e.target as HTMLInputElement).value)}
                style={{ width:'100%' }} />
              <div style={{ display:'flex', gap:4, marginTop:4 }}>
                {(['circle','square'] as const).map(s => (
                  <button key={s} onClick={() => setBrushShape(s)}
                    style={s === brushShape ? SHAPE_ON : SHAPE_OFF}>
                    {s === 'circle' ? '◯' : '□'}
                  </button>
                ))}
              </div>
            </Param>
            {activeToolId === 'ditherbrush' && (
              <Param label={`Strength: ${ditherStrDisplay.toFixed(1)}`}>
                <input type="range" min={0.1} max={1} step={0.1}
                  value={ditherStrDisplay}
                  onInput={(e) => {
                    const v = +(e.target as HTMLInputElement).value;
                    ditherBrush.strength = v;
                    setDitherStrDisplay(v);
                  }}
                  style={{ width:'100%' }} />
                <span style={{ fontSize:10, color:'#666' }}>
                  left-drag: paint · right-drag: erase · M: toggle heatmap
                </span>
              </Param>
            )}
          </>
        )}

        {activeToolId === 'fill' && (
          <Param label={`Tolerance: ${fillTol.toFixed(2)}`}>
            <input type="range" min={0} max={1} step={0.01} value={fillTol}
              onInput={(e) => setFillTol(+(e.target as HTMLInputElement).value)}
              style={{ width:'100%' }} />
          </Param>
        )}

        {activeToolId === 'wand' && (
          <Param label={`Tolerance: ${wandTol.toFixed(2)}`}>
            <input type="range" min={0} max={1} step={0.01} value={wandTol}
              onInput={(e) => setWandTol(+(e.target as HTMLInputElement).value)}
              style={{ width:'100%' }} />
          </Param>
        )}

        {/* Selection ops */}
        {selMask && (
          <>
            <div style={DIVIDER} />
            <button style={SEL_BTN} onClick={() => setSelMask(null)}>✕ Desel</button>
            <button style={SEL_BTN} onClick={() => {
              const { gridCols, gridRows } = compRef.current;
              setSelMask(dilateSelMask(selMask, gridCols * MAP_SIZE, gridRows * MAP_SIZE));
            }}>+ Grow</button>
            <button style={SEL_BTN} onClick={() => {
              const { gridCols, gridRows } = compRef.current;
              setSelMask(erodeSelMask(selMask, gridCols * MAP_SIZE, gridRows * MAP_SIZE));
            }}>− Shrink</button>
          </>
        )}

        <div style={DIVIDER} />

        {/* ── Requantize section ──────────────────────────────────────────── */}
        <div style={SECTION_LABEL}>Requantize (R)</div>

        {/* Dither algorithm */}
        <div style={MICRO_LABEL}>Dither</div>
        <div style={CHIP_ROW}>
          {(['NONE','FS','ATKINSON','BAYER'] as const).map(a => (
            <ChipBtn key={a} active={reqAlgo === a} onClick={() => setReqAlgo(a)}>
              {a === 'FS' ? 'FS' : a === 'ATKINSON' ? 'Atk' : a === 'BAYER' ? 'Bayer' : 'None'}
            </ChipBtn>
          ))}
        </div>

        {/* Per-algo slider */}
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

        {/* Match metric */}
        <div style={MICRO_LABEL}>Metric</div>
        <div style={CHIP_ROW}>
          {(['OKLAB','CHROMA_FIRST','LUMA_FIRST','HUE_ONLY','RGB'] as const).map(m => (
            <ChipBtn key={m} active={reqMetric === m} onClick={() => setReqMetric(m)}>
              {m === 'OKLAB' ? 'OKLab' : m === 'CHROMA_FIRST' ? 'Chr+' : m === 'LUMA_FIRST' ? 'Lum+' : m === 'HUE_ONLY' ? 'Hue' : 'RGB'}
            </ChipBtn>
          ))}
        </div>

        {/* Chroma boost */}
        <Param label={`Chroma: ${reqChroma.toFixed(2)}×`}>
          <input type="range" min={0.25} max={4} step={0.05} value={reqChroma}
            onInput={(e) => setReqChroma(+(e.target as HTMLInputElement).value)}
            style={{ width:'100%' }} />
        </Param>

        {/* Tile palette + custom mask */}
        <label style={TOGGLE_ROW}>
          <input type="checkbox" checked={reqTilePal}
            onChange={(e) => setReqTilePal((e.target as HTMLInputElement).checked)} />
          <span style={{ fontSize:11 }}>Tile palette</span>
        </label>

        {reqAlgo === 'FS' && (
          <label style={{ ...TOGGLE_ROW, opacity: hasDitherMask ? 1 : 0.4 }}>
            <input type="checkbox" checked={reqCustomMask}
              disabled={!hasDitherMask}
              onChange={(e) => setReqCustomMask((e.target as HTMLInputElement).checked)} />
            <span style={{ fontSize:11 }}>Custom mask (T)</span>
          </label>
        )}

        {/* Apply button */}
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
            No source image — will requantize from current pixels
          </div>
        )}
      </div>

      {/* Canvas area */}
      <div style={CANVAS_AREA}>
        <canvas ref={canvasElRef} style={{ display:'block', width:'100%', height:'100%' }} />
      </div>

      {/* Right — palette */}
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

// ─── Small helper components ──────────────────────────────────────────────────

function Param({ label, children }: { label: string; children: ComponentChildren }) {
  return (
    <div style={{ display:'flex', flexDirection:'column', gap:3, marginTop:2 }}>
      <span style={{ fontSize:11, color:'#888' }}>{label}</span>
      {children}
    </div>
  );
}

function ChipBtn({ active, onClick, children }: { active: boolean; onClick: () => void; children: ComponentChildren }) {
  return (
    <button onClick={onClick} style={{
      flex:          1,
      background:    active ? '#1b3556' : 'transparent',
      border:        `1px solid ${active ? '#4a9eff' : '#444'}`,
      borderRadius:   3,
      color:         active ? '#8cf' : '#888',
      cursor:        'pointer',
      fontSize:       10,
      padding:       '2px 0',
      textAlign:     'center',
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
  display:       'flex',
  flexDirection: 'column',
  gap:            1,
  fontSize:       12,
  lineHeight:     1.2,
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
  fontSize:       11,
  textAlign:     'left',
};

const DIVIDER: h.JSX.CSSProperties = {
  borderTop: '1px solid #333',
  margin:    '4px 0',
};

const SECTION_LABEL: h.JSX.CSSProperties = {
  fontSize:    11,
  color:       '#5af',
  fontWeight:  'bold',
  marginTop:    2,
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
