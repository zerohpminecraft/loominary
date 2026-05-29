/**
 * Mux (payload redistribution) — port of poolMuxTiles() in LoominaryCommand.java.
 *
 * Mux distributes overflow bytes from over-budget tiles (receivers) into spare
 * capacity on under-budget tiles (donors).
 *
 * BANNER mode: fully computed in browser — generates LB receiver banners + MG
 *   routing banners on donors so the mod can place them directly.
 *
 * Carpet modes: the mux allocation is computed in the browser.  muxCargoB64 is
 *   set on receivers (own segment) and donors (own + guest cargo), so the Java
 *   mod has full routing info in the state JSON.  The Java mod uses this when
 *   running /loominary place (it re-encodes the LOOM carpet channel at place time).
 */

import { CAPACITY }                                 from './codec-mode.js';
import { buildChunks, assembleChunks, BYTES_PER_BANNER } from './cjk-codec.js';
import type { CodecMode }                           from './codec-mode.js';
import type { TileData }                            from './payload-state.js';

// ─── Constants matching LoominaryCommand.java / CarpetChannel.java ────────────

const MAX_CHUNKS     = 63;
const LOOM_GUEST_DESC = 10;   // bytes per guest descriptor embedded in LOOM header

// ─── Per-codec capacities ─────────────────────────────────────────────────────

/** Maximum own-segment bytes a receiver can hold in its own tile channels. */
function rxOwnMax(codec: CodecMode): number {
  switch (codec) {
    case 'BANNER':               return (MAX_CHUNKS - 1) * BYTES_PER_BANNER; // 5208 (62 payload + 1 LB)
    case 'CARPET':               return 8_176;
    case 'CARPET_SHADE':         return 10_192;
    case 'CARPET_BANNERS':       return 13_466;
    case 'CARPET_SHADE_BANNERS': return 15_482;
    case 'CARPET_BANNERS_SHADE': return 15_482;
  }
}

/** Spare guest-byte capacity remaining in donor tile d. */
function donorSpareCalc(
  codec:     CodecMode,
  guestCount: number,
  cargoSize: number,
): number {
  const cap = CAPACITY[codec];
  switch (codec) {
    case 'BANNER':
      // 1 MG routing banner per guest
      return (MAX_CHUNKS - guestCount - 1) * BYTES_PER_BANNER - cargoSize;
    case 'CARPET':
    case 'CARPET_SHADE':
      // guest descriptors embedded in LOOM header (10 B each), no overflow banners
      return cap - (guestCount + 1) * LOOM_GUEST_DESC - cargoSize;
    case 'CARPET_BANNERS':
    case 'CARPET_SHADE_BANNERS':
    case 'CARPET_BANNERS_SHADE':
      // 1 MG routing banner (84 B) per guest
      return cap - (guestCount + 1) * BYTES_PER_BANNER - cargoSize;
  }
}

// ─── Public types ─────────────────────────────────────────────────────────────

export interface TileRole {
  ti:         number;
  role:       'normal' | 'receiver' | 'donor';
  /** Own-segment size in bytes (payload this tile's channels carry for itself). */
  ownBytes:   number;
  /** Full compressed payload size (receiver: includes bytes held by donors). */
  totalBytes: number;
  /** Total guest bytes this donor carries for receivers. */
  guestBytes: number;
  /** For receivers: which donors carry which byte range. */
  donors: Array<{ donorTi: number; offset: number; len: number }>;
  /** For donors: which receiver segments this tile carries. */
  guests: Array<{ rxTi: number; rxOffset: number; len: number }>;
}

export interface MuxAllocation {
  roles:       TileRole[];
  unresolved:  number;   // receivers that could not be fully redistributed
}

/** Simple budget stats — usable without running the full allocation. */
export function budgetStatus(
  compressedSizes: number[],
  codec: CodecMode,
): { overBudget: number[]; budget: number } {
  const budget = CAPACITY[codec];
  return {
    budget,
    overBudget: compressedSizes.reduce<number[]>((acc, sz, i) => sz > budget ? [...acc, i] : acc, []),
  };
}

/** Rough estimate of blank donor tiles needed to absorb all overflow. */
export function donorsNeeded(compressedSizes: number[], codec: CodecMode): number {
  const budget = CAPACITY[codec];
  const overflow = compressedSizes.reduce((s, sz) => sz > budget ? s + sz - budget : s, 0);
  if (overflow === 0) return 0;
  const spare = budget - BYTES_PER_BANNER;
  return Math.ceil(overflow / Math.max(1, spare));
}

// ─── Core allocation algorithm ────────────────────────────────────────────────

/**
 * Compute the mux allocation (which tiles are receivers/donors) for any codec.
 * Does NOT modify tiles — call applyBannerMux or applyCarpetMux to commit.
 *
 * Pass `forceReceiversMask` to voluntarily mux specific tiles even if under budget.
 */
