package vn.aimtt.trello;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.aimtt.common.ApiException;
import vn.aimtt.trello.TrelloClient.*;

/**
 * Trello connection lifecycle (SDS §6.6, §10.2). OAuth transactions are bound to the user/session, short-lived and
 * single-use; tokens are encrypted at rest and only ever used by the backend.
 */
@Service
public class TrelloConnectionService {
    private static final Logger log = LoggerFactory.getLogger(TrelloConnectionService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    public record Connection(UUID id, UUID userId, String trelloMemberId, String username, String fullName, String authType, String status,
                             String accessTokenEnc, String refreshTokenEnc, Instant expiresAt, String scopes, Instant createdAt, Instant updatedAt) {}
    public record ConnectionView(UUID connectionId, String trelloMemberId, String username, String fullName, String authType, String status,
                                 String scopes, Instant connectedAt, Instant updatedAt) {}
    public record ConfigView(boolean encryptionReady, boolean oauthAvailable, boolean tokenModeAvailable, String tokenAuthorizeUrl) {}
    public record AuthorizationView(UUID transactionId, String authorizationUrl, Instant expiresAt) {}
    public record TransactionView(UUID transactionId, String status, String errorCode, UUID connectionId, Instant expiresAt) {}
    public record CallbackResult(boolean success, String code) {}

    private final JdbcTemplate jdbc;
    private final TrelloProperties properties;
    private final TrelloClient client;
    private final TokenCipher cipher;
    private final TransactionTemplate tx;
    private final RowMapper<Connection> mapper = (r, n) -> new Connection(r.getObject("id", UUID.class), r.getObject("user_id", UUID.class),
            r.getString("trello_member_id"), r.getString("trello_username"), r.getString("trello_full_name"), r.getString("auth_type"),
            r.getString("status"), r.getString("access_token_enc"), r.getString("refresh_token_enc"), instant(r, "expires_at"),
            r.getString("scopes"), instant(r, "created_at"), instant(r, "updated_at"));

    public TrelloConnectionService(JdbcTemplate jdbc, TrelloProperties properties, TrelloClient client, TokenCipher cipher, TransactionTemplate tx) {
        this.jdbc = jdbc; this.properties = properties; this.client = client; this.cipher = cipher; this.tx = tx;
    }
    private static Instant instant(ResultSet rs, String name) throws SQLException { Timestamp t = rs.getTimestamp(name); return t == null ? null : t.toInstant(); }
    private Instant now() { return jdbc.queryForObject("select clock_timestamp()", (r, n) -> r.getTimestamp(1).toInstant()); }

    public ConfigView config() {
        String tokenUrl = properties.tokenModeConfigured() ? "https://trello.com/1/authorize?expiration=30days&scope=read,write&response_type=token&name="
                + URLEncoder.encode(Optional.ofNullable(properties.appName()).orElse("AI Meeting to Task"), StandardCharsets.UTF_8)
                + "&key=" + URLEncoder.encode(properties.apiKey(), StandardCharsets.UTF_8) : null;
        return new ConfigView(cipher.ready(), cipher.ready() && properties.oauthConfigured(), cipher.ready() && properties.tokenModeConfigured(), tokenUrl);
    }

    // ---------- OAuth 2.0 + PKCE ----------
    public AuthorizationView startOAuth(UUID owner, UUID sessionId) {
        if (!config().oauthAvailable()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TRELLO_OAUTH_NOT_CONFIGURED", "Backend chưa cấu hình OAuth Trello (client ID/secret/callback).");
        String state = random(32), verifier = random(48);
        UUID id = UUID.randomUUID(); Instant now = now(); Instant expires = now.plus(properties.transactionTtl());
        jdbc.update("""
                insert into authorization_transactions(id, user_id, session_id, state_hash, pkce_verifier_enc, status, expires_at, created_at)
                values (?, ?, ?, ?, ?, 'PENDING', ?, ?)""", id, owner, sessionId, sha256(state), cipher.encrypt(verifier), Timestamp.from(expires), Timestamp.from(now));
        String url = properties.authorizeUrl() + "?" + query(Map.of("client_id", properties.clientId(), "scope", properties.scopes(),
                "redirect_uri", properties.callbackUrl(), "state", state, "response_type", "code", "prompt", "consent",
                "code_challenge", challenge(verifier), "code_challenge_method", "S256"));
        return new AuthorizationView(id, url, expires);
    }

    public TransactionView transaction(UUID owner, UUID id) {
        return jdbc.query("select * from authorization_transactions where id = ? and user_id = ?", (r, n) -> {
            String status = r.getString("status");
            if ("PENDING".equals(status) && r.getTimestamp("expires_at").toInstant().isBefore(Instant.now())) status = "EXPIRED";
            return new TransactionView(id, status, r.getString("error_code"), r.getObject("connection_id", UUID.class), r.getTimestamp("expires_at").toInstant());
        }, id, owner).stream().findFirst().orElseThrow(ApiException::notFound);
    }

    /** Public callback: correlated by state hash, one use, TTL checked; a denial never touches review drafts. */
    public CallbackResult callback(String code, String state, String error) {
        if (state == null || state.length() > 200) return new CallbackResult(false, "INVALID_STATE");
        String hash = sha256(state);
        record Pending(UUID id, UUID owner, String verifierEnc) {}
        Pending pending = tx.execute(s -> {
            var rows = jdbc.query("select * from authorization_transactions where state_hash = ? for update",
                    (r, n) -> new Object[] {r.getObject("id", UUID.class), r.getObject("user_id", UUID.class), r.getString("pkce_verifier_enc"),
                            r.getString("status"), r.getTimestamp("expires_at").toInstant()}, hash);
            if (rows.isEmpty()) return null;
            var row = rows.get(0); UUID id = (UUID) row[0]; Instant now = now();
            if (!"PENDING".equals(row[3])) return null;
            boolean expired = ((Instant) row[4]).isBefore(now);
            String terminal = error != null ? "DENIED" : expired ? "EXPIRED" : "FAILED"; // FAILED until the exchange succeeds
            jdbc.update("update authorization_transactions set status = ?, error_code = ?, consumed_at = ? where id = ?",
                    terminal, error != null ? "ACCESS_DENIED" : expired ? "EXPIRED" : "EXCHANGE_PENDING", Timestamp.from(now), id);
            return error != null || expired || code == null || code.isBlank() ? new Pending(id, null, null) : new Pending(id, (UUID) row[1], (String) row[2]);
        });
        if (pending == null) return new CallbackResult(false, "INVALID_STATE");
        if (pending.owner() == null) return new CallbackResult(false, error != null ? "ACCESS_DENIED" : "EXPIRED");
        try {
            var token = client.exchange(Map.of("grant_type", "authorization_code", "client_id", properties.clientId(), "client_secret", properties.clientSecret(),
                    "code", code, "redirect_uri", properties.callbackUrl(), "code_verifier", cipher.decrypt(pending.verifierEnc())));
            var identity = client.me(TrelloClient.bearer(token.accessToken()));
            Instant expires = now().plusSeconds(Math.max(60, Math.min(token.expiresIn(), 86400)));
            UUID connection = save(pending.owner(), identity, "OAUTH2", token.accessToken(), token.refreshToken(), expires, token.scope());
            jdbc.update("update authorization_transactions set status = 'COMPLETED', error_code = null, connection_id = ? where id = ?", connection, pending.id());
            return new CallbackResult(true, "CONNECTED");
        } catch (TrelloException e) {
            log.warn("Trello OAuth exchange failed transactionId={} kind={} status={}", pending.id(), e.kind(), e.status());
            jdbc.update("update authorization_transactions set error_code = ? where id = ?", "EXCHANGE_" + e.kind(), pending.id());
            return new CallbackResult(false, "EXCHANGE_FAILED");
        }
    }

    // ---------- API key + token (development/demo boards) ----------
    public ConnectionView connectToken(UUID owner, String token) {
        if (!config().tokenModeAvailable()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TRELLO_TOKEN_MODE_DISABLED", "Backend chưa cấu hình TRELLO_API_KEY cho chế độ token.");
        if (token == null || !token.trim().matches("[A-Za-z0-9_-]{20,256}")) throw ApiException.invalid("Token Trello không đúng định dạng.");
        Identity identity;
        try { identity = client.me(TrelloClient.keyToken(properties.apiKey(), token.trim())); }
        catch (TrelloException e) { throw translate(e, null); }
        UUID id = save(owner, identity, "TOKEN", token.trim(), null, null, "read,write");
        return view(get(owner, id));
    }

    /** One usable connection per user: same Trello identity is refreshed in place, another identity replaces it. */
    private UUID save(UUID owner, Identity identity, String type, String access, String refresh, Instant expires, String scopes) {
        return tx.execute(s -> {
            jdbc.query("select id from users where id = ? for update", (r, n) -> 1, owner);
            Instant now = now();
            var existing = jdbc.query("select * from trello_connections where user_id = ? and status in ('ACTIVE','REAUTH_REQUIRED')", mapper, owner);
            for (var old : existing) {
                if (old.trelloMemberId().equals(identity.id())) {
                    jdbc.update("""
                            update trello_connections set auth_type = ?, status = 'ACTIVE', access_token_enc = ?, refresh_token_enc = ?, expires_at = ?,
                            scopes = ?, key_version = ?, trello_username = ?, trello_full_name = ?, updated_at = ? where id = ?""",
                            type, cipher.encrypt(access), refresh == null ? null : cipher.encrypt(refresh), expires == null ? null : Timestamp.from(expires),
                            scopes, cipher.version(), identity.username(), identity.fullName(), Timestamp.from(now), old.id());
                    return old.id();
                }
                disconnectLocked(old.id(), now);
            }
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into trello_connections(id, user_id, trello_member_id, trello_username, trello_full_name, auth_type, status,
                    access_token_enc, refresh_token_enc, expires_at, scopes, key_version, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?)""", id, owner, identity.id(), identity.username(), identity.fullName(), type,
                    cipher.encrypt(access), refresh == null ? null : cipher.encrypt(refresh), expires == null ? null : Timestamp.from(expires), scopes,
                    cipher.version(), Timestamp.from(now), Timestamp.from(now));
            return id;
        });
    }

    // ---------- reads / disconnect ----------
    public List<ConnectionView> list(UUID owner) {
        return jdbc.query("select * from trello_connections where user_id = ? and status <> 'DISCONNECTED' order by created_at desc", mapper, owner)
                .stream().map(this::view).toList();
    }
    public Connection get(UUID owner, UUID id) {
        return jdbc.query("select * from trello_connections where id = ? and user_id = ?", mapper, id, owner).stream().findFirst().orElseThrow(ApiException::notFound);
    }
    /** The user's usable connection for a Trello identity pinned in a snapshot. */
    public Optional<Connection> activeFor(UUID owner, String trelloIdentity) {
        return jdbc.query("select * from trello_connections where user_id = ? and trello_member_id = ? and status = 'ACTIVE'", mapper, owner, trelloIdentity).stream().findFirst();
    }
    public void disconnect(UUID owner, UUID id) {
        tx.executeWithoutResult(s -> {
            var connection = jdbc.query("select * from trello_connections where id = ? and user_id = ? for update", mapper, id, owner)
                    .stream().findFirst().orElseThrow(ApiException::notFound);
            if ("DISCONNECTED".equals(connection.status())) return;
            Instant now = now();
            disconnectLocked(id, now);
            // Items never dispatched cannot have created a card; they fail safely and can be retried after reconnecting.
            jdbc.update("""
                    update sync_items i set status = 'FAILED', error_code = 'TRELLO_DISCONNECTED', error_retryable = true, updated_at = ?, completed_at = ?
                    from task_snapshots s where s.id = i.snapshot_id and s.trello_identity = ? and i.status = 'QUEUED' and i.dispatch_state = 'NOT_DISPATCHED'
                    and s.approved_by = ?""", Timestamp.from(now), Timestamp.from(now), connection.trelloMemberId(), owner);
            jdbc.update("""
                    update tasks t set sync_status = 'FAILED', updated_at = ? from sync_items i where i.task_id = t.id and i.status = 'FAILED'
                    and i.error_code = 'TRELLO_DISCONNECTED' and t.sync_status = 'QUEUED'""", Timestamp.from(now));
            jdbc.update("insert into audit_events(id, user_id, event_type, details, created_at) values (?, ?, 'TRELLO_DISCONNECTED', '{}'::jsonb, ?)",
                    UUID.randomUUID(), owner, Timestamp.from(now));
        });
    }
    private void disconnectLocked(UUID id, Instant now) {
        jdbc.update("update trello_connections set status = 'DISCONNECTED', access_token_enc = null, refresh_token_enc = null, disconnected_at = ?, updated_at = ? where id = ?",
                Timestamp.from(now), Timestamp.from(now), id);
    }
    public ConnectionView view(Connection c) {
        return new ConnectionView(c.id(), c.trelloMemberId(), c.username(), c.fullName(), c.authType(), c.status(), c.scopes(), c.createdAt(), c.updatedAt());
    }

    // ---------- credentials ----------
    /** Runs a Trello call with fresh credentials; one refresh on 401 for OAuth, otherwise the connection needs re-auth. */
    public <T> T call(Connection connection, Function<Credentials, T> action) {
        var current = connection;
        if (!"ACTIVE".equals(current.status())) throw reauth();
        if ("OAUTH2".equals(current.authType()) && current.expiresAt() != null && current.expiresAt().isBefore(Instant.now().plusSeconds(60))) current = refresh(current.id());
        try { return action.apply(credentials(current)); }
        catch (TrelloException e) {
            if (e.kind() != Kind.AUTH) throw e;
            if ("OAUTH2".equals(current.authType()) && current.refreshTokenEnc() != null) {
                current = refresh(current.id());
                try { return action.apply(credentials(current)); }
                catch (TrelloException again) { if (again.kind() == Kind.AUTH) markReauth(current.id()); throw again; }
            }
            markReauth(current.id());
            throw e;
        }
    }
    private Credentials credentials(Connection c) {
        String access = cipher.decrypt(c.accessTokenEnc());
        return "TOKEN".equals(c.authType()) ? TrelloClient.keyToken(properties.apiKey(), access) : TrelloClient.bearer(access);
    }
    /** Refresh is serialized per connection and stored atomically; refresh tokens are single-use. */
    private Connection refresh(UUID id) {
        // Returns null when the grant is no longer valid; the REAUTH mark is written outside the rolled-back scope.
        Connection refreshed = tx.execute(s -> {
            var c = jdbc.query("select * from trello_connections where id = ? for update", mapper, id).get(0);
            if (!"ACTIVE".equals(c.status())) return null;
            if (c.expiresAt() != null && c.expiresAt().isAfter(Instant.now().plusSeconds(60))) return c; // another thread refreshed
            if (c.refreshTokenEnc() == null) return null;
            TrelloClient.TokenResponse token;
            try {
                token = client.exchange(Map.of("grant_type", "refresh_token", "client_id", properties.clientId(), "client_secret", properties.clientSecret(),
                        "refresh_token", cipher.decrypt(c.refreshTokenEnc())));
            } catch (TrelloException e) {
                if (e.kind() == Kind.AUTH || e.kind() == Kind.REJECTED) return null;
                throw e;
            }
            Instant now = now();
            jdbc.update("update trello_connections set access_token_enc = ?, refresh_token_enc = ?, expires_at = ?, updated_at = ? where id = ?",
                    cipher.encrypt(token.accessToken()), token.refreshToken() == null ? c.refreshTokenEnc() : cipher.encrypt(token.refreshToken()),
                    Timestamp.from(now.plusSeconds(Math.max(60, Math.min(token.expiresIn(), 86400)))), Timestamp.from(now), id);
            return jdbc.query("select * from trello_connections where id = ?", mapper, id).get(0);
        });
        if (refreshed == null) { markReauth(id); throw reauth(); }
        return refreshed;
    }
    public void markReauth(UUID id) {
        jdbc.update("update trello_connections set status = 'REAUTH_REQUIRED', updated_at = now() where id = ? and status = 'ACTIVE'", id);
    }
    static ApiException reauth() { return new ApiException(HttpStatus.CONFLICT, "TRELLO_REAUTH_REQUIRED", "Kết nối Trello đã hết quyền. Kết nối lại Trello rồi thử lại."); }

    /** Maps Trello failures for interactive API calls; the cause kind is safe to expose, Trello bodies are not. */
    public static ApiException translate(TrelloException e, Connection c) {
        return switch (e.kind()) {
            case AUTH -> c == null ? new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TRELLO_TOKEN_INVALID", "Trello từ chối token này.") : reauth();
            case FORBIDDEN -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TRELLO_FORBIDDEN", "Tài khoản Trello không có quyền trên Board/List này.");
            case NOT_FOUND, REJECTED -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TRELLO_RESOURCE_NOT_FOUND", "Không tìm thấy Board/List/thành viên trên Trello hoặc bạn không có quyền.");
            case RATE_LIMIT -> new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT", "Trello đang giới hạn request. Chờ rồi thử lại.");
            default -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TRELLO_UNAVAILABLE", "Không kết nối được Trello. Thử lại sau.");
        };
    }

    // ---------- helpers ----------
    private static String random(int bytes) { byte[] b = new byte[bytes]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String challenge(String verifier) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String query(Map<String, String> values) {
        var sorted = new TreeMap<>(values); var out = new StringJoiner("&");
        sorted.forEach((k, v) -> out.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return out.toString();
    }
}
