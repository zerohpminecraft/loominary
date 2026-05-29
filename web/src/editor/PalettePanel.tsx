/**
 * PalettePanel — right-side panel showing swatches + frequency bars.
 *
 * Tabs:
 *   ALL   — every legal map colour (shade 0–2, colorId 1–61)
 *   FRAME — colours present in the current frame
 *   SEL   — colours present in the current selection
 *
 * Frequency bars reflect pixel counts across the entire composition.
 * Click a swatch to set it as the active colour.
 * Making a selection automatically switches to the SEL tab.
 * Clearing a selection switches back to FRAME.
 */

import { h } from 'preact';
import { useState, useMemo, useEffect, useRef } from 'preact/hooks';
import { IS_LEGAL, IS_VALID, MC_PALETTE } from '../palette.js';
import { rgbToOklab } from '../oklab.js';
import type { CompositionState } from '../payload-state.js';
import { PALETTE_CHOICES, buildPaletteFlag, type PaletteRestriction, type PaletteOptions } from '../palette-filters.js';

// ─── Sort modes ───────────────────────────────────────────────────────────────

type SortMode = 'byte' | 'hue' | 'lightness' | 'chroma' | 'frequency';

const SORT_MODES: SortMode[] = ['byte', 'hue', 'lightness', 'chroma', 'frequency'];
const SORT_LABELS: Record<SortMode, string> = {
  byte:      '#',
  hue:       'Hue',
  lightness: 'L',
  chroma:    'Chr',
  frequency: 'Freq',
};
const SORT_TIPS: Record<SortMode, string> = {
  byte:      'Natural byte order',
  hue:       'Sort by hue (rainbow)',
  lightness: 'Sort dark → bright',
  chroma:    'Sort grey → vivid',
  frequency: 'Sort by pixel count',
};

// ─── Props ────────────────────────────────────────────────────────────────────

interface PalettePanelProps {
  comp:          CompositionState;
  activeColor:   number;
  selMask:       Uint8Array | null;
  mergeQueue:    ReadonlySet<number>;
  distinctCount: number;
  onColorPick:   (mapByte: number) => void;
  onCtrlClick:   (mapByte: number) => void;
  /** Shift+click/drag — remove pixels of this color from the selection. */
  onShiftClick?:  (mapByte: number) => void;
  /** Hover enter/leave — flash matching pixels in the canvas. */
  onColorHover?:  (mapByte: number | null) => void;
  /** Panel width in px — controlled by the parent's resize handle. */
  panelWidth?:   number;
}

// ─── Component ────────────────────────────────────────────────────────────────

