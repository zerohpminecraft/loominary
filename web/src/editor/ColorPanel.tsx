/**
 * ColorPanel — right-side panel for sRGB (full-colour) mode.
 *
 * Replaces PalettePanel when the composition's colorSpace is 'srgb': instead of
 * map-byte swatches the user gets a true-colour picker —
 *   - SV square (saturation × value for the current hue) + hue slider
 *   - hex (#RRGGBB) and R/G/B numeric inputs, all two-way bound
 *   - large swatch showing the current colour
 *   - a "recent colours" row of the last committed picks
 *
 * The panel is stateless with respect to the colour itself: `color` (packed
 * 0xRRGGBB) comes from the Editor and every change goes back via `onChange`.
 * `onCommit` fires when a colour is "settled" (picker pointer-up, hex entry,
 * numeric change) so the Editor can maintain the recent list.
 */

import { h } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';

// ─── Props ────────────────────────────────────────────────────────────────────

interface ColorPanelProps {
  /** Current colour as packed 0xRRGGBB. */
  color:      number;
  /** Live colour change (fires continuously while dragging). */
  onChange:   (packed: number) => void;
  /** Colour committed — parent should push it onto the recent list. */
  onCommit:   (packed: number) => void;
  /** Recently committed colours, most recent first (already deduped). */
  recent:     number[];
  /** Panel width in px — controlled by the parent's resize handle. */
  panelWidth?: number;
}

// ─── Colour helpers ───────────────────────────────────────────────────────────

function unpack(rgb: number): [r: number, g: number, b: number] {
  return [(rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff];
}

function pack(r: number, g: number, b: number): number {
  return (r << 16) | (g << 8) | b;
}

function hex6(rgb: number): string {
  return '#' + rgb.toString(16).padStart(6, '0').toUpperCase();
}

/** RGB (0–255) → HSV (h 0–360, s 0–1, v 0–1). */
function rgbToHsv(r: number, g: number, b: number): [h: number, s: number, v: number] {
  const rn = r / 255, gn = g / 255, bn = b / 255;
  const max = Math.max(rn, gn, bn), min = Math.min(rn, gn, bn);
  const d = max - min;
  let hue = 0;
  if (d > 0) {
    if      (max === rn) hue = 60 * (((gn - bn) / d) % 6);
    else if (max === gn) hue = 60 * ((bn - rn) / d + 2);
    else                 hue = 60 * ((rn - gn) / d + 4);
    if (hue < 0) hue += 360;
  }
  return [hue, max === 0 ? 0 : d / max, max];
}

/** HSV (h 0–360, s 0–1, v 0–1) → RGB (0–255). */
function hsvToRgb(hue: number, s: number, v: number): [r: number, g: number, b: number] {
  const c = v * s;
  const x = c * (1 - Math.abs(((hue / 60) % 2) - 1));
  const m = v - c;
  let r = 0, g = 0, b = 0;
  if      (hue <  60) { r = c; g = x; b = 0; }
  else if (hue < 120) { r = x; g = c; b = 0; }
  else if (hue < 180) { r = 0; g = c; b = x; }
  else if (hue < 240) { r = 0; g = x; b = c; }
  else if (hue < 300) { r = x; g = 0; b = c; }
  else                { r = c; g = 0; b = x; }
  return [Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255)];
}

// ─── Component ────────────────────────────────────────────────────────────────

