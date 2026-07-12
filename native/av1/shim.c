/*
 * Loominary AV1 codec shim — thin C ABI over libaom, compiled to a standalone
 * (WASI reactor) wasm module.  The SAME decoder binary is hosted by the Java mod
 * (via Chicory) and the web editor (via WebAssembly.instantiate); the encoder
 * binary is web-only.
 *
 * Frames are 128x128 8-bit MONOCHROME planes whose pixel value is a *permuted*
 * palette index (see PalettePermutation / palette-perm.ts).  The permutation is
 * applied by the caller, not here — this shim only moves bytes through libaom.
 *
 * Bitstream framing (what enc_finish emits / dec_tu consumes): the payload is a
 * sequence of `frameCount` length-prefixed temporal units, [u32 LE len][TU bytes].
 * The shim exposes ONE TU at a time so the caller controls framing/allocation.
 *
 * Memory model: the caller (JS or Chicory) allocates via `shim_malloc`/`shim_free`
 * and passes wasm linear-memory offsets (i32).  All returns are via out-params
 * written into caller-provided buffers, or via `enc_take` for the encoded stream.
 *
 * ABI (exported):
 *   void* shim_malloc(int n);
 *   void  shim_free(void* p);
 *   int   enc_init(int w, int h, int frame_count, int mode);   // 0=mono+lossless, 1=color(4:2:0)+CQ
 *   int   enc_frame(const uint8_t* plane, int len);            // push one frame (mono: Y only; color: packed RGB w*h*3)
 *   int   enc_finish(void);                                    // flush; returns total encoded length (bytes)
 *   const uint8_t* enc_data(void);                             // pointer to the encoded stream (valid until enc_reset)
 *   void  enc_reset(void);                                     // free encoder + stream
 *   int   dec_decode(const uint8_t* obu, int len, uint8_t* out, int out_cap); // one TU -> Y plane; returns bytes written or <0
 *
 * Return convention: 0 / non-negative = ok, negative = error.
 */

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "aom/aom_encoder.h"
#include "aom/aomcx.h"
#include "aom/aom_decoder.h"
#include "aom/aomdx.h"
#include "aom/aom_image.h"

#define MODE_MONO_LOSSLESS 0
#define MODE_COLOR_CQ      1

/* Forward declarations (some are called before their definitions). */
void enc_reset(void);
void dec_close(void);

/* ── growable output buffer for the encoded stream ─────────────────────────── */
static uint8_t *g_out;
static int      g_out_len;
static int      g_out_cap;

static int out_reserve(int extra) {
    if (g_out_len + extra <= g_out_cap) return 0;
    int cap = g_out_cap ? g_out_cap * 2 : 65536;
    while (cap < g_out_len + extra) cap *= 2;
    uint8_t *p = (uint8_t *) realloc(g_out, cap);
    if (!p) return -1;
    g_out = p;
    g_out_cap = cap;
    return 0;
}

static void out_put_len_prefixed(const uint8_t *buf, int len) {
    if (out_reserve(len + 4)) return;
    g_out[g_out_len++] = (uint8_t)(len & 0xff);
    g_out[g_out_len++] = (uint8_t)((len >> 8) & 0xff);
    g_out[g_out_len++] = (uint8_t)((len >> 16) & 0xff);
    g_out[g_out_len++] = (uint8_t)((len >> 24) & 0xff);
    memcpy(g_out + g_out_len, buf, len);
    g_out_len += len;
}

/* ── encoder state ─────────────────────────────────────────────────────────── */
static aom_codec_ctx_t g_enc;
static int             g_enc_ready;
static int             g_w, g_h, g_mode, g_frame_count, g_pts, g_quality;

/* Exported allocation helpers so the host can hand us linear-memory buffers. */
void *shim_malloc(int n) { return malloc((size_t) n); }
void  shim_free(void *p) { free(p); }

