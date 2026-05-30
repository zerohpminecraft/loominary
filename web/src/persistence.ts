/**
 * Session persistence — saves and restores compositions to IndexedDB.
 *
 * DB schema (version 3):
 *   sessions       — session metadata + pixel data (no source image)
 *   session_images — source image buffers, keyed by session id
 *
 * Keeping images in a separate store means loadSessionMetas() and
 * loadMostRecentSession() never pull large image ArrayBuffers into memory —
 * they only load the images store when a specific session is being restored.
 * updateSession() (auto-save, every 3 s) never writes to session_images at
 * all since the source image doesn't change between saves.
 */

import type { CompositionState } from './payload-state.js';
import type { RequantizeParams }  from './quantize.js';
import type { PreprocessParams }  from './preprocess.js';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface SavedSession {
  formatVersion: 3;
  id:             string;
  savedAt:        number;

  gridCols:       number;
  gridRows:       number;
  frameDelays:    number[][];
  activeFrame:    number;
  sourceFilename: string | null;
  title:          string | null;
  author:         string | null;
  allShades:      boolean;
  codecMode:      string;
  frameCounts:    number[];
  pixelData:      ArrayBuffer;
  cropMode:       'scale' | 'center';
  pre:            PreprocessParams;
  reqParams:      RequantizeParams | null;
  thumbnailData:  Uint8Array | null;

  // Populated by loadSessionById / loadMostRecentSession from the
  // session_images store — NOT stored inline in the sessions store.
  sourceImageBuffer: ArrayBuffer | null;
  sourceImageMime:   string | null;
}

export interface SessionMeta {
  id:             string;
  savedAt:        number;
  sourceFilename: string | null;
  title:          string | null;
  gridCols:       number;
  gridRows:       number;
  totalTiles:     number;
  totalFrames:    number;
  thumbnailData:  Uint8Array | null;
}

export interface SourceImage {
  buffer: ArrayBuffer;
  mime:   string;
}

// ─── IndexedDB helpers ────────────────────────────────────────────────────────

const DB_NAME    = 'loominary';
const DB_VERSION = 3;
const S_META     = 'sessions';       // metadata + pixelData (no source image)
const S_IMG      = 'session_images'; // source image buffers only
const MAX_HISTORY = 30;
const MAX_SOURCE_IMAGE_BYTES = 20 * 1024 * 1024; // 20 MB

// Row stored in S_META (no sourceImageBuffer)
interface SessionRow extends Omit<SavedSession, 'sourceImageBuffer' | 'sourceImageMime'> {}

// Row stored in S_IMG
interface ImageRow { id: string; buffer: ArrayBuffer; mime: string }

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = (e) => {
      const db  = req.result;
      const old = e.oldVersion;

      // v1 → v2: replaced single-key 'session' store with 'sessions'
      if (old < 2 && db.objectStoreNames.contains('session')) {
        db.deleteObjectStore('session');
      }
      if (!db.objectStoreNames.contains(S_META)) {
        db.createObjectStore(S_META, { keyPath: 'id' });
      }

      // v2 → v3: split source images into a separate store so getAll()
      // on the sessions store never loads large ArrayBuffers.
      if (!db.objectStoreNames.contains(S_IMG)) {
        db.createObjectStore(S_IMG, { keyPath: 'id' });
      }
      // Migrate all existing sessions in S_META:
      //   • bump formatVersion to 3
      //   • move any inline sourceImageBuffer to S_IMG
      // This runs whenever upgrading from any version that already has S_META.
      if (old >= 2) {
        const txn       = req.transaction!;
        const metaStore = txn.objectStore(S_META);
        const imgStore  = txn.objectStore(S_IMG);
        const cursor    = metaStore.openCursor();
        cursor.onsuccess = () => {
          const c = cursor.result;
          if (!c) return;
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          const row: any = c.value;
          if (row.sourceImageBuffer) {
            imgStore.put({ id: row.id, buffer: row.sourceImageBuffer, mime: row.sourceImageMime ?? '' });
          }
          // Always bump to v3 so the session is visible after the upgrade.
          c.update({
            ...row,
            formatVersion: 3,
            sourceImageBuffer: undefined,
            sourceImageMime:   undefined,
          });
          c.continue();
        };
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror   = () => reject(req.error);
  });
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function putSession(db: IDBDatabase, row: SessionRow, img: SourceImage | null): Promise<void> {
  return new Promise((res, rej) => {
    const stores: string[] = img ? [S_META, S_IMG] : [S_META];
    const tx = db.transaction(stores, 'readwrite');
    tx.objectStore(S_META).put(row);
    if (img) tx.objectStore(S_IMG).put({ id: row.id, buffer: img.buffer, mime: img.mime } as ImageRow);
    tx.oncomplete = () => res();
    tx.onerror    = () => rej(tx.error);
  });
}

