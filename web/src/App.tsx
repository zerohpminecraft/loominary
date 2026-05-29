/**
 * App — 3-step wizard shell.
 *
 * Step 1: Import  — image selection + settings  (ImportPage)
 * Step 2: Edit    — the full Editor              (Editor)
 * Step 3: Export  — metadata + download          (ExportPage)
 *
 * A persistent StepBar at the top shows progress and allows back-navigation.
 */

import { h } from 'preact';
import { useState, useRef, useEffect } from 'preact/hooks';

import { Editor }      from './editor/Editor.js';
import { ImportPage }  from './pages/ImportPage.js';
import { ExportPage }  from './pages/ExportPage.js';
import type { CompositionState } from './payload-state.js';
import type { RequantizeParams } from './quantize.js';
import { type PreprocessParams, DEFAULT_PREPROCESS } from './preprocess.js';
import {
  saveSession, loadSession, clearSession,
  sessionToComposition, savedAgoLabel,
} from './persistence.js';
import { decodeGifFrames } from './gif-decode.js';

// ─── Step type ────────────────────────────────────────────────────────────────

type Step = 'import' | 'edit' | 'export';

// ─── StepBar ─────────────────────────────────────────────────────────────────

interface StepBarProps {
  step:           Step;
  canEdit:        boolean;
  canExport:      boolean;
  showGridLines:  boolean;
  uiFontSize:     number;
  onStepClick:    (s: Step) => void;
  onGridToggle:   () => void;
  onFontChange:   (n: number) => void;
}

function StepBar({
  step, canEdit, canExport,
  showGridLines, uiFontSize,
  onStepClick, onGridToggle, onFontChange,
}: StepBarProps) {
  const steps: { id: Step; num: string; label: string }[] = [
    { id: 'import', num: '①', label: 'Import' },
    { id: 'edit',   num: '②', label: 'Edit'   },
    { id: 'export', num: '③', label: 'Export' },
  ];

  function isClickable(s: Step): boolean {
    if (s === 'import') return true;
    if (s === 'edit')   return canEdit;
    if (s === 'export') return canExport;
    return false;
  }

  function isActive(s: Step): boolean { return s === step; }

  function stepStyle(s: Step): h.JSX.CSSProperties {
    const active    = isActive(s);
    const clickable = isClickable(s);
    const past      = (s === 'import') || (s === 'edit' && (step === 'edit' || step === 'export'));
    return {
      padding:    '2px 8px',
      borderRadius: 3,
      cursor:     clickable ? 'pointer' : 'default',
      fontSize:   13,
      fontWeight: active ? 'bold' : 'normal',
      color:      active ? '#fff' : (clickable && past ? '#aaa' : '#555'),
      textDecoration: active ? 'underline' : 'none',
      background: active ? '#1b3556' : 'transparent',
      border:     active ? '1px solid #4a9eff' : '1px solid transparent',
      userSelect: 'none',
    };
  }

  return (
    <div style={{
      display:       'flex',
      alignItems:    'center',
      gap:            6,
      padding:       '3px 10px',
      background:    '#1a1a1a',
      borderBottom:  '1px solid #333',
      fontSize:       13,
      flexShrink:     0,
      minHeight:      36,
    }}>
      {/* Brand */}
      <span style={{ fontWeight: 'bold', color: '#5af', marginRight: 6, whiteSpace: 'nowrap' }}>
        🧵 Loominary
      </span>

      {/* Steps */}
      {steps.map((s, i) => (
        <span key={s.id} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          {i > 0 && <span style={{ color: '#444' }}>→</span>}
          <span
            style={stepStyle(s.id)}
            onClick={() => { if (isClickable(s.id)) onStepClick(s.id); }}
          >
            {s.num} {s.label}
          </span>
        </span>
      ))}

      {/* Spacer */}
      <div style={{ flex: 1 }} />

      {/* Grid toggle — edit step only */}
      {step === 'edit' && (
        <button
          onClick={onGridToggle}
          title={showGridLines ? 'Hide tile grid lines' : 'Show tile grid lines'}
          style={{
            background:   showGridLines ? '#1b3556' : '#252525',
            border:       `1px solid ${showGridLines ? '#4a9eff' : '#444'}`,
            borderRadius:  3,
            color:        showGridLines ? '#8cf' : '#666',
            cursor:       'pointer',
            padding:      '3px 8px',
            fontSize:      12,
          }}
        >
          ⊞ Grid
        </button>
      )}

      {/* Font size — all steps */}
      <label style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 12, color: '#aaa' }}>
        <span>Aa</span>
        <input
          type="range" min={10} max={22} value={uiFontSize}
          onInput={e => onFontChange(+(e.target as HTMLInputElement).value)}
          style={{ width: 60 }}
          title={`UI font size: ${uiFontSize}px`}
        />
        <span style={{ fontSize: 11, color: '#666', minWidth: 22 }}>{uiFontSize}</span>
      </label>
    </div>
  );
}

