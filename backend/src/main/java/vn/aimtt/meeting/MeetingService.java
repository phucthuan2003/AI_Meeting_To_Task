package vn.aimtt.meeting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.aimtt.common.ApiException;
import vn.aimtt.transcript.*;
import vn.aimtt.job.AnalysisJobStore;
import vn.aimtt.task.TaskStore;
import vn.aimtt.sync.SyncStore;

@Service
public class MeetingService {
    public record MeetingView(UUID meetingId, long inputVersion, UUID transcriptRevision,
                              String title, LocalDate meetingDate, String timezone, String preview,
                              int characterCount, long segmentCount, List<String> warnings,
                              String analysisStatus, Instant createdAt, Instant sourceExpiresAt, UUID currentAnalysisJobId, UUID latestSyncJobId,
                              boolean sourceAvailable) {}
    public record MeetingSummary(UUID meetingId, String title, LocalDate meetingDate, long inputVersion, Instant createdAt,
                                 String analysisStatus, int pendingTasks, int rejectedTasks) {}
    public record MeetingPage(List<MeetingSummary> items, String nextCursor) {}
    public record SegmentView(UUID segmentId, int sequence, String speaker, String timestamp,
                              String text, String sourceText, int normalizedStart, int normalizedEnd,
                              Map<String, Object> sourceLocator) {}
    public record TranscriptPage(UUID transcriptRevision, boolean sourceAvailable, List<SegmentView> segments, Integer nextCursor) {}

    private final MeetingRepository meetings;
    private final RevisionRepository revisions;
    private final SegmentRepository segments;
    private final InputProperties limits;
    private final Clock clock;
    private final AnalysisJobStore jobs;
    private final TaskStore tasks;
    private final SyncStore sync;

    public MeetingService(MeetingRepository meetings, RevisionRepository revisions, SegmentRepository segments,
                          InputProperties limits, Clock clock, AnalysisJobStore jobs, TaskStore tasks, SyncStore sync) {
        this.sync = sync;
        this.meetings = meetings; this.revisions = revisions; this.segments = segments;
        this.limits = limits; this.clock = clock;
        this.jobs = jobs; this.tasks = tasks;
    }

    @Transactional
    public MeetingView create(UUID owner, String title, LocalDate date, String timezone, ParsedTranscript input) {
        validateMetadata(title, timezone);
        Instant now = clock.instant();
        var meeting = meetings.saveAndFlush(new Meeting(owner, blankToNull(title), date, blankToNull(timezone), now));
        appendRevision(meeting, 1, input, now);
        meetings.flush();
        return view(meeting);
    }

    @Transactional
    public MeetingView replace(UUID owner, UUID id, long expectedVersion, String title,
                               LocalDate date, String timezone, ParsedTranscript input) {
        var meeting = meetings.findOwnedForUpdate(id, owner).orElseThrow(ApiException::notFound);
        if (jobs.active(id)) throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_BUSY", "Input đang được phân tích. Hủy hoặc chờ job kết thúc trước khi sửa.");
        if (sync.busy(id)) throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_BUSY", "Có card đang được tạo hoặc chờ đối soát. Xử lý xong trước khi thay input.");
        if (meeting.inputVersion() != expectedVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_VERSION", "Input đã thay đổi. Tải lại trước khi sửa.");
        }
        validateMetadata(title, timezone);
        int revision = revision(meeting).revision() + 1;
        Instant now = clock.instant();
        meeting.replaceMetadata(blankToNull(title), date, blankToNull(timezone), now);
        appendRevision(meeting, revision, input, now);
        meetings.flush();
        jobs.clearCurrent(id);
        return view(meeting);
    }

    @Transactional(readOnly = true)
    public MeetingView get(UUID owner, UUID id) { return view(owned(owner, id)); }

