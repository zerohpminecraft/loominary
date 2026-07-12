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
  saveNewSession, updateSession, loadMostRecentSession, loadSessionById,
  sessionToComposition, savedAgoLabel,
  type SourceImage,
} from './persistence.js';
import { track } from './analytics.js';

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

      {/* GitHub link */}
      <a
        href="https://github.com/zerohpminecraft/loominary"
        target="_blank"
        rel="noopener noreferrer"
        title="View Loominary on GitHub"
        style={{
          display:      'flex',
          alignItems:   'center',
          gap:           4,
          marginLeft:    8,
          color:        '#aaa',
          textDecoration:'none',
          fontSize:      12,
          whiteSpace:   'nowrap',
        }}
      >
        <span style={{ fontSize: 14 }}>⭐</span> GitHub
      </a>
    </div>
  );
}

// ─── App ──────────────────────────────────────────────────────────────────────

export function App() {
  const [step,            setStep]            = useState<Step>('import');
  const [composition,     setComposition]     = useState<CompositionState | null>(null);
  const [importQuality,   setImportQuality]   = useState<{ accuratePct: number; avgDelta: number; frames: number } | null>(null);
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

  // Analytics funnel: record reaching each stage. Visitors/pageviews are
  // captured automatically by Umami; export_completed is fired from ExportPage.
  useEffect(() => {
    if (step === 'edit') track('editor_opened');
    else if (step === 'export') track('export_opened');
  }, [step]);

  // ── Session persistence ───────────────────────────────────────────────────
  const [restoreNotice,   setRestoreNotice]   = useState<string | null>(null);
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null);
  const activeSessionIdRef  = useRef<string | null>(null);
  const sourceImageRef      = useRef<SourceImage | null>(null);
  // True once sourceImageRef has been persisted to session_images for the
  // current session.  Cleared whenever sourceImageRef changes so the next
  // auto-save writes it.
  const sourceImageSavedRef = useRef(false);
  const saveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // ── Restore source image from a stored session ────────────────────────────
  // Reconstructs sourceBitmap (and sourceFrames for animated GIFs) from the
  // stored file bytes so the user doesn't need to re-link after a reload.
  async function restoreSourceImage(
    buffer: ArrayBuffer | null,
    mime:   string | null,
  ) {
    // Always clear both immediately — previous session's bitmap/frames must
    // never bleed into the session being restored.
    setSourceBitmap(null);
    setSourceFrames(null);
    sourceImageRef.current = null;

    if (!buffer || !mime) return;
    try {
      const blob = new Blob([buffer], { type: mime });
      const bmp  = await createImageBitmap(blob);
      setSourceBitmap(bmp);
      sourceImageRef.current = { buffer, mime };
      if (mime === 'image/gif') {
        const file = new File([blob], 'source.gif', { type: mime });
        const { decodeGifFrames } = await import('./gif-decode.js');
        const frames = await decodeGifFrames(file);
        if (frames.length > 1) setSourceFrames(frames.map(f => f.bitmap));
      }
      // Non-GIF: sourceFrames stays null (cleared above) — correct.
    } catch { /* corrupt bytes — ignore */ }
  }

  // On mount: auto-restore the most recent session unless the user explicitly
  // navigated back to Import last time (flagged via sessionStorage).
  useEffect(() => {
    if (sessionStorage.getItem('loominary_fresh') === '1') {
      sessionStorage.removeItem('loominary_fresh');
      return;
    }
    loadMostRecentSession().then(async s => {
      if (!s) return;
      try {
        const comp = sessionToComposition(s);
        setComposition(comp);
        setImportCropMode(s.cropMode);
        setImportPre(s.pre);
        if (s.reqParams) setImportReqParams(s.reqParams);
        latestCompRef.current      = comp;
        activeSessionIdRef.current = s.id;
        setActiveSessionId(s.id);
        setStep('edit');
        setRestoreNotice('Session restored — saved ' + savedAgoLabel(s.savedAt));
        await restoreSourceImage(s.sourceImageBuffer ?? null, s.sourceImageMime ?? null);
        sourceImageSavedRef.current = true; // image already in IDB
      } catch { /* corrupt session */ }
    });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Debounced auto-save: fires 3 s after the last composition change.
  function scheduleSave(
    comp: CompositionState,
    crop: 'scale' | 'center',
    pre:  PreprocessParams,
    req:  RequantizeParams | null,
  ) {
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
    saveTimerRef.current = setTimeout(() => {
      const id = activeSessionIdRef.current;
      if (!id) return;
      const img = sourceImageSavedRef.current ? null : sourceImageRef.current;
      void updateSession(id, comp, crop, pre, req, img).then(() => {
        if (img) sourceImageSavedRef.current = true;
      });
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
          // Store the newly linked file so future auto-saves include it.
          const buffer = await file.arrayBuffer();
          sourceImageRef.current    = { buffer, mime: file.type || 'image/png' };
          sourceImageSavedRef.current = false;
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
    sourceFile?:  File | null,
    quality?:     { accuratePct: number; avgDelta: number; frames: number } | null,
  ) {
    setImportQuality(quality ?? null);
    // Null out the active session ID immediately so scheduleSave skips any
    // auto-save fired between now and when saveNewSession resolves.  Without
    // this, the Editor's initialComp effect fires onCompChange on mount and
    // the 3-second timer writes the new composition into the OLD session.
    activeSessionIdRef.current  = null;
    sourceImageSavedRef.current = false;
    setActiveSessionId(null);

    setComposition(comp);
    setSourceBitmap(bitmap);
    setImportReqParams(reqParams);
    setImportCropMode(cropMode);
    setImportPre(pre);
    setSourceFrames(gifFrames ?? null);
    latestCompRef.current = comp;
    setStep('edit');
    setRestoreNotice(null);
    sessionStorage.removeItem('loominary_fresh');
    // Read the source file bytes for persistence (async, but saveNewSession
    // awaits it internally so the session is written with the image).
    void (async () => {
      let img: SourceImage | null = null;
      if (sourceFile) {
        try {
          const buffer = await sourceFile.arrayBuffer();
          img = { buffer, mime: sourceFile.type || 'image/png' };
        } catch { /* ignore */ }
      }
      sourceImageRef.current      = img;
      const id = await saveNewSession(comp, cropMode, pre, reqParams, img);
      activeSessionIdRef.current  = id;
      sourceImageSavedRef.current = img !== null;
      setActiveSessionId(id);
    })();
  }

  // ── Proceed from state JSON import (no source bitmap) ────────────────────
  function handleProceedFromState(comp: CompositionState) {
    setImportQuality(null);
    activeSessionIdRef.current  = null;
    sourceImageSavedRef.current = true; // no source image; nothing to save
    setActiveSessionId(null);

    setComposition(comp);
    setSourceBitmap(null);
    setSourceFrames(null);
    setImportReqParams(null);
    sourceImageRef.current = null;
    latestCompRef.current  = comp;
    setStep('edit');
    setRestoreNotice(null);
    sessionStorage.removeItem('loominary_fresh');
    void saveNewSession(comp, importCropMode, importPre, null, null).then(id => {
      activeSessionIdRef.current = id;
      setActiveSessionId(id);
    });
  }

  // ── Load a session from history ───────────────────────────────────────────
  async function handleLoadSession(id: string) {
    // Clear source state immediately — don't show the previous session's image
    // while the IDB read is in flight.
    setSourceBitmap(null);
    setSourceFrames(null);
    sourceImageRef.current = null;

    const s = await loadSessionById(id);
    if (!s) return;
    try {
      const comp = sessionToComposition(s);
      setComposition(comp);
      setImportCropMode(s.cropMode);
      setImportPre(s.pre);
      if (s.reqParams) setImportReqParams(s.reqParams);
      latestCompRef.current      = comp;
      activeSessionIdRef.current = id;
      setActiveSessionId(id);
      setStep('edit');
      setRestoreNotice('Session restored — saved ' + savedAgoLabel(s.savedAt));
      sessionStorage.removeItem('loominary_fresh');
      await restoreSourceImage(s.sourceImageBuffer ?? null, s.sourceImageMime ?? null);
      sourceImageSavedRef.current = true; // image already in IDB
    } catch { /* corrupt session */ }
  }

  // ── Step navigation ────────────────────────────────────────────────────────
  function handleStepClick(s: Step) {
    if (s === 'export' && latestCompRef.current) {
      setComposition(latestCompRef.current);
    }
    if (s === 'import') {
      // Flag so the next page load doesn't auto-restore into Edit.
      sessionStorage.setItem('loominary_fresh', '1');
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
          <ImportPage
            onProceed={handleImportProceed}
            onProceedFromState={handleProceedFromState}
            onLoadSession={handleLoadSession}
            uiFontSize={uiFontSize}
          />
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
            importQuality={importQuality}
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
