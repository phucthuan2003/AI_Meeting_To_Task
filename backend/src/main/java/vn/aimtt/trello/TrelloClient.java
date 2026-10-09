package vn.aimtt.trello;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Minimal Trello REST client (SDS §5.15, [R2]). No redirects, bounded bodies, fixed configured base URL.
 * Errors are classified by whether the request could have reached Trello, which decides FAILED vs UNKNOWN.
 */
@Component
public class TrelloClient {
    public record Identity(String id, String username, String fullName) {}
    public record Board(String id, String name, boolean closed, String url) {}
    public record TrelloList(String id, String name, boolean closed, String idBoard) {}
    public record Member(String id, String username, String fullName) {}
    public record Card(String id, String url, String idList, String idBoard, String desc, boolean closed) {}
    public record CardRequest(String name, String desc, String idList, List<String> idMembers, String due) {}
    public record TokenResponse(String accessToken, String refreshToken, long expiresIn, String scope) {}

    /** How to authenticate one call. Values are secrets: never logged, never echoed in errors. */
    public interface Credentials { String header(); }
    public static Credentials bearer(String token) { return () -> "Bearer " + token; }
    public static Credentials keyToken(String key, String token) { return () -> "OAuth oauth_consumer_key=\"" + key + "\", oauth_token=\"" + token + "\""; }

    public enum Kind { AUTH, FORBIDDEN, NOT_FOUND, REJECTED, RATE_LIMIT, SERVER, NOT_SENT, UNKNOWN_OUTCOME, INVALID_RESPONSE }
    public static final class TrelloException extends RuntimeException {
        private final Kind kind; private final int status; private final Long retryAfterSeconds;
        public TrelloException(Kind kind, int status, Long retryAfterSeconds) { super(kind + ":" + status); this.kind = kind; this.status = status; this.retryAfterSeconds = retryAfterSeconds; }
        public Kind kind() { return kind; }
        public int status() { return status; }
        public Long retryAfterSeconds() { return retryAfterSeconds; }
    }

    private final HttpClient http;
    private final TrelloProperties properties;
    private final ObjectMapper json;

