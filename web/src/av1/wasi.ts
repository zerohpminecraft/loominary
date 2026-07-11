/**
 * Minimal WASI preview1 import shim for the AV1 codec wasm in the BROWSER.
 *
 * av1-encode.wasm / av1-decode.wasm are pure-compute reactor modules; they only import a
 * handful of WASI calls (stdio + a few fd/path stubs libc references) and never touch the
 * filesystem on the happy path.  So most calls just return an errno; only fd_write (stderr
 * diagnostics) and proc_exit (abort) do anything.  Works in Node too, so the same client
 * code is testable without node:wasi.
 */

const ERRNO_SUCCESS = 0;
const ERRNO_BADF    = 8;

export interface WasiShim {
  imports: WebAssembly.Imports;
  /** Called by the loader once the instance exists, so fd_write can read linear memory. */
  bind(memory: WebAssembly.Memory): void;
}

export function createWasiShim(): WasiShim {
  let mem: WebAssembly.Memory | null = null;
  const dv = () => new DataView(mem!.buffer);

  const fd_write = (fd: number, iovsPtr: number, iovsLen: number, nwrittenPtr: number): number => {
    const view = dv();
    let total = 0;
    let text = '';
    for (let i = 0; i < iovsLen; i++) {
      const p   = view.getUint32(iovsPtr + i * 8, true);
      const len = view.getUint32(iovsPtr + i * 8 + 4, true);
      total += len;
      if (fd === 1 || fd === 2) {
        text += new TextDecoder().decode(new Uint8Array(mem!.buffer, p, len));
      }
    }
    view.setUint32(nwrittenPtr, total, true);
    if (text && (fd === 1 || fd === 2)) {
      // libaom is silent in normal operation; surface anything it does print.
      (fd === 2 ? console.warn : console.log)('[av1]', text.replace(/\n$/, ''));
    }
    return ERRNO_SUCCESS;
  };

  const badf = () => ERRNO_BADF;
  const noop = () => ERRNO_SUCCESS;

  const wasi_snapshot_preview1: Record<string, (...a: number[]) => number> = {
    fd_write,
    fd_close: noop,
    fd_seek: badf,
    fd_read: badf,
    fd_fdstat_get: badf,
    fd_fdstat_set_flags: badf,
    fd_prestat_get: badf,
    fd_prestat_dir_name: badf,
    path_open: badf,
    proc_exit: (code: number) => { throw new Error(`av1 wasm called proc_exit(${code})`); },
  };

  return {
    imports: { wasi_snapshot_preview1 },
    bind(memory) { mem = memory; },
  };
}