function getAllMeta(db: IDBDatabase): Promise<SessionRow[]> {
  return new Promise((res, rej) => {
    const tx  = db.transaction(S_META, 'readonly');
    const req = tx.objectStore(S_META).getAll();
    req.onsuccess = () => res(req.result as SessionRow[]);
    req.onerror   = () => rej(req.error);
  });
}

function getOne(db: IDBDatabase, id: string): Promise<SessionRow | null> {
  return new Promise((res, rej) => {
    const tx  = db.transaction(S_META, 'readonly');
    const req = tx.objectStore(S_META).get(id);
    req.onsuccess = () => res((req.result as SessionRow) ?? null);
    req.onerror   = () => rej(req.error);
  });
}

function getImage(db: IDBDatabase, id: string): Promise<ImageRow | null> {
  return new Promise((res, rej) => {
    const tx  = db.transaction(S_IMG, 'readonly');
    const req = tx.objectStore(S_IMG).get(id);
    req.onsuccess = () => res((req.result as ImageRow) ?? null);
    req.onerror   = () => rej(req.error);
  });
}

function deleteOne(db: IDBDatabase, id: string): Promise<void> {
  return new Promise((res, rej) => {
    const tx = db.transaction([S_META, S_IMG], 'readwrite');
    tx.objectStore(S_META).delete(id);
    tx.objectStore(S_IMG).delete(id);
    tx.oncomplete = () => res();
    tx.onerror    = () => rej(tx.error);
  });
}

function clearAll(db: IDBDatabase): Promise<void> {
  return new Promise((res, rej) => {
    const tx = db.transaction([S_META, S_IMG], 'readwrite');
    tx.objectStore(S_META).clear();
    tx.objectStore(S_IMG).clear();
    tx.oncomplete = () => res();
    tx.onerror    = () => rej(tx.error);
  });
}

function mergeRow(row: SessionRow, img: ImageRow | null): SavedSession {
  return {
    ...row,
    sourceImageBuffer: img?.buffer ?? null,
    sourceImageMime:   img?.mime   ?? null,
  } as SavedSession;
}

// ─── Frame packing / unpacking ────────────────────────────────────────────────

const FRAME_BYTES = 128 * 128; // 16 384

function packFrames(frames: Uint8Array[][]): { pixelData: ArrayBuffer; frameCounts: number[] } {
  const frameCounts  = frames.map(tf => tf.length);
  const totalFrames  = frameCounts.reduce((s, c) => s + c, 0);
  const buf          = new ArrayBuffer(totalFrames * FRAME_BYTES);
  const dest         = new Uint8Array(buf);
  let   offset       = 0;
  for (const tileFrames of frames) {
    for (const frame of tileFrames) {
      dest.set(frame.length === FRAME_BYTES ? frame : frame.subarray(0, FRAME_BYTES), offset);
      offset += FRAME_BYTES;
    }
  }
  return { pixelData: buf, frameCounts };
}

function unpackFrames(pixelData: ArrayBuffer, frameCounts: number[]): Uint8Array[][] {
  const src    = new Uint8Array(pixelData);
  const frames: Uint8Array[][] = [];
  let   offset = 0;
  for (const count of frameCounts) {
    const tileFrames: Uint8Array[] = [];
    for (let f = 0; f < count; f++) {
      tileFrames.push(src.slice(offset, offset + FRAME_BYTES) as Uint8Array);
      offset += FRAME_BYTES;
    }
    frames.push(tileFrames);
  }
  return frames;
}

// ─── ID / row builder ─────────────────────────────────────────────────────────

function generateId(): string {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
}

function buildRow(
  id:        string,
  comp:      CompositionState,
  cropMode:  'scale' | 'center',
  pre:       PreprocessParams,
  reqParams: RequantizeParams | null,
): SessionRow {
  const { pixelData, frameCounts } = packFrames(comp.frames);
  const rawThumb = comp.frames[0]?.[0];
  return {
    formatVersion: 3,
    id,
    savedAt:        Date.now(),
    gridCols:       comp.gridCols,
    gridRows:       comp.gridRows,
    frameDelays:    comp.frameDelays,
    activeFrame:    comp.activeFrame,
    sourceFilename: comp.sourceFilename,
    title:          comp.title,
    author:         comp.author,
    allShades:      comp.allShades,
    codecMode:      comp.codecMode,
    frameCounts,
    pixelData,
    cropMode,
    pre,
    reqParams,
    thumbnailData: rawThumb ? new Uint8Array(rawThumb) : null,
  };
}

// ─── Public API ───────────────────────────────────────────────────────────────

/**
 * Create a new history entry.  Source image is written to session_images
 * in the same transaction so it never clutters the sessions store.
 * Trims oldest entries when MAX_HISTORY is exceeded.
 */