    public TrelloClient(TrelloProperties properties, ObjectMapper json) {
        this.properties = properties; this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    // ---------- read API ----------
    public Identity me(Credentials c) {
        var n = get(c, "/members/me", Map.of("fields", "id,username,fullName"));
        return new Identity(text(n, "id"), n.path("username").asText(null), n.path("fullName").asText(null));
    }
    public List<Board> boards(Credentials c) {
        var list = new ArrayList<Board>();
        for (var n : get(c, "/members/me/boards", Map.of("filter", "open", "fields", "id,name,closed,url"))) list.add(board(n));
        return list;
    }
    public Board board(Credentials c, String id) { return board(get(c, "/boards/" + id(id), Map.of("fields", "id,name,closed,url"))); }
    public List<TrelloList> lists(Credentials c, String boardId) {
        var list = new ArrayList<TrelloList>();
        for (var n : get(c, "/boards/" + id(boardId) + "/lists", Map.of("filter", "open", "fields", "id,name,closed,idBoard"))) list.add(trelloList(n));
        return list;
    }
    public TrelloList list(Credentials c, String listId) { return trelloList(get(c, "/lists/" + id(listId), Map.of("fields", "id,name,closed,idBoard"))); }
    public List<Member> members(Credentials c, String boardId) {
        var list = new ArrayList<Member>();
        for (var n : get(c, "/boards/" + id(boardId) + "/members", Map.of("fields", "id,username,fullName")))
            list.add(new Member(text(n, "id"), n.path("username").asText(null), n.path("fullName").asText(null)));
        return list;
    }
    public Card card(Credentials c, String cardId) { return card(get(c, "/cards/" + id(cardId), Map.of("fields", "id,url,shortUrl,idList,idBoard,desc,closed"))); }
    /** All cards of a board (open and archived), newest first, paged by "before" (SDS §5.16 reconciliation). */
    public List<Card> boardCards(Credentials c, String boardId, String before, int limit) {
        var query = new LinkedHashMap<String, String>();
        query.put("fields", "id,url,shortUrl,idList,idBoard,desc,closed"); query.put("limit", String.valueOf(limit));
        if (before != null) query.put("before", before);
        var list = new ArrayList<Card>();
        for (var n : get(c, "/boards/" + id(boardId) + "/cards/all", query)) list.add(card(n));
        return list;
    }

    // ---------- write API ----------
    /** One request with the complete payload; card ID is returned immediately for persistence. */
    public Card createCard(Credentials c, CardRequest request) {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", request.name()); body.put("desc", request.desc()); body.put("idList", request.idList()); body.put("pos", "bottom");
        if (!request.idMembers().isEmpty()) body.put("idMembers", String.join(",", request.idMembers()));
        if (request.due() != null) body.put("due", request.due());
        return card(send(c, "POST", "/cards", Map.of(), body));
    }

    // ---------- OAuth 2.0 token endpoint ----------
    public TokenResponse exchange(Map<String, String> form) {
        var request = HttpRequest.newBuilder(URI.create(properties.tokenUrl())).timeout(properties.requestTimeout())
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(form))).build();
        var node = execute(request);
        String access = node.path("access_token").asText(null);
        if (access == null || access.isBlank()) throw new TrelloException(Kind.INVALID_RESPONSE, 200, null);
        return new TokenResponse(access, node.path("refresh_token").asText(null), node.path("expires_in").asLong(3600), node.path("scope").asText(null));
    }

    // ---------- plumbing ----------
    private JsonNode get(Credentials c, String path, Map<String, String> query) { return send(c, "GET", path, query, null); }
    private JsonNode send(Credentials c, String method, String path, Map<String, String> query, Object body) {
        var url = new StringBuilder(properties.apiBaseUrl().replaceAll("/+$", "")).append(path);
        if (!query.isEmpty()) {
            url.append('?');
            query.forEach((k, v) -> url.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append('&'));
            url.setLength(url.length() - 1);
        }
        var builder = HttpRequest.newBuilder(URI.create(url.toString())).timeout(properties.requestTimeout())
                .header("Authorization", c.header()).header("Accept", "application/json");
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(write(body)));
        return execute(builder.build());
    }
    private JsonNode execute(HttpRequest request) {
        HttpResponse<byte[]> response;
        try { response = http.send(request, HttpResponse.BodyHandlers.ofByteArray()); }
        catch (ConnectException e) { throw new TrelloException(Kind.NOT_SENT, 0, null); }
        catch (HttpTimeoutException e) {
            // Connect timeout means nothing reached Trello; a request timeout may have been processed.
            throw new TrelloException(e instanceof HttpConnectTimeoutException ? Kind.NOT_SENT : Kind.UNKNOWN_OUTCOME, 0, null);
        }
        catch (IOException e) { throw new TrelloException(Kind.UNKNOWN_OUTCOME, 0, null); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new TrelloException(Kind.UNKNOWN_OUTCOME, 0, null); }
        int status = response.statusCode();
        if (response.body().length > 4 * 1024 * 1024) throw new TrelloException(Kind.INVALID_RESPONSE, status, null);
        if (status == 401) throw new TrelloException(Kind.AUTH, status, null);
        if (status == 403) throw new TrelloException(Kind.FORBIDDEN, status, null);
        if (status == 404) throw new TrelloException(Kind.NOT_FOUND, status, null);
        if (status == 429) throw new TrelloException(Kind.RATE_LIMIT, status, response.headers().firstValue("Retry-After").flatMap(TrelloClient::seconds).orElse(null));
        if (status >= 500) throw new TrelloException(Kind.SERVER, status, null);
        if (status < 200 || status >= 300) throw new TrelloException(status == 400 && request.uri().getPath().contains("/token") ? Kind.AUTH : Kind.REJECTED, status, null);
        try {
            var node = json.readTree(response.body());
            if (node == null) throw new TrelloException(Kind.INVALID_RESPONSE, status, null);
            return node;
        } catch (IOException e) { throw new TrelloException(Kind.INVALID_RESPONSE, status, null); }
    }
    private static Optional<Long> seconds(String value) {
        try { long s = Long.parseLong(value.trim()); return s >= 0 && s <= 3600 ? Optional.of(s) : Optional.empty(); }
        catch (NumberFormatException e) { return Optional.empty(); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (IOException e) { throw new IllegalStateException("JSON encoding failed"); }
    }
    /** Trello IDs are 24 hex chars; anything else is rejected before it can become part of a URL. */
    public static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9]{8,64}")) throw new TrelloException(Kind.NOT_FOUND, 400, null);
        return value;
    }
    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) throw new TrelloException(Kind.INVALID_RESPONSE, 200, null);
        return value;
    }
    private static Board board(JsonNode n) { return new Board(text(n, "id"), n.path("name").asText(""), n.path("closed").asBoolean(false), n.path("url").asText(null)); }
    private static TrelloList trelloList(JsonNode n) { return new TrelloList(text(n, "id"), n.path("name").asText(""), n.path("closed").asBoolean(false), n.path("idBoard").asText(null)); }
    private static Card card(JsonNode n) {
        String url = n.path("url").asText(null); if (url == null || url.isBlank()) url = n.path("shortUrl").asText(null);
        return new Card(text(n, "id"), url, n.path("idList").asText(null), n.path("idBoard").asText(null), n.path("desc").asText(""), n.path("closed").asBoolean(false));
    }
}