export function computeMuxAllocation(
  compressedSizes:     number[],
  codec:               CodecMode,
  forceReceiversMask?: boolean[],   // optional: force these tiles to be receivers
): MuxAllocation {
  const budget = CAPACITY[codec];
  const ownMax = rxOwnMax(codec);

  const cargoSize = [...compressedSizes];

  type Guest = { rxIdx: number; rxOff: number; len: number };
  const guestMeta: Guest[][] = compressedSizes.map(() => []);
  const receiverSet = new Set<number>();

  // Determine receivers: over-budget tiles, plus any forced ones.
  const receivers: number[] = [];
  for (let i = 0; i < compressedSizes.length; i++) {
    if (compressedSizes[i] > budget || forceReceiversMask?.[i]) receivers.push(i);
  }

  let unresolved = 0;

  for (const rIdx of receivers) {
    const payloadSize = compressedSizes[rIdx];
    let pos   = Math.min(ownMax, payloadSize);
    let fits  = true;

    while (pos < payloadSize) {
      const viable: Array<{ d: number; spare: number }> = [];
      for (let d = 0; d < compressedSizes.length; d++) {
        if (receiverSet.has(d)) continue;
        const spare = donorSpareCalc(codec, guestMeta[d].length, cargoSize[d]);
        if (spare > 0) viable.push({ d, spare });
      }
      if (viable.length === 0) { fits = false; break; }

      const remaining = payloadSize - pos;
      const share = Math.ceil(remaining / viable.length);
      for (const { d, spare } of viable) {
        if (pos >= payloadSize) break;
        const take = Math.min(spare, share, payloadSize - pos);
        if (take <= 0) continue;
        guestMeta[d].push({ rxIdx: rIdx, rxOff: pos, len: take });
        cargoSize[d] += take;
        pos += take;
      }
    }

    if (!fits) {
      // Roll back this receiver's allocations on donors.
      for (let d = 0; d < compressedSizes.length; d++) {
        for (let g = guestMeta[d].length - 1; g >= 0; g--) {
          if (guestMeta[d][g].rxIdx === rIdx) {
            cargoSize[d] -= guestMeta[d][g].len;
            guestMeta[d].splice(g, 1);
          }
        }
      }
      unresolved++;
      continue;
    }
    receiverSet.add(rIdx);
  }

  // Build TileRole array.
  const roles: TileRole[] = compressedSizes.map((sz, ti) => {
    const isReceiver = receiverSet.has(ti);
    const isDonor    = guestMeta[ti].length > 0;

    const guests = guestMeta[ti].map(g => ({ rxTi: g.rxIdx, rxOffset: g.rxOff, len: g.len }));
    const guestBytes = guests.reduce((s, g) => s + g.len, 0);

    // Donors of this receiver tile.
    const donors: Array<{ donorTi: number; offset: number; len: number }> = [];
    if (isReceiver) {
      for (let d = 0; d < compressedSizes.length; d++) {
        for (const g of guestMeta[d]) {
          if (g.rxIdx === ti) donors.push({ donorTi: d, offset: g.rxOff, len: g.len });
        }
      }
      donors.sort((a, b) => a.offset - b.offset);
    }

    return {
      ti,
      role:       isReceiver ? 'receiver' : isDonor ? 'donor' : 'normal',
      ownBytes:   isReceiver ? Math.min(ownMax, sz) : sz,
      totalBytes: sz,
      guestBytes,
      guests,
      donors,
    };
  });

  return { roles, unresolved };
}

// ─── BANNER-mode mux (full in-browser encoding) ───────────────────────────────

export interface MuxResult {
  unresolved: number;
}

/**
 * Apply banner-mode mux in-place on `tiles`.
 * Generates LB receiver banners and MG routing banners on donors.
 */
