package vn.aimtt.task;

import java.time.*;
import java.util.*;

/** Persistent review draft (SDS §7.6). JSON columns are decoded by {@link TaskStore}. */
public record TaskRow(UUID id, UUID meetingId, UUID analysisJobId, String origin, UUID sourceRevisionId, String taskRef, int ordinal,
                      String taskName, String description, String assigneeRaw, String trelloMemberId, String memberResolution,
                      String deadlineRaw, LocalDateTime dueLocal, Instant dueAt, String timezone, String deadlineResolution,
                      String priority, Map<String, Object> aiSuggestion, List<String> aiNotes, List<String> editedFields,
                      String reviewStatus, String syncStatus, long version, boolean includeEvidenceInCard,
                      String trelloCardId, String trelloCardUrl, Instant createdAt, Instant updatedAt, Instant rejectedAt,
                      List<Map<String, Object>> memberCandidates, Long memberDestinationVersion) {
    public boolean ai() { return "AI".equals(origin); }

    /** Fields the user may change through PATCH; copied as a whole to keep updates explicit. */
    public record Draft(String taskName, String description, String assigneeRaw, String trelloMemberId, String memberResolution,
                        String deadlineRaw, LocalDateTime dueLocal, Instant dueAt, String timezone, String deadlineResolution,
                        String priority, boolean includeEvidenceInCard, Long memberDestinationVersion) {}

    public Draft draft() {
        return new Draft(taskName, description, assigneeRaw, trelloMemberId, memberResolution, deadlineRaw, dueLocal, dueAt,
                timezone, deadlineResolution, priority, includeEvidenceInCard, memberDestinationVersion);
    }
}
