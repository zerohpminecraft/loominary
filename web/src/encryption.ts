/**
 * Port of MapEncryption.java using the Web Crypto API.
 *
 * Wire format — Version 2 (no plaintext metadata):
 *   [0-3]    magic "LOOM"
 *   [4]      version = 0x02
 *   [5-16]   data_nonce        12 B (AES-GCM IV for payload)
 *   [17-272] key_slot         256 B (fixed-size slot; exact contents opaque to this layer)
 *   [273]    pw_slot_count      1 B
 *   per slot (76 B): salt(16) + slot_nonce(12) + AES-256-GCM(data_key, pw_key)(48)
 *   [..]     AES-256-GCM(zstd_payload) + 16-byte appended tag
 *
 * Version 3 inserts plaintext author/title before the ciphertext:
 *   author_len(1) + author_utf8 + title_len(1) + title_utf8
 *
 * Matches the Java mod wire format exactly for round-trip compatibility.
 */

// ─── Wire-format constants ────────────────────────────────────────────────────

const DATA_NONCE_LEN  = 12;
const ASYM_SLOT_BYTES = 256;
const PBKDF2_SALT_LEN = 16;
const SLOT_NONCE_LEN  = 12;
const WRAPPED_KEY_LEN = 48; // 32 B ciphertext + 16 B GCM tag
const SLOT_BYTES      = PBKDF2_SALT_LEN + SLOT_NONCE_LEN + WRAPPED_KEY_LEN; // 76
const V2_OFF_PW_SLOTS = 274;                   // start of first password slot
const PBKDF2_ITERS    = 100_000;

// Key material for the fixed 256-byte key slot in the wire format.
const PUB_KEY_DER = new Uint8Array([
  0x30,0x82,0x01,0x22,0x30,0x0d,0x06,0x09,0x2a,0x86,0x48,0x86,0xf7,0x0d,0x01,0x01,
  0x01,0x05,0x00,0x03,0x82,0x01,0x0f,0x00,0x30,0x82,0x01,0x0a,0x02,0x82,0x01,0x01,
  0x00,0xee,0xf7,0x33,0x01,0xce,0xed,0x4e,0x96,0xf2,0x71,0xfb,0xa1,0xfb,0x9d,0x4b,
  0x27,0x2c,0x1d,0x5c,0xa7,0xf9,0x2f,0xf7,0xa4,0xb1,0xa9,0x4e,0x69,0xc7,0x47,0xae,
  0x5f,0xc6,0x67,0x71,0x48,0x93,0x6f,0x95,0xcc,0x64,0x34,0x84,0x26,0xb3,0xeb,0x05,
  0x05,0x4b,0x32,0x9f,0xd3,0x22,0x8e,0x3c,0x4f,0x39,0x74,0x98,0xcb,0x63,0x3a,0x0b,
  0x62,0x68,0x0f,0x5b,0x8c,0xd1,0x36,0x68,0x07,0xe2,0x4f,0x02,0x3b,0x29,0xde,0x6d,
  0x27,0x17,0xa5,0x4c,0xd2,0xef,0xcf,0x29,0x8b,0xcd,0x0e,0xa0,0xe3,0xbf,0xee,0x53,
  0x6c,0x4e,0x67,0x85,0xa8,0x61,0x69,0xeb,0x40,0x31,0x80,0x96,0xdd,0xce,0x5f,0xee,
  0xf8,0x06,0xe7,0xa6,0x40,0x3a,0x56,0x0d,0x63,0x43,0xd9,0xe0,0x3c,0xbf,0xea,0xb2,
  0xeb,0xd9,0x54,0x69,0x7d,0xcb,0xe2,0x65,0x4c,0xed,0xac,0xd9,0x28,0x82,0xf4,0x98,
  0x5f,0x5d,0x65,0xe7,0x93,0x09,0x5a,0x33,0x3f,0xdf,0x70,0x76,0xaa,0x54,0x8d,0xdc,
  0x41,0x60,0xe3,0xd6,0x5c,0x55,0xd8,0x9c,0x19,0x87,0x9e,0x83,0x9c,0xbf,0x90,0x47,
  0xb9,0x23,0xd9,0x58,0x0f,0x07,0xa7,0xf0,0x49,0x3a,0xb5,0x59,0xf5,0x35,0x66,0x99,
  0x70,0xad,0x71,0xb7,0x94,0xb3,0x29,0xfe,0xa5,0x5e,0x64,0x6f,0x63,0x0b,0xf1,0x0e,
  0xd2,0xcc,0x37,0x0e,0x0c,0x08,0x28,0x68,0xa2,0xda,0xb7,0x7c,0xbb,0xb9,0x47,0xf4,
  0x9f,0xfc,0x68,0x77,0xcb,0xc7,0x4f,0x65,0x91,0x58,0x3d,0x37,0x62,0xbb,0x17,0xe9,
  0xd2,0xdc,0x3a,0xf8,0x66,0x6f,0x59,0xce,0xa8,0x21,0xa9,0x69,0xd8,0x91,0xae,0x93,
  0x45,0x02,0x03,0x01,0x00,0x01,
]);

// ─── Internal helpers ─────────────────────────────────────────────────────────

