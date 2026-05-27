# P2P Broadcast / Gossip Protocol — Implementation Plan

Hybrid peer discovery + data transfer for Loominary catalogue sharing on 2b2t.

**Status:** Design finalized (reviewed 2026-05-18). Stage 1 empirical gate must pass before shipping.

---

## Review Findings (2026-05-18)

Issues found by reviewing Stage 0 source (`P2PValidationCommand.java`) against the original draft:

| # | Severity | Issue | Resolution |
|---|----------|-------|------------|
| 1 | Bug | `FRAGMENT_DATA_BYTES = 431` ignores command overhead | Fixed: 384 bytes (see capacity math) |
| 2 | Bug | `peerId` shift arithmetic inconsistent between wire format and code sections; "48-bit" claim wrong | Fixed: dropped peerId from ANNOUNCE, 32-bit CRC fills both X/Z slots |
| 3 | Bug | Y-clamping [60..67] conflicts with embedding 16-bit payload in Y slot | Fixed: only X/Z carry payload; Y slot is randomized for realism |
| 4 | Bug | `indexRequested = false` set on first INDEX_ENTRY — re-requests index after one reply | Fixed: add INDEX_DONE (0x07), reset only on INDEX_DONE |
| 5 | Bug | Own ANNOUNCE triggers GET_INDEX to self | Fixed: skip if `senderName == ownName` |
| 6 | Bug | Whisper format is `"whispers:"` not `"whispers to you:"` — wrong regex | Fixed: use Text sibling extraction from Stage 0 |
| 7 | Bug | **Echo: 2b2t echoes your own whispers back to you, appearing as if the recipient sent them.** Without echo detection, every outbound DATA fragment re-enters `handleData()` as an inbound fragment | Fixed: prepend 1-byte random nonce to all whisper payloads; record outbound nonces |
| 8 | Bug | No ACK / retransmit — 2b2t drops whispers; Stage 0 had full ACK+CRC+retry because whispers are not reliable | Fixed: add MSG_TYPE ACK (0x06), per-fragment retry |
| 9 | Design | Fragment delay 600ms assumes Velocity rate limit is binding; Stage 0 used 10s empirical default | Flagged as tuning target — see fragment delay note |
| 10 | Design | `sendIndexEntries` queuing unspecified — INDEX_ENTRY messages need same rate-limited queue as DATA | Fixed: unified outbound whisper queue |
| 11 | Design | Double-compression: JSON containing base64-zstd data re-zstd-compressed; net gain ≈ 0 | Fixed: keep compression but note the ceiling; or strip base64 at transfer boundary |
| 12 | Design | `downloads` keyed by full entryId, DATA lookup by 8-byte prefix — lookup needs iteration | Fixed: clarified as linear scan |
| 13 | Design | `peers` map grows unbounded | Fixed: evict when >128 entries or lastSeen >30min |
| 14 | Design | No timeout on stalled GET_INDEX or incomplete downloads | Fixed: 30s GET_INDEX timeout, 5-minute download timeout |

---

## Background and Constraints

### What Stage 0 Established

- CJK characters (U+4E00–U+8DFF) **pass** through `/msg` whispers on 2b2t. Velocity processes whispers at the proxy layer, before backend spam-filter plugins see them.
- CJK characters **are blocked** in public chat by a third-party content-filter plugin on the backend.
- **2b2t whisper format (confirmed):** `Text` siblings: `sib[0]` = empty (light purple), `sib[1]` = sender name (light purple), `sib[2]` = `" whispers: <body>"`. Extract with `extractSender`/`extractBody` — see Stage 0 source.
- **2b2t echoes your own whispers back to you** in the same format as received whispers — sender appears as the recipient. Echo detection is required.
- **Body budget: 230 chars** (conservative: 256-char cmd limit − "msg " − 16-char name − " " − 4-char safety margin). Stage 0's `MAX_PAYLOAD = 224` respects this budget with 6-char wire overhead; the new design uses 229 chars for CJK (1 char for MAGIC).
- **Fragment delay:** Stage 0 used 10 s as empirical default (`sendPauseMs = 10_000`). The Velocity rate limit of 2.5 msg/s is not necessarily the binding constraint — 2b2t anti-spam plugins may be stricter. 600 ms (FRAGMENT_DELAY_TICKS = 12) is a target to validate in Stage 1.
- Anti-spam similarity threshold: ~85%. Template rotation handles this for ANNOUNCE.
- Public chat message length: **not yet measured empirically** — use 256 chars as default.