/* Drain any ready packets from the encoder into the output stream. */
static void enc_drain(void) {
    aom_codec_iter_t iter = NULL;
    const aom_codec_cx_pkt_t *pkt;
    while ((pkt = aom_codec_get_cx_data(&g_enc, &iter)) != NULL) {
        if (pkt->kind == AOM_CODEC_CX_FRAME_PKT) {
            out_put_len_prefixed((const uint8_t *) pkt->data.frame.buf,
                                 (int) pkt->data.frame.sz);
        }
    }
}

int enc_init(int w, int h, int frame_count, int mode, int quality) {
    enc_reset();
    g_w = w; g_h = h; g_mode = mode; g_frame_count = frame_count; g_pts = 0;
    g_quality = quality;

    aom_codec_iface_t *iface = aom_codec_av1_cx();
    aom_codec_enc_cfg_t cfg;
    /* ALL_INTRA gives best all-keyframe behaviour; for temporal (inter) prediction
     * across frames use GOOD_QUALITY so libaom can reference previous frames. */
    unsigned int usage = (frame_count > 1) ? AOM_USAGE_GOOD_QUALITY : AOM_USAGE_ALL_INTRA;
    if (aom_codec_enc_config_default(iface, &cfg, usage) != AOM_CODEC_OK) return -1;

    cfg.g_w = w;
    cfg.g_h = h;
    cfg.g_timebase.num = 1;
    cfg.g_timebase.den = 30;         /* nominal; real timing lives in the manifest */
    cfg.g_threads = 1;
    cfg.g_bit_depth = AOM_BITS_8;
    cfg.g_input_bit_depth = 8;
    cfg.g_lag_in_frames = 0;         /* low-latency, deterministic packet-per-frame */
    cfg.g_profile = 0;
    cfg.rc_end_usage = AOM_Q;

    if (mode == MODE_MONO_LOSSLESS) {
        cfg.monochrome = 1;
        cfg.rc_min_quantizer = 0;
        cfg.rc_max_quantizer = 0;
    } else {
        cfg.monochrome = 0;
        cfg.rc_min_quantizer = 0;
        cfg.rc_max_quantizer = 63;
    }

    if (aom_codec_enc_init(&g_enc, iface, &cfg, 0) != AOM_CODEC_OK) return -2;
    g_enc_ready = 1;

    if (mode == MODE_MONO_LOSSLESS) {
        aom_codec_control(&g_enc, AV1E_SET_LOSSLESS, 1);
    } else {
        /* quality = AV1 quantizer 0..63 (lower = better/larger).  Clamp to a sane range. */
        int q = quality < 1 ? 1 : quality > 63 ? 63 : quality;
        aom_codec_control(&g_enc, AOME_SET_CQ_LEVEL, q);
    }
    aom_codec_control(&g_enc, AOME_SET_CPUUSED, 6);
    aom_codec_control(&g_enc, AV1E_SET_ROW_MT, 0);
    return 0;
}

