/**
 * App — top-level shell.
 *
 * Shows a thin toolbar at the top and the Editor below.
 * The editor occupies all remaining height.
 */

import { h } from 'preact';
import { useState, useCallback, useEffect, useRef } from 'preact/hooks';
import { Editor } from './editor/Editor.js';
import type { CompositionState } from './payload-state.js';
import type { QuantizeParams } from './quantize.js';
import {
  importStateFile,
  downloadState,
  compositionFromState,
  emptyPayloadState,
  saveState,
} from './payload-state.js';

// ─── Image scaling helper ─────────────────────────────────────────────────────

async function scaleImageTo(bmp: ImageBitmap, w: number, h: number): Promise<ImageData> {
  const osc  = new OffscreenCanvas(w, h);
  const octx = osc.getContext('2d')!;
  octx.imageSmoothingEnabled = true;
  octx.imageSmoothingQuality = 'high';
  octx.drawImage(bmp, 0, 0, w, h);
  return octx.getImageData(0, 0, w, h);
}

// ─── Auto-grid detection ──────────────────────────────────────────────────────

/**
 * Given an image's pixel dimensions, pick the best (cols × rows) grid
 * where cols, rows ≤ maxDim and the grid's aspect ratio is closest to
 * the image's aspect ratio.  Tiebreak by fewest total tiles.
 */
function bestGridSize(w: number, h: number, maxDim = 8): [number, number] {
  const ratio = w / h;
  let bestCols = 1, bestRows = 1, bestScore = Infinity;
  for (let cols = 1; cols <= maxDim; cols++) {
    for (let rows = 1; rows <= maxDim; rows++) {
      const gridRatio = cols / rows;
      const maxR  = Math.max(ratio, gridRatio);
      const diff  = Math.abs(ratio - gridRatio) / maxR;   // 0 = perfect match
      const score = diff * 100 + cols * rows * 0.1;        // fewer tiles win ties
      if (score < bestScore) { bestScore = score; bestCols = cols; bestRows = rows; }
    }
  }
  return [bestCols, bestRows];
}

// ─── GridInput ────────────────────────────────────────────────────────────────
// A number input that shows a draft value while typing and only commits
// on blur or Enter, so the grid doesn't change on every keystroke.

interface GridInputProps {
  value:    number;
  onChange: (v: number) => void;
}

function GridInput({ value, onChange }: GridInputProps) {
  const [draft, setDraft] = useState(String(value));

  // Keep draft in sync when value changes externally (e.g. auto-detection).
  useEffect(() => setDraft(String(value)), [value]);

  function commit() {
    const n = Math.max(1, Math.min(128, parseInt(draft, 10) || 1));
    setDraft(String(n));
    if (n !== value) onChange(n);
  }

  return (
    <input
      type="number"
      min={1}
      max={128}
      value={draft}
      onInput={e => setDraft((e.target as HTMLInputElement).value)}
      onBlur={commit}
      onKeyDown={e => { if (e.key === 'Enter') commit(); }}
      style={NUM_INPUT_STYLE}
    />
  );
}

// ─── App ──────────────────────────────────────────────────────────────────────