export function ColorPanel({
  color, onChange, onCommit, recent,
  panelWidth = 168,
}: ColorPanelProps) {
  // HSV is the picker's working state — kept separate from the packed prop so
  // hue/saturation survive round-trips through grey/black/white (where they
  // collapse to 0 in RGB).
  const [hsv, setHsv] = useState<[number, number, number]>(() => rgbToHsv(...unpack(color)));
  const [hexText, setHexText] = useState(() => hex6(color));

  // Last packed colour this panel emitted — lets us tell external changes
  // (eyedropper, undo) apart from our own onChange echoing back as a prop.
  const emittedRef = useRef(color);

  useEffect(() => {
    if (color === emittedRef.current) return;
    emittedRef.current = color;
    const [nh, ns, nv] = rgbToHsv(...unpack(color));
    // Keep the previous hue (and saturation at v=0) when the incoming colour
    // has no chroma — otherwise the SV square snaps to red.
    setHsv(([ph, ps]) => [ns === 0 ? ph : nh, nv === 0 ? ps : ns, nv]);
    setHexText(hex6(color));
  }, [color]);

  const [hue, sat, val] = hsv;

  /** Apply a new HSV triple: update local state and emit the packed colour. */
  function applyHsv(nh: number, ns: number, nv: number): void {
    setHsv([nh, ns, nv]);
    const packed = pack(...hsvToRgb(nh, ns, nv));
    emittedRef.current = packed;
    setHexText(hex6(packed));
    onChange(packed);
  }

  /** Apply a packed colour directly (hex / numeric / recent-swatch input). */
  function applyPacked(packed: number, commit: boolean): void {
    emittedRef.current = packed;
    const [nh, ns, nv] = rgbToHsv(...unpack(packed));
    setHsv(([ph, ps]) => [ns === 0 ? ph : nh, nv === 0 ? ps : ns, nv]);
    setHexText(hex6(packed));
    onChange(packed);
    if (commit) onCommit(packed);
  }

  // ── SV square drag handling ─────────────────────────────────────────────────
  const svRef = useRef<HTMLDivElement>(null);
  const svDragging = useRef(false);

  function svPick(clientX: number, clientY: number): void {
    const el = svRef.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    const ns = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
    const nv = Math.max(0, Math.min(1, 1 - (clientY - rect.top) / rect.height));
    applyHsv(hue, ns, nv);
  }

  const [r, g, b] = unpack(color);
  const hueRgb  = hsvToRgb(hue, 1, 1);
  const hueCss  = `rgb(${hueRgb[0]},${hueRgb[1]},${hueRgb[2]})`;
  const colorCss = `rgb(${r},${g},${b})`;

  function commitHex(): void {
    const m = /^#?([0-9a-fA-F]{6})$/.exec(hexText.trim());
    if (m) applyPacked(parseInt(m[1], 16), true);
    else setHexText(hex6(color)); // invalid — revert
  }

  return (
    <div style={{ ...PANEL_STYLE, width: panelWidth, minWidth: Math.min(panelWidth, 80) }}>
      {/* Current colour swatch */}
      <div style={{ ...SWATCH_BIG, background: colorCss }} title={hex6(color)} />

      {/* SV square */}
      <div
        ref={svRef}
        onPointerDown={e => {
          e.preventDefault();
          (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
          svDragging.current = true;
          svPick(e.clientX, e.clientY);
        }}
        onPointerMove={e => { if (svDragging.current) svPick(e.clientX, e.clientY); }}
        onPointerUp={() => {
          if (!svDragging.current) return;
          svDragging.current = false;
          onCommit(emittedRef.current);
        }}
        style={{
          ...SV_SQUARE,
          // White→hue horizontally, transparent→black vertically = classic SV plane.
          background: `linear-gradient(to top, #000, rgba(0,0,0,0)), linear-gradient(to right, #fff, ${hueCss})`,
        }}
      >
        {/* SV marker */}
        <div style={{
          position: 'absolute',
          left: `calc(${(sat * 100).toFixed(1)}% - 5px)`,
          top:  `calc(${((1 - val) * 100).toFixed(1)}% - 5px)`,
          width: 10, height: 10, borderRadius: '50%',
          border: '2px solid #fff', boxShadow: '0 0 2px #000, inset 0 0 2px #000',
          pointerEvents: 'none', boxSizing: 'border-box',
        }} />
      </div>

      {/* Hue slider */}
      <input
        type="range" min={0} max={360} step={1} value={Math.round(hue)}
        onInput={e => applyHsv(+(e.target as HTMLInputElement).value, sat, val)}
        onChange={() => onCommit(emittedRef.current)}
        title="Hue"
        style={HUE_SLIDER}
      />

      {/* Hex input */}
      <div style={ROW}>
        <span style={FIELD_LABEL}>Hex</span>
        <input
          type="text" value={hexText}
          onInput={e => setHexText((e.target as HTMLInputElement).value)}
          onChange={commitHex}
          onKeyDown={e => { if (e.key === 'Enter') { commitHex(); (e.target as HTMLInputElement).blur(); } }}
          spellcheck={false}
          style={{ ...FIELD_INPUT, flex: 1 }}
        />
      </div>

      {/* R / G / B numeric inputs */}
      <div style={{ display: 'flex', gap: 3, marginBottom: 6 }}>
        {([['R', r, 16], ['G', g, 8], ['B', b, 0]] as [string, number, number][]).map(([label, chan, shift]) => (
          <div key={label} style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span style={{ ...FIELD_LABEL, textAlign: 'center' }}>{label}</span>
            <input
              type="number" min={0} max={255} step={1} value={chan}
              onInput={e => {
                const v = Math.max(0, Math.min(255, +(e.target as HTMLInputElement).value | 0));
                applyPacked((color & ~(0xff << shift)) | (v << shift), false);
              }}
              onChange={() => onCommit(emittedRef.current)}
              style={{ ...FIELD_INPUT, textAlign: 'center', width: '100%', boxSizing: 'border-box' }}
            />
          </div>
        ))}
      </div>

      {/* Recent colours */}
      <div style={MICRO_LABEL}>Recent</div>
      {recent.length > 0 ? (
        <div style={RECENT_GRID}>
          {recent.map(c => (
            <div
              key={c}
              onClick={() => applyPacked(c, true)}
              title={hex6(c)}
              style={{
                width: 22, height: 22,
                background: `#${c.toString(16).padStart(6, '0')}`,
                cursor: 'pointer',
                outline: c === color ? '2px solid #fff' : '1px solid #222',
                outlineOffset: c === color ? -2 : -1,
                boxSizing: 'border-box',
                borderRadius: 2,
              }}
            />
          ))}
        </div>
      ) : (
        <div style={{ fontSize: '0.68em', color: '#555', marginTop: 2 }}>
          Committed colours appear here
        </div>
      )}

      {/* Active colour info */}
      <div style={{ marginTop: 8, fontSize: '0.79em', color: '#aaa', textAlign: 'center' }}>
        {hex6(color)} · rgb({r},{g},{b})
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

const SWATCH_BIG: h.JSX.CSSProperties = {
  width:        '100%',
  height:        36,
  border:       '1px solid #444',
  borderRadius:  3,
  boxSizing:    'border-box',
  marginBottom:  6,
};

const SV_SQUARE: h.JSX.CSSProperties = {
  position:     'relative',
  width:        '100%',
  height:        128,
  borderRadius:  3,
  cursor:       'crosshair',
  touchAction:  'none',
  marginBottom:  6,
};

const HUE_SLIDER: h.JSX.CSSProperties = {
  width:        '100%',
  height:        14,
  marginBottom:  6,
  cursor:       'pointer',
  appearance:   'none',
  borderRadius:  3,
  background:   'linear-gradient(to right, #f00, #ff0, #0f0, #0ff, #00f, #f0f, #f00)',
};

const ROW: h.JSX.CSSProperties = {
  display:      'flex',
  alignItems:   'center',
  gap:           5,
  marginBottom:  5,
};

const FIELD_LABEL: h.JSX.CSSProperties = {
  fontSize: '0.71em',
  color:    '#888',
};

const FIELD_INPUT: h.JSX.CSSProperties = {
  background:   '#252525',
  border:       '1px solid #444',
  borderRadius:  3,
  color:        '#ccc',
  fontSize:     '0.82em',
  padding:      '3px 5px',
  fontFamily:   'monospace',
};

const MICRO_LABEL: h.JSX.CSSProperties = {
  fontSize:     '0.79em',
  color:        '#666',
  marginBottom:  3,
};

const RECENT_GRID: h.JSX.CSSProperties = {
  display:  'flex',
  flexWrap: 'wrap',
  gap:       2,
};
