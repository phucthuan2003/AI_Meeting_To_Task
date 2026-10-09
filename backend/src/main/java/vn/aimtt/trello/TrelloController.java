package vn.aimtt.trello;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.aimtt.common.ApiException;

/** Trello Connection, Destination and resolve APIs (SDS §6.6, §6.7). Tokens never appear in responses. */
@RestController
@RequestMapping("/api/v1")
public class TrelloController {
    private final TrelloConnectionService connections;
    private final DestinationService destinations;
    public TrelloController(TrelloConnectionService connections, DestinationService destinations) { this.connections = connections; this.destinations = destinations; }

    @GetMapping("/trello/config") TrelloConnectionService.ConfigView config() { return connections.config(); }
    @GetMapping("/trello/connections") List<TrelloConnectionService.ConnectionView> list(@AuthenticationPrincipal Jwt jwt) { return connections.list(owner(jwt)); }

    @PostMapping("/trello/connections/authorize")
    TrelloConnectionService.AuthorizationView authorize(@AuthenticationPrincipal Jwt jwt) {
        return connections.startOAuth(owner(jwt), UUID.fromString(jwt.getId()));
    }
    @GetMapping("/trello/authorizations/{id}")
    TrelloConnectionService.TransactionView transaction(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return connections.transaction(owner(jwt), id); }

    /** Public: correlated by the one-time state, not by a JWT in the URL. */
    @GetMapping(value = "/trello/oauth/callback", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state, @RequestParam(required = false) String error) {
        var result = connections.callback(code, state, error);
        String title = result.success() ? "Đã kết nối Trello" : "Chưa kết nối Trello";
        String body = result.success() ? "Bạn có thể đóng tab này và quay lại Side Panel." : switch (result.code()) {
            case "ACCESS_DENIED" -> "Bạn đã từ chối cấp quyền. Review draft vẫn được giữ; có thể kết nối lại bất cứ lúc nào.";
            case "EXPIRED" -> "Phiên kết nối đã hết hạn. Quay lại Side Panel và bấm Kết nối Trello lần nữa.";
            default -> "Không hoàn tất được kết nối. Quay lại Side Panel và thử lại.";
        };
        String html = "<!doctype html><html lang=\"vi\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>" + title
                + "</title></head><body style=\"font-family:system-ui;padding:32px;color:#203731\"><h1>" + title + "</h1><p>" + body + "</p></body></html>";
        return ResponseEntity.status(result.success() ? 200 : 400).header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'").contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8)).body(html);
    }

    @PostMapping("/trello/connections/token")
    @ResponseStatus(HttpStatus.CREATED)
    TrelloConnectionService.ConnectionView token(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode body) {
        if (body == null || !body.path("token").isTextual() || body.size() != 1) throw ApiException.invalid("Gửi đúng một trường token.");
        return connections.connectToken(owner(jwt), body.get("token").asText());
    }
    @DeleteMapping("/trello/connections/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disconnect(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { connections.disconnect(owner(jwt), id); }

    @GetMapping("/trello/connections/{id}/boards")
    List<TrelloClient.Board> boards(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return destinations.boards(owner(jwt), id); }
    @GetMapping("/trello/boards/{boardId}/lists")
    List<TrelloClient.TrelloList> lists(@AuthenticationPrincipal Jwt jwt, @PathVariable String boardId, @RequestParam UUID connectionId) {
        return destinations.lists(owner(jwt), connectionId, boardId);
    }
    @GetMapping("/trello/boards/{boardId}/members")
    List<DestinationService.MemberView> members(@AuthenticationPrincipal Jwt jwt, @PathVariable String boardId, @RequestParam UUID connectionId) {
        return destinations.members(owner(jwt), connectionId, boardId);
    }

    @GetMapping("/meetings/{id}/destination")
    ResponseEntity<DestinationService.DestinationView> destination(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var view = destinations.view(owner(jwt), id);
        return view == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(view);
    }
    @PutMapping("/meetings/{id}/destination")
    DestinationService.DestinationView saveDestination(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody JsonNode body) {
        if (body == null || !body.path("connectionId").isTextual() || !body.path("boardId").isTextual() || !body.path("listId").isTextual()
                || !body.path("expectedVersion").isIntegralNumber()) throw ApiException.invalid("Gửi connectionId, boardId, listId và expectedVersion (0 khi chưa có đích).");
        UUID connection;
        try { connection = UUID.fromString(body.get("connectionId").asText()); } catch (IllegalArgumentException e) { throw ApiException.invalid("connectionId không hợp lệ."); }
        return destinations.save(owner(jwt), id, connection, body.get("boardId").asText(), body.get("listId").asText(), body.get("expectedVersion").asLong());
    }
    @PostMapping("/meetings/{id}/resolve-members")
    List<DestinationService.MemberResolution> resolveMembers(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody JsonNode body) {
        if (body == null || !body.path("expectedDestinationVersion").isIntegralNumber()) throw ApiException.invalid("Gửi expectedDestinationVersion.");
        return destinations.resolveMembers(owner(jwt), id, body.get("expectedDestinationVersion").asLong());
    }
    @PostMapping("/meetings/{id}/resolve-deadlines")
    List<DestinationService.DeadlineSuggestionView> resolveDeadlines(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return destinations.resolveDeadlines(owner(jwt), id);
    }
    private UUID owner(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
