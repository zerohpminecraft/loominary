package net.zerohpminecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/**
 * Encrypts and decrypts Loominary map payloads.
 *
 * <h3>Wire format</h3>
 *
 * <b>Version 2</b> (no metadata):
 * <pre>
 *   [0-3]    magic "LOOM"
 *   [4]      version = 0x02
 *   [5-16]   data_nonce : 12 bytes
 *   [17-272] key_slot : 256 bytes
 *   [273]    password_slot_count : u8
 *   For each password slot (76 bytes):
 *     [0-15]   PBKDF2 salt   [16-27] slot GCM nonce   [28-75] AES-256-GCM(K_data, K_pass)
 *   [..]  AES-256-GCM(zstd_payload) + 16-byte appended tag
 * </pre>
 *
 * <b>Version 3</b> (adds plaintext author/title before ciphertext):
 * <pre>
 *   [0-273 + pw slots]  same as v2
 *   [..]  author_len : u8  (0 = absent; max 16 UTF-8 bytes)
 *   [..]  author : UTF-8
 *   [..]  title_len  : u8  (0 = absent; max 64 UTF-8 bytes)
 *   [..]  title  : UTF-8
 *   [..]  AES-256-GCM(zstd_payload) + 16-byte appended tag
 * </pre>
 *
 * <p>The author and title fields are plaintext — readable without the password.
 * Use {@link #readMeta} to extract them. The ciphertext is unaffected.
 */
public class MapEncryption {
    static final String KEY_FILE = "loominary.key";

    // ── Wire format offsets ───────────────────────────────────────────────

    static final int V2_OFF_DATA_NONCE  = 5;   // 12 bytes
    static final int V2_OFF_ASYM_SLOT   = 17;  // 256 bytes
    static final int V2_OFF_PW_COUNT    = 273; // 1 byte
    static final int V2_OFF_PW_SLOTS    = 274;

    static final int DATA_NONCE_LEN  = 12;
    static final int ASYM_SLOT_BYTES = 256;
    static final int PBKDF2_SALT_LEN = 16;
    static final int SLOT_NONCE_LEN  = 12;
    static final int WRAPPED_KEY_LEN = 48; // 32 ct + 16 GCM tag
    static final int SLOT_BYTES      = PBKDF2_SALT_LEN + SLOT_NONCE_LEN + WRAPPED_KEY_LEN; // 76
    static final int AES_KEY_BYTES   = 32;
    static final int GCM_TAG_BITS    = 128;
    static final int PBKDF2_ITERATIONS = 100_000;