int enc_frame(const uint8_t *plane, int len) {
    if (!g_enc_ready) return -1;

    aom_image_t img;
    aom_img_fmt_t fmt = (g_mode == MODE_MONO_LOSSLESS) ? AOM_IMG_FMT_I420 : AOM_IMG_FMT_I420;
    if (!aom_img_alloc(&img, fmt, g_w, g_h, 1)) return -2;

    if (g_mode == MODE_MONO_LOSSLESS) {
        /* Copy the mono plane into Y; neutralise chroma (ignored when monochrome). */
        for (int y = 0; y < g_h; y++)
            memcpy(img.planes[0] + y * img.stride[0], plane + y * g_w, g_w);
        int cw = (g_w + 1) >> 1, ch = (g_h + 1) >> 1;
        for (int y = 0; y < ch; y++) {
            memset(img.planes[1] + y * img.stride[1], 128, cw);
            memset(img.planes[2] + y * img.stride[2], 128, cw);
        }
        (void) len;
    } else {
        /* Pack RGB -> BT.601 limited-range YUV420 for the preview video. */
        for (int y = 0; y < g_h; y++) {
            for (int x = 0; x < g_w; x++) {
                const uint8_t *px = plane + (y * g_w + x) * 3;
                int r = px[0], gg = px[1], b = px[2];
                int yy = (77 * r + 150 * gg + 29 * b) >> 8;
                img.planes[0][y * img.stride[0] + x] = (uint8_t) yy;
            }
        }
        int cw = (g_w + 1) >> 1, ch = (g_h + 1) >> 1;
        for (int cy = 0; cy < ch; cy++) {
            for (int cx = 0; cx < cw; cx++) {
                int x = cx << 1, y = cy << 1;
                const uint8_t *px = plane + (y * g_w + x) * 3;
                int r = px[0], gg = px[1], b = px[2];
                int u = ((-43 * r - 84 * gg + 127 * b) >> 8) + 128;
                int v = ((127 * r - 106 * gg - 21 * b) >> 8) + 128;
                img.planes[1][cy * img.stride[1] + cx] = (uint8_t)(u < 0 ? 0 : u > 255 ? 255 : u);
                img.planes[2][cy * img.stride[2] + cx] = (uint8_t)(v < 0 ? 0 : v > 255 ? 255 : v);
            }
        }
    }

    aom_codec_err_t e = aom_codec_encode(&g_enc, &img, g_pts++, 1, 0);
    aom_img_free(&img);
    if (e != AOM_CODEC_OK) return -3;
    enc_drain();
    return 0;
}

int enc_finish(void) {
    if (!g_enc_ready) return -1;
    /* Flush: encode NULL until no more packets. */
    while (aom_codec_encode(&g_enc, NULL, g_pts, 1, 0) == AOM_CODEC_OK) {
        aom_codec_iter_t iter = NULL;
        const aom_codec_cx_pkt_t *pkt;
        int got = 0;
        while ((pkt = aom_codec_get_cx_data(&g_enc, &iter)) != NULL) {
            got = 1;
            if (pkt->kind == AOM_CODEC_CX_FRAME_PKT)
                out_put_len_prefixed((const uint8_t *) pkt->data.frame.buf, (int) pkt->data.frame.sz);
        }
        if (!got) break;
    }
    return g_out_len;
}

const uint8_t *enc_data(void) { return g_out; }

void enc_reset(void) {
    if (g_enc_ready) { aom_codec_destroy(&g_enc); g_enc_ready = 0; }
    free(g_out);
    g_out = NULL; g_out_len = 0; g_out_cap = 0;
}

/* ── decoder ───────────────────────────────────────────────────────────────── */
/* STATEFUL across temporal units: inter-frames reference earlier frames and the
 * sequence header (which rides in TU[0]), so a single persistent decoder context
 * must span the whole animation.  dec_open() once, dec_tu() per TU in order,
 * dec_close() at the end. */
static aom_codec_ctx_t g_dec;
static int             g_dec_ready;

/* Palette for lossy-colour decode: nearest-match maps decoded RGB back to a map byte.
 * Layout: g_pal_count entries of {mapByte, r, g, b}.  Set via dec_set_palette(). */
static uint8_t g_pal[256 * 4];
static int     g_pal_count;

/* Register the map palette used to re-quantise lossy-colour frames.  `entries` points to
 * `count` packed {mapByte, r, g, b} quads.  Harmless (unused) for monochrome streams. */
void dec_set_palette(const uint8_t *entries, int count) {
    if (count < 0) count = 0;
    if (count > 256) count = 256;
    memcpy(g_pal, entries, (size_t) count * 4);
    g_pal_count = count;
}

int dec_open(void) {
    dec_close();
    aom_codec_iface_t *iface = aom_codec_av1_dx();
    if (aom_codec_dec_init(&g_dec, iface, NULL, 0) != AOM_CODEC_OK) return -1;
    g_dec_ready = 1;
    return 0;
}