export function App() {
  const [comp,         setComp]        = useState<CompositionState | null>(null);
  const [importing,    setImporting]   = useState(false);
  const [gridCols,     setGridCols]    = useState(1);
  const [gridRows,     setGridRows]    = useState(1);
  /** Source bitmap kept in memory so grid changes can reimport at the new size. */
  const [sourceBitmap, setSourceBitmap] = useState<ImageBitmap | null>(null);
  /** Live quantize params from Editor — updated whenever the user changes a setting. */
  const importParamsRef = useRef<QuantizeParams>({
    legalOnly: true, targetColors: 0, dither: 'NONE',
  });

  // ─── Core reimport ─────────────────────────────────────────────────────────
  const reimportImage = useCallback(async (bitmap: ImageBitmap, cols: number, rows: number) => {
    setImporting(true);
    try {
      const { convertTwoPassGrid } = await import('./quantize.js');
      const img   = await scaleImageTo(bitmap, cols * 128, rows * 128);
      // Use whatever params the Editor currently has; default NONE if not yet set.
      const tiles = convertTwoPassGrid(img, cols, rows, importParamsRef.current);
      const ps = emptyPayloadState(cols, rows);
      setComp(compositionFromState(ps, tiles.map(t => [t])));
    } catch (err) {
      alert(`Failed to import image: ${err}`);
    } finally {
      setImporting(false);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ─── Grid dimension change ──────────────────────────────────────────────────
  // If a source image is loaded, reimport it at the new size.
  // Otherwise just update the dimensions — the Editor's resize effect handles it.
  const handleColsChange = useCallback((cols: number) => {
    setGridCols(cols);
    if (sourceBitmap) {
      void reimportImage(sourceBitmap, cols, gridRows);
    }
  }, [sourceBitmap, gridRows, reimportImage]);

  const handleRowsChange = useCallback((rows: number) => {
    setGridRows(rows);
    if (sourceBitmap) {
      void reimportImage(sourceBitmap, gridCols, rows);
    }
  }, [sourceBitmap, gridCols, reimportImage]);

  // ─── Import image ───────────────────────────────────────────────────────────
  const handleImageImport = useCallback(async (e: Event) => {
    const file = (e.target as HTMLInputElement).files?.[0];
    if (!file) return;
    try {
      const bmp = await createImageBitmap(file);
      setSourceBitmap(bmp);

      // Always auto-detect the best grid layout from the image's aspect ratio.
      const [cols, rows] = bestGridSize(bmp.width, bmp.height);
      setGridCols(cols);
      setGridRows(rows);

      await reimportImage(bmp, cols, rows);
    } catch (err) {
      alert(`Failed to import image: ${err}`);
    }
    // Reset the input so the same file can be re-selected.
    (e.target as HTMLInputElement).value = '';
  }, [reimportImage]);

  // ─── Import state JSON ──────────────────────────────────────────────────────
  const handleStateImport = useCallback(async (e: Event) => {
    const file = (e.target as HTMLInputElement).files?.[0];
    if (!file) return;
    try {
      const ps      = await importStateFile(file);
      const n       = ps.columns * ps.rows;
      const pixelData: Uint8Array[][] = Array.from({ length: n }, () => [new Uint8Array(128 * 128)]);
      setGridCols(ps.columns);
      setGridRows(ps.rows);
      setSourceBitmap(null); // state files don't have a source image
      setComp(compositionFromState(ps, pixelData));
    } catch (err) {
      alert(`Failed to load state: ${err}`);
    }
    (e.target as HTMLInputElement).value = '';
  }, []);

  // ─── New blank ─────────────────────────────────────────────────────────────
  const handleNew = useCallback(() => {
    const n  = gridCols * gridRows;
    const ps = emptyPayloadState(gridCols, gridRows);
    const pd: Uint8Array[][] = Array.from({ length: n }, () => [new Uint8Array(128 * 128)]);
    setSourceBitmap(null);
    setComp(compositionFromState(ps, pd));
  }, [gridCols, gridRows]);

  // ─── Export state JSON ──────────────────────────────────────────────────────
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

  // ─── Render ─────────────────────────────────────────────────────────────────
  return (
    <div style={{ display:'flex', flexDirection:'column', width:'100%', height:'100%', background:'#111', color:'#ccc', fontFamily:'system-ui,sans-serif' }}>

      {/* ── Toolbar ── */}
      <div style={TOOLBAR_STYLE}>
        <span style={{ fontWeight:'bold', color:'#5af', marginRight:8 }}>🧵 Loominary</span>

        {/* Grid size */}
        <label style={LABEL}>Grid:</label>
        <GridInput value={gridCols} onChange={handleColsChange} />
        <span style={{ color:'#555' }}>×</span>
        <GridInput value={gridRows} onChange={handleRowsChange} />

        {/* Source image indicator */}
        {sourceBitmap && (
          <span style={{ fontSize:11, color:'#888', fontStyle:'italic' }}>
            ↺ re-tiles on resize
          </span>
        )}

        <div style={{ width:1, height:16, background:'#333', margin:'0 4px' }} />

        <button onClick={handleNew} style={BTN}>New</button>

        <label style={{ ...BTN, cursor:'pointer' }}>
          {importing ? 'Loading…' : 'Import Image'}
          <input type="file" accept="image/*" onChange={handleImageImport} style={{ display:'none' }} />
        </label>

        <label style={{ ...BTN, cursor:'pointer' }}>
          Load State
          <input type="file" accept=".json" onChange={handleStateImport} style={{ display:'none' }} />
        </label>

        {comp && <button onClick={handleExport} style={BTN}>Export State</button>}

        <div style={{ flex:1 }} />

        {comp && (
          <span style={{ fontSize:11, color:'#555' }}>
            {gridCols}×{gridRows} tile{gridCols * gridRows !== 1 ? 's' : ''}
          </span>
        )}
      </div>

      {/* ── Editor / placeholder ── */}
      <div style={{ flex:1, overflow:'hidden' }}>
        {comp ? (
          <Editor
            initialComp={comp}
            gridCols={gridCols}
            gridRows={gridRows}
            sourceBitmap={sourceBitmap}
            onImportParamsChange={p => { importParamsRef.current = p; }}
          />
        ) : (
          <div style={{ display:'flex', flexDirection:'column', alignItems:'center', justifyContent:'center', height:'100%', gap:16 }}>
            <p style={{ color:'#555', fontSize:14, margin:0 }}>No composition loaded.</p>
            <p style={{ color:'#444', fontSize:12, margin:0 }}>
              Choose a grid size, then click <b>New</b> or <b>Import Image</b>.
            </p>
          </div>
        )}
      </div>
    </div>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const TOOLBAR_STYLE: h.JSX.CSSProperties = {
  display:    'flex',
  alignItems: 'center',
  gap:         6,
  padding:    '4px 8px',
  background: '#1a1a1a',
  borderBottom: '1px solid #333',
  fontSize:    13,
  flexShrink:  0,
};

const LABEL: h.JSX.CSSProperties = { color:'#aaa', fontSize:12 };

const BTN: h.JSX.CSSProperties = {
  background:   '#252525',
  border:       '1px solid #444',
  borderRadius:  3,
  color:         '#ccc',
  cursor:        'pointer',
  padding:       '3px 8px',
  fontSize:       12,
};

const NUM_INPUT_STYLE: h.JSX.CSSProperties = {
  width:        44,
  background:   '#252525',
  border:       '1px solid #444',
  borderRadius:  3,
  color:         '#ccc',
  fontSize:       12,
  padding:       '2px 4px',
  textAlign:     'center',
};