### What Stage 1 Must Validate (Gate Before Ship)

- [ ] Does U+200B (zero-width space) survive 2b2t public chat?
- [ ] What is the actual public chat message length limit?
- [ ] Do template-rotated coordinate messages pass StopSpam at 60-second cadence?
- [ ] What is the safe minimum inter-fragment delay for whispers? (Target: 600ms; baseline: 10s)

Fallback if U+200B is stripped: use Y-slot parity as signature — Y1 always even, Y2 always odd (parse by parity of the second and fourth extracted integers in the template).

---

## Architecture

### Model: Hybrid Gossip-Pull

```
Public chat (sendChatMessage)
  └── ANNOUNCE beacons (steganographic coordinate messages)
        └── trigger peer discovery

Whispers (sendCommand "msg <player> <body>")
  ├── GET_INDEX    → receiver sends INDEX_ENTRY list, then INDEX_DONE
  ├── INDEX_ENTRY  → receiver learns what's available
  ├── INDEX_DONE   → receiver knows index is complete
  ├── REQUEST      → receiver starts sending DATA fragments
  ├── DATA         → fragment stream; each fragment acknowledged by ACK
  └── ACK          → fragment acknowledgment with CRC check + gap-skip hint
```

Public chat carries **only discovery signals** (no binary data).
All binary data travels over whispers using CjkCodec.

---

## Wire Formats

### ANNOUNCE (public chat)

A natural-looking coordinate share message, indistinguishable from player chat.

**Detection marker:** U+200B (zero-width space) appended invisibly at the end.

**Payload:** Only two "big-integer" slots (X, Z — any value plausible on 2b2t). Two constrained slots (Y, auxiliary) are randomized for realism.

```
// Slot layout — these are the 4 integers extracted from the message text:
slot[0] = X  → (short)(crc >>> 16)       // upper 16 bits of CRC32
slot[1] = Y  → random in [60..67]         // realistic Y for nether/cave context
slot[2] = Z  → (short)(crc & 0xFFFF)     // lower 16 bits of CRC32
slot[3] = aux → (short) sharedCount       // number of shared entries (0–N)
```

`crc` = CRC32 of sorted entry-id list for all `shareEnabled` catalogue entries.
`sharedCount` gives the receiver a preview without fetching the index.

Sender is identified by their Minecraft username embedded in the chat Text component — no peerId field needed.

**Template pool (≥50 templates, rotate per send):**

Templates are parameterized with `{x}`, `{y}`, `{z}`, `{w}` placeholders. The regex `(-?\d+)` extracts all integers on parse; the first 4 are used.

Examples:
```
"found cave at {x} {y} {z} depth {w}​"
"nether portal {x} {y} {z} overworld {w}​"
"stash coords {x} {y} {z} {w}​"
"base x={x} y={y} z={z} items={w}​"
"dropped stuff at {x} {y} {z} rush {w}​"
```

`{x}` and `{z}` receive the raw int16 payload (any value, large absolute values look normal on 2b2t). `{y}` receives a random value in [60..67] (realistic cave/nether Y). `{w}` receives `sharedCount` (0–small number, looks natural as an item count or portal index).

Templates rotate sequentially; the same template is never used twice in a row.

**Sender:** `client.getNetworkHandler().sendChatMessage(text)` — produces a `ServerboundChatPacket`.

---

### Whisper Payload Format

All whispers:
```
MAGIC_CHAR + NONCE_CHAR + CjkCodec.encode(payload_bytes)
```

- `MAGIC_CHAR` = U+8E08 (`踈`) — outside CjkCodec alphabet, used as framing sentinel.
- `NONCE_CHAR` = a CJK char encoding a random 14-bit value: `(char)(CjkCodec.ALPHA_BASE + random.nextInt(CjkCodec.ALPHA_SIZE))`. Recorded in `outboundNonces` set; incoming whispers with a matching nonce are echoes.
- `CjkCodec.encode(payload_bytes)` — encodes the protocol payload bytes (MSG_TYPE + fields).

