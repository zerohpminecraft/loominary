/**
 * App — top-level shell.
 *
 * Shows a thin import/export toolbar at the top and the Editor below.
 * The editor occupies all remaining height.
 */

import { h } from 'preact';
import { useState, useCallback } from 'preact/hooks';
import { Editor } from './editor/Editor.js';
import type { CompositionState } from './payload-state.js';
import {
  importStateFile,
  downloadState,
  compositionFromState,
  emptyPayloadState,
  saveState,
} from './payload-state.js';

// ─── Image import helpers ─────────────────────────────────────────────────────

async function loadImageFromFile(file: File): Promise<ImageBitmap> {
  return createImageBitmap(file);
}

async function scaleImageTo(bmp: ImageBitmap, w: number, h: number): Promise<ImageData> {
  const osc  = new OffscreenCanvas(w, h);
  const octx = osc.getContext('2d')!;
  octx.imageSmoothingEnabled = true;
  octx.imageSmoothingQuality = 'high';
  octx.drawImage(bmp, 0, 0, w, h);
  return octx.getImageData(0, 0, w, h);
}

// ─── App ──────────────────────────────────────────────────────────────────────

export function App() {
  const [comp,      setComp]      = useState<CompositionState | null>(null);
  const [importing, setImporting] = useState(false);
  const [gridCols,  setGridCols]  = useState(1);
  const [gridRows,  setGridRows]  = useState(1);

  // ─── Import state JSON ────────────────────────────────────────────────────
  const handleStateImport = useCallback(async (e: Event) => {
    const file = (e.target as HTMLInputElement).files?.[0];
    if (!file) return;
    try {
      const ps      = await importStateFile(file);
      const n       = ps.columns * ps.rows;
      // Create blank pixel data (tiles without carpetCompressedB64 start blank).
      const pixelData: Uint8Array[][] = Array.from({ length: n }, () => [new Uint8Array(128 * 128)]);
      setGridCols(ps.columns);
      setGridRows(ps.rows);
      setComp(compositionFromState(ps, pixelData));
    } catch (err) {
      alert(`Failed to load state: ${err}`);
    }
  }, []);

  // ─── Import image ─────────────────────────────────────────────────────────
  const handleImageImport = useCallback(async (e: Event) => {
    const file = (e.target as HTMLInputElement).files?.[0];
    if (!file) return;
    setImporting(true);
    try {
      const { convertTwoPassGrid } = await import('./quantize.js');
      const bmp   = await loadImageFromFile(file);
      const imgW  = gridCols * 128;
      const imgH  = gridRows * 128;
      const img   = await scaleImageTo(bmp, imgW, imgH);
      const tiles = convertTwoPassGrid(img, gridCols, gridRows, {
        legalOnly:    true,
        targetColors: 0,
        dither:       'FS',
      });
      const pixelData: Uint8Array[][] = tiles.map(t => [t]);
      const ps = emptyPayloadState(gridCols, gridRows);
      ps.sourceFilename = file.name;
      setComp(compositionFromState(ps, pixelData));
    } catch (err) {
      alert(`Failed to import image: ${err}`);
    } finally {
      setImporting(false);
    }
  }, [gridCols, gridRows]);

  // ─── New blank composition ────────────────────────────────────────────────
  const handleNew = useCallback(() => {
    const n = gridCols * gridRows;
    const ps = emptyPayloadState(gridCols, gridRows);
    const pixelData: Uint8Array[][] = Array.from({ length: n }, () => [new Uint8Array(128 * 128)]);
    setComp(compositionFromState(ps, pixelData));
  }, [gridCols, gridRows]);

  // ─── Export state JSON ────────────────────────────────────────────────────
  const handleExport = useCallback(() => {
    if (!comp) return;
    const ps = emptyPayloadState(comp.gridCols, comp.gridRows);
    ps.title          = comp.title ?? null;
    ps.authorOverride = comp.author ?? null;
    ps.allShades      = comp.allShades;
    ps.codecMode      = comp.codecMode;
    downloadState(ps);
    saveState(ps);
  }, [comp]);

  return (
    <div style={{ display:'flex', flexDirection:'column', width:'100%', height:'100%', background:'#111', color:'#ccc', fontFamily:'system-ui,sans-serif' }}>
      {/* Toolbar */}
      <div style={{
        display:'flex', alignItems:'center', gap:8,
        padding:'4px 8px', background:'#1a1a1a', borderBottom:'1px solid #333',
        fontSize:13, flexShrink:0,
      }}>
        <span style={{ fontWeight:'bold', color:'#5af', marginRight:8 }}>🧵 Loominary</span>

        {/* Grid size selector */}
        <label style={{ color:'#aaa', fontSize:12 }}>Grid:</label>
        <select value={gridCols} onChange={e => setGridCols(+(e.target as HTMLSelectElement).value)} style={SELECT_STYLE}>
          {[1,2,3,4].map(n => <option key={n} value={n}>{n}</option>)}
        </select>
        <span style={{ color:'#555' }}>×</span>
        <select value={gridRows} onChange={e => setGridRows(+(e.target as HTMLSelectElement).value)} style={SELECT_STYLE}>
          {[1,2,3,4].map(n => <option key={n} value={n}>{n}</option>)}
        </select>

        <button onClick={handleNew} style={BTN}>New</button>

        {/* Image import */}
        <label style={{ ...BTN, cursor:'pointer' }}>
          {importing ? 'Loading…' : 'Import Image'}
          <input type="file" accept="image/*" onChange={handleImageImport} style={{ display:'none' }} />
        </label>

        {/* State import */}
        <label style={{ ...BTN, cursor:'pointer' }}>
          Load State
          <input type="file" accept=".json" onChange={handleStateImport} style={{ display:'none' }} />
        </label>

        {comp && (
          <button onClick={handleExport} style={BTN}>Export State</button>
        )}

        <div style={{ flex:1 }} />

        {comp && (
          <span style={{ fontSize:11, color:'#555' }}>
            {gridCols}×{gridRows} tile{gridCols * gridRows !== 1 ? 's' : ''}
          </span>
        )}
      </div>

      {/* Editor or placeholder */}
      <div style={{ flex:1, overflow:'hidden' }}>
        {comp ? (
          <Editor initialComp={comp} gridCols={gridCols} gridRows={gridRows} />
        ) : (
          <div style={{ display:'flex', flexDirection:'column', alignItems:'center', justifyContent:'center', height:'100%', gap:16 }}>
            <p style={{ color:'#555', fontSize:14, margin:0 }}>No composition loaded.</p>
            <p style={{ color:'#444', fontSize:12, margin:0 }}>Choose a grid size above, then click <b>New</b> or <b>Import Image</b>.</p>
          </div>
        )}
      </div>
    </div>
  );
}

const BTN: h.JSX.CSSProperties = {
  background:   '#252525',
  border:       '1px solid #444',
  borderRadius:  3,
  color:         '#ccc',
  cursor:        'pointer',
  padding:       '3px 8px',
  fontSize:       12,
};

const SELECT_STYLE: h.JSX.CSSProperties = {
  background:   '#252525',
  border:       '1px solid #444',
  borderRadius:  3,
  color:         '#ccc',
  fontSize:       12,
  padding:       '2px 4px',
};
