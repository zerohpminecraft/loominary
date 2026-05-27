/**
 * EditHistory — undo/redo stack for the pixel editor.
 *
 * Port of MapEditorScreen.EditHistory.
 * Snapshots the full frames array (all tiles, all frames) as Uint8Array clones.
 * Max 20 levels.
 */

const MAX_HISTORY = 20;

export class EditHistory {
  private undoStack: Uint8Array[][][] = [];
  private redoStack: Uint8Array[][][] = [];

  /** Take a snapshot of the current frame data BEFORE modifying it. */
  snapshot(frames: Uint8Array[][]): void {
    if (this.undoStack.length >= MAX_HISTORY) {
      this.undoStack.shift(); // drop oldest
    }
    this.undoStack.push(cloneFrames(frames));
    this.redoStack = [];
  }

  /**
   * Undo: restore the previous snapshot.
   * Returns the restored frame data, or null if the stack is empty.
   * Caller is responsible for passing the current frames so redo works.
   */
  undo(current: Uint8Array[][]): Uint8Array[][] | null {
    if (this.undoStack.length === 0) return null;
    this.redoStack.push(cloneFrames(current));
    return cloneFrames(this.undoStack.pop()!);
  }

  /** Redo: reapply the undone change. Returns the restored frames or null. */
  redo(current: Uint8Array[][]): Uint8Array[][] | null {
    if (this.redoStack.length === 0) return null;
    this.undoStack.push(cloneFrames(current));
    return cloneFrames(this.redoStack.pop()!);
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