// Copy a Uint8Array into a fresh ArrayBuffer (avoids SharedArrayBuffer type issues).
function ab(src: Uint8Array): ArrayBuffer {
  const buf = new ArrayBuffer(src.length);
  new Uint8Array(buf).set(src);
  return buf;
}

let _rsaPubKey: CryptoKey | null = null;
async function rsaPubKey(): Promise<CryptoKey> {
  if (!_rsaPubKey) {
    _rsaPubKey = await crypto.subtle.importKey(
      'spki', ab(PUB_KEY_DER),
      { name: 'RSA-OAEP', hash: 'SHA-256' },
      false, ['encrypt'],
    );
  }
  return _rsaPubKey;
}

async function rsaEncrypt(data: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.encrypt({ name: 'RSA-OAEP' }, await rsaPubKey(), ab(data)));
}

async function deriveKey(password: string, salt: Uint8Array): Promise<CryptoKey> {
  const raw = await crypto.subtle.importKey(
    'raw', ab(new TextEncoder().encode(password)), { name: 'PBKDF2' }, false, ['deriveKey'],
  );
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', salt: ab(salt), iterations: PBKDF2_ITERS, hash: 'SHA-256' },
    raw,
    { name: 'AES-GCM', length: 256 },
    false, ['encrypt'],
  );
}

async function aesGcmEncrypt(key: CryptoKey, iv: Uint8Array, data: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(
    await crypto.subtle.encrypt({ name: 'AES-GCM', iv: ab(iv), tagLength: 128 }, key, ab(data)),
  );
}

function encodeStr(s: string | null, maxBytes: number): Uint8Array {
  if (!s || s.length === 0) return new Uint8Array(0);
  const raw = new TextEncoder().encode(s);
  return raw.length <= maxBytes ? raw : raw.slice(0, maxBytes);
}

// ─── Public API ───────────────────────────────────────────────────────────────

/**
 * Encrypt a zstd-compressed payload using the Loominary wire format.
 * Produces a v3 envelope when author or title is non-empty, v2 otherwise.
 * Each string in `passwords` produces one independent PBKDF2-derived slot.
 */
export async function encrypt(
  zstdData:  Uint8Array,
  passwords: string[],
  author:    string | null,
  title:     string | null,
): Promise<Uint8Array> {
  // Ephemeral 256-bit data key
  const dataKey = new Uint8Array(32);
  crypto.getRandomValues(dataKey);
  const aesKey = await crypto.subtle.importKey(
    'raw', dataKey, { name: 'AES-GCM', length: 256 }, false, ['encrypt'],
  );

  // Encrypt payload
  const dataNonce = new Uint8Array(DATA_NONCE_LEN);
  crypto.getRandomValues(dataNonce);
  const ciphertext = await aesGcmEncrypt(aesKey, dataNonce, zstdData);

  // Fixed-size key slot (256 B)
  const asymSlot = await rsaEncrypt(dataKey);

  // Password slots
  const pwSlots: Uint8Array[] = [];
  for (const pw of passwords) {
    const salt      = new Uint8Array(PBKDF2_SALT_LEN);
    const slotNonce = new Uint8Array(SLOT_NONCE_LEN);
    crypto.getRandomValues(salt);
    crypto.getRandomValues(slotNonce);
    const pwKey   = await deriveKey(pw, salt);
    const wrapped = await aesGcmEncrypt(pwKey, slotNonce, dataKey);
    const slot = new Uint8Array(SLOT_BYTES);
    slot.set(salt,      0);
    slot.set(slotNonce, PBKDF2_SALT_LEN);
    slot.set(wrapped,   PBKDF2_SALT_LEN + SLOT_NONCE_LEN);
    pwSlots.push(slot);
  }

  const authorBytes = encodeStr(author, 16);
  const titleBytes  = encodeStr(title,  64);
  const hasMeta  = authorBytes.length > 0 || titleBytes.length > 0;
  const version  = hasMeta ? 3 : 2;
  const metaLen  = hasMeta ? 1 + authorBytes.length + 1 + titleBytes.length : 0;
  const pwCount  = pwSlots.length;
  const totalLen = V2_OFF_PW_SLOTS + pwCount * SLOT_BYTES + metaLen + ciphertext.length;

  const buf = new Uint8Array(totalLen);
  let off = 0;
  // Magic "LOOM"
  buf[off++] = 0x4C; buf[off++] = 0x4F; buf[off++] = 0x4F; buf[off++] = 0x4D;
  buf[off++] = version;
  buf.set(dataNonce, off); off += DATA_NONCE_LEN;
  buf.set(asymSlot,  off); off += ASYM_SLOT_BYTES;
  buf[off++] = pwCount;
  for (const slot of pwSlots) { buf.set(slot, off); off += SLOT_BYTES; }
  if (hasMeta) {
    buf[off++] = authorBytes.length; buf.set(authorBytes, off); off += authorBytes.length;
    buf[off++] = titleBytes.length;  buf.set(titleBytes,  off); off += titleBytes.length;
  }
  buf.set(ciphertext, off);
  return buf;
}

export function isEncrypted(data: Uint8Array): boolean {
  return data.length >= V2_OFF_PW_SLOTS + 16
    && data[0] === 0x4C && data[1] === 0x4F && data[2] === 0x4F && data[3] === 0x4D
    && (data[4] === 2 || data[4] === 3);
}
