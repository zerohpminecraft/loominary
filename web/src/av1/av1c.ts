/**
 * Build an MP4 AV1CodecConfigurationRecord (`av1C`) from a raw AV1 temporal-unit stream, so the
 * lossy payload stream can be muxed into a playable MP4 with NO re-encode.  We control the encoder
 * (libaom, 8-bit 4:2:0 colour, no timing/decoder-model), so only seq_profile / seq_level_idx /
 * seq_tier are read from the sequence header; the chroma fields are known constants.
 */

const OBU_SEQUENCE_HEADER = 1;

function readLeb128(buf: Uint8Array, p: number): { value: number; next: number } {
  let value = 0;
  for (let i = 0; i < 8; i++) {
    const b = buf[p++];
    value |= (b & 0x7f) << (i * 7);
    if (!(b & 0x80)) break;
  }
  return { value: value >>> 0, next: p };
}

/** Find the sequence-header OBU (whole OBU: header + size + payload) inside a temporal unit. */
function findSeqHeaderObu(tu: Uint8Array): { obu: Uint8Array; payloadStart: number; payloadLen: number } | null {
  let p = 0;
  while (p < tu.length) {
    const start = p;
    const b0 = tu[p++];
    const obuType = (b0 >> 3) & 0x0f;
    const ext = (b0 >> 2) & 1;
    const hasSize = (b0 >> 1) & 1;
    if (ext) p++;
    let size: number;
    if (hasSize) { const r = readLeb128(tu, p); size = r.value; p = r.next; }
    else size = tu.length - p; // last OBU
    const payloadStart = p;
    p += size;
    if (obuType === OBU_SEQUENCE_HEADER) {
      return { obu: tu.subarray(start, p), payloadStart, payloadLen: size };
    }
  }
  return null;
}

/** Minimal MSB-first bit reader. */
class Bits {
  private p = 0; private bit = 0;
  constructor(private buf: Uint8Array, start: number) { this.p = start; }
  f(n: number): number {
    let v = 0;
    for (let i = 0; i < n; i++) {
      v = (v << 1) | ((this.buf[this.p] >> (7 - this.bit)) & 1);
      if (++this.bit === 8) { this.bit = 0; this.p++; }
    }
    return v >>> 0;
  }
}

/**
 * Build the av1C record for `tu0` (the first temporal unit, which carries the sequence header).
 * Returns null if no sequence header is found (caller falls back).
 */
export function buildAv1c(tu0: Uint8Array): Uint8Array | null {
  const seq = findSeqHeaderObu(tu0);
  if (!seq) return null;

  const b = new Bits(tu0, seq.payloadStart);
  const seqProfile = b.f(3);
  b.f(1); // still_picture
  const reduced = b.f(1);
  let seqLevelIdx0: number, seqTier0 = 0;
  if (reduced) {
    seqLevelIdx0 = b.f(5);
  } else {
    const timing = b.f(1);
    // We never enable timing_info / decoder_model, so those sub-fields are absent.
    if (timing) return null; // unexpected for our encoder — fall back rather than misparse
    b.f(1); // initial_display_delay_present_flag (0)
    b.f(5); // operating_points_cnt_minus_1 (0 for us)
    b.f(12); // operating_point_idc[0]
    seqLevelIdx0 = b.f(5);
    if (seqLevelIdx0 > 7) seqTier0 = b.f(1);
  }

  const out = new Uint8Array(4 + seq.obu.length);
  out[0] = 0x81;                                   // marker=1, version=1
  out[1] = (seqProfile << 5) | (seqLevelIdx0 & 0x1f);
  // seq_tier | high_bitdepth(0) | twelve_bit(0) | monochrome(0) | chroma_x(1) | chroma_y(1) | sample_pos(0)
  out[2] = (seqTier0 << 7) | (1 << 3) | (1 << 2);
  out[3] = 0;                                       // reserved + no initial_presentation_delay
  out.set(seq.obu, 4);                              // configOBUs = the sequence header OBU
  return out;
}
