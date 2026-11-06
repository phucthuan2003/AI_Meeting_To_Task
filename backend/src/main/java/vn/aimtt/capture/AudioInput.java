package vn.aimtt.capture;

import java.security.MessageDigest;
import java.util.*;
import java.nio.charset.StandardCharsets;
import vn.aimtt.common.ApiException;

public final class AudioInput {
    private AudioInput() {}
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static String validate(String filename, byte[] bytes) {
        String name = Optional.ofNullable(filename).orElse("").toLowerCase(Locale.ROOT);
        String ext = name.substring(name.lastIndexOf('.') + 1);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw ApiException.invalid("Audio cần từ 1 byte tới 10 MiB.");
        String prefix = new String(bytes, 0, Math.min(bytes.length, 16), StandardCharsets.ISO_8859_1);
        boolean valid = switch (ext) {
            case "wav" -> prefix.startsWith("RIFF") && prefix.length() >= 12 && prefix.substring(8,12).equals("WAVE");
            case "webm" -> bytes.length > 4 && (bytes[0]&255)==0x1a && (bytes[1]&255)==0x45 && (bytes[2]&255)==0xdf && (bytes[3]&255)==0xa3;
            case "mp3" -> prefix.startsWith("ID3") || bytes.length > 2 && (bytes[0]&255)==255 && (bytes[1]&0xe0)==0xe0;
            case "m4a" -> prefix.length() >= 8 && prefix.substring(4,8).equals("ftyp");
            default -> false;
        };
        if (!valid) throw ApiException.invalid("Dùng audio WAV, MP3, M4A hoặc WebM đúng container; backend/STT còn kiểm tra khả năng giải mã.");
        return ext;
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(); }
    }
}
