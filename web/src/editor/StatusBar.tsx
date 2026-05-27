/**
 * StatusBar — bottom strip showing coordinates, colour info, tool hint, frame.
 */

import { h } from 'preact';
import { MC_PALETTE } from '../palette.js';
import type { CompositionState } from '../payload-state.js';

interface StatusBarProps {
  comp:           CompositionState;
  cursorGx:       number;
  cursorGy:       number;
  hoverColor:     number;  // map byte under cursor
  activeColor:    number;
  activeTool:     string;
  scale:          number;
  canUndo:        boolean;
  canRedo:        boolean;
  maxFrames:      number;
  frameDelay:     number;
  distinctCount:  number;
  inPreview:      boolean;
  mergeQueueSize: number;
}

export function StatusBar({
  comp, cursorGx, cursorGy, hoverColor, activeColor, activeTool, scale,
  canUndo, canRedo, maxFrames, frameDelay, distinctCount, inPreview, mergeQueueSize,
}: StatusBarProps) {
  const inBounds = cursorGx >= 0 && cursorGy >= 0
    && cursorGx < comp.gridCols * 128
    && cursorGy < comp.gridRows * 128;

  const tileCol = inBounds ? (cursorGx / 128) | 0 : -1;
  const tileRow = inBounds ? (cursorGy / 128) | 0 : -1;
  const lx      = inBounds ? cursorGx % 128 : -1;
  const ly      = inBounds ? cursorGy % 128 : -1;

  const hoverRgb = MC_PALETTE[hoverColor] ?? 0;
  const hr = (hoverRgb >> 16) & 0xff;
  const hg = (hoverRgb >>  8) & 0xff;
  const hb =  hoverRgb        & 0xff;

  const activeRgb = MC_PALETTE[activeColor] ?? 0;
  const ar = (activeRgb >> 16) & 0xff;
  const ag = (activeRgb >>  8) & 0xff;
  const ab =  activeRgb        & 0xff;

  return (
    <div style={BAR_STYLE}>
      {/* Preview indicator */}
      {inPreview && (
        <>
          <span style={{ ...SEG, color:'#5cf', fontWeight:'bold' }}>PREVIEW</span>
          <Sep />
        </>
      )}

      {/* Tool */}
      <span style={SEG}>{activeTool}</span>
      <Sep />

      {/* Cursor position */}
      {inBounds ? (
        <span style={SEG}>
          ({cursorGx}, {cursorGy}) — tile ({tileCol},{tileRow}) local ({lx},{ly})
        </span>
      ) : (
        <span style={SEG}>—</span>
      )}
      <Sep />

      {/* Hover colour */}
      {inBounds && hoverColor > 0 && (
        <>
          <ColorChip r={hr} g={hg} b={hb} label={`byte ${hoverColor}`} />
          <Sep />
        </>
      )}

      {/* Active colour */}
      <ColorChip r={ar} g={ag} b={ab} label={`fg ${activeColor}`} />
      <Sep />

      {/* Distinct color count */}
      <span style={{ ...SEG, color: distinctCount > 180 ? '#f77' : distinctCount > 120 ? '#fa5' : '#aaa' }}
        title="Distinct map colors in current frame">
        {distinctCount} colors
      </span>
      <Sep />

      {/* Merge queue */}
      {mergeQueueSize > 0 && (
        <>
          <span style={{ ...SEG, color:'#f93' }} title="Colors in merge queue — C to commit">
            {mergeQueueSize} queued
          </span>
          <Sep />
        </>
      )}

      {/* Zoom */}
      <span style={SEG}>{scale}×</span>
      <Sep />

      {/* Frame */}
      <span style={SEG}>
        Frame {comp.activeFrame + 1}/{maxFrames}
        {maxFrames > 1 && <span style={{ color:'#666' }}> · {frameDelay}ms</span>}
      </span>
      <Sep />

      {/* Undo hints */}
      <span style={{ ...SEG, opacity: canUndo ? 1 : 0.3 }}>⌘Z</span>
      <span style={{ ...SEG, opacity: canRedo ? 1 : 0.3 }}>⌘Y</span>
    </div>
  );
}

function Sep() {
  return <span style={{ color:'#444', margin:'0 4px' }}>│</span>;
}

function ColorChip({ r, g, b, label }: { r:number; g:number; b:number; label:string }) {
  return (
    <span style={{ display:'inline-flex', alignItems:'center', gap:4 }}>
      <span style={{
        display:       'inline-block',
        width:          12,
        height:         12,
        background:    `rgb(${r},${g},${b})`,
        border:        '1px solid #555',
        verticalAlign: 'middle',
      }} />
      <span style={SEG}>{label}</span>
    </span>
  );
}

const BAR_STYLE: h.JSX.CSSProperties = {
  height:     24,
  background: '#141414',
  borderTop:  '1px solid #333',
  display:    'flex',
  alignItems: 'center',
  padding:    '0 8px',
  fontSize:   11,
  color:      '#aaa',
  userSelect: 'none',
  flexShrink:  0,
  fontFamily: 'monospace',
  overflow:   'hidden',
};

const SEG: h.JSX.CSSProperties = {
  whiteSpace: 'nowrap',
};