    // Minimum valid v2 envelope: header + 0 pw slots + 16-byte GCM tag.
    static final int MIN_V2_BYTES = V2_OFF_PW_SLOTS + 16;
    private static final byte[] PUB_KEY_DER = {
        (byte)0x30,(byte)0x82,(byte)0x01,(byte)0x22,(byte)0x30,(byte)0x0d,(byte)0x06,(byte)0x09,
        (byte)0x2a,(byte)0x86,(byte)0x48,(byte)0x86,(byte)0xf7,(byte)0x0d,(byte)0x01,(byte)0x01,
        (byte)0x01,(byte)0x05,(byte)0x00,(byte)0x03,(byte)0x82,(byte)0x01,(byte)0x0f,(byte)0x00,
        (byte)0x30,(byte)0x82,(byte)0x01,(byte)0x0a,(byte)0x02,(byte)0x82,(byte)0x01,(byte)0x01,
        (byte)0x00,(byte)0xee,(byte)0xf7,(byte)0x33,(byte)0x01,(byte)0xce,(byte)0xed,(byte)0x4e,
        (byte)0x96,(byte)0xf2,(byte)0x71,(byte)0xfb,(byte)0xa1,(byte)0xfb,(byte)0x9d,(byte)0x4b,
        (byte)0x27,(byte)0x2c,(byte)0x1d,(byte)0x5c,(byte)0xa7,(byte)0xf9,(byte)0x2f,(byte)0xf7,
        (byte)0xa4,(byte)0xb1,(byte)0xa9,(byte)0x4e,(byte)0x69,(byte)0xc7,(byte)0x47,(byte)0xae,
        (byte)0x5f,(byte)0xc6,(byte)0x67,(byte)0x71,(byte)0x48,(byte)0x93,(byte)0x6f,(byte)0x95,
        (byte)0xcc,(byte)0x64,(byte)0x34,(byte)0x84,(byte)0x26,(byte)0xb3,(byte)0xeb,(byte)0x05,
        (byte)0x05,(byte)0x4b,(byte)0x32,(byte)0x9f,(byte)0xd3,(byte)0x22,(byte)0x8e,(byte)0x3c,
        (byte)0x4f,(byte)0x39,(byte)0x74,(byte)0x98,(byte)0xcb,(byte)0x63,(byte)0x3a,(byte)0x0b,
        (byte)0x62,(byte)0x68,(byte)0x0f,(byte)0x5b,(byte)0x8c,(byte)0xd1,(byte)0x36,(byte)0x68,
        (byte)0x07,(byte)0xe2,(byte)0x4f,(byte)0x02,(byte)0x3b,(byte)0x29,(byte)0xde,(byte)0x6d,
        (byte)0x27,(byte)0x17,(byte)0xa5,(byte)0x4c,(byte)0xd2,(byte)0xef,(byte)0xcf,(byte)0x29,
        (byte)0x8b,(byte)0xcd,(byte)0x0e,(byte)0xa0,(byte)0xe3,(byte)0xbf,(byte)0xee,(byte)0x53,
        (byte)0x6c,(byte)0x4e,(byte)0x67,(byte)0x85,(byte)0xa8,(byte)0x61,(byte)0x69,(byte)0xeb,
        (byte)0x40,(byte)0x31,(byte)0x80,(byte)0x96,(byte)0xdd,(byte)0xce,(byte)0x5f,(byte)0xee,
        (byte)0xf8,(byte)0x06,(byte)0xe7,(byte)0xa6,(byte)0x40,(byte)0x3a,(byte)0x56,(byte)0x0d,
        (byte)0x63,(byte)0x43,(byte)0xd9,(byte)0xe0,(byte)0x3c,(byte)0xbf,(byte)0xea,(byte)0xb2,
        (byte)0xeb,(byte)0xd9,(byte)0x54,(byte)0x69,(byte)0x7d,(byte)0xcb,(byte)0xe2,(byte)0x65,
        (byte)0x4c,(byte)0xed,(byte)0xac,(byte)0xd9,(byte)0x28,(byte)0x82,(byte)0xf4,(byte)0x98,
        (byte)0x5f,(byte)0x5d,(byte)0x65,(byte)0xe7,(byte)0x93,(byte)0x09,(byte)0x5a,(byte)0x33,
        (byte)0x3f,(byte)0xdf,(byte)0x70,(byte)0x76,(byte)0xaa,(byte)0x54,(byte)0x8d,(byte)0xdc,
        (byte)0x41,(byte)0x60,(byte)0xe3,(byte)0xd6,(byte)0x5c,(byte)0x55,(byte)0xd8,(byte)0x9c,
        (byte)0x19,(byte)0x87,(byte)0x9e,(byte)0x83,(byte)0x9c,(byte)0xbf,(byte)0x90,(byte)0x47,
        (byte)0xb9,(byte)0x23,(byte)0xd9,(byte)0x58,(byte)0x0f,(byte)0x07,(byte)0xa7,(byte)0xf0,
        (byte)0x49,(byte)0x3a,(byte)0xb5,(byte)0x59,(byte)0xf5,(byte)0x35,(byte)0x66,(byte)0x99,
        (byte)0x70,(byte)0xad,(byte)0x71,(byte)0xb7,(byte)0x94,(byte)0xb3,(byte)0x29,(byte)0xfe,
        (byte)0xa5,(byte)0x5e,(byte)0x64,(byte)0x6f,(byte)0x63,(byte)0x0b,(byte)0xf1,(byte)0x0e,
        (byte)0xd2,(byte)0xcc,(byte)0x37,(byte)0x0e,(byte)0x0c,(byte)0x08,(byte)0x28,(byte)0x68,
        (byte)0xa2,(byte)0xda,(byte)0xb7,(byte)0x7c,(byte)0xbb,(byte)0xb9,(byte)0x47,(byte)0xf4,
        (byte)0x9f,(byte)0xfc,(byte)0x68,(byte)0x77,(byte)0xcb,(byte)0xc7,(byte)0x4f,(byte)0x65,
        (byte)0x91,(byte)0x58,(byte)0x3d,(byte)0x37,(byte)0x62,(byte)0xbb,(byte)0x17,(byte)0xe9,
        (byte)0xd2,(byte)0xdc,(byte)0x3a,(byte)0xf8,(byte)0x66,(byte)0x6f,(byte)0x59,(byte)0xce,
        (byte)0xa8,(byte)0x21,(byte)0xa9,(byte)0x69,(byte)0xd8,(byte)0x91,(byte)0xae,(byte)0x93,
        (byte)0x45,(byte)0x02,(byte)0x03,(byte)0x01,(byte)0x00,(byte)0x01
    };