export async function saveNewSession(
  comp:        CompositionState,
  cropMode:    'scale' | 'center',
  pre:         PreprocessParams,
  reqParams:   RequantizeParams | null,
  sourceImage: SourceImage | null = null,
): Promise<string> {
  const id  = generateId();
  const img = sourceImage && sourceImage.buffer.byteLength <= MAX_SOURCE_IMAGE_BYTES
    ? sourceImage : null;
  try {
    const db  = await openDb();
    const row = buildRow(id, comp, cropMode, pre, reqParams);
    await putSession(db, row, img);

    // Trim oldest entries.
    const all = await getAllMeta(db);
    if (all.length > MAX_HISTORY) {
      const sorted = [...all].sort((a, b) => a.savedAt - b.savedAt);
      for (let i = 0; i < all.length - MAX_HISTORY; i++) {
        await deleteOne(db, sorted[i].id);
      }
    }
  } catch { /* storage failure never interrupts editing */ }
  return id;
}

/**
 * Update an existing session in-place (auto-save).
 * Writes sourceImage to session_images when provided (e.g. after a re-link,
 * or when saveNewSession couldn't write it yet).  Pass null to leave the
 * stored image unchanged.
 */
export async function updateSession(
  id:          string,
  comp:        CompositionState,
  cropMode:    'scale' | 'center',
  pre:         PreprocessParams,
  reqParams:   RequantizeParams | null,
  sourceImage: SourceImage | null = null,
): Promise<void> {
  try {
    const db  = await openDb();
    const row = buildRow(id, comp, cropMode, pre, reqParams);
    const img = sourceImage && sourceImage.buffer.byteLength <= MAX_SOURCE_IMAGE_BYTES
      ? sourceImage : null;
    await putSession(db, row, img);
  } catch { /* ignore */ }
}

/** Load the full session by ID (includes source image), or null if not found. */
export async function loadSessionById(id: string): Promise<SavedSession | null> {
  try {
    const db  = await openDb();
    const row = await getOne(db, id);
    if (!row || (row.formatVersion !== 3 && row.formatVersion !== 2)) return null;
    const img = await getImage(db, id);
    return mergeRow(row, img);
  } catch { return null; }
}

/**
 * Load the most recently saved session (includes source image).
 * Only the winning session's image is loaded — all others are ignored.
 */
export async function loadMostRecentSession(): Promise<SavedSession | null> {
  try {
    const db   = await openDb();
    const all  = await getAllMeta(db);
    const valid = all.filter(s => s.formatVersion === 3 || s.formatVersion === 2);
    if (!valid.length) return null;
    const row = valid.sort((a, b) => b.savedAt - a.savedAt)[0];
    const img = await getImage(db, row.id);
    return mergeRow(row, img);
  } catch { return null; }
}

/**
 * Load lightweight metadata for all saved sessions, newest first.
 * Never loads pixelData or source image buffers — safe and fast even for
 * large animated compositions.
 */
export async function loadSessionMetas(): Promise<SessionMeta[]> {
  try {
    const db    = await openDb();
    const all   = await getAllMeta(db);
    const valid = all.filter(s => s.formatVersion === 3 || s.formatVersion === 2);
    return valid
      .sort((a, b) => b.savedAt - a.savedAt)
      .map(s => ({
        id:             s.id,
        savedAt:        s.savedAt,
        sourceFilename: s.sourceFilename,
        title:          s.title,
        gridCols:       s.gridCols,
        gridRows:       s.gridRows,
        totalTiles:     s.gridCols * s.gridRows,
        totalFrames:    s.frameCounts.reduce((a, b) => a + b, 0),
        thumbnailData:  s.thumbnailData,
      }));
  } catch { return []; }
}

/** Delete one session (metadata + image) from history. */
export async function deleteSession(id: string): Promise<void> {
  try { await deleteOne(await openDb(), id); } catch { /* ignore */ }
}

/** Delete all sessions. */
export async function clearAllSessions(): Promise<void> {
  try { await clearAll(await openDb()); } catch { /* ignore */ }
}

// ─── Deserialization ──────────────────────────────────────────────────────────

export function sessionToComposition(s: SavedSession): CompositionState {
  return {
    gridCols:       s.gridCols,
    gridRows:       s.gridRows,
    frames:         unpackFrames(s.pixelData, s.frameCounts),
    frameDelays:    s.frameDelays,
    activeFrame:    s.activeFrame,
    sourceFilename: s.sourceFilename,
    title:          s.title,
    author:         s.author,
    allShades:      s.allShades,
    codecMode:      s.codecMode as import('./payload-state.js').CompositionState['codecMode'],
  };
}

export function savedAgoLabel(savedAt: number): string {
  const s = Math.round((Date.now() - savedAt) / 1000);
  if (s < 60)    return 'just now';
  if (s < 3600)  return Math.floor(s / 60)    + ' min ago';
  if (s < 86400) return Math.floor(s / 3600)  + ' hr ago';
  return Math.floor(s / 86400) + ' days ago';
}
