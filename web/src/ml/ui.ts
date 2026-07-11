/**
 * Imperative ML UI overlays: first-use consent dialog + reusable task status
 * (spinner + progress + cancel). Kept framework-agnostic so any entry point
 * (import dialog, editor action) can call them without prop drilling.
 */

import type { MlModel } from './types.js';

// ─── Shared styling ───────────────────────────────────────────────────────────

const ACCENT = '#4a9eff';
const PANEL_BG = '#252525';
const BACKDROP = 'rgba(0,0,0,0.75)';

function el<K extends keyof HTMLElementTagNameMap>(
  tag: K, style: Partial<CSSStyleDeclaration>, text?: string,
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  Object.assign(node.style, style);
  if (text != null) node.textContent = text;
  return node;
}

function backdrop(): HTMLDivElement {
  return el('div', {
    position: 'fixed', inset: '0', background: BACKDROP, zIndex: '10000',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    font: '13px system-ui, sans-serif', color: '#ddd',
  });
}

export function formatBytes(n: number): string {
  if (n >= 1 << 20) return `${(n / (1 << 20)).toFixed(0)} MB`;
  if (n >= 1 << 10) return `${(n / (1 << 10)).toFixed(0)} KB`;
  return `${n} B`;
}

// ─── Consent ──────────────────────────────────────────────────────────────────

const REMEMBER_PREFIX = 'loominary_ml_consent_';

export function hasRemembered(modelId: string): boolean {
  try { return localStorage.getItem(REMEMBER_PREFIX + modelId) === '1'; } catch { return false; }
}
function remember(modelId: string): void {
  try { localStorage.setItem(REMEMBER_PREFIX + modelId, '1'); } catch { /* ignore */ }
}

/**
 * Ask the user to consent to downloading a model. Resolves true if they accept
 * (or previously chose "don't ask again" for this model), false on cancel.
 */
export function requestConsent(model: MlModel): Promise<boolean> {
  if (hasRemembered(model.id)) return Promise.resolve(true);
  return new Promise((resolve) => {
    const root = backdrop();
    const panel = el('div', {
      background: PANEL_BG, borderRadius: '8px', padding: '20px 22px',
      width: '420px', maxWidth: '90vw', boxShadow: '0 8px 32px rgba(0,0,0,0.5)',
    });
    panel.appendChild(el('div', { fontSize: '15px', fontWeight: '600', marginBottom: '10px', color: '#fff' },
      'Download AI model'));
    panel.appendChild(el('div', { lineHeight: '1.5', marginBottom: '8px' },
      `The "${model.label}" feature needs to download a ${formatBytes(model.sizeBytes)} model ` +
      `from huggingface.co. It runs entirely in your browser; your image is never uploaded.`));

    panel.appendChild(el('div', { fontSize: '12px', color: '#aaa', marginBottom: '4px' },
      `License: ${model.license}`));
    if (model.licenseNote) {
      panel.appendChild(el('div', {
        fontSize: '12px', color: '#e0b050', marginBottom: '12px', lineHeight: '1.4',
      }, `⚠ ${model.licenseNote}`));
    }

    const remwrap = el('label', { display: 'flex', alignItems: 'center', gap: '6px', margin: '6px 0 14px', cursor: 'pointer', fontSize: '12px', color: '#bbb' });
    const remcb = document.createElement('input');
    remcb.type = 'checkbox';
    remwrap.appendChild(remcb);
    remwrap.appendChild(document.createTextNode("Don't ask again for this model"));
    panel.appendChild(remwrap);

    const row = el('div', { display: 'flex', gap: '8px', justifyContent: 'flex-end' });
    const cancel = el('button', btnStyle('#444'), 'Cancel');
    const ok = el('button', btnStyle(ACCENT), 'Download');
    row.appendChild(cancel); row.appendChild(ok);
    panel.appendChild(row);
    root.appendChild(panel);
    document.body.appendChild(root);

    const close = (result: boolean) => {
      if (result && remcb.checked) remember(model.id);
      root.remove();
      resolve(result);
    };
    cancel.onclick = () => close(false);
    ok.onclick = () => close(true);
    root.onclick = (e) => { if (e.target === root) close(false); };
  });
}

function btnStyle(bg: string): Partial<CSSStyleDeclaration> {
  return {
    background: bg, color: '#fff', border: 'none', borderRadius: '5px',
    padding: '7px 16px', cursor: 'pointer', fontSize: '13px', fontWeight: '500',
  };
}

// ─── Task status ──────────────────────────────────────────────────────────────

