package vn.aimtt.trello;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import vn.aimtt.common.ApiException;
import org.springframework.http.HttpStatus;

/** AES-256-GCM for Trello tokens and PKCE verifiers; the key lives outside the DB (TOKEN_ENCRYPTION_KEY). */
@Component
public class TokenCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;
    private final int version;

    public TokenCipher(TrelloProperties properties) {
        this.version = properties.keyVersion();
        if (!properties.encryptionConfigured()) { key = null; return; }
        byte[] raw;
        try { raw = Base64.getDecoder().decode(properties.tokenEncryptionKey().trim()); }
        catch (IllegalArgumentException e) { throw new IllegalStateException("TOKEN_ENCRYPTION_KEY must be base64."); }
        if (raw.length != 32) throw new IllegalStateException("TOKEN_ENCRYPTION_KEY must decode to 32 bytes.");
        key = new SecretKeySpec(raw, "AES");
    }
    public boolean ready() { return key != null; }
    public int version() { return version; }
    public String encrypt(String plain) {
        require();
        try {
            byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length); System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return "v" + version + ":" + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Token encryption failed"); }
    }
    public String decrypt(String stored) {
        require();
        try {
            int colon = stored.indexOf(':');
            byte[] all = Base64.getDecoder().decode(stored.substring(colon + 1));
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(cipher.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException | StringIndexOutOfBoundsException e) {
            // Never include the ciphertext or key material in the error.
            throw new ApiException(HttpStatus.CONFLICT, "TRELLO_REAUTH_REQUIRED", "Không giải mã được token Trello đã lưu. Kết nối lại Trello.");
        }
    }
    private void require() {
        if (key == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TRELLO_NOT_CONFIGURED", "Backend chưa cấu hình TOKEN_ENCRYPTION_KEY cho Trello.");
    }
}