Total body = 1 (MAGIC) + 1 (NONCE) + CJK chars ≤ 230 chars (Stage 0 proven budget).
CJK chars ≤ 228. CjkCodec produces ceil((payload.length + 2) × 8 / 14) chars.

**Whisper sent via:**
```java
client.getNetworkHandler().sendCommand("msg " + targetName + " " + MAGIC + nonceChar + cjkBody);
```
This produces a `ServerboundChatCommandPacket`, bypassing client-side chat signing.

**Echo detection:**
```java
// Before sending:
outboundNonces.add(nonceChar);

// In onGameMessage, before routing:
if (body.length() >= 2 && outboundNonces.contains(body.charAt(1))) {
    return; // own echo
}
```
`outboundNonces` is a `LinkedHashSet` capped at 64 entries (rotate out old entries).

---

#### MSG_TYPE byte (first byte of decoded payload)

| Value | Name        | Direction        |
|-------|-------------|-----------------|
| 0x02  | GET_INDEX   | requester → peer |
| 0x03  | INDEX_ENTRY | peer → requester |
| 0x04  | REQUEST     | requester → peer |
| 0x05  | DATA        | peer → requester |
| 0x06  | ACK         | requester → peer |
| 0x07  | INDEX_DONE  | peer → requester |

---

#### GET_INDEX (0x02)

```
[0x02]
```

Sent after receiving an ANNOUNCE with unknown peer or changed CRC. Recipient replies with one INDEX_ENTRY per shared entry, then INDEX_DONE.

---

#### INDEX_ENTRY (0x03)

```
[0x03]
[entryId: 16 bytes]          // 32-char hex UUID no dashes, packed as raw bytes
[compressedSize: 4 bytes BE] // size of the transfer payload (see note below)
[gridCols: 1 byte]
[gridRows: 1 byte]
[frameCount: 2 bytes BE]
[titleLen: 1 byte]
[title: titleLen bytes, UTF-8]
[authorLen: 1 byte]
[author: authorLen bytes, UTF-8]
```

`compressedSize`: zstd-compress the CatalogueEntry JSON (GSON, compact) → record `compressed.length`. Used by receiver to preallocate `assembled[]` and compute `totalFragments = ceil(compressedSize / FRAGMENT_DATA_BYTES)`.

Note on double-compression: `TileSnapshot.carpetCompressedB64` is already zstd+base64. Re-zstd-compressing it yields ~0% gain (already compressed). Acceptable for code simplicity — the zstd call on JSON adds overhead but not meaningful size increase. As a future optimization, strip base64 at the transfer boundary and send raw tile bytes.

`entryId` packed with:
```java
static byte[] putEntryId(String hexId) {
    long hi = Long.parseUnsignedLong(hexId.substring(0, 16), 16);
    long lo = Long.parseUnsignedLong(hexId.substring(16), 16);
    ByteBuffer b = ByteBuffer.allocate(16);
    b.putLong(hi); b.putLong(lo);
    return b.array();
}
static String readEntryId(byte[] buf, int off) {
    ByteBuffer b = ByteBuffer.wrap(buf, off, 16);
    return String.format("%016x%016x", b.getLong(), b.getLong());
}
```

---

#### INDEX_DONE (0x07)

```
[0x07]
```

Sent by the peer after all INDEX_ENTRY messages for a GET_INDEX response. The receiver sets `peer.indexRequested = false` only on this message. Without it, interim ANNOUNCE beacons would trigger duplicate GET_INDEX requests.

---

#### REQUEST (0x04)

```
[0x04]
[entryId: 16 bytes]
```

Recipient begins sending DATA fragments for the requested entry.

---

#### DATA (0x05)

```
[0x05]
[entryId: 8 bytes]          // first 8 bytes of entryId (sufficient for disambiguation)
[seq: 2 bytes BE]           // 0-based fragment index
[total: 2 bytes BE]         // total fragment count
[fragData: remaining bytes]
```

**Capacity math:**

