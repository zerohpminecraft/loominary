/**
 * App — top-level shell.
 *
 * Shows a thin toolbar at the top and the Editor below.
 * The editor occupies all remaining height.
 */

import { h } from 'preact';
import { useState, useCallback, useEffect } from 'preact/hooks';
import { Editor } from './editor/Editor.js';
import type { CompositionState } from './payload-state.js';
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

  // Keep draft in sync when value changes externally (e.g. state file load).
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
  /** Source bitmap kept in memory so grid changes can reimport without re-reading the file. */
  const [sourceBitmap, setSourceBitmap] = useState<ImageBitmap | null>(null);
  /** When locked, grid changes only resize; they do NOT reimport the image. */
  const [gridLocked,   setGridLocked]  = useState(true);

  // ─── Core reimport ─────────────────────────────────────────────────────────
  // Called both on initial image import and on grid-size changes when unlocked.
  const reimportImage = useCallback(async (bitmap: ImageBitmap, cols: number, rows: number) => {
    setImporting(true);
    try {
      const { convertTwoPassGrid } = await import('./quantize.js');
      const img   = await scaleImageTo(bitmap, cols * 128, rows * 128);
      const tiles = convertTwoPassGrid(img, cols, rows, {
        legalOnly:    true,
        targetColors: 0,
        dither:       'FS',
      });
      const ps = emptyPayloadState(cols, rows);
      setComp(compositionFromState(ps, tiles.map(t => [t])));
    } catch (err) {
      alert(`Failed to import image: ${err}`);
    } finally {
      setImporting(false);
    }
  }, []);

  // ─── Grid dimension change ──────────────────────────────────────────────────
  // When unlocked and a source image exists, reimport at the new size.
  // When locked (or no source), only resize the existing composition.
  const handleColsChange = useCallback((cols: number) => {
    setGridCols(cols);
    if (!gridLocked && sourceBitmap) {
      reimportImage(sourceBitmap, cols, gridRows);
    }
  }, [gridLocked, sourceBitmap, gridRows, reimportImage]);

  const handleRowsChange = useCallback((rows: number) => {
    setGridRows(rows);
    if (!gridLocked && sourceBitmap) {
      reimportImage(sourceBitmap, gridCols, rows);
    }
  }, [gridLocked, sourceBitmap, gridCols, reimportImage]);

  // ─── Import image ───────────────────────────────────────────────────────────
  const handleImageImport = useCallback(async (e: Event) => {
    const file = (e.target as HTMLInputElement).files?.[0];
    if (!file) return;
    try {
      const bmp = await createImageBitmap(file);
      setSourceBitmap(bmp);

      // Always auto-detect the best grid layout from the image's aspect ratio.
      // The lock only controls whether *manual* grid-input changes trigger a
      // reimport — it does not suppress auto-sizing on initial import.
      const [cols, rows] = bestGridSize(bmp.width, bmp.height);
      setGridCols(cols);
      setGridRows(rows);

      await reimportImage(bmp, cols, rows);
    } catch (err) {
      alert(`Failed to import image: ${err}`);
    }
    // Reset the input so the same file can be re-selected.
    (e.target as HTMLInputElement).value = '';
  }, [gridCols, gridRows, gridLocked, reimportImage]);

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
  const lockTitle = gridLocked
    ? 'Grid locked — changes only resize. Click to unlock and allow reimport.'
    : 'Grid unlocked — changing size will reimport the image. Click to lock.';

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

        {/* Lock button */}
        <button
          onClick={() => setGridLocked(l => !l)}
          title={lockTitle}
          style={{
            ...BTN,
            padding:     '3px 7px',
            fontSize:     15,
            background:   gridLocked ? '#1e3a1e' : '#3a1e1e',
            borderColor:  gridLocked ? '#3a6b3a' : '#6b3a3a',
            color:        gridLocked ? '#7c7'    : '#f87',
          }}
        >
          {gridLocked ? '🔒' : '🔓'}
        </button>

        {/* Source image indicator */}
        {!gridLocked && sourceBitmap && (
          <span style={{ fontSize:11, color:'#f87', fontStyle:'italic' }}>
            ⚠ live reimport
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
          <Editor initialComp={comp} gridCols={gridCols} gridRows={gridRows} sourceBitmap={sourceBitmap} />
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
