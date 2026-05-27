/**
 * PalettePanel — right-side panel showing swatches + frequency bars.
 *
 * Tabs:
 *   ALL  — every legal map colour (shade 0–2, colorId 1–61)
 *   SEL  — colours present in the current selection (or all tiles)
 *
 * Frequency bars reflect pixel counts across the entire composition.
 * Click a swatch to set it as the active colour.
 */

import { h } from 'preact';
import { useState, useMemo } from 'preact/hooks';
import { IS_LEGAL, IS_VALID, MC_PALETTE } from '../palette.js';
import type { CompositionState } from '../payload-state.js';

// ─── Props ────────────────────────────────────────────────────────────────────

interface PalettePanelProps {
  comp:        CompositionState;
  activeColor: number;
  selMask:     Uint8Array | null;
  onColorPick: (mapByte: number) => void;
}

// ─── Component ────────────────────────────────────────────────────────────────

export function PalettePanel({ comp, activeColor, selMask, onColorPick }: PalettePanelProps) {
  const [tab, setTab] = useState<'all' | 'sel'>('all');

  // Count pixel frequency across the whole composition.
  const freq = useMemo(() => {
    const f = new Int32Array(256);
    for (const tileFames of comp.frames) {
      const frame = tileFames[comp.activeFrame];
      if (!frame) continue;
      for (let i = 0; i < frame.length; i++) f[frame[i]]++;
    }
    return f;
  }, [comp.frames, comp.activeFrame]);

  const maxFreq = useMemo(() => Math.max(1, ...Array.from(freq)), [freq]);

  // Which colours are present in the active selection or overall.
  const inSel = useMemo(() => {
    if (!selMask) return null;
    const present = new Uint8Array(256);
    const gridW = comp.gridCols * 128;
    // Walk all tiles + frames
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

  // Build the list of displayed colours.
  const colors: number[] = useMemo(() => {
    const validArr = comp.allShades ? IS_VALID : IS_LEGAL;
    if (tab === 'all') {
      return Array.from({ length: 256 }, (_, i) => i).filter(i => validArr[i]);
    } else {
      const basis = inSel ?? (() => {
        const p = new Uint8Array(256);
        for (const tf of comp.frames) {
          const f = tf[comp.activeFrame]; if (!f) continue;
          for (let i = 0; i < f.length; i++) if (f[i]) p[f[i]] = 1;
        }
        return p;
      })();
      return Array.from({ length: 256 }, (_, i) => i).filter(i => validArr[i] && basis[i]);
    }
  }, [tab, comp.allShades, comp.frames, comp.activeFrame, inSel]);

  return (
    <div style={PANEL_STYLE}>
      {/* Tab bar */}
      <div style={{ display:'flex', borderBottom:'1px solid #333', marginBottom:4 }}>
        {(['all','sel'] as const).map(t => (
          <button key={t} onClick={() => setTab(t)} style={tab === t ? TAB_ACTIVE : TAB_INACTIVE}>
            {t.toUpperCase()}
          </button>
        ))}
      </div>

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
          // Checkerboard via CSS gradient — matches the canvas transparent fill
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
          fontSize:    10, color: 'rgba(255,255,255,0.7)',
          textShadow: '0 0 3px #000',
        }}>
          eraser
        </span>
        {freq[0] > 0 && (
          <div style={{
            position:'absolute', bottom:0, left:0,
            width: `${Math.round(freq[0] / maxFreq * 100)}%`, height: 3,
            background: 'rgba(255,255,255,0.5)',
          }} />
        )}
      </div>

      {/* Swatch grid */}
      <div style={SWATCH_GRID}>
        {colors.map(c => {
          const rgb = MC_PALETTE[c];
          const r = (rgb >> 16) & 0xff;
          const g = (rgb >>  8) & 0xff;
          const b = rgb & 0xff;
          const bgColor = `rgb(${r},${g},${b})`;
          const barW = Math.round(freq[c] / maxFreq * 100);
          const isActive = c === activeColor;

          return (
            <div
              key={c}
              onClick={() => onColorPick(c)}
              title={`Map byte ${c} | rgb(${r},${g},${b})`}
              style={{
                position:    'relative',
                width:       28,
                height:      28,
                background:  bgColor,
                cursor:      'pointer',
                outline:     isActive ? '2px solid #fff' : '1px solid #222',
                outlineOffset: isActive ? -2 : -1,
                boxSizing:   'border-box',
                overflow:    'hidden',
              }}
            >
              {/* Frequency bar (bottom strip) */}
              {barW > 0 && (
                <div style={{
                  position:'absolute', bottom:0, left:0,
                  width: `${barW}%`, height: 3,
                  background: 'rgba(255,255,255,0.5)',
                }} />
              )}
            </div>
          );
        })}
      </div>

      {/* Active colour info */}
      <div style={{ marginTop:8, fontSize:11, color:'#aaa', textAlign:'center' }}>
        {activeColor === 0 ? 'Transparent (byte 0)' : `Byte ${activeColor}`}
        {freq[activeColor] > 0 && ` · ${freq[activeColor].toLocaleString()} px`}
      </div>
    </div>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const PANEL_STYLE: h.JSX.CSSProperties = {
  width:      160,
  minWidth:   160,
  height:     '100%',
  background: '#1e1e1e',
  borderLeft: '1px solid #333',
  padding:    6,
  overflowY:  'auto',
  boxSizing:  'border-box',
  userSelect: 'none',
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
  fontSize:   11,
};
const TAB_ACTIVE:   h.JSX.CSSProperties = { ...TAB_BASE, color: '#fff', borderBottom: '2px solid #5af' };
const TAB_INACTIVE: h.JSX.CSSProperties = { ...TAB_BASE };