Stage 0 proven body budget: **230 chars** (256 cmd − "msg " − 16-char name − " " − 4 safety).
MAGIC (1) + NONCE (1) = 2 chars overhead.
CJK available: **228 chars**.

CjkCodec decode produces: `floor(228 × 14 / 8) = floor(399) = 399` bytes total (including 2-byte length header).
Usable payload bytes: `399 − 2 = 397`.
DATA header: `1 (type) + 8 (entryId) + 2 (seq) + 2 (total) = 13 bytes`.
`FRAGMENT_DATA_BYTES` = **384 bytes**.

Transfer time for a typical single-tile entry (~50 KB JSON, ~30 KB compressed):
`ceil(30_000 / 384) ≈ 79 fragments`. At 600ms/fragment ≈ 47s; at 10s/fragment ≈ 13 minutes.

---

#### ACK (0x06)

```
[0x06]
[entryId: 8 bytes]
[seq: 2 bytes BE]           // seq of the fragment being ACKed
[crc32: 4 bytes BE]         // CRC32 of the received fragData bytes
[nextExpected: 2 bytes BE]  // lowest seq the receiver still needs (gap-skip hint)
```

Sender on receiving ACK:
- Validate `crc32` matches what was sent (if mismatch → retransmit same fragment).
- If `nextExpected > seq + 1`, advance `upload.nextSeq = nextExpected` (receiver already has intermediate fragments from a prior interrupted transfer).
- Advance to next fragment.

Retransmit policy: if no ACK within `ACK_TIMEOUT_TICKS` (500 ticks = 25s), retransmit. After `MAX_RETRIES = 3`, abort and log.

---

## Data Structures

### `BroadcastChannel` (static class, no instances)

```java
// Peer table
static final Map<String, PeerInfo> peers = new LinkedHashMap<>();
static final int MAX_PEERS = 128;

// In-flight downloads (keyed by full entryId)
static final Map<String, RemoteEntry> downloads = new LinkedHashMap<>();

// In-flight upload (at most one active at a time)
static volatile UploadState upload = null;

// Unified outbound whisper queue (target → body)
// Drained one message per tick cycle when ticksUntilNext <= 0
static final Queue<OutboundMsg> outboundQueue = new ArrayDeque<>();
static int outboundTicksUntilNext = 0;

// Echo detection
static final LinkedHashSet<Character> outboundNonces = new LinkedHashSet<>();
static final int MAX_NONCE_HISTORY = 64;

// Announce state
static int announceTemplateIdx = 0;
static boolean beaconing = false;
static int beaconTickCounter = 0;

// Constants
static final int BEACON_INTERVAL_TICKS = 1200; // 60 s
static final int FRAGMENT_DELAY_TICKS   = 12;  // 600 ms — validate in Stage 1
static final int ACK_TIMEOUT_TICKS      = 500; // 25 s
static final int MAX_RETRIES            = 3;
static final int FRAGMENT_DATA_BYTES    = 384;
static final char MAGIC = '踈';          // U+8E08
static final String ZWSP = "​";    // zero-width space for ANNOUNCE detection
```

### `PeerInfo`

```java
String name;
long lastSeen;               // System.currentTimeMillis()
long catalogueCrc;           // last seen CRC32 from ANNOUNCE
int sharedCount;             // last seen entry count from ANNOUNCE
boolean indexRequested;      // GET_INDEX sent, awaiting INDEX_DONE
long indexRequestedAt;       // timestamp of last GET_INDEX (for timeout)
Map<String, RemoteEntry> index; // entryId → metadata (no assembled data)
```

### `RemoteEntry`

```java
String entryId;         // 32-char hex
String title;
String author;
int gridCols, gridRows, frameCount;
int compressedSize;     // from INDEX_ENTRY
byte[] assembled;       // preallocated to compressedSize, filled by DATA fragments
BitSet receivedSeqs;    // tracks which fragments arrived
int totalFragments;     // ceil(compressedSize / FRAGMENT_DATA_BYTES)
long lastFragmentAt;    // for download timeout (5 min)
String fromPeer;        // who we requested from (for ACK routing)
```

### `UploadState`

