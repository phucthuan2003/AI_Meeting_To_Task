package vn.aimtt.meeting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import vn.aimtt.common.ApiException;
import vn.aimtt.transcript.TranscriptParser;

@RestController
@RequestMapping("/api/v1/meetings")
public class MeetingController {
    public record PasteInput(@Size(max = 255) String title, LocalDate meetingDate,
                             @Size(max = 64) String timezone, @NotNull String transcriptText) {}
    public record ReplaceInput(@NotNull @PositiveOrZero Long expectedVersion, @Size(max = 255) String title,
                               LocalDate meetingDate, @Size(max = 64) String timezone, @NotNull String transcriptText) {}
    private final MeetingService service;
    private final TranscriptParser parser;
    public MeetingController(MeetingService service, TranscriptParser parser) { this.service = service; this.parser = parser; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    MeetingService.MeetingView paste(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PasteInput input) {
        return service.create(owner(jwt), input.title(), input.meetingDate(), input.timezone(), parser.paste(input.transcriptText()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    MeetingService.MeetingView file(@AuthenticationPrincipal Jwt jwt, MultipartHttpServletRequest request) {
        Set<String> allowed = Set.of("title", "meetingDate", "timezone");
        if (!allowed.containsAll(request.getParameterMap().keySet())
                || request.getMultiFileMap().size() != 1 || !request.getMultiFileMap().containsKey("file")
                || request.getFiles("file").size() != 1
                || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) {
            throw ApiException.invalid("Nhập đúng một file; không kèm transcriptText hoặc trường lạ.");
        }
        LocalDate date = null;
        String rawDate = request.getParameter("meetingDate");
        if (rawDate != null && !rawDate.isBlank()) {
            try { date = LocalDate.parse(rawDate); }
            catch (java.time.DateTimeException e) { throw ApiException.invalid("Ngày họp phải có dạng YYYY-MM-DD."); }
        }
        return service.create(owner(jwt), request.getParameter("title"), date, request.getParameter("timezone"), parser.file(request.getFile("file")));
    }

    @GetMapping
    MeetingService.MeetingPage list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String cursor,
                                   @RequestParam(defaultValue = "20") int limit) {
        return service.list(owner(jwt), cursor, limit);
    }

    @GetMapping("/{id}")
    MeetingService.MeetingView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return service.get(owner(jwt), id); }

    @GetMapping("/{id}/transcript")
    MeetingService.TranscriptPage transcript(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                           @RequestParam(defaultValue = "-1") int cursor, @RequestParam(defaultValue = "50") int limit) {
        return service.transcript(owner(jwt), id, cursor, limit);
    }

    @PatchMapping(path = "/{id}/input", consumes = MediaType.APPLICATION_JSON_VALUE)
    MeetingService.MeetingView replace(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                      @Valid @RequestBody ReplaceInput input) {
        // Owner check before parsing a replacement belonging to another user.
        service.get(owner(jwt), id);
        return service.replace(owner(jwt), id, input.expectedVersion(), input.title(), input.meetingDate(),
                input.timezone(), parser.paste(input.transcriptText()));
    }

    private UUID owner(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