static uint8_t nearest_map_byte(int r, int g, int b) {
    int best = 0, bestd = 0x7fffffff;
    for (int i = 0; i < g_pal_count; i++) {
        const uint8_t *e = g_pal + i * 4;
        int dr = r - e[1], dg = g - e[2], db = b - e[3];
        int d = dr * dr + dg * dg + db * db;
        if (d < bestd) { bestd = d; best = e[0]; }
    }
    return (uint8_t) best;
}

/* Decode one temporal unit, writing up to two views of the frame:
 *   - `out_idx` (may be NULL): map-byte plane, w*h bytes.
 *       monochrome stream (lossless): the Y plane already holds (permuted) map
 *       indices — copied through verbatim; the caller applies INV_PERM.
 *       colour stream (lossy): nearest palette map byte per pixel (needs
 *       dec_set_palette).
 *   - `out_rgb` (may be NULL): packed RGB, w*h*3 bytes.  Colour streams only —
 *       the reconstructed BT.601 RGB before any palette quantisation (sRGB mode);
 *       ignored for monochrome streams.
 * Returns w*h or negative on error. */
int dec_tu_full(const uint8_t *obu, int len, uint8_t *out_idx, int idx_cap,
                uint8_t *out_rgb, int rgb_cap) {
    if (!g_dec_ready) return -1;
    if (aom_codec_decode(&g_dec, obu, (size_t) len, NULL) != AOM_CODEC_OK) return -2;
    aom_codec_iter_t iter = NULL;
    aom_image_t *img = aom_codec_get_frame(&g_dec, &iter);
    if (!img) return -3;
    int w = img->d_w, h = img->d_h;
    if (out_idx && w * h > idx_cap) return -4;
    if (out_rgb && w * h * 3 > rgb_cap) return -5;

    if (img->monochrome) {
        if (out_idx)
            for (int y = 0; y < h; y++)
                memcpy(out_idx + y * w, img->planes[0] + y * img->stride[0], w);
        return w * h;
    }

    /* Colour: limited-range BT.601 YUV420 → RGB → palette byte and/or raw RGB. */
    for (int y = 0; y < h; y++) {
        const uint8_t *Y = img->planes[0] + y * img->stride[0];
        const uint8_t *U = img->planes[1] + (y >> 1) * img->stride[1];
        const uint8_t *V = img->planes[2] + (y >> 1) * img->stride[2];
        for (int x = 0; x < w; x++) {
            int yy = Y[x], uu = U[x >> 1] - 128, vv = V[x >> 1] - 128;
            int r = yy + ((91881 * vv) >> 16);
            int g = yy - ((22554 * uu + 46802 * vv) >> 16);
            int b = yy + ((116130 * uu) >> 16);
            r = r < 0 ? 0 : r > 255 ? 255 : r;
            g = g < 0 ? 0 : g > 255 ? 255 : g;
            b = b < 0 ? 0 : b > 255 ? 255 : b;
            if (out_idx) out_idx[y * w + x] = nearest_map_byte(r, g, b);
            if (out_rgb) {
                uint8_t *px = out_rgb + (y * w + x) * 3;
                px[0] = (uint8_t) r; px[1] = (uint8_t) g; px[2] = (uint8_t) b;
            }
        }
    }
    return w * h;
}

/* Legacy single-output entry point — identical behaviour to dec_tu_full with no
 * RGB buffer.  Kept as its own export so existing callers (and fixtures) are
 * untouched. */
int dec_tu(const uint8_t *obu, int len, uint8_t *out, int out_cap) {
    return dec_tu_full(obu, len, out, out_cap, NULL, 0);
}

void dec_close(void) {
    if (g_dec_ready) { aom_codec_destroy(&g_dec); g_dec_ready = 0; }
}
