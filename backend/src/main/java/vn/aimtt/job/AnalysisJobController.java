package vn.aimtt.job;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.aimtt.common.ApiException;

@RestController
@RequestMapping("/api/v1")
public class AnalysisJobController {
    public record Input(@NotNull @PositiveOrZero Long expectedInputVersion,
                        @NotBlank @Size(max = 64) String providerId, @NotBlank @Size(max = 64) String processingPolicyId) {}
    private final AnalysisJobService service;
    private final AnalysisPolicy policy;
    private final AnalysisResultService results;
    public AnalysisJobController(AnalysisJobService service, AnalysisPolicy policy, AnalysisResultService results) { this.service = service; this.policy = policy; this.results = results; }
    @GetMapping("/meetings/{id}/tasks")
    AnalysisResultService.View tasks(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam(required = false) UUID analysisJobId) {
        return results.get(owner(jwt), id, analysisJobId);
    }
    @GetMapping("/analysis-policies")
    List<AnalysisPolicy.View> policies() { return policy.views(); }
    @PostMapping("/meetings/{id}/analysis-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    AnalysisJobService.JobView start(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Input input,
                                    @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return service.start(owner(jwt), id, new AnalysisJobService.StartInput(input.expectedInputVersion(), input.providerId(), input.processingPolicyId()), key);
    }
    @GetMapping("/jobs/{id}")
    AnalysisJobService.JobView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return service.get(owner(jwt), id); }
    @PostMapping("/jobs/{id}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    AnalysisJobService.JobView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                     @RequestBody(required = false) Map<String, Object> input) {
        noNewInput(input); return service.cancel(owner(jwt), id);
    }
    @PostMapping("/jobs/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    AnalysisJobService.JobView retry(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                    @RequestBody(required = false) Map<String, Object> input) {
        noNewInput(input); return service.retry(owner(jwt), id);
    }
    private void noNewInput(Map<String, Object> input) {
        if (input != null && !input.isEmpty()) throw ApiException.invalid("Cancel/retry dùng job đã lưu; không nhận input hoặc metadata mới.");
    }
    private UUID owner(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