    private static final PublicKey PUB_KEY;
    static {
        try {
            PUB_KEY = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(PUB_KEY_DER));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static volatile PrivateKey loadedPrivKey = null;

    // ── Password list ─────────────────────────────────────────────────────

    /** User-added passwords tried when decrypting symmetric slots. */
    public static final List<String> passwords = new ArrayList<>();

    /** Passwords tried and failed per mapId; cleared on disconnect. */
    private static final Map<Integer, Set<String>> failedCache = new HashMap<>();

    private static final Gson GSON = new GsonBuilder().create();
    private static final String PASSWORDS_FILE = "loominary_passwords.json";

    // ── Public API ────────────────────────────────────────────────────────

    public static boolean isEncrypted(byte[] data) {
        if (data == null || data.length < MIN_V2_BYTES) return false;
        if (!(data[0] == 'L' && data[1] == 'O' && data[2] == 'O' && data[3] == 'M')) return false;
        int v = data[4] & 0xFF;
        return v == 2 || v == 3;
    }

    /**
     * Encrypts {@code zstdData} without plaintext metadata (version 2 envelope).
     */
    public static byte[] encrypt(byte[] zstdData, List<String> pwSlots) throws Exception {
        return encrypt(zstdData, pwSlots, null, null);
    }

    /**
     * Encrypts {@code zstdData}. When {@code author} or {@code title} are non-null
     * a version 3 envelope is produced; the strings are stored in plaintext before
     * the ciphertext so they can be read by anyone without the password.
     */
    public static byte[] encrypt(byte[] zstdData, List<String> pwSlots,
                                  String author, String title) throws Exception {
        SecureRandom rng = new SecureRandom();

        byte[] dataKey = new byte[AES_KEY_BYTES];
        rng.nextBytes(dataKey);
        SecretKey aesKey = new SecretKeySpec(dataKey, "AES");

        byte[] dataNonce = new byte[DATA_NONCE_LEN];
        rng.nextBytes(dataNonce);

        Cipher gcmData = Cipher.getInstance("AES/GCM/NoPadding");
        gcmData.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, dataNonce));
        byte[] ciphertext = gcmData.doFinal(zstdData);

        byte[] asymSlot = rsaEncrypt(dataKey);

        int pwCount = pwSlots.size();
        byte[] pwBytes = new byte[pwCount * SLOT_BYTES];
        for (int i = 0; i < pwCount; i++) {
            byte[] salt  = new byte[PBKDF2_SALT_LEN];
            byte[] snonce = new byte[SLOT_NONCE_LEN];
            rng.nextBytes(salt);
            rng.nextBytes(snonce);
            SecretKey passKey = deriveKey(pwSlots.get(i), salt);
            Cipher w = Cipher.getInstance("AES/GCM/NoPadding");
            w.init(Cipher.ENCRYPT_MODE, passKey, new GCMParameterSpec(GCM_TAG_BITS, snonce));
            byte[] wrapped = w.doFinal(dataKey);
            int off = i * SLOT_BYTES;
            System.arraycopy(salt,    0, pwBytes, off,      PBKDF2_SALT_LEN);
            System.arraycopy(snonce,  0, pwBytes, off + 16, SLOT_NONCE_LEN);
            System.arraycopy(wrapped, 0, pwBytes, off + 28, WRAPPED_KEY_LEN);
        }