```java
String targetName;
String entryId;
byte[] payload;             // zstd-compressed CatalogueEntry JSON
int nextSeq;
int totalFragments;
int retryCount;             // retries on current fragment
long lastSentAt;            // for ACK timeout
byte[] lastFragData;        // kept for retransmit
int lastFragCrc;            // CRC32 of lastFragData
```

### `OutboundMsg`

```java
String targetName;
String whisperBody;   // MAGIC + NONCE + CjkCodec output (already built)
boolean isCritical;   // if true, prepend to front of queue on retry
```

---

## Implementation: `BroadcastChannel.java`

Location: `src/main/java/net/zerohpminecraft/BroadcastChannel.java`

### `register()`

Called from `ClientModInit.onInitializeClient()`, replacing `P2PValidationCommand.register()`.

```java
ClientTickEvents.END_CLIENT_TICK.register(BroadcastChannel::onTick);
ClientReceiveMessageEvents.GAME.register(BroadcastChannel::onGameMessage);
```

### `onTick(MinecraftClient client)`

```
1. Beacon: if beaconing && beaconTickCounter++ >= BEACON_INTERVAL_TICKS → sendAnnounce(client), reset counter.
2. Outbound queue: if outboundTicksUntilNext-- <= 0 && !outboundQueue.isEmpty():
     poll one OutboundMsg, send via network handler, reset outboundTicksUntilNext = FRAGMENT_DELAY_TICKS.
3. Upload ACK timeout: if upload != null && (ticks since lastSentAt) > ACK_TIMEOUT_TICKS:
     if upload.retryCount >= MAX_RETRIES → abort upload, log error.
     else → retransmit lastFragData, increment retryCount.
4. Download timeout: for each entry in downloads.values():
     if (System.currentTimeMillis() - entry.lastFragmentAt) > 5_min_ms → cancel, log.
5. GET_INDEX timeout: for each peer with indexRequested:
     if (now - peer.indexRequestedAt) > 30_000 → peer.indexRequested = false (will retry on next ANNOUNCE).
6. Peer eviction: if peers.size() > MAX_PEERS → remove oldest entry (LinkedHashMap insertion order).
   Also evict peers where lastSeen > 30 minutes.
```

### `onGameMessage(Text message, boolean overlay)`

```
1. Skip overlays.
2. Extract plain string from message.getText().
3. ANNOUNCE detection:
   - If string ends with ZWSP (U+200B):
     - sender = message.getSiblings().get(1) if size >= 3, else null
     - If sender == ownName → skip (own echo).
     - tryParseAnnounce(string, sender).
4. Whisper detection:
   - sender = extractSender(message) [Stage 0's Text-sibling method]
   - body   = extractBody(message)   [Stage 0's Text-sibling method — colon-prefix strip]
   - If sender != null && body starts with MAGIC:
     - nonceChar = body.charAt(1)
     - If outboundNonces.contains(nonceChar) → skip (own echo).
     - cjkPayload = body.substring(2)
     - handlePayload(sender, cjkPayload).
```

`extractSender` and `extractBody` are copied directly from `P2PValidationCommand`:
```java
private static String extractSender(Text message) {
    List<Text> sibs = message.getSiblings();
    return sibs.size() >= 3 ? sibs.get(1).getString().trim() : null;
}
private static String extractBody(Text message) {
    List<Text> sibs = message.getSiblings();
    if (sibs.size() < 3) return null;
    String raw = sibs.get(2).getString();
    int colonIdx = raw.indexOf(": ");
    return colonIdx >= 0 ? raw.substring(colonIdx + 2) : null;
}
```

### `tryParseAnnounce(String text, String senderName)`

```
1. Strip ZWSP from end.
2. Extract all signed integers with regex (-?\d+), take first 4.
3. If fewer than 4 → return (not a Loominary ANNOUNCE).
4. short x = (short) ints[0], y = (short) ints[1], z = (short) ints[2], w = (short) ints[3]
5. long crc = ((x & 0xFFFFL) << 16) | (z & 0xFFFFL)
6. int sharedCount = w & 0xFFFF
7. handleAnnounce(senderName, crc, sharedCount)
```

Note: slot[1] (Y) is ignored — it carries no payload, only realism.

### `handleAnnounce(String senderName, long catalogueCrc, int sharedCount)`

