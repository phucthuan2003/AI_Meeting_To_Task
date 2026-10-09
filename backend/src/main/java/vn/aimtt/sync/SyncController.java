package vn.aimtt.sync;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.aimtt.common.ApiException;

/** Approve-and-sync and sync result APIs (SDS §6.8). */
@RestController
@RequestMapping("/api/v1")
public class SyncController {
    private final ApproveService approve;
    private final SyncActions actions;
    private final SyncStore store;
    public SyncController(ApproveService approve, SyncActions actions, SyncStore store) { this.approve = approve; this.actions = actions; this.store = store; }

    @PostMapping("/meetings/{id}/approve-and-sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApproveService.Accepted approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody JsonNode body,
                                    @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return approve.approve(owner(jwt), id, body, key);
    }
    @GetMapping("/tasks/{id}/card-preview")
    ApproveService.CardPreview preview(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return approve.preview(owner(jwt), id); }
    @GetMapping("/sync-jobs/{id}")
    SyncActions.JobView job(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return actions.job(owner(jwt), id); }
    @GetMapping("/meetings/{id}/sync-jobs/latest")
    ResponseEntity<SyncActions.JobView> latest(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var owner = owner(jwt);
        var jobId = store.latestJobOwned(owner, id);
        return jobId.map(j -> ResponseEntity.ok(actions.job(owner, j))).orElse(ResponseEntity.noContent().build());
    }
    @PostMapping("/sync-items/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SyncActions.JobView retry(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return actions.retry(owner(jwt), id); }
    @PostMapping("/sync-items/{id}/reconcile")
    SyncActions.JobView reconcile(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return actions.reconcile(owner(jwt), id); }
    @PostMapping("/sync-items/{id}/link-card")
    SyncActions.JobView link(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody JsonNode body) {
        if (body == null || !body.path("card").isTextual()) throw ApiException.invalid("Gửi card là link hoặc ID card Trello.");
        return actions.linkCard(owner(jwt), id, body.get("card").asText(), body.path("acknowledgeNoMarker").asBoolean(false));
    }
    @PostMapping("/sync-items/{id}/recreate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SyncActions.JobView recreate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody JsonNode body) { return actions.recreate(owner(jwt), id, body); }
    private UUID owner(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
