#!/usr/bin/env bash
# Build the Loominary AV1 codec wasm binaries from shim.c + libaom, using wasi-sdk.
#
#   av1-decode.wasm  -> web/public/av1/ AND src/main/resources/av1/  (shared decoder)
#   av1-encode.wasm  -> web/public/av1/                              (web-only encoder)
#
# Both are standalone WASI "reactor" modules (callable exports, no _start), so the
# decoder runs in Chicory (JVM) and in the browser, and the encoder runs in the browser.
#
# Prereqs (see native/av1/README.md):
#   WASI_SDK_PATH  = path to an extracted wasi-sdk (default: ~/opt/wasi-sdk)
#   LIBAOM_SRC     = path to a libaom checkout   (default: ~/opt/libaom)
set -euo pipefail

WASI_SDK_PATH="${WASI_SDK_PATH:-$HOME/opt/wasi-sdk}"
LIBAOM_SRC="${LIBAOM_SRC:-$HOME/opt/libaom}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
BUILD="${BUILD_DIR:-$HOME/opt/libaom-wasm-build}"

CLANG="$WASI_SDK_PATH/bin/clang"
SYSROOT="$WASI_SDK_PATH/share/wasi-sysroot"
TOOLCHAIN="$WASI_SDK_PATH/share/cmake/wasi-sdk.cmake"

echo ">> wasi-sdk: $WASI_SDK_PATH"
echo ">> libaom:   $LIBAOM_SRC"
echo ">> build:    $BUILD"

# ── 1. Build libaom (encoder + decoder) as a static wasm library ─────────────
mkdir -p "$BUILD"
cmake -S "$LIBAOM_SRC" -B "$BUILD" -G "Unix Makefiles" \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DCMAKE_BUILD_TYPE=Release \
  -DAOM_TARGET_CPU=generic \
  -DCONFIG_MULTITHREAD=0 \
  -DCONFIG_RUNTIME_CPU_DETECT=0 \
  -DCONFIG_AV1_HIGHBITDEPTH=0 \
  -DCONFIG_WEBM_IO=0 \
  -DCONFIG_LIBYUV=0 \
  -DENABLE_EXAMPLES=0 \
  -DENABLE_TOOLS=0 \
  -DENABLE_TESTS=0 \
  -DENABLE_DOCS=0 \
  -DENABLE_TESTDATA=0 \
  -DCMAKE_C_FLAGS="-Oz -mllvm -wasm-enable-sjlj -mllvm -wasm-use-legacy-eh=false -ffunction-sections -fdata-sections"
cmake --build "$BUILD" --target aom -j"$(nproc)"

AOM_A="$BUILD/libaom.a"
[ -f "$AOM_A" ] || { echo "ERROR: $AOM_A not found"; exit 1; }

# ── 2. Compile the shim ──────────────────────────────────────────────────────
COMMON_CFLAGS=(--target=wasm32-wasip1 --sysroot="$SYSROOT" -Oz
  -mllvm -wasm-enable-sjlj -mllvm -wasm-use-legacy-eh=false -I"$LIBAOM_SRC" -ffunction-sections -fdata-sections)
# -lsetjmp provides the wasm SjLj runtime (__wasm_setjmp/__wasm_longjmp, lowered to
# native wasm exception-handling) so the module is self-contained — no `env` imports,
# which is what Chicory needs.  (No --allow-undefined: undefined symbols must error.)
# stack-size: wasm-ld defaults the C stack to 64 KB, but libaom's encoder uses large
# stack frames + deep RD recursion on high-entropy content and overruns it (OOB trap).
LINK_COMMON=(-mexec-model=reactor -Wl,--gc-sections -Wl,--strip-all -Wl,-z,stack-size=8388608)
LINK_LIBS=(-lsetjmp)

"$CLANG" "${COMMON_CFLAGS[@]}" -c "$HERE/shim.c" -o "$BUILD/shim.o"

exports() { local s=""; for f in "$@"; do s="$s -Wl,--export=$f"; done; echo "$s"; }

DEC_EXPORTS=(shim_malloc shim_free dec_open dec_set_palette dec_tu dec_close)
ENC_EXPORTS=(shim_malloc shim_free enc_init enc_frame enc_finish enc_data enc_reset dec_open dec_set_palette dec_tu dec_close)

# ── 3. Link the two reactor modules ──────────────────────────────────────────
"$CLANG" "${COMMON_CFLAGS[@]}" "${LINK_COMMON[@]}" $(exports "${DEC_EXPORTS[@]}") \
  "$BUILD/shim.o" "$AOM_A" "${LINK_LIBS[@]}" -o "$BUILD/av1-decode.wasm"

"$CLANG" "${COMMON_CFLAGS[@]}" "${LINK_COMMON[@]}" $(exports "${ENC_EXPORTS[@]}") \
  "$BUILD/shim.o" "$AOM_A" "${LINK_LIBS[@]}" -o "$BUILD/av1-encode.wasm"

# ── 4. Install into the repo ─────────────────────────────────────────────────
mkdir -p "$REPO/web/public/av1" "$REPO/src/main/resources/av1"
cp "$BUILD/av1-decode.wasm" "$REPO/web/public/av1/av1-decode.wasm"
cp "$BUILD/av1-decode.wasm" "$REPO/src/main/resources/av1/av1-decode.wasm"
cp "$BUILD/av1-encode.wasm" "$REPO/web/public/av1/av1-encode.wasm"

echo ">> done:"
ls -l "$REPO/web/public/av1/"*.wasm "$REPO/src/main/resources/av1/av1-decode.wasm"
