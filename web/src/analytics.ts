// Umami analytics — privacy-friendly, cookieless, lightweight.
//
// Configured via Vite env vars (these are PUBLIC — they appear in the deployed
// page source — so they are not secrets and can live in repo Variables):
//   VITE_UMAMI_SRC        — the Umami tracker script URL
//                           (e.g. https://cloud.umami.is/script.js, or your self-host)
//   VITE_UMAMI_WEBSITE_ID — the website's UUID from the Umami dashboard
//
// When either is unset (local dev, or before the account exists) everything
// here is a safe no-op, so analytics can never break the editor.

const SRC = import.meta.env.VITE_UMAMI_SRC as string | undefined;
const WEBSITE_ID = import.meta.env.VITE_UMAMI_WEBSITE_ID as string | undefined;

let initialized = false;

/** Injects the Umami tracker script. Call once at app start. */
export function initAnalytics(): void {
  if (initialized || !SRC || !WEBSITE_ID) return;
  initialized = true;
  const s = document.createElement('script');
  s.async = true;
  s.defer = true;
  s.src = SRC;
  s.setAttribute('data-website-id', WEBSITE_ID);
  document.head.appendChild(s);
}

type EventProps = Record<string, string | number | boolean>;

/**
 * Records a custom event. No-ops silently if Umami isn't loaded/configured, and
 * never throws — analytics must not affect the app.
 */
export function track(event: string, props?: EventProps): void {
  try {
    const umami = (window as unknown as { umami?: { track: (e: string, p?: EventProps) => void } }).umami;
    if (umami && typeof umami.track === 'function') {
      if (props) umami.track(event, props);
      else umami.track(event);
    }
  } catch {
    /* ignore — analytics is best-effort */
  }
}
