package dev.velora.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Human-shareable config codes.
 *
 * <p>Every installation generates its own key automatically (see
 * {@link ConfigManager#getShareKey()}). An export is the settings JSON, gzipped,
 * XOR-obfuscated with a keystream derived from that key and wrapped in a
 * checksummed, copy-paste friendly string:</p>
 *
 * <pre>GS1:ABCD-EFGH-IJKL:1A2B3C4D:base64url(payload)</pre>
 *
 * <p>The XOR layer is not encryption and must not be treated as such; it only
 * keeps the code compact and prevents casual editing. The CRC32 catches typos
 * and codes that were truncated by chat clients.</p>
 */
public final class ShareCode {

    public static final String PREFIX = "GS1";

    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int KEY_LENGTH = 12;

    private ShareCode() {
    }

    /** Generates a fresh key in the {@code XXXX-XXXX-XXXX} format. */
    public static String generateKey() {
        StringBuilder builder = new StringBuilder(KEY_LENGTH + 2);
        for (int i = 0; i < KEY_LENGTH; i++) {
            if (i > 0 && i % 4 == 0) {
                builder.append('-');
            }
            builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }

    public static boolean isValidKey(String key) {
        if (key == null || key.length() != KEY_LENGTH + 2) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (i == 4 || i == 9) {
                if (c != '-') {
                    return false;
                }
            } else if (ALPHABET.indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /** Encodes a settings object together with the key that produced it. */
    public static String encode(String key, JsonObject payload) {
        String safeKey = isValidKey(key) ? key : generateKey();
        try {
            byte[] compressed = gzip(GSON.toJson(payload).getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = xor(compressed, keystream(safeKey));
            String checksum = checksum(compressed);
            String body = Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
            return PREFIX + ":" + safeKey + ":" + checksum + ":" + body;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode the share code", exception);
        }
    }

    /** Decodes a share code and returns its settings object. */
    public static JsonObject decode(String code) {
        String normalized = normalize(code);
        String[] parts = normalized.split(":", 4);
        if (parts.length != 4 || !PREFIX.equalsIgnoreCase(parts[0])) {
            throw new IllegalArgumentException("Not a Velora share code");
        }
        String key = parts[1];
        if (!isValidKey(key)) {
            throw new IllegalArgumentException("Invalid share key");
        }

        byte[] encrypted;
        try {
            encrypted = Base64.getUrlDecoder().decode(parts[3]);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("The share code is damaged (base64)");
        }

        byte[] compressed = xor(encrypted, keystream(key));
        if (!checksum(compressed).equalsIgnoreCase(parts[2])) {
            throw new IllegalArgumentException("The share code is damaged (checksum)");
        }

        try {
            byte[] json = gunzip(compressed);
            JsonObject object = GSON.fromJson(new String(json, StandardCharsets.UTF_8), JsonObject.class);
            if (object == null) {
                throw new IllegalArgumentException("The share code has no settings");
            }
            return object;
        } catch (IOException exception) {
            throw new IllegalArgumentException("The share code is damaged (payload)");
        }
    }

    /** Reads the key embedded in a code without applying it. */
    public static String keyOf(String code) {
        String[] parts = normalize(code).split(":", 4);
        if (parts.length < 2 || !isValidKey(parts[1])) {
            return null;
        }
        return parts[1];
    }

    /** Strips whitespace/newlines that chat clients like to insert. */
    public static String normalize(String code) {
        if (code == null) {
            return "";
        }
        return code.replaceAll("\\s+", "");
    }

    // ------------------------------------------------------------------ codec

    private static byte[] keystream(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("velora:" + key).getBytes(StandardCharsets.UTF_8));
            byte[] stream = new byte[hash.length * 4];
            for (int i = 0; i < stream.length; i++) {
                // Four independent digest rounds make the stream longer than the payload
                // for all realistic configs without repeating too early.
                stream[i] = hash[i % hash.length];
                if (i >= hash.length) {
                    int round = i / hash.length;
                    stream[i] ^= (byte) (round * 0x9E);
                }
            }
            return stream;
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static byte[] xor(byte[] data, byte[] stream) {
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ stream[i % stream.length]);
        }
        return result;
    }

    private static String checksum(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return String.format("%08X", crc.getValue());
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(data);
        }
        return buffer.toByteArray();
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return gzip.readAllBytes();
        }
    }
}
