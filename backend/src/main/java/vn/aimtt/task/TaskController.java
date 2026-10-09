package vn.aimtt.task;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Task Review API (SDS §6.5). Clients cannot write origin, review/sync status or Trello card fields. */
@RestController
@RequestMapping("/api/v1")
public class TaskController {
    private final TaskService service;
    public TaskController(TaskService service) { this.service = service; }

    @GetMapping("/meetings/{id}/tasks")
    TaskService.TaskList list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam(required = false) UUID analysisJobId) {
        return service.list(owner(jwt), id, analysisJobId);
    }

    @PostMapping("/meetings/{id}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    TaskService.TaskView create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody JsonNode body,
                                @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return service.create(owner(jwt), id, body, key);
    }

    @PatchMapping("/tasks/{id}")
    TaskService.TaskView patch(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody JsonNode body) {
        return service.patch(owner(jwt), id, body);
    }

    @DeleteMapping("/tasks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam(required = false) Long expectedVersion) {
        service.reject(owner(jwt), id, expectedVersion);
    }

    @PostMapping("/tasks/{id}/restore")
    TaskService.TaskView restore(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody JsonNode body) {
        return service.restore(owner(jwt), id, body);
    }

    @GetMapping("/tasks/{id}/evidence")
    TaskService.EvidencePage evidence(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.evidence(owner(jwt), id);
    }

    private UUID owner(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
