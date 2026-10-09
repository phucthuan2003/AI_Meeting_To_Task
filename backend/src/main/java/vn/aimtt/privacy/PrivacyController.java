package vn.aimtt.privacy;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/meetings")
public class PrivacyController {
    private final PrivacyService privacy;
    public PrivacyController(PrivacyService privacy) { this.privacy = privacy; }
    @DeleteMapping("/{id}/transcript") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteTranscript(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { privacy.deleteTranscript(UUID.fromString(jwt.getSubject()), id); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteMeeting(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { privacy.deleteMeeting(UUID.fromString(jwt.getSubject()), id); }
}