        byte[] authorBytes = encodeStr(author, 16);
        byte[] titleBytes  = encodeStr(title,  64);
        boolean hasMeta = authorBytes.length > 0 || titleBytes.length > 0;
        int version = hasMeta ? 3 : 2;

        int size = V2_OFF_PW_SLOTS + pwBytes.length
                + (hasMeta ? 1 + authorBytes.length + 1 + titleBytes.length : 0)
                + ciphertext.length;
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(new byte[]{'L','O','O','M'});
        buf.put((byte) version);
        buf.put(dataNonce);
        buf.put(asymSlot);
        buf.put((byte) pwCount);
        buf.put(pwBytes);
        if (hasMeta) {
            buf.put((byte) authorBytes.length);
            buf.put(authorBytes);
            buf.put((byte) titleBytes.length);
            buf.put(titleBytes);
        }
        buf.put(ciphertext);
        return buf.array();
    }

    /**
     * Reads the plaintext author and title from an encrypted envelope without
     * decrypting it. Returns {@code null} if the data is not a v3 envelope.
     * The returned array is always {@code [author, title]}, either element may be null.
     */
    public static String[] readMeta(byte[] envelope) {
        if (envelope == null || envelope.length < MIN_V2_BYTES) return null;
        if (!(envelope[0] == 'L' && envelope[1] == 'O' && envelope[2] == 'O' && envelope[3] == 'M')) return null;
        if ((envelope[4] & 0xFF) != 3) return null;
        int pwCount = envelope[V2_OFF_PW_COUNT] & 0xFF;
        int off = V2_OFF_PW_SLOTS + pwCount * SLOT_BYTES;
        if (off + 1 > envelope.length) return null;
        int aLen = envelope[off] & 0xFF;
        if (off + 1 + aLen > envelope.length) return null;
        String author = aLen > 0 ? new String(envelope, off + 1, aLen, StandardCharsets.UTF_8) : null;
        off += 1 + aLen;
        if (off + 1 > envelope.length) return new String[]{author, null};
        int tLen = envelope[off] & 0xFF;
        if (off + 1 + tLen > envelope.length) return new String[]{author, null};
        String title = tLen > 0 ? new String(envelope, off + 1, tLen, StandardCharsets.UTF_8) : null;
        return new String[]{author, title};
    }

    /**
     * Attempts to decrypt {@code envelope}. Tries each stored password against each slot.
     *
     * @param mapId used for the per-password fail cache; -1 disables caching
     * @return decrypted zstd bytes, or {@code null} if nothing matched
     */
    public static synchronized byte[] tryDecrypt(byte[] envelope, int mapId) {
        if (!isEncrypted(envelope)) return envelope;

        int version      = envelope[4] & 0xFF;
        byte[] dataNonce = Arrays.copyOfRange(envelope, V2_OFF_DATA_NONCE, V2_OFF_DATA_NONCE + DATA_NONCE_LEN);
        byte[] asymSlot  = Arrays.copyOfRange(envelope, V2_OFF_ASYM_SLOT,  V2_OFF_ASYM_SLOT  + ASYM_SLOT_BYTES);
        int pwCount      = envelope[V2_OFF_PW_COUNT] & 0xFF;

        // For v3 envelopes, skip over the plaintext metadata to reach the ciphertext.
        int ciphOff = V2_OFF_PW_SLOTS + pwCount * SLOT_BYTES;
        if (version == 3 && ciphOff + 2 <= envelope.length) {
            int aLen = envelope[ciphOff] & 0xFF;
            ciphOff += 1 + aLen;
            if (ciphOff + 1 <= envelope.length) {
                int tLen = envelope[ciphOff] & 0xFF;
                ciphOff += 1 + tLen;
            }
        }

        byte[] ciphertext = Arrays.copyOfRange(envelope, ciphOff, envelope.length);

        if (loadedPrivKey != null) {
            byte[] result = tryAsymSlot(asymSlot, dataNonce, ciphertext);
            if (result != null) return result;
        }

        return tryPasswordSlots(envelope, dataNonce, pwCount, ciphertext, mapId);
    }

    /** Clears per-map fail cache; call on disconnect. */
    public static synchronized void clearCache() {
        failedCache.clear();
    }

    public static void tryLoadKeyFile() {
        Path p = FabricLoader.getInstance().getConfigDir().resolve(KEY_FILE);
        if (!Files.exists(p)) return;
        try {
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(p)));
            byte[] probe = new byte[32];
            byte[] enc   = rsaEncrypt(probe);
            byte[] dec   = rsaDecrypt(enc, key);
            if (!Arrays.equals(probe, dec)) throw new Exception("key mismatch");
            loadedPrivKey = key;
        } catch (Exception e) {
            System.err.println("[Loominary] " + KEY_FILE + ": " + e.getMessage());
        }
    }

    // ── Password persistence ──────────────────────────────────────────────

    public static synchronized void loadPasswords() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(PASSWORDS_FILE);
        if (!Files.exists(path)) return;
        try {
            String[] loaded = GSON.fromJson(Files.readString(path), String[].class);
            passwords.clear();
            if (loaded != null)
                for (String p : loaded)
                    if (p != null && !p.isEmpty() && !passwords.contains(p))
                        passwords.add(p);
        } catch (IOException e) {
            System.err.println("[Loominary] Failed to load passwords: " + e.getMessage());
        }
    }

    public static synchronized void savePasswords() {
        Path dir = FabricLoader.getInstance().getConfigDir();
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(PASSWORDS_FILE),
                    GSON.toJson(passwords.toArray(new String[0])));
        } catch (IOException e) {
            System.err.println("[Loominary] Failed to save passwords: " + e.getMessage());
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private static byte[] tryAsymSlot(byte[] asymSlot, byte[] dataNonce, byte[] ciphertext) {
        try {
            byte[] dataKey  = rsaDecrypt(asymSlot, loadedPrivKey);
            SecretKey aesKey = new SecretKeySpec(dataKey, "AES");
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, dataNonce));
            return gcm.doFinal(ciphertext);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] tryPasswordSlots(byte[] envelope, byte[] dataNonce,
            int pwCount, byte[] ciphertext, int mapId) {
        Set<String> failed = mapId >= 0
                ? failedCache.computeIfAbsent(mapId, k -> new HashSet<>())
                : null;

        for (String pw : passwords) {
            if (failed != null && failed.contains(pw)) continue;
            for (int s = 0; s < pwCount; s++) {
                int off      = V2_OFF_PW_SLOTS + s * SLOT_BYTES;
                byte[] salt      = Arrays.copyOfRange(envelope, off,      off + 16);
                byte[] slotNonce = Arrays.copyOfRange(envelope, off + 16, off + 28);
                byte[] wrapped   = Arrays.copyOfRange(envelope, off + 28, off + 76);
                try {
                    SecretKey passKey = deriveKey(pw, salt);
                    Cipher gcmWrap = Cipher.getInstance("AES/GCM/NoPadding");
                    gcmWrap.init(Cipher.DECRYPT_MODE, passKey,
                            new GCMParameterSpec(GCM_TAG_BITS, slotNonce));
                    byte[] dataKey = gcmWrap.doFinal(wrapped);
                    Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
                    gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                            new GCMParameterSpec(GCM_TAG_BITS, dataNonce));
                    return gcm.doFinal(ciphertext);
                } catch (Exception ignored) {}
            }
            if (failed != null) failed.add(pw);
        }
        return null;
    }

    private static byte[] rsaEncrypt(byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        c.init(Cipher.ENCRYPT_MODE, PUB_KEY, oaepSpec());
        return c.doFinal(data);
    }

    private static byte[] rsaDecrypt(byte[] data, PrivateKey key) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        c.init(Cipher.DECRYPT_MODE, key, oaepSpec());
        return c.doFinal(data);
    }

    private static OAEPParameterSpec oaepSpec() {
        return new OAEPParameterSpec("SHA-256", "MGF1",
                MGF1ParameterSpec.SHA256, javax.crypto.spec.PSource.PSpecified.DEFAULT);
    }

    private static byte[] encodeStr(String s, int maxBytes) {
        if (s == null || s.isEmpty()) return new byte[0];
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        return raw.length <= maxBytes ? raw : Arrays.copyOf(raw, maxBytes);
    }

    private static SecretKey deriveKey(String password, byte[] salt) throws Exception {
        SecretKeyFactory kdf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_BYTES * 8);
        try {
            return new SecretKeySpec(kdf.generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }
}
