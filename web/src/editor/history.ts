/**
 * EditHistory — undo/redo stack for the pixel editor.
 *
 * Port of MapEditorScreen.EditHistory.
 * Snapshots the full frames array (all tiles, all frames) as Uint8Array clones; in sRGB mode
 * the rgbFrames twin is snapshotted alongside so undo/redo restores both views atomically.
 * Max 20 levels.
 */

const MAX_HISTORY = 20;

export interface FramesSnapshot {
  frames: Uint8Array[][];
  rgbFrames: Uint8Array[][] | null;
}

export class EditHistory {
  private undoStack: FramesSnapshot[] = [];
  private redoStack: FramesSnapshot[] = [];

  /** Take a snapshot of the current frame data BEFORE modifying it. */
  snapshot(frames: Uint8Array[][], rgbFrames?: Uint8Array[][] | null): void {
    if (this.undoStack.length >= MAX_HISTORY) {
      this.undoStack.shift(); // drop oldest
    }
    this.undoStack.push(snap(frames, rgbFrames ?? null));
    this.redoStack = [];
  }

  /**
   * Undo: restore the previous snapshot.
   * Returns the restored frame data, or null if the stack is empty.
   * Caller is responsible for passing the current frames so redo works.
   */
  undo(current: Uint8Array[][], currentRgb?: Uint8Array[][] | null): FramesSnapshot | null {
    if (this.undoStack.length === 0) return null;
    this.redoStack.push(snap(current, currentRgb ?? null));
    const restored = this.undoStack.pop()!;
    return snap(restored.frames, restored.rgbFrames);
  }

  /** Redo: reapply the undone change. Returns the restored frames or null. */
  redo(current: Uint8Array[][], currentRgb?: Uint8Array[][] | null): FramesSnapshot | null {
    if (this.redoStack.length === 0) return null;
    this.undoStack.push(snap(current, currentRgb ?? null));
    const restored = this.redoStack.pop()!;
    return snap(restored.frames, restored.rgbFrames);
  }

  get canUndo(): boolean { return this.undoStack.length > 0; }
  get canRedo(): boolean { return this.redoStack.length > 0; }

  /** Clear all history (e.g. on tile switch or full state reload). */
  clear(): void {
    this.undoStack = [];
    this.redoStack = [];
  }
}

function cloneFrames(frames: Uint8Array[][]): Uint8Array[][] {
  return frames.map(tile => tile.map(frame => frame.slice()));
}

function snap(frames: Uint8Array[][], rgbFrames: Uint8Array[][] | null): FramesSnapshot {
  return { frames: cloneFrames(frames), rgbFrames: rgbFrames ? cloneFrames(rgbFrames) : null };
}
