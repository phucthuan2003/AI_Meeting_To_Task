package vn.aimtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** In-JVM Trello API double: real HTTP so client timeouts/status mapping are exercised; faults injectable per test. */
final class FakeTrello {
    static final String BOARD = "b0000000000000000000000a", OTHER_BOARD = "b0000000000000000000000b";
    static final String LIST = "l0000000000000000000000a", CLOSED_LIST = "l0000000000000000000000c", OTHER_LIST = "l0000000000000000000000b";
    static final String MAI = "m000000000000000000000a1", LONG_TRAN = "m000000000000000000000a2", LONG_PHAM = "m000000000000000000000a3", NAM = "m000000000000000000000a4";
    static final String ME = "u000000000000000000000me";
    enum Fault { NONE, TIMEOUT_AFTER_CREATE, SERVER_ERROR_AFTER_CREATE, REJECT, RATE_LIMIT, AUTH }

    final ObjectMapper json = new ObjectMapper();
    final HttpServer server;
    final Set<String> validTokens = ConcurrentHashMap.newKeySet();
    final List<Map<String, Object>> cards = new CopyOnWriteArrayList<>();
    final List<Map<String, Object>> members = new CopyOnWriteArrayList<>();
    final Map<String, String> codeChallenges = new ConcurrentHashMap<>();
    final Map<String, String> refreshTokens = new ConcurrentHashMap<>(); // refresh -> access
    final AtomicInteger createCalls = new AtomicInteger();
    final AtomicInteger tokenCalls = new AtomicInteger();
    volatile Fault fault = Fault.NONE;
    volatile int faultCount = 0;
    volatile long sleepMs = 3500;