export function PalettePanel({
  comp, activeColor, selMask,
  mergeQueue, distinctCount,
  onColorPick, onCtrlClick, onShiftClick, onColorHover,
  panelWidth = 168,
}: PalettePanelProps) {
  const [tab,               setTab]              = useState<'all' | 'frame' | 'sel'>('frame');
  const [sortMode,          setSortMode]         = useState<SortMode>('byte');
  const [allTabFilter,      setAllTabFilter]     = useState<PaletteRestriction | 'none'>('none');
  const [allTabGreyThresh,  setAllTabGreyThresh] = useState(40);

  // Ctrl+drag state — adds colours to the merge queue as cursor moves over swatches.
  const ctrlDragActive     = useRef(false);
  const ctrlDragMoved      = useRef(false);
  const ctrlDragFirstColor = useRef<number | null>(null);
  const ctrlDragVisited    = useRef<Set<number>>(new Set());

  // Shift+drag state — tracks an ongoing shift-drag across swatches.
  const shiftDragActive  = useRef(false);
  const shiftDragVisited = useRef<Set<number>>(new Set());
  const shiftDragOriginX = useRef(0);
  const shiftDragOriginY = useRef(0);
  // Pointer must move at least this far (px) from the click origin before
  // drag-removal activates on neighbouring swatches.
  const SHIFT_DRAG_PX = 8;

  useEffect(() => {
    const stop = () => {
      shiftDragActive.current  = false;
      shiftDragVisited.current.clear();
      ctrlDragActive.current   = false;
      ctrlDragMoved.current    = false;
      ctrlDragFirstColor.current = null;
      ctrlDragVisited.current.clear();
    };
    window.addEventListener('pointerup', stop);
    return () => window.removeEventListener('pointerup', stop);
  }, []);

  // Auto-switch to SEL when a selection is made; back to FRAME when cleared.
  useEffect(() => {
    if (selMask) {
      setTab('sel');
    } else {
      setTab(t => t === 'sel' ? 'frame' : t);
    }
  }, [selMask]);

  // Pixel frequency — selection-aware when selMask is active.
  // totalCount is the denominator for percentages.
  const { freq, totalCount } = useMemo(() => {
    const f = new Int32Array(256);
    let total = 0;
    if (!selMask) {
      for (const tf of comp.frames) {
        const frame = tf[comp.activeFrame]; if (!frame) continue;
        for (let i = 0; i < frame.length; i++) { f[frame[i]]++; total++; }
      }
    } else {
      const gridW = comp.gridCols * 128;
      for (let tileRow = 0; tileRow < comp.gridRows; tileRow++) {
        for (let tileCol = 0; tileCol < comp.gridCols; tileCol++) {
          const ti    = tileRow * comp.gridCols + tileCol;
          const frame = comp.frames[ti]?.[comp.activeFrame]; if (!frame) continue;
          for (let ly = 0; ly < 128; ly++) {
            for (let lx = 0; lx < 128; lx++) {
              if (!selMask[(tileRow * 128 + ly) * gridW + tileCol * 128 + lx]) continue;
              f[frame[lx + ly * 128]]++; total++;
            }
          }
        }
      }
    }
    return { freq: f, totalCount: total };
  }, [comp.frames, comp.activeFrame, comp.gridCols, comp.gridRows, selMask]);

  const maxFreq = useMemo(() => Math.max(1, ...Array.from(freq)), [freq]);

  // Colours present in the current frame.
  const frameColors = useMemo(() => {
    const p = new Uint8Array(256);
    for (const tf of comp.frames) {
      const f = tf[comp.activeFrame]; if (!f) continue;
      for (let i = 0; i < f.length; i++) if (f[i]) p[f[i]] = 1;
    }
    return p;
  }, [comp.frames, comp.activeFrame]);

  // Colours present in the active selection.
  const inSel = useMemo(() => {
    if (!selMask) return null;
    const present = new Uint8Array(256);
    const gridW = comp.gridCols * 128;
    for (let tileRow = 0; tileRow < comp.gridRows; tileRow++) {
      for (let tileCol = 0; tileCol < comp.gridCols; tileCol++) {
        const ti = tileRow * comp.gridCols + tileCol;
        const frame = comp.frames[ti]?.[comp.activeFrame];
        if (!frame) continue;
        for (let ly = 0; ly < 128; ly++) {
          for (let lx = 0; lx < 128; lx++) {
            const gx = tileCol * 128 + lx;
            const gy = tileRow * 128 + ly;
            if (selMask[gy * gridW + gx]) {
              present[frame[lx + ly * 128]] = 1;
            }
          }
        }
      }
    }
    return present;
  }, [selMask, comp.frames, comp.activeFrame, comp.gridCols, comp.gridRows]);

  // Build the list of displayed colours for the active tab.
  const colors: number[] = useMemo(() => {
    const validArr = comp.allShades ? IS_VALID : IS_LEGAL;
    if (tab === 'all') {
      if (allTabFilter !== 'none') {
        const opts: PaletteOptions = allTabFilter === 'greyscale' ? { greyThreshold: allTabGreyThresh } : {};
        const filterFlag = buildPaletteFlag(allTabFilter, opts);
        return Array.from({ length: 256 }, (_, i) => i).filter(i => filterFlag[i]);
      }
      return Array.from({ length: 256 }, (_, i) => i).filter(i => validArr[i]);
    } else if (tab === 'frame') {
      return Array.from({ length: 256 }, (_, i) => i).filter(i => validArr[i] && frameColors[i]);
    } else {
      const basis = inSel ?? frameColors;
      return Array.from({ length: 256 }, (_, i) => i).filter(i => validArr[i] && basis[i]);
    }
  }, [tab, comp.allShades, frameColors, inSel, allTabFilter, allTabGreyThresh]);

  // Per-color OKLab sort keys (rebuilt only when sort mode changes, not on every frame).
  const sortKeys = useMemo<Float32Array>(() => {
    const k = new Float32Array(256);
    for (let c = 1; c < 256; c++) {
      const rgb = MC_PALETTE[c]; if (!rgb) continue;
      if (sortMode === 'byte') { k[c] = c; continue; }
      const [L, a, b] = rgbToOklab((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
      if      (sortMode === 'hue')       k[c] = Math.atan2(b, a);   // –π to π
      else if (sortMode === 'lightness') k[c] = L;
      else if (sortMode === 'chroma')    k[c] = Math.sqrt(a*a + b*b);
      // 'frequency' key filled separately during render
    }
    return k;
  }, [sortMode]);

  const sortedColors = useMemo(() => {
    if (sortMode === 'frequency') return [...colors].sort((a, b) => (freq[b] || 0) - (freq[a] || 0));
    return [...colors].sort((a, b) => sortKeys[a] - sortKeys[b]);
  }, [colors, sortMode, sortKeys, freq]);

  return (
    <div style={{ ...PANEL_STYLE, width: panelWidth, minWidth: Math.min(panelWidth, 80) }}>
      {/* Tab bar + distinct count badge */}
      {(() => {
        const selCount = inSel ? Array.from(inSel).slice(1).filter(Boolean).length : null;
        return (
          <div style={{ display:'flex', alignItems:'center', borderBottom:'1px solid #333', marginBottom:4 }}>
            {(['all','frame','sel'] as const).map(t => (
              <button key={t} onClick={() => setTab(t)} style={tab === t ? TAB_ACTIVE : TAB_INACTIVE}>
                {t === 'sel' && selCount !== null ? `SEL(${selCount})` : t.toUpperCase()}
              </button>
            ))}
            <span style={{ marginLeft:'auto', fontSize:'0.71em', color:'#666', paddingRight:2 }} title="Distinct colors in composition">
              {distinctCount}c
            </span>
          </div>
        );
      })()}

      {/* Sort mode chips */}
      <div style={{ display:'flex', gap:2, marginBottom:5, flexWrap:'wrap' }}>
        {SORT_MODES.map(m => (
          <button key={m} onClick={() => setSortMode(m)}
            title={SORT_TIPS[m]}
            style={{
              flex:         1,
              background:   m === sortMode ? '#1b3556' : 'transparent',
              border:       `1px solid ${m === sortMode ? '#4a9eff' : '#444'}`,
              borderRadius:  3,
              color:        m === sortMode ? '#8cf' : '#666',
              cursor:       'pointer',
              fontSize:     '0.71em',
              padding:      '2px 0',
              textAlign:    'center',
              whiteSpace:   'nowrap',
            }}>
            {SORT_LABELS[m]}
          </button>
        ))}
      </div>

      {/* ALL-tab palette filter */}
      {tab === 'all' && (
        <select
          value={allTabFilter}
          onChange={e => setAllTabFilter((e.target as HTMLSelectElement).value as PaletteRestriction | 'none')}
          title="Restrict the ALL tab to a specific palette"
          style={{
            width:        '100%',
            background:   '#252525',
            border:       '1px solid #444',
            borderRadius:  3,
            color:         '#ccc',
            fontSize:     '0.82em',
            padding:      '3px 5px',
            marginBottom:  5,
            cursor:       'pointer',
          }}
        >
          <option value="none">— All legal colors —</option>
          {PALETTE_CHOICES.map(c => (
            <option key={c.id} value={c.id}>{c.label}</option>
          ))}
        </select>
      )}

      {/* Greyscale threshold (only shown when ALL tab + greyscale filter active) */}
      {tab === 'all' && allTabFilter === 'greyscale' && (
        <div style={{ marginBottom: 5 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.71em', color: '#888', marginBottom: 2 }}>
            <span>Chroma threshold</span>
            <span>{allTabGreyThresh}</span>
          </div>
          <input type="range" min={5} max={120} step={5} value={allTabGreyThresh}
            onInput={e => setAllTabGreyThresh(+(e.target as HTMLInputElement).value)}
            style={{ width: '100%' }} />
        </div>
      )}

      {/* Eraser / transparent swatch — always pinned at top */}
      <div
        onClick={() => onColorPick(0)}
        title="Transparent / erase (byte 0)"
        style={{
          position:      'relative',
          width:         '100%',
          height:         22,
          marginBottom:   6,
          cursor:        'pointer',
          outline:        activeColor === 0 ? '2px solid #fff' : '1px solid #333',
          outlineOffset:  activeColor === 0 ? -2 : -1,
          boxSizing:     'border-box',
          overflow:      'hidden',
          borderRadius:   2,
          backgroundImage: 'linear-gradient(45deg,#555 25%,transparent 25%),' +
                           'linear-gradient(-45deg,#555 25%,transparent 25%),' +
                           'linear-gradient(45deg,transparent 75%,#555 75%),' +
                           'linear-gradient(-45deg,transparent 75%,#555 75%)',
          backgroundSize:   '8px 8px',
          backgroundPosition: '0 0, 0 4px, 4px -4px, -4px 0px',
          backgroundColor: '#333',
        }}
      >
        <span style={{
          position:   'absolute', inset: 0,
          display:    'flex', alignItems: 'center', justifyContent: 'center',
          fontSize:    '0.71em', color: 'rgba(255,255,255,0.7)',
          textShadow: '0 0 3px #000',
        }}>
          eraser
        </span>
        {freq[0] > 0 && (
          <div style={{
            position:'absolute', bottom:0, left:0,
            width: `${Math.round(freq[0] / maxFreq * 100)}%`, height: 6,
            background: 'rgba(255,255,255,0.85)',
          }} />
        )}
      </div>

      {/* Shift-click hint when a selection is active */}
      {selMask && onShiftClick && (
        <div style={{ fontSize:'0.68em', color:'#555', marginBottom:3, lineHeight:1.3 }}>
          Shift+click/drag: deselect that color
        </div>
      )}

      {/* Swatch grid */}
      <div style={SWATCH_GRID} onMouseLeave={() => onColorHover?.(null)}>
        {sortedColors.map(c => {
          const rgb = MC_PALETTE[c];
          const r = (rgb >> 16) & 0xff;
          const g = (rgb >>  8) & 0xff;
          const b = rgb & 0xff;
          const bgColor = `rgb(${r},${g},${b})`;
          const count    = freq[c];
          const barW     = Math.round(count / maxFreq * 100);
          const isActive  = c === activeColor;
          const inMergeQ  = mergeQueue.has(c);

          const outlineColor  = isActive ? '#fff' : inMergeQ ? '#f93' : '#222';
          const outlineWidth  = (isActive || inMergeQ) ? 2 : 1;
          const outlineOffset = (isActive || inMergeQ) ? -2 : -1;

          // Tooltip: show count + percentage
          const pct       = totalCount > 0 ? (count / totalCount * 100).toFixed(1) : '0.0';
          const countLine = count > 0 ? `\n${count.toLocaleString()} px (${pct}%)${selMask ? ' in selection' : ''}` : '';
          const tooltip   = `Byte ${c} · rgb(${r},${g},${b})${countLine}`
            + (inMergeQ ? '\nIn merge queue' : '')
            + `\nCtrl+click: toggle merge queue`
            + (selMask ? '\nShift+click/drag: deselect this color' : '');

          function fireShiftDesel(mapByte: number) {
            if (!selMask || !onShiftClick) return;
            if (!shiftDragVisited.current.has(mapByte)) {
              shiftDragVisited.current.add(mapByte);
              onShiftClick(mapByte);
            }
          }

          return (
            <div
              key={c}
              onClick={e => {
                if (e.shiftKey) return; // handled by onPointerDown
                if (e.ctrlKey || e.metaKey) {
                  // Only toggle on single ctrl+click (no drag detected)
                  if (!ctrlDragMoved.current) onCtrlClick(c);
                  return;
                }
                onColorPick(c);
              }}
              onPointerDown={e => {
                if (e.shiftKey && selMask && onShiftClick) {
                  e.preventDefault();
                  shiftDragActive.current  = true;
                  shiftDragOriginX.current = e.clientX;
                  shiftDragOriginY.current = e.clientY;
                  shiftDragVisited.current.clear();
                  fireShiftDesel(c);
                } else if (e.ctrlKey || e.metaKey) {
                  // Start ctrl-drag tracking; the actual add/toggle happens in
                  // onMouseEnter (drag) or onClick (single click).
                  ctrlDragActive.current      = true;
                  ctrlDragMoved.current       = false;
                  ctrlDragFirstColor.current  = c;
                  ctrlDragVisited.current.clear();
                  ctrlDragVisited.current.add(c);
                }
              }}
              onMouseEnter={(e) => {
                if (shiftDragActive.current) {
                  // Only drag-deselect additional swatches once the pointer
                  // has moved far enough from the click origin.
                  const dx = e.clientX - shiftDragOriginX.current;
                  const dy = e.clientY - shiftDragOriginY.current;
                  if (dx * dx + dy * dy >= SHIFT_DRAG_PX * SHIFT_DRAG_PX) {
                    fireShiftDesel(c);
                  }
                }
                if (ctrlDragActive.current && !ctrlDragVisited.current.has(c)) {
                  if (!ctrlDragMoved.current) {
                    // First new swatch reached — drag is now confirmed; add the origin swatch too
                    ctrlDragMoved.current = true;
                    const first = ctrlDragFirstColor.current;
                    if (first !== null && !mergeQueue.has(first)) onCtrlClick(first);
                  }
                  ctrlDragVisited.current.add(c);
                  if (!mergeQueue.has(c)) onCtrlClick(c);
                }
                onColorHover?.(c);
              }}
              onMouseLeave={() => onColorHover?.(null)}
              title={tooltip}
              style={{
                position:      'relative',
                width:          28,
                height:         28,
                background:     bgColor,
                cursor:        'pointer',
                outline:       `${outlineWidth}px solid ${outlineColor}`,
                outlineOffset: `${outlineOffset}px`,
                boxSizing:     'border-box',
                overflow:      'hidden',
              }}
            >
              {barW > 0 && (
                <div style={{
                  position:'absolute', bottom:0, left:0,
                  width: `${barW}%`, height: 6,
                  background: 'rgba(255,255,255,0.85)',
                }} />
              )}
              {inMergeQ && (
                <div style={{
                  position:'absolute', top:1, right:1,
                  width:5, height:5, borderRadius:'50%',
                  background:'#f93', border:'1px solid rgba(0,0,0,0.5)',
                }} />
              )}
            </div>
          );
        })}
      </div>

      {/* Active colour info */}
      <div style={{ marginTop:8, fontSize:'0.79em', color:'#aaa', textAlign:'center' }}>
        {activeColor === 0 ? 'Transparent (byte 0)' : `Byte ${activeColor}`}
        {freq[activeColor] > 0 && (() => {
          const cnt = freq[activeColor];
          const pct = totalCount > 0 ? ` (${(cnt / totalCount * 100).toFixed(1)}%)` : '';
          return ` · ${cnt.toLocaleString()} px${pct}`;
        })()}
        {selMask && freq[activeColor] > 0 && (
          <span style={{ color:'#666' }}> in sel</span>
        )}
      </div>
    </div>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const PANEL_STYLE: h.JSX.CSSProperties = {
  height:     '100%',
  background: '#1e1e1e',
  borderLeft: '1px solid #333',
  padding:    6,
  overflowY:  'auto',
  boxSizing:  'border-box',
  userSelect: 'none',
  flexShrink: 0,
};

const SWATCH_GRID: h.JSX.CSSProperties = {
  display:   'flex',
  flexWrap:  'wrap',
  gap:        2,
};

const TAB_BASE: h.JSX.CSSProperties = {
  flex:       1,
  border:     'none',
  background: 'transparent',
  color:      '#aaa',
  cursor:     'pointer',
  padding:    '3px 0',
  fontSize:   '0.79em',
};
const TAB_ACTIVE:   h.JSX.CSSProperties = { ...TAB_BASE, color: '#fff', borderBottom: '2px solid #5af' };
const TAB_INACTIVE: h.JSX.CSSProperties = { ...TAB_BASE };