```
1. Upsert PeerInfo in peers (keyed by senderName), update lastSeen, catalogueCrc, sharedCount.
2. If peer.catalogueCrc changed && !peer.indexRequested:
   peer.indexRequested = true
   peer.indexRequestedAt = now
   enqueue(senderName, buildGetIndex())
```

### `handlePayload(String senderName, String cjkBody)`

```
1. byte[] decoded = CjkCodec.decode(cjkBody)
2. If decoded.length == 0 → return.
3. Switch on decoded[0]:
   0x03 → handleIndexEntry(senderName, decoded)
   0x04 → handleRequest(senderName, decoded)
   0x05 → handleData(senderName, decoded)
   0x06 → handleAck(senderName, decoded)
   0x07 → handleIndexDone(senderName)
   else → log unknown type
```

### `sendAnnounce(MinecraftClient client)`

```java
long crc = computeCatalogueCrc(); // CRC32 of sorted shareEnabled entry ids
int sharedCount = (int) CatalogueState.entries.stream().filter(e -> e.shareEnabled).count();
short x = (short)(crc >>> 16);
short z = (short)(crc & 0xFFFF);
short y = (short)(60 + ThreadLocalRandom.current().nextInt(8)); // [60..67]
short w = (short) Math.min(sharedCount, 32767);

String template = TEMPLATES[announceTemplateIdx++ % TEMPLATES.length];
String text = template
    .replace("{x}", String.valueOf((int)x))
    .replace("{y}", String.valueOf((int)y))
    .replace("{z}", String.valueOf((int)z))
    .replace("{w}", String.valueOf((int)w))
    + ZWSP;
client.getNetworkHandler().sendChatMessage(text);
```

### `sendIndexEntries(String targetName)`

For each `shareEnabled` entry, enqueue one INDEX_ENTRY whisper.
After all entries, enqueue INDEX_DONE.
All messages go through the unified `outboundQueue`.

```java
for (CatalogueEntry entry : CatalogueState.entries) {
    if (!entry.shareEnabled) continue;
    byte[] json = GSON.toJson(entry).getBytes(StandardCharsets.UTF_8);
    byte[] compressed = Zstd.compress(json, 3);
    byte[] payload = buildIndexEntry(entry, compressed.length);
    enqueue(targetName, CjkCodec.encode(payload));
}
enqueue(targetName, CjkCodec.encode(new byte[]{0x07})); // INDEX_DONE
```

### `handleIndexEntry(String senderName, byte[] decoded)`

```
1. Parse: entryId (bytes 1–16), compressedSize (bytes 17–20), gridCols, gridRows, frameCount, title, author.
2. Create RemoteEntry stub (no assembled buffer — not downloading yet).
3. peers.get(senderName).index.put(entryId, entry)
4. Log: [Loominary] <senderName> has "<title>" (CxR, N frames, X KB)
```

### `handleIndexDone(String senderName)`

```
1. peers.get(senderName).indexRequested = false
2. Log: [Loominary] Index from <senderName> complete (N entries)
```

### `handleRequest(String senderName, byte[] decoded)`

```
1. entryId = readEntryId(decoded, 1)
2. Find entry in CatalogueState with matching id and shareEnabled. If not found → ignore.
3. byte[] json = GSON.toJson(entry).getBytes(UTF_8)
4. byte[] compressed = Zstd.compress(json, 3)
5. If upload != null → reject (one upload at a time; log "busy, try again")
6. Create UploadState, set nextSeq=0, totalFragments=ceil(compressed.length/FRAGMENT_DATA_BYTES)
7. Enqueue first DATA fragment immediately (ticksUntilNext=0)
```

### `enqueueNextFragment()`

Called after each ACK and on upload start:

```java
int off = upload.nextSeq * FRAGMENT_DATA_BYTES;
int len = Math.min(FRAGMENT_DATA_BYTES, upload.payload.length - off);
upload.lastFragData = Arrays.copyOfRange(upload.payload, off, off + len);
upload.lastFragCrc = crc32(upload.lastFragData);

byte[] packet = buildDataPacket(upload.entryId, upload.nextSeq, upload.totalFragments, upload.lastFragData);
enqueue(upload.targetName, CjkCodec.encode(packet));
upload.lastSentAt = System.currentTimeMillis();
```