export interface MlTaskHandle {
  setStatus(text: string): void;
  setProgress(loaded: number, total: number): void;
  /** Register a cancel handler; shows the Cancel button. */
  onCancel(fn: () => void): void;
  done(): void;
  readonly cancelled: boolean;
}

/** Show a modal task overlay (spinner + progress bar + optional cancel). */
export function showMlTask(label: string): MlTaskHandle {
  const root = backdrop();
  const panel = el('div', {
    background: PANEL_BG, borderRadius: '8px', padding: '20px 22px',
    width: '360px', maxWidth: '90vw', textAlign: 'center',
  });
  panel.appendChild(el('div', { fontSize: '14px', fontWeight: '600', color: '#fff', marginBottom: '12px' }, label));
  const status = el('div', { fontSize: '12px', color: '#bbb', marginBottom: '10px', minHeight: '16px' }, 'Starting…');
  panel.appendChild(status);

  const barOuter = el('div', { height: '6px', background: '#3a3a3a', borderRadius: '3px', overflow: 'hidden' });
  const barInner = el('div', { height: '100%', width: '0%', background: ACCENT, transition: 'width 0.1s linear' });
  barOuter.appendChild(barInner);
  panel.appendChild(barOuter);

  const cancelRow = el('div', { marginTop: '14px', display: 'none' });
  const cancelBtn = el('button', btnStyle('#444'), 'Cancel');
  cancelRow.appendChild(cancelBtn);
  panel.appendChild(cancelRow);

  root.appendChild(panel);
  document.body.appendChild(root);

  const handle: MlTaskHandle = {
    cancelled: false,
    setStatus(text: string) { status.textContent = text; },
    setProgress(loaded: number, total: number) {
      const pct = total > 0 ? Math.min(100, (loaded / total) * 100) : 0;
      barInner.style.width = `${pct}%`;
      if (total > 0) status.textContent = `Downloading… ${formatBytes(loaded)} / ${formatBytes(total)}`;
    },
    onCancel(fn: () => void) {
      cancelRow.style.display = 'block';
      cancelBtn.onclick = () => { (handle as { cancelled: boolean }).cancelled = true; fn(); };
    },
    done() { root.remove(); },
  };
  return handle;
}

/**
 * Modal report overlay for informational features (reduce ranking, frame
 * scoring). `rows` are plain strings rendered monospace; `onApply` (optional)
 * adds an action button.
 */
export function showMlReport(
  title: string, rows: string[], action?: { label: string; run: () => void },
): void {
  const root = backdrop();
  const panel = el('div', {
    background: PANEL_BG, borderRadius: '8px', padding: '18px 20px',
    width: '460px', maxWidth: '92vw', maxHeight: '80vh', overflowY: 'auto',
  });
  panel.appendChild(el('div', { fontSize: '14px', fontWeight: '600', color: '#fff', marginBottom: '12px' }, title));
  const pre = el('pre', {
    margin: '0 0 12px', fontFamily: 'ui-monospace, monospace', fontSize: '12px',
    color: '#cdd', lineHeight: '1.5', whiteSpace: 'pre-wrap',
  });
  pre.textContent = rows.join('\n');
  panel.appendChild(pre);

  const row = el('div', { display: 'flex', gap: '8px', justifyContent: 'flex-end' });
  if (action) {
    const apply = el('button', btnStyle(ACCENT), action.label);
    apply.onclick = () => { root.remove(); action.run(); };
    row.appendChild(apply);
  }
  const close = el('button', btnStyle('#444'), 'Close');
  close.onclick = () => root.remove();
  row.appendChild(close);
  panel.appendChild(row);

  root.appendChild(panel);
  root.onclick = (e) => { if (e.target === root) root.remove(); };
  document.body.appendChild(root);
}

/** Lightweight, non-modal toast for warnings (e.g. reduced-quality fallbacks). */
export function showMlToast(message: string, kind: 'info' | 'warn' = 'info'): void {
  const toast = el('div', {
    position: 'fixed', bottom: '20px', left: '50%', transform: 'translateX(-50%)',
    background: kind === 'warn' ? '#5a4a20' : PANEL_BG, color: '#eee', zIndex: '10001',
    padding: '10px 16px', borderRadius: '6px', fontSize: '12px', maxWidth: '80vw',
    boxShadow: '0 4px 16px rgba(0,0,0,0.4)', font: '12px system-ui, sans-serif',
  }, message);
  document.body.appendChild(toast);
  setTimeout(() => toast.remove(), 5000);
}