    @Transactional(readOnly = true)
    public MeetingPage list(UUID owner, String cursor, int limit) {
        validateLimit(limit);
        List<Meeting> rows;
        if (cursor == null) rows = meetings.findByUserIdOrderByCreatedAtDescIdDesc(owner, PageRequest.of(0, limit + 1));
        else {
            try {
                String[] values = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
                if (values.length != 2) throw new IllegalArgumentException();
                rows = meetings.afterCursor(owner, Instant.parse(values[0]), UUID.fromString(values[1]), PageRequest.of(0, limit + 1));
            } catch (IllegalArgumentException | DateTimeException e) { throw ApiException.invalid("Cursor không hợp lệ."); }
        }
        boolean more = rows.size() > limit;
        List<Meeting> selected = rows.stream().limit(limit).toList();
        String next = null;
        if (more) {
            Meeting last = selected.get(selected.size() - 1);
            next = Base64.getUrlEncoder().withoutPadding().encodeToString((last.createdAt() + "|" + last.id()).getBytes(StandardCharsets.UTF_8));
        }
        // Rows were selected by owner above; stats are read only for those IDs.
        var stats = tasks.stats(selected.stream().map(Meeting::id).toList());
        return new MeetingPage(selected.stream().map(m -> {
            var stat = stats.getOrDefault(m.id(), new TaskStore.MeetingStats("NOT_STARTED", 0, 0));
            return new MeetingSummary(m.id(), m.title(), m.meetingDate(), m.inputVersion(), m.createdAt(),
                    stat.analysisStatus(), stat.pendingTasks(), stat.rejectedTasks());
        }).toList(), next);
    }

    @Transactional(readOnly = true)
    public TranscriptPage transcript(UUID owner, UUID id, int cursor, int limit) {
        validateLimit(limit);
        if (cursor < -1) throw ApiException.invalid("Cursor không hợp lệ.");
        var meeting = owned(owner, id);
        var revision = revision(meeting);
        if (revision.rawContent() == null) return new TranscriptPage(revision.id(), false, List.of(), null);
        var rows = segments.findByRevisionIdAndSequenceGreaterThanOrderBySequence(revision.id(), cursor, PageRequest.of(0, limit + 1));
        var selected = rows.stream().limit(limit).toList();
        var result = selected.stream().map(s -> {
            int start = ((Number) s.sourceLocator().get("rawStart")).intValue();
            int end = ((Number) s.sourceLocator().get("rawEnd")).intValue();
            return new SegmentView(s.id(), s.sequence(), s.speaker(), s.timestamp(), s.text(),
                    revision.rawContent().substring(start, end), s.normalizedStart(), s.normalizedEnd(), s.sourceLocator());
        }).toList();
        Integer next = rows.size() > limit ? selected.get(selected.size() - 1).sequence() : null;
        return new TranscriptPage(revision.id(), true, result, next);
    }

    private void appendRevision(Meeting meeting, int number, ParsedTranscript input, Instant now) {
        var revision = revisions.saveAndFlush(new TranscriptRevision(meeting.id(), number, input,
                hash(input.rawText()), now, now.plus(limits.sourceRetention())));
        segments.saveAll(input.segments().stream().map(s -> new TranscriptSegment(revision.id(), s)).toList());
        meeting.attachRevision(revision.id());
    }

    private Meeting owned(UUID owner, UUID id) { return meetings.findByIdAndUserId(id, owner).orElseThrow(ApiException::notFound); }
    private TranscriptRevision revision(Meeting meeting) { return revisions.findById(meeting.currentRevisionId()).orElseThrow(ApiException::notFound); }
    private MeetingView view(Meeting meeting) {
        var job = jobs.current(meeting.id(), meeting.currentRevisionId(), meeting.inputVersion());
        var revision = revision(meeting);
        String text = Optional.ofNullable(revision.normalizedContent()).orElse("");
        int end = Math.min(4000, text.length());
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return new MeetingView(meeting.id(), meeting.inputVersion(), revision.id(), meeting.title(), meeting.meetingDate(),
                meeting.timezone(), text.substring(0, end), text.length(), segments.countByRevisionId(revision.id()),
                revision.warnings(), job.map(j -> j.status().name()).orElse("NOT_STARTED"), meeting.createdAt(), revision.expiresAt(),
                job.map(j -> j.id()).orElse(null), sync.latestJob(meeting.id()).orElse(null),
                revision.rawContent() != null && revision.normalizedContent() != null);
    }

    private void validateMetadata(String title, String timezone) {
        if (title != null && title.length() > 255) throw ApiException.invalid("Title tối đa 255 ký tự.");
        if (timezone != null && !timezone.isBlank()) {
            if (timezone.length() > 64 || !ZoneId.getAvailableZoneIds().contains(timezone)) {
                throw ApiException.invalid("Dùng timezone IANA hợp lệ, ví dụ Asia/Ho_Chi_Minh.");
            }
        }
    }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private void validateLimit(int limit) { if (limit < 1 || limit > 100) throw ApiException.invalid("Limit phải từ 1 đến 100."); }
    private String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
