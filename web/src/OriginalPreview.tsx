/**
 * OriginalPreviewPanel — collapsible sidebar showing the unprocessed source
 * image with pan-and-zoom.
 *
 * The key design constraint: the wheel event listener and ResizeObserver are
 * attached once (to avoid losing non-passive registration) but must always
 * access the current bitmap.  We solve this by storing bitmap in a ref that
 * is kept synchronised on every render, so the stable callbacks always see
 * the latest value — no stale-closure disappearing act.
 */

import { h } from 'preact';
import { useRef, useCallback, useEffect, useState } from 'preact/hooks';

// ─── Props ────────────────────────────────────────────────────────────────────

export interface OriginalPreviewPanelProps {
  bitmap:    ImageBitmap | null;
  open:      boolean;
  onClose:   () => void;
  onOpen:    () => void;
  onRelink?: () => void;
}

// ─── OriginalPreviewPanel ─────────────────────────────────────────────────────

export function OriginalPreviewPanel({
  bitmap, open, onClose, onOpen, onRelink,
}: OriginalPreviewPanelProps) {
  const [panelW, setPanelW] = useState(220);

  // ── Refs ──────────────────────────────────────────────────────────────────
  const canvasRef  = useRef<HTMLCanvasElement>(null);
  const bitmapRef  = useRef<ImageBitmap | null>(null); // always-current bitmap
  const zoomRef    = useRef(1);
  const panXRef    = useRef(0);
  const panYRef    = useRef(0);
  const isPanRef   = useRef(false);
  const lastPanRef = useRef({ x: 0, y: 0 });
  const needsFitRef    = useRef(true);  // set when a fit is needed
  const prevDimsRef    = useRef<{ w: number; h: number } | null>(null);

  // Keep bitmapRef synchronised every render — this is what makes the stable
  // callbacks (wheel, ResizeObserver) always see the current bitmap.
  bitmapRef.current = bitmap;

  // ── Core draw — reads all state from refs, never captures prop values ─────
  // Declared with no deps so it is a stable reference and can safely be used
  // inside a once-registered event listener.
  const draw = useCallback(() => {
    const canvas = canvasRef.current;
    const bmp    = bitmapRef.current;
    if (!canvas) return;
    const dpr = window.devicePixelRatio || 1;
    const cw  = canvas.clientWidth;
    const ch  = canvas.clientHeight;
    if (!cw || !ch) return;
    const pw = Math.round(cw * dpr);
    const ph = Math.round(ch * dpr);
    if (canvas.width !== pw || canvas.height !== ph) {
      canvas.width  = pw;
      canvas.height = ph;
    }
    const ctx = canvas.getContext('2d')!;
    ctx.fillStyle = '#111111';
    ctx.fillRect(0, 0, pw, ph);
    if (!bmp) return;
    ctx.imageSmoothingEnabled = zoomRef.current < 2;
    ctx.drawImage(
      bmp,
      Math.round(panXRef.current * dpr),
      Math.round(panYRef.current * dpr),
      Math.round(bmp.width  * zoomRef.current * dpr),
      Math.round(bmp.height * zoomRef.current * dpr),
    );
  }, []); // no deps — all values read from refs

  // ── Fit-to-view ───────────────────────────────────────────────────────────
  // Reads panelW from its own closure (acceptable: we only call this explicitly
  // or via needsFitRef, so it always gets the current panelW from the render).
  const fitToView = useCallback(() => {
    const canvas = canvasRef.current;
    const bmp    = bitmapRef.current;
    if (!canvas || !bmp) return;
    const cw = canvas.clientWidth  || panelW;
    const ch = canvas.clientHeight || 300;
    const z  = Math.min(cw / bmp.width, ch / bmp.height) * 0.92;
    zoomRef.current = Math.max(0.05, Math.min(32, z));
    panXRef.current = (cw - bmp.width  * zoomRef.current) / 2;
    panYRef.current = (ch - bmp.height * zoomRef.current) / 2;
    draw();
  }, [draw, panelW]);

  // ── Trigger a re-fit when the panel opens or the image itself changes ─────
  // Compare by dimensions rather than object identity so animation frames
  // (same-size bitmaps, different objects) don't reset the user's zoom/pan
  // on every tick.
  useEffect(() => {
    if (!open) return;
    needsFitRef.current = true; // always fit when panel first opens/re-opens
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  useEffect(() => {
    if (!open || !bitmap) return;
    const w = bitmap.width, h = bitmap.height;
    const prev = prevDimsRef.current;
    if (!prev || prev.w !== w || prev.h !== h) {
      prevDimsRef.current  = { w, h };
      needsFitRef.current  = true;
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bitmap]);

  // After every render, check if a fit is needed (runs after DOM is updated).
  // No deps — intentional: we want this to fire after every render until the
  // fit actually succeeds (canvas may have zero size on first mount attempt).
  useEffect(() => {
    if (!open || !bitmap || !needsFitRef.current) return;
    const raf = requestAnimationFrame(() => {
      const canvas = canvasRef.current;
      if (!canvas || !canvas.clientWidth) return; // not laid out yet — retry
      fitToView();
      needsFitRef.current = false;
    });
    return () => cancelAnimationFrame(raf);
  });

  // ── Register non-passive wheel listener + ResizeObserver — once only ──────
  // Both use `draw` (stable) which reads bitmap via `bitmapRef` — no staleness.
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const oldZ = zoomRef.current;
      const newZ = Math.max(0.05, Math.min(32, oldZ * (e.deltaY < 0 ? 1.25 : 0.8)));
      if (newZ === oldZ) return;
      panXRef.current = e.offsetX - (e.offsetX - panXRef.current) * newZ / oldZ;
      panYRef.current = e.offsetY - (e.offsetY - panYRef.current) * newZ / oldZ;
      zoomRef.current = newZ;
      draw();
    };
    canvas.addEventListener('wheel', onWheel, { passive: false });

    const ro = new ResizeObserver(() => draw());
    ro.observe(canvas);

    return () => {
      canvas.removeEventListener('wheel', onWheel);
      ro.disconnect();
    };
  }, [draw]); // draw is stable — this runs exactly once

  // ── Pointer pan (JSX handlers — Preact keeps these fresh automatically) ───
  const onPointerDown = useCallback((e: PointerEvent) => {
    if (!bitmapRef.current) return;
    isPanRef.current   = true;
    lastPanRef.current = { x: e.clientX, y: e.clientY };
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
    (e.target as HTMLElement).style.cursor = 'grabbing';
  }, []);

  const onPointerMove = useCallback((e: PointerEvent) => {
    if (!isPanRef.current) return;
    panXRef.current   += e.clientX - lastPanRef.current.x;
    panYRef.current   += e.clientY - lastPanRef.current.y;
    lastPanRef.current = { x: e.clientX, y: e.clientY };
    draw();
  }, [draw]);

  const onPointerUp = useCallback((e: PointerEvent) => {
    isPanRef.current = false;
    (e.target as HTMLElement).style.cursor = bitmapRef.current ? 'grab' : 'default';
  }, []);

  // ── Resize handle drag ────────────────────────────────────────────────────
  const onResizeMouseDown = useCallback((e: MouseEvent) => {
    e.preventDefault();
    let lastX = e.clientX;
    const onMove = (ev: MouseEvent) => {
      const dx = ev.clientX - lastX;
      lastX    = ev.clientX;
      setPanelW(w => Math.max(120, Math.min(600, w + dx)));
    };
    const onUp = () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup',   onUp);
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup',   onUp);
  }, []);

  // ── Collapsed strip ───────────────────────────────────────────────────────
  if (!open) {
    return (
      <div
        onClick={onOpen}
        title="Show original image"
        style={{
          width: 16, flexShrink: 0,
          background: '#181818', borderLeft: '1px solid #2a2a2a',
          cursor: 'pointer',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}
      >
        <span style={{ fontSize: 10, color: '#444', writingMode: 'vertical-rl', userSelect: 'none' }}>
          Original
        </span>
      </div>
    );
  }

  // ── Expanded panel ────────────────────────────────────────────────────────
  return (
    <>
      <div style={{
        width: panelW, flexShrink: 0,
        background: '#161616', borderLeft: '1px solid #2a2a2a',
        display: 'flex', flexDirection: 'column', overflow: 'hidden',
      }}>
        {/* Header */}
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '3px 6px 3px 8px',
          borderBottom: '1px solid #2a2a2a', flexShrink: 0,
        }}>
          <span style={{ fontSize: 11, color: '#666', fontWeight: 'bold', userSelect: 'none' }}>
            Original
          </span>
          <div style={{ display: 'flex', gap: 3 }}>
            <button onClick={fitToView} title="Fit to view" style={HDR_BTN}>⊡</button>
            <button onClick={onClose}   title="Collapse"    style={HDR_BTN}>×</button>
          </div>
        </div>

        {/* Canvas area */}
        <div style={{ flex: 1, position: 'relative', overflow: 'hidden' }}>
          <canvas
            ref={canvasRef}
            style={{
              display: 'block', width: '100%', height: '100%',
              cursor: bitmap ? 'grab' : 'default',
            }}
            onPointerDown={onPointerDown as unknown as h.JSX.MouseEventHandler<HTMLCanvasElement>}
            onPointerMove={onPointerMove as unknown as h.JSX.MouseEventHandler<HTMLCanvasElement>}
            onPointerUp={onPointerUp     as unknown as h.JSX.MouseEventHandler<HTMLCanvasElement>}
          />

          {/* Empty state */}
          {!bitmap && (
            <div style={{
              position: 'absolute', inset: 0,
              display: 'flex', flexDirection: 'column',
              alignItems: 'center', justifyContent: 'center',
              pointerEvents: 'none', gap: 8,
            }}>
              {onRelink ? (
                <button
                  onClick={onRelink}
                  style={{
                    pointerEvents: 'all',
                    background: 'none', border: '1px solid #333', borderRadius: 3,
                    color: '#666', cursor: 'pointer', fontSize: 11, padding: '3px 8px',
                  }}
                >Re-link source…</button>
              ) : (
                <span style={{ fontSize: 11, color: '#333' }}>No source image</span>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Resize handle */}
      <div
        onMouseDown={onResizeMouseDown}
        style={{
          width: 5, flexShrink: 0, cursor: 'ew-resize',
          background: '#252525',
          borderLeft: '1px solid #333', borderRight: '1px solid #333',
          transition: 'background 0.1s', userSelect: 'none',
        }}
        onMouseEnter={e => { (e.target as HTMLElement).style.background = '#4a9eff55'; }}
        onMouseLeave={e => { (e.target as HTMLElement).style.background = '#252525'; }}
      />
    </>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const HDR_BTN: h.JSX.CSSProperties = {
  background: 'none', border: 'none', color: '#444',
  cursor: 'pointer', fontSize: 13, lineHeight: 1,
  padding: '0 3px',
};
