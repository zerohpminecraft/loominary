/**
 * The editor's single AI entry point: a small overlay menu listing AI actions.
 *
 * This module is tiny and may sit in the main bundle; every actual feature is
 * loaded with a dynamic import() the moment its action runs, so neither the
 * feature code nor ORT touches the critical path until used (Phase 0 bundle
 * discipline). Adding a feature = adding one entry to ACTIONS.
 */

import type { EditorBridge } from './bridge.js';

interface AiAction {
  label: string;
  hint: string;
  run: (bridge: EditorBridge) => Promise<void>;
}

const ACTIONS: AiAction[] = [
  {
    label: '🪄 Remove background',
    hint: 'Cut the subject onto a transparent background (shrinks the byte budget).',
    run: async (b) => (await import('./features/backgroundRemoval.js')).runBackgroundRemoval(b),
  },
  {
    label: '✂ Denoise (current frame)',
    hint: 'Reduce speckle from stolen maps / crusty GIFs, then re-quantize to the same palette.',
    run: async (b) => (await import('./features/denoise.js')).runDenoise(b, { scope: 'frame' }),
  },
  {
    label: '✂ Denoise (all frames)',
    hint: 'Apply the NL-means denoiser to every animation frame.',
    run: async (b) => (await import('./features/denoise.js')).runDenoise(b, { scope: 'all' }),
  },
  {
    label: '🎨 Suggest palette',
    hint: 'K-means in Oklab proposes the most useful map colors, previewed as a requantize.',
    run: async (b) => (await import('./features/paletteSuggest.js')).runPaletteSuggestion(b),
  },
  {
    label: '🪮 Auto dither-mask',
    hint: 'Dither smooth gradients, keep edges crisp — previewed as a requantize.',
    run: async (b) => (await import('./features/autoDitherMask.js')).runAutoDitherMask(b),
  },
  {
    label: '📊 Rank reduce strategies',
    hint: 'Simulate rarest/closest/weighted reduction and show projected color counts.',
    run: async (b) => (await import('./features/reduceRanking.js')).runReduceRanking(b),
  },
  {
    label: '🎞 Score GIF frames',
    hint: 'Rank animation frames by redundancy to pick which to drop when over budget.',
    run: async (b) => (await import('./features/gifFrameScore.js')).runGifFrameScore(b),
  },
];

export function openAiMenu(bridge: EditorBridge): void {
  const root = document.createElement('div');
  Object.assign(root.style, {
    position: 'fixed', inset: '0', background: 'rgba(0,0,0,0.6)', zIndex: '10000',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    font: '13px system-ui, sans-serif', color: '#ddd',
  } as Partial<CSSStyleDeclaration>);

  const panel = document.createElement('div');
  Object.assign(panel.style, {
    background: '#252525', borderRadius: '8px', padding: '16px', width: '340px',
    maxWidth: '90vw', boxShadow: '0 8px 32px rgba(0,0,0,0.5)',
  } as Partial<CSSStyleDeclaration>);

  const title = document.createElement('div');
  Object.assign(title.style, { fontSize: '14px', fontWeight: '600', color: '#fff', marginBottom: '12px' } as Partial<CSSStyleDeclaration>);
  title.textContent = '✨ AI tools';
  panel.appendChild(title);

  for (const action of ACTIONS) {
    const btn = document.createElement('button');
    Object.assign(btn.style, {
      display: 'block', width: '100%', textAlign: 'left', background: '#2f2f2f',
      color: '#eee', border: '1px solid #3a3a3a', borderRadius: '6px',
      padding: '9px 11px', margin: '0 0 8px', cursor: 'pointer',
    } as Partial<CSSStyleDeclaration>);
    const l = document.createElement('div');
    l.style.fontWeight = '600';
    l.textContent = action.label;
    const h = document.createElement('div');
    Object.assign(h.style, { fontSize: '11px', color: '#999', marginTop: '2px', lineHeight: '1.3' } as Partial<CSSStyleDeclaration>);
    h.textContent = action.hint;
    btn.appendChild(l); btn.appendChild(h);
    btn.onmouseenter = () => { btn.style.borderColor = '#4a9eff'; };
    btn.onmouseleave = () => { btn.style.borderColor = '#3a3a3a'; };
    btn.onclick = () => {
      root.remove();
      void action.run(bridge).catch((e) => console.error('[Loominary AI]', e));
    };
    panel.appendChild(btn);
  }

  root.appendChild(panel);
  root.onclick = (e) => { if (e.target === root) root.remove(); };
  document.body.appendChild(root);
}