### `handleAck(String senderName, byte[] decoded)`

```
1. If upload == null || !senderName.equals(upload.targetName) → ignore stale.
2. entryIdPrefix = bytes 1–8, seq = bytes 9–10, crc32 = bytes 11–14, nextExpected = bytes 15–16.
3. If seq != upload.nextSeq → ignore stale.
4. If crc32 != upload.lastFragCrc → retransmit (increment retryCount, check MAX_RETRIES).
5. upload.nextSeq = max(seq+1, nextExpected)  // gap-skip
6. upload.retryCount = 0
7. If upload.nextSeq >= upload.totalFragments → finish upload, log, clear upload.
8. Else → enqueueNextFragment()
```

### `handleData(String senderName, byte[] decoded)`

```
1. entryIdPrefix (first 8 bytes of decoded[1..8])
2. Find matching RemoteEntry in downloads by linear scan for entryId prefix match.
   (downloads typically has 1–3 entries; O(N) is fine)
3. seq = decoded[9..10], total = decoded[11..12], fragData = decoded[13..]
4. int off = seq * FRAGMENT_DATA_BYTES
5. System.arraycopy(fragData, 0, entry.assembled, off, fragData.length)
6. entry.receivedSeqs.set(seq)
7. entry.lastFragmentAt = System.currentTimeMillis()
8. Send ACK: enqueue(senderName, CjkCodec.encode(buildAck(entry.entryId, seq, crc32(fragData), firstMissingSeq(entry))))
9. If entry.receivedSeqs.cardinality() == entry.totalFragments → finishDownload(entry)
```

`firstMissingSeq(entry)`: returns lowest index not set in `receivedSeqs` — this is the `nextExpected` gap-skip hint.

### `finishDownload(RemoteEntry entry)`

```
1. Look up full CatalogueEntry in downloads (we have byte[] assembled = compressed bytes).
2. long contentSize = Zstd.getFrameContentSize(entry.assembled)
3. byte[] json = Zstd.decompress(entry.assembled, (int)contentSize)
4. CatalogueEntry parsed = GSON.fromJson(new String(json, UTF_8), CatalogueEntry.class)
5. parsed.sourcePeer = entry.fromPeer
6. parsed.dateAdded = LocalDate.now().toString()
7. CatalogueState.entries.removeIf(e -> e.id.equals(parsed.id))  // dedup
8. CatalogueState.entries.add(parsed)
9. CatalogueState.save()
10. downloads.remove(entry.entryId)
11. Log: [Loominary] Downloaded "<title>" from <peer> — added to catalogue
```

### `requestEntry(String peerName, String entryId)`

Called by `/loominary broadcast get <peer> <entryId>`.

```
1. RemoteEntry meta = peers.get(peerName).index.get(entryId)
2. Create new RemoteEntry with assembled = new byte[meta.compressedSize], fromPeer = peerName
3. downloads.put(entryId, entry)
4. enqueue(peerName, CjkCodec.encode(buildRequest(entryId)))
```

### `enqueue(String targetName, String cjkBody)`

```java
private static void enqueue(String targetName, String cjkBody) {
    char nonceChar = (char)(CjkCodec.ALPHA_BASE + ThreadLocalRandom.current().nextInt(CjkCodec.ALPHA_SIZE));
    outboundNonces.add(nonceChar);
    if (outboundNonces.size() > MAX_NONCE_HISTORY) {
        outboundNonces.remove(outboundNonces.iterator().next());
    }
    String body = MAGIC + nonceChar + cjkBody;
    outboundQueue.add(new OutboundMsg(targetName, body, false));
}
```

The tick handler drains one `OutboundMsg` per `FRAGMENT_DELAY_TICKS`:
```java
MinecraftClient.getInstance().getNetworkHandler()
    .sendCommand("msg " + msg.targetName + " " + msg.whisperBody);
```

---

## Commands: `/loominary broadcast` Subtree

Add to `LoominaryCommand.java` alongside the existing `catalogue` subtree.