// ─── App ──────────────────────────────────────────────────────────────────────

export function App() {
  const [step,            setStep]            = useState<Step>('import');
  const [composition,     setComposition]     = useState<CompositionState | null>(null);
  const [sourceBitmap,    setSourceBitmap]    = useState<ImageBitmap | null>(null);
  const [importReqParams, setImportReqParams] = useState<RequantizeParams | null>(null);
  const [importCropMode,  setImportCropMode]  = useState<'scale' | 'center'>('center');
  const [importPre,       setImportPre]       = useState<PreprocessParams>(DEFAULT_PREPROCESS);
  // Per-animation-frame source bitmaps decoded from a multi-frame GIF.
  const [sourceFrames,    setSourceFrames]    = useState<ImageBitmap[] | null>(null);
  const [showGridLines,   setShowGridLines]   = useState(true);
  const [uiFontSize,      setUiFontSize]      = useState(19);

  // Track the Editor's latest composition for export (without losing edits).
  const latestCompRef = useRef<CompositionState | null>(null);

  // ── Session persistence ───────────────────────────────────────────────────
  const [restoreNotice, setRestoreNotice] = useState<string | null>(null);
  const saveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // On mount: try to restore a saved session.
  useEffect(() => {
    loadSession().then(s => {
      if (!s) return;
      try {
        const comp = sessionToComposition(s);
        setComposition(comp);
        setImportCropMode(s.cropMode);
        setImportPre(s.pre);
        if (s.reqParams) setImportReqParams(s.reqParams);
        latestCompRef.current = comp;
        setStep('edit');
        setRestoreNotice('Session restored — saved ' + savedAgoLabel(s.savedAt));
      } catch {
        // Corrupt session; ignore.
      }
    });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Debounced auto-save: fires 3 s after the last composition change.
  function scheduleSave(
    comp: CompositionState,
    crop: 'scale' | 'center',
    pre: PreprocessParams,
    req: RequantizeParams | null,
  ) {
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
    saveTimerRef.current = setTimeout(() => {
      void saveSession(comp, crop, pre, req);
    }, 3000);
  }

  // ── Re-link source image (after session restore) ──────────────────────────
  const relinkInputRef = useRef<HTMLInputElement | null>(null);

  function handleRelinkSource() {
    if (!relinkInputRef.current) {
      const input = document.createElement('input');
      input.type = 'file';
      input.accept = 'image/*';
      input.style.display = 'none';
      input.addEventListener('change', async (e) => {
        const file = (e.target as HTMLInputElement).files?.[0];
        if (!file) return;
        try {
          const bmp = await createImageBitmap(file);
          setSourceBitmap(bmp);
        } catch { /* ignore invalid file */ }
        (e.target as HTMLInputElement).value = '';
      });
      document.body.appendChild(input);
      relinkInputRef.current = input;
    }
    relinkInputRef.current.click();
  }

  // ── Import proceed ────────────────────────────────────────────────────────
  function handleImportProceed(
    comp:         CompositionState,
    bitmap:       ImageBitmap,
    reqParams:    RequantizeParams,
    cropMode:     'scale' | 'center',
    pre:          PreprocessParams,
    gifFrames?:   ImageBitmap[] | null,
  ) {
    setComposition(comp);
    setSourceBitmap(bitmap);
    setImportReqParams(reqParams);
    setImportCropMode(cropMode);
    setImportPre(pre);
    setSourceFrames(gifFrames ?? null);
    latestCompRef.current = comp;
    setStep('edit');
    setRestoreNotice(null);
    void saveSession(comp, cropMode, pre, reqParams);
  }

  // ── Step navigation ────────────────────────────────────────────────────────
  function handleStepClick(s: Step) {
    if (s === 'export' && latestCompRef.current) {
      setComposition(latestCompRef.current);
    }
    if (s === 'import') {
      // User is explicitly going back to start fresh — clear saved session.
      void clearSession();
      setRestoreNotice(null);
    }
    setStep(s);
  }

  // ── Back from export ──────────────────────────────────────────────────────
  function handleBackFromExport() {
    setStep('edit');
  }

  // ── Composition the export page sees ──────────────────────────────────────
  // When navigating to export, use latestCompRef so it reflects any edits.
  const exportComp = latestCompRef.current ?? composition;

  // ─── Render ────────────────────────────────────────────────────────────────
  return (
    <div style={{
      display:       'flex',
      flexDirection: 'column',
      width:         '100%',
      height:        '100%',
      background:    '#111',
      color:         '#ccc',
      fontFamily:    'system-ui, sans-serif',
      overflow:      'hidden',
    }}>
      {/* Step bar */}
      <StepBar
        step={step}
        canEdit={composition !== null}
        canExport={composition !== null}
        showGridLines={showGridLines}
        uiFontSize={uiFontSize}
        onStepClick={handleStepClick}
        onGridToggle={() => setShowGridLines(v => !v)}
        onFontChange={setUiFontSize}
      />

      {/* Restore notice */}
      {restoreNotice && (
        <div style={{
          background: '#1a2a1a', borderBottom: '1px solid #3a6a3a',
          color: '#8f8', fontSize: 12, padding: '4px 12px',
          display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0,
        }}>
          <span>↺ {restoreNotice}</span>
          {!sourceBitmap && (
            <button onClick={handleRelinkSource} style={{
              background: 'none', border: '1px solid #3a6a3a', borderRadius: 3,
              color: '#6a9a6a', cursor: 'pointer', fontSize: 11, padding: '1px 7px',
            }} title="Re-link the original source file to re-enable requantize from source">
              Re-link source…
            </button>
          )}
          <button onClick={() => setRestoreNotice(null)} style={{
            marginLeft: 'auto', background: 'none', border: 'none',
            color: '#5a8a5a', cursor: 'pointer', fontSize: 13,
          }}>✕</button>
        </div>
      )}

      {/* Page content */}
      <div style={{ flex: 1, overflow: 'hidden' }}>
        {step === 'import' && (
          <ImportPage onProceed={handleImportProceed} uiFontSize={uiFontSize} />
        )}

        {step === 'edit' && composition && (
          <Editor
            initialComp={composition}
            sourceBitmap={sourceBitmap}
            importCropMode={importCropMode}
            importPre={importPre}
            showGridLines={showGridLines}
            uiFontSize={uiFontSize}
            initialReqParams={importReqParams ?? undefined}
            sourceFrames={sourceFrames}
            onCompChange={c => {
              latestCompRef.current = c;
              scheduleSave(c, importCropMode, importPre, importReqParams);
            }}
            onRelinkSource={handleRelinkSource}
          />
        )}

        {step === 'export' && exportComp && (
          <ExportPage
            comp={exportComp}
            onBack={handleBackFromExport}
            uiFontSize={uiFontSize}
          />
        )}

        {/* Fallback if edit step but no comp (shouldn't happen normally) */}
        {step === 'edit' && !composition && (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', color: '#555' }}>
            No composition loaded — go back to Import.
          </div>
        )}
      </div>
    </div>
  );
}
