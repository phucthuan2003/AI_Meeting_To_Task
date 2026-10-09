package vn.aimtt.trello;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Trello integration settings (SDS §10.2, §12.3). OAuth 2.0 confidential client is the primary mode;
 * API key + user token is an explicit alternative for development/demo boards. Secrets never leave the backend.
 */
@ConfigurationProperties("app.trello")
public record TrelloProperties(String clientId, String clientSecret, String callbackUrl, String scopes,
                               String authorizeUrl, String tokenUrl, String apiBaseUrl, String apiKey, String appName,
                               String tokenEncryptionKey, int keyVersion, Duration requestTimeout, Duration transactionTtl,
                               boolean allowLocalHttp, String cardUrlHosts) {
    public TrelloProperties {
        if (requestTimeout == null || requestTimeout.toSeconds() < 2 || requestTimeout.toSeconds() > 120) throw new IllegalArgumentException("Trello timeout out of bounds.");
        if (transactionTtl == null || transactionTtl.toMinutes() < 1 || transactionTtl.toMinutes() > 30) throw new IllegalArgumentException("OAuth transaction TTL out of bounds.");
        for (String url : new String[] {authorizeUrl, tokenUrl, apiBaseUrl}) checkUrl(url, allowLocalHttp);
        if (callbackUrl != null && !callbackUrl.isBlank()) checkUrl(callbackUrl, allowLocalHttp);
        if (keyVersion < 1) throw new IllegalArgumentException("Key version must be positive.");
    }
    static void checkUrl(String value, boolean allowLocalHttp) {
        URI uri;
        try { uri = URI.create(value); } catch (Exception e) { throw new IllegalArgumentException("Invalid Trello URL."); }
        boolean local = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost());
        if (uri.getUserInfo() != null || uri.getHost() == null || !("https".equals(uri.getScheme()) || (allowLocalHttp && local && "http".equals(uri.getScheme())))) {
            throw new IllegalArgumentException("Trello URLs must be HTTPS (HTTP only for loopback with allow-local-http).");
        }
    }
    public boolean oauthConfigured() { return present(clientId) && present(clientSecret) && present(callbackUrl); }
    public boolean tokenModeConfigured() { return present(apiKey); }
    public boolean encryptionConfigured() { return present(tokenEncryptionKey); }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
    @Override public String toString() {
        return "TrelloProperties[oauth=" + oauthConfigured() + ", tokenMode=" + tokenModeConfigured() + ", apiBaseUrl=" + apiBaseUrl + "]";
    }
}
