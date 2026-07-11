# AV1 codec wasm (`av1-encode.wasm` / `av1-decode.wasm`)

Lossless (and preview) AV1 codec used by Loominary's animated-map format. Built from
[libaom](https://aomedia.googlesource.com/aom) + `shim.c` with **wasi-sdk**, producing
standalone WASI *reactor* modules:

| Artifact | Where it ships | Used by |
|---|---|---|
| `av1-decode.wasm` (~1.2 MB) | `web/public/av1/` **and** `src/main/resources/av1/` | web editor decode **and** the mod (via Chicory) — one shared binary |
| `av1-encode.wasm` (~3.2 MB) | `web/public/av1/` only | web editor (payload encode + MP4 preview) |

The prebuilt binaries are committed to the repo (like the generated palette tables), so a
normal `./gradlew build` / `vite build` needs no toolchain. Only rebuild when `shim.c` or
libaom changes.

## Rebuild

```bash
# one-time toolchain (see values below), then:
WASI_SDK_PATH=~/opt/wasi-sdk LIBAOM_SRC=~/opt/libaom ./native/av1/build.sh
```

Prereqs:
- **wasi-sdk** ≥ 33 extracted somewhere (`WASI_SDK_PATH`). Download the
  `wasi-sdk-NN.0-x86_64-linux.tar.gz` release asset from
  <https://github.com/WebAssembly/wasi-sdk/releases>.
- **libaom** checkout (`LIBAOM_SRC`): `git clone --depth 1 https://aomedia.googlesource.com/aom`
- `cmake`, `make`.

`build.sh` builds libaom as a static wasm lib, compiles `shim.c`, links two reactor modules
(exports GC'd per module so the decoder stays small), and copies them into the repo.

## Two non-obvious build flags (do not remove)

Both are required for the decoder to run **inside the JVM via Chicory**:

1. **`-mllvm -wasm-enable-sjlj -mllvm -wasm-use-legacy-eh=false`** — libaom uses
   `setjmp`/`longjmp` for error handling. On wasm that lowers to exception-handling opcodes.
   The *legacy* `try`/`catch` (0x06/0x07) is rejected by Chicory; the standardized
   `try_table` (0x1f) is accepted. `libsetjmp.a` (`-lsetjmp`) provides the SjLj runtime so the
   module is self-contained (no `env` imports).
2. **no `-msimd128`** — Chicory's interpreter does not implement wasm SIMD (`v128.*`). Frames
   are 128×128, so the generic (non-SIMD) path is more than fast enough. V8 (browser/node) runs
   the same binary fine.

## ABI (`shim.c`)

```
void* shim_malloc(int n);  void shim_free(void* p);
int   enc_init(int w,int h,int frame_count,int mode); // mode 0=mono+lossless (payload), 1=4:2:0+CQ (preview)
int   enc_frame(const uint8_t* plane,int len);        // mono: Y (w*h); color: packed RGB (w*h*3)
int   enc_finish(void);                               // flush; returns encoded length
const uint8_t* enc_data(void);                         // pointer to encoded stream; valid until enc_reset
void  enc_reset(void);
int   dec_open(void);                                  // stateful across TUs (inter-frame refs)
int   dec_tu(const uint8_t* obu,int len,uint8_t* out,int out_cap); // one TU -> Y plane; returns w*h
void  dec_close(void);
```

Bitstream: `frameCount` length-prefixed temporal units, each `[u32 LE len][TU bytes]`; the
sequence-header OBU rides in TU[0]. Callers apply the OKLab palette permutation (see
`web/src/palette-perm.ts` / `PalettePermutation.java`) around this — the shim only moves bytes.