export function applyBannerMux(
  tiles:      TileData[],
  allocation: MuxAllocation,
  columns:    number,
): MuxResult {
  // Reset mux state.
  for (const t of tiles) { t.muxed = false; t.muxReceiver = false; t.muxCargoB64 = null; }

  // Reassemble compressed payloads from CJK chunks.
  const payloads: Uint8Array[] = tiles.map(t => {
    const cjk = t.chunks.filter(c => c.length > 2 && c.charCodeAt(2) >= 0x4E00);
    if (cjk.length === 0) return new Uint8Array(0);
    try { return assembleChunks(cjk); } catch { return new Uint8Array(0); }
  });

  // Collect guest bytes per donor.
  const guestDataByDonor: Map<number, Array<{ rxOff: number; len: number; data: Uint8Array }>> = new Map();
  for (const role of allocation.roles) {
    if (role.role !== 'receiver') continue;
    for (const { donorTi, offset, len } of role.donors) {
      if (!guestDataByDonor.has(donorTi)) guestDataByDonor.set(donorTi, []);
      guestDataByDonor.get(donorTi)!.push({ rxOff: offset, len, data: payloads[role.ti].slice(offset, offset + len) });
    }
  }

  // Encode receivers.
  for (const role of allocation.roles) {
    if (role.role !== 'receiver') continue;
    const tile    = tiles[role.ti];
    const payload = payloads[role.ti];
    const col     = role.ti % columns;
    const row     = Math.floor(role.ti / columns);
    const ownSeg  = payload.slice(0, role.ownBytes);

    tile.chunks      = [`LB${x2(col)}${x2(row)}${x4(ownSeg.length)}${x8(payload.length)}`, ...buildChunks(ownSeg)];
    tile.muxCargoB64 = toB64(ownSeg);
    tile.muxReceiver = true;
    tile.muxed       = true;
  }

  // Encode donors.
  for (const role of allocation.roles) {
    if (role.role !== 'donor') continue;
    const tile    = tiles[role.ti];
    const ownData = payloads[role.ti];

    const gSegments = guestDataByDonor.get(role.ti) ?? [];

    let totalLen = ownData.length;
    for (const g of gSegments) totalLen += g.len;
    const cargo = new Uint8Array(totalLen);
    cargo.set(ownData, 0);
    let pos = ownData.length;
    for (const g of gSegments) { cargo.set(g.data, pos); pos += g.len; }

    const chunks: string[] = [...buildChunks(cargo)];
    for (let g = 0; g < role.guests.length; g++) {
      const { rxTi, rxOffset, len } = role.guests[g];
      const rxCol = rxTi % columns;
      const rxRow = Math.floor(rxTi / columns);
      chunks.push(`MG${x2(g)}${x4(ownData.length)}${x2(rxCol)}${x2(rxRow)}${x8(rxOffset)}${x4(len)}`);
    }
    tile.chunks      = chunks;
    tile.muxCargoB64 = toB64(cargo);
    tile.muxed       = true;
  }

  return { unresolved: allocation.unresolved };
}

// ─── Carpet-mode mux (sets muxCargoB64 for Java mod routing) ─────────────────

/**
 * Apply carpet-mode mux to `tiles` using the pre-computed allocation.
 * Sets muxCargoB64 (own segment for receivers, own+guest cargo for donors)
 * so the Java mod has full routing info in the state JSON.
 *
 * `payloads[ti]` = compressed bytes for tile ti (decoded from carpetCompressedB64).
 */
export function applyCarpetMux(
  tiles:      TileData[],
  allocation: MuxAllocation,
  payloads:   Uint8Array[],
): MuxResult {
  // Reset mux state.
  for (const t of tiles) { t.muxed = false; t.muxReceiver = false; t.muxCargoB64 = null; }

  // Build guest-data map (donor → list of byte slices from receiver payloads).
  const guestData: Map<number, Uint8Array[]> = new Map();
  for (const role of allocation.roles) {
    if (role.role !== 'receiver') continue;
    for (const { donorTi, offset, len } of role.donors) {
      if (!guestData.has(donorTi)) guestData.set(donorTi, []);
      guestData.get(donorTi)!.push(payloads[role.ti].slice(offset, offset + len));
    }
  }

  // Apply receiver tags.
  for (const role of allocation.roles) {
    if (role.role !== 'receiver') continue;
    const tile   = tiles[role.ti];
    const ownSeg = payloads[role.ti].slice(0, role.ownBytes);
    tile.muxCargoB64 = toB64(ownSeg);
    tile.muxReceiver = true;
    tile.muxed       = true;
  }

  // Apply donor cargo (own payload concatenated with guest segments).
  for (const role of allocation.roles) {
    if (role.role !== 'donor') continue;
    const tile    = tiles[role.ti];
    const ownData = payloads[role.ti];
    const guests  = guestData.get(role.ti) ?? [];

    let totalLen = ownData.length;
    for (const g of guests) totalLen += g.length;
    const cargo = new Uint8Array(totalLen);
    cargo.set(ownData, 0);
    let pos = ownData.length;
    for (const g of guests) { cargo.set(g, pos); pos += g.length; }

    tile.muxCargoB64 = toB64(cargo);
    tile.muxed       = true;
  }

  return { unresolved: allocation.unresolved };
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function x2(n: number): string { return n.toString(16).padStart(2, '0').toUpperCase(); }
function x4(n: number): string { return n.toString(16).padStart(4, '0').toUpperCase(); }
function x8(n: number): string { return n.toString(16).padStart(8, '0').toUpperCase(); }

function toB64(bytes: Uint8Array): string {
  let s = '';
  for (let i = 0; i < bytes.length; i += 8192) s += String.fromCharCode(...bytes.subarray(i, i + 8192));
  return btoa(s);
}