    FakeTrello() {
        try { server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); } catch (IOException e) { throw new IllegalStateException(e); }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
        reset();
    }
    int port() { return server.getAddress().getPort(); }
    String base() { return "http://127.0.0.1:" + port(); }
    synchronized void reset() {
        validTokens.clear(); validTokens.add("validtoken000000000000000000000001");
        cards.clear(); codeChallenges.clear(); refreshTokens.clear(); fault = Fault.NONE; faultCount = 0; createCalls.set(0); tokenCalls.set(0);
        members.clear();
        members.add(member(MAI, "mainguyen", "Nguyễn Thị Mai"));
        members.add(member(LONG_TRAN, "longtran", "Trần Long"));
        members.add(member(LONG_PHAM, "longpham", "Phạm Long"));
        members.add(member(NAM, "nam", "Nam Lê"));
    }
    void fault(Fault value, int count) { fault = value; faultCount = count; }
    private static Map<String, Object> member(String id, String username, String fullName) { return new LinkedHashMap<>(Map.of("id", id, "username", username, "fullName", fullName)); }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
            if (path.equals("/oauth/token") && method.equals("POST")) { token(ex); return; }
            if (!authorized(ex.getRequestHeaders().getFirst("Authorization"))) { send(ex, 401, Map.of("message", "invalid token")); return; }
            if (method.equals("GET")) {
                switch (path) {
                    case "/1/members/me" -> { send(ex, 200, Map.of("id", ME, "username", "demo", "fullName", "Demo User")); return; }
                    case "/1/members/me/boards" -> { send(ex, 200, List.of(board(BOARD, "Demo Board", false), board(OTHER_BOARD, "Other Board", false))); return; }
                    default -> { }
                }
                String[] p = path.split("/");
                if (p.length == 4 && p[2].equals("boards")) { if (p[3].equals(BOARD) || p[3].equals(OTHER_BOARD)) send(ex, 200, board(p[3], p[3].equals(BOARD) ? "Demo Board" : "Other Board", false)); else send(ex, 404, Map.of()); return; }
                if (p.length == 5 && p[2].equals("boards") && p[4].equals("lists")) { send(ex, 200, p[3].equals(BOARD) ? List.of(list(LIST, "To Do", BOARD, false)) : List.of(list(OTHER_LIST, "Backlog", OTHER_BOARD, false))); return; }
                if (p.length == 5 && p[2].equals("boards") && p[4].equals("members")) { send(ex, 200, p[3].equals(BOARD) ? members : List.of(members.get(0))); return; }
                if (p.length == 6 && p[2].equals("boards") && p[4].equals("cards")) {
                    var result = new ArrayList<Map<String, Object>>(); for (var c : cards) if (p[3].equals(c.get("idBoard"))) result.add(c);
                    Collections.reverse(result); send(ex, 200, result); return;
                }
                if (p.length == 4 && p[2].equals("lists")) {
                    switch (p[3]) {
                        case LIST -> send(ex, 200, list(LIST, "To Do", BOARD, false));
                        case CLOSED_LIST -> send(ex, 200, list(CLOSED_LIST, "Archived", BOARD, true));
                        case OTHER_LIST -> send(ex, 200, list(OTHER_LIST, "Backlog", OTHER_BOARD, false));
                        default -> send(ex, 404, Map.of());
                    }
                    return;
                }
                if (p.length == 4 && p[2].equals("cards")) {
                    for (var c : cards) if (c.get("id").equals(p[3]) || c.get("shortLink").equals(p[3])) { send(ex, 200, c); return; }
                    send(ex, 404, Map.of()); return;
                }
            }
            if (method.equals("POST") && path.equals("/1/cards")) { create(ex); return; }
            send(ex, 404, Map.of());
        } catch (RuntimeException e) { send(ex, 500, Map.of()); }
    }
    private boolean authorized(String header) {
        if (header == null) return false;
        for (String token : validTokens) if (header.equals("Bearer " + token) || header.contains("oauth_token=\"" + token + "\"")) return true;
        return false;
    }
    private void create(HttpExchange ex) throws IOException {
        createCalls.incrementAndGet();
        JsonNode body = json.readTree(ex.getRequestBody());
        Fault current = fault;
        if (faultCount > 0) { if (--faultCount == 0) fault = Fault.NONE; } else current = Fault.NONE;
        switch (current) {
            case REJECT -> { send(ex, 400, Map.of("message", "invalid value for idList")); return; }
            case RATE_LIMIT -> { ex.getResponseHeaders().add("Retry-After", "0"); send(ex, 429, Map.of()); return; }
            case AUTH -> { send(ex, 401, Map.of()); return; }
            default -> { }
        }
        String listId = body.path("idList").asText();
        if (!listId.equals(LIST) && !listId.equals(OTHER_LIST)) { send(ex, 400, Map.of("message", "invalid list")); return; }
        String id = String.format("c%023d", cards.size() + 1), shortLink = String.format("s%07d", cards.size() + 1);
        var card = new LinkedHashMap<String, Object>();
        card.put("id", id); card.put("shortLink", shortLink); card.put("url", "https://trello.com/c/" + shortLink + "/" + (cards.size() + 1));
        card.put("shortUrl", "https://trello.com/c/" + shortLink); card.put("name", body.path("name").asText()); card.put("desc", body.path("desc").asText());
        card.put("idList", listId); card.put("idBoard", listId.equals(LIST) ? BOARD : OTHER_BOARD); card.put("closed", false);
        card.put("idMembers", body.path("idMembers").asText("")); card.put("due", body.path("due").isMissingNode() ? null : body.path("due").asText());
        cards.add(card);
        if (current == Fault.TIMEOUT_AFTER_CREATE) { sleep(sleepMs); }
        if (current == Fault.SERVER_ERROR_AFTER_CREATE) { send(ex, 502, Map.of()); return; }
        send(ex, 200, card);
    }
    private void token(HttpExchange ex) throws IOException {
        tokenCalls.incrementAndGet();
        JsonNode body = json.readTree(ex.getRequestBody());
        String grant = body.path("grant_type").asText();
        if (grant.equals("authorization_code")) {
            String challenge = codeChallenges.remove(body.path("code").asText());
            if (challenge == null || !challenge.equals(s256(body.path("code_verifier").asText())) || !"client-secret-test".equals(body.path("client_secret").asText())) {
                send(ex, 400, Map.of("error", "invalid_grant")); return;
            }
        } else if (grant.equals("refresh_token")) {
            if (refreshTokens.remove(body.path("refresh_token").asText()) == null) { send(ex, 400, Map.of("error", "invalid_grant")); return; }
        } else { send(ex, 400, Map.of("error", "unsupported_grant_type")); return; }
        String access = "oauthaccess" + UUID.randomUUID().toString().replace("-", ""), refresh = "oauthrefresh" + UUID.randomUUID().toString().replace("-", "");
        validTokens.add(access); refreshTokens.put(refresh, access);
        send(ex, 200, Map.of("access_token", access, "refresh_token", refresh, "expires_in", 3600, "scope", "read:board:trello write:board:trello offline_access"));
    }
    static String s256(String verifier) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    static Map<String, String> query(String url) {
        var result = new HashMap<String, String>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            int eq = pair.indexOf('='); result.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return result;
    }
    private static Map<String, Object> board(String id, String name, boolean closed) { return Map.of("id", id, "name", name, "closed", closed, "url", "https://trello.com/b/" + id); }
    private static Map<String, Object> list(String id, String name, String board, boolean closed) { return Map.of("id", id, "name", name, "idBoard", board, "closed", closed); }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    private void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        try { ex.sendResponseHeaders(status, bytes.length); ex.getResponseBody().write(bytes); } catch (IOException ignored) { } finally { ex.close(); }
    }
}