| Subcommand | Action |
|---|---|
| `broadcast start` | Begin 60-second ANNOUNCE beaconing |
| `broadcast stop` | Stop beaconing |
| `broadcast announce` | Send a single ANNOUNCE now |
| `broadcast peers` | List known peers: name, CRC, entry count, last seen, index size |
| `broadcast index <peer>` | Send GET_INDEX to named peer |
| `broadcast get <peer> <entryId>` | Request and download a specific entry |
| `broadcast status` | Show active upload/download progress and queue depth |
| `broadcast debug` | Toggle verbose logging to chat |

Tab-completion: `<peer>` completes from `BroadcastChannel.peers.keySet()`. `<entryId>` completes from the peer's index, displaying title alongside each id.

`broadcast peers` output format:
```
[Loominary] 3 peers:
  Alice — CRC=A3F21B2C  shared=4  index=4 entries  seen=12s ago
  Bob   — CRC=09FF4411  shared=0  index=0 entries  seen=4m ago  (fetching index...)
```

`broadcast status` output format:
```
[Loominary] Upload: "Nyan Cat" → Alice  frag 23/79  retry=0  queue=2
[Loominary] Download: "Space" from Bob  frag 14/34  timeout in 4m52s
[Loominary] Outbound queue: 5 msgs
```

---

## `ClientModInit.java` Change

Replace:
```java
P2PValidationCommand.register(dispatcher);
```
With:
```java
BroadcastChannel.register();
```

The `dispatcher` arg is not needed since `BroadcastChannel` registers its own Fabric events.
`P2PValidationCommand.java` can be deleted — it is superseded.

---

## Constants Summary

```java
static final int FRAGMENT_DATA_BYTES    = 384;   // verified against 230-char body budget
static final int FRAGMENT_DELAY_TICKS   = 12;    // 600ms target — validate in Stage 1
static final int BEACON_INTERVAL_TICKS  = 1200;  // 60 s
static final int ACK_TIMEOUT_TICKS      = 500;   // 25 s
static final int MAX_RETRIES            = 3;
static final int MAX_PEERS              = 128;
static final char MAGIC                 = '踈';   // U+8E08
static final String ZWSP               = "​";
```

---

## Stage 1 Empirical Test Procedure

Before implementing, validate on 2b2t:

1. **U+200B survival:** Send `hello world​` in public chat. Second client reads `message.getString()` and checks for U+200B presence.
2. **Public chat length limit:** Send a 256-char message; binary-search down if rejected.
3. **Template anti-spam:** Send the same filled template 3× at 60-second intervals. Confirm no kick/mute.
4. **Fragment delay:** Run a test transfer at 600ms/frag. If fragments drop or rate-limit kicks in, back off toward 10s (Stage 0's empirical baseline).
5. **Whisper echo format:** Confirm `extractSender`/`extractBody` from Stage 0 still work (Text sibling structure may change with Velocity updates).

Fallback if U+200B stripped: detect ANNOUNCE by checking parity of `ints[1]` (even) and `ints[3]` (odd) as a convention-based signature, encoding 1 bit each in the Y and aux slots.

---

## Open Questions / Future Work

- **Parallel uploads.** Current design: one upload at a time. A per-peer upload queue (`Map<String, Queue<UploadState>>`) would support concurrent uploads; add when single-upload limitation is observed as a UX problem.
- **CatalogueScreen Peers panel.** Add a Peers tab to `CatalogueScreen` showing discovered peers, their shared art, and a Download button wired to `BroadcastChannel.requestEntry()`.
- **Resume interrupted downloads.** If a session ends mid-transfer, save `RemoteEntry` state to disk (analogous to Stage 0's `receiverSavePath()`). On re-enable, re-send REQUEST with `nextExpected` set to `firstMissingSeq(entry)` and hope the peer is still online.
- **Multi-sender security.** Currently anyone can send a REQUEST for a shared entry. No authentication. Acceptable for now — the only cost is CPU/bandwidth for unwanted transfers.
- **Thumbnail in INDEX_ENTRY.** Include a scaled-down 5×5 mode-pool thumbnail (say 25 bytes, CJK-encoded in the INDEX_ENTRY itself) so the receiver can display a tiny preview before downloading.
