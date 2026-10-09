package vn.aimtt.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.aimtt.common.ApiException;

/**
 * Data lifecycle (SDS §6.3, §7.4–7.7, §10.3). Purge removes every copy of source text — raw, normalized, segments,
 * chunk outputs, AI result quotes and evidence quotes in snapshots — while approved tasks and card mappings stay so
 * cards are never re-created. Deleting a meeting never deletes Trello cards.
 */
@Service
public class PrivacyService {
    private static final Logger log = LoggerFactory.getLogger(PrivacyService.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RetentionProperties properties;
    private final TransactionTemplate tx;

    public PrivacyService(JdbcTemplate jdbc, ObjectMapper json, RetentionProperties properties, TransactionTemplate tx) {
        this.jdbc = jdbc; this.json = json; this.properties = properties; this.tx = tx;
    }

    private void lockOwned(UUID owner, UUID meetingId) {
        if (jdbc.query("select id from meetings where id = ? and user_id = ? for update", (r, n) -> 1, meetingId, owner).isEmpty()) throw ApiException.notFound();
    }
    private boolean analysisActive(UUID meetingId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from analysis_jobs where meeting_id = ? and status in ('QUEUED','PROCESSING','CANCEL_REQUESTED'))", Boolean.class, meetingId));
    }
    private boolean syncInFlight(UUID meetingId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from sync_items i join tasks t on t.id = i.task_id where t.meeting_id = ? and i.status in ('QUEUED','SYNCING','UNKNOWN'))""", Boolean.class, meetingId));
    }

    /** DELETE /meetings/{id}/transcript: remove source content now; tasks and card links remain viewable. */
    @Transactional
    public void deleteTranscript(UUID owner, UUID meetingId) {
        lockOwned(owner, meetingId);
        if (analysisActive(meetingId)) throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_BUSY", "Đang phân tích. Hủy job và chờ dừng trước khi xóa nội dung.");
        for (UUID revision : jdbc.query("select id from transcript_revisions where meeting_id = ? and purged_at is null", (r, n) -> r.getObject(1, UUID.class), meetingId)) purgeRevision(revision, true);
        audit(owner, meetingId, "TRANSCRIPT_DELETED");
    }

    /** DELETE /meetings/{id}: not while analysis runs or a card outcome is unknown; Trello cards are untouched. */
    @Transactional
    public void deleteMeeting(UUID owner, UUID meetingId) {
        lockOwned(owner, meetingId);
        if (analysisActive(meetingId)) throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_BUSY", "Đang phân tích. Hủy job và chờ dừng trước khi xóa meeting.");
        if (syncInFlight(meetingId)) throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_BUSY", "Có card đang tạo hoặc chờ đối soát. Xử lý xong trước khi xóa meeting.");
        String tasks = "select id from tasks where meeting_id = ?";
        jdbc.update("delete from sync_attempts where sync_item_id in (select id from sync_items where task_id in (" + tasks + "))", meetingId);
        jdbc.update("delete from sync_items where task_id in (" + tasks + ")", meetingId);
        jdbc.update("delete from sync_items where sync_job_id in (select id from sync_jobs where meeting_id = ?)", meetingId);
        jdbc.update("delete from sync_jobs where meeting_id = ?", meetingId);
        jdbc.update("delete from task_snapshots where meeting_id = ?", meetingId);
        jdbc.update("delete from task_evidence where task_id in (" + tasks + ")", meetingId);
        jdbc.update("delete from tasks where meeting_id = ?", meetingId);
        jdbc.update("delete from meeting_destinations where meeting_id = ?", meetingId);
        jdbc.update("update meetings set current_analysis_job_id = null where id = ?", meetingId);
        String jobs = "select id from analysis_jobs where meeting_id = ?";
        jdbc.update("delete from processing_logs where job_id in (" + jobs + ")", meetingId);
        jdbc.update("delete from chunk_results where job_id in (" + jobs + ")", meetingId);
        jdbc.update("delete from analysis_results where job_id in (" + jobs + ")", meetingId);
        jdbc.update("delete from analysis_checkpoints where job_id in (" + jobs + ")", meetingId);
        jdbc.update("delete from analysis_jobs where meeting_id = ?", meetingId);
        jdbc.update("update meetings set current_revision_id = null where id = ?", meetingId);
        jdbc.update("delete from transcript_segments where revision_id in (select id from transcript_revisions where meeting_id = ?)", meetingId);
        jdbc.update("delete from transcript_revisions where meeting_id = ?", meetingId);
        jdbc.update("delete from audit_events where meeting_id = ?", meetingId);
        jdbc.update("delete from meetings where id = ?", meetingId);
        audit(owner, null, "MEETING_DELETED");
    }

    /** Scrubs one revision and every derived copy of its text. */
    void purgeRevision(UUID revisionId, boolean force) {
        Instant now = Instant.now();
        var jobs = jdbc.query("select id from analysis_jobs where revision_id = ?", (r, n) -> r.getObject(1, UUID.class), revisionId);
        jdbc.update("update transcript_revisions set raw_content = null, normalized_content = null, purged_at = ? where id = ?", Timestamp.from(now), revisionId);
        jdbc.update("delete from transcript_segments where revision_id = ?", revisionId); // task_evidence.segment_id → NULL (FK)
        for (UUID job : jobs) {
            jdbc.update("delete from chunk_results where job_id = ?", job);
            var rows = jdbc.query("select result_json::text from analysis_results where job_id = ? and scrubbed_at is null", (r, n) -> r.getString(1), job);
            if (!rows.isEmpty()) {
                ObjectNode scrubbed = json.createObjectNode();
                try {
                    var original = json.readTree(rows.get(0));
                    for (String keep : List.of("inputTokens", "outputTokens", "latencyMs")) if (original.has(keep)) scrubbed.set(keep, original.get(keep));
                    scrubbed.set("warnings", json.createArrayNode());
                } catch (Exception ignored) { }
                scrubbed.put("scrubbed", true); scrubbed.set("events", json.createArrayNode()); scrubbed.set("candidates", json.createArrayNode());
                jdbc.update("update analysis_results set result_json = ?::jsonb, scrubbed_at = ? where job_id = ?", scrubbed.toString(), Timestamp.from(now), job);
            }
        }
        // Evidence quotes inside snapshots of tasks from this revision; active items keep their payload until they finish (≤ grace).
        var snapshots = jdbc.query("""
                select s.id, s.payload_json::text from task_snapshots s join tasks t on t.id = s.task_id
                where t.source_revision_id = ? and s.scrubbed_at is null and (? or not exists (
                    select 1 from sync_items i where i.snapshot_id = s.id and i.status in ('QUEUED','SYNCING','UNKNOWN')))""",
                (r, n) -> new String[] {r.getString(1), r.getString(2)}, revisionId, force);
        for (var snapshot : snapshots) {
            try {
                var payload = (ObjectNode) json.readTree(snapshot[1]);
                payload.set("evidenceQuotes", json.createArrayNode());
                String desc = payload.path("desc").asText("");
                int start = desc.indexOf("Trích dẫn từ transcript:");
                if (start >= 0) { int end = desc.indexOf("Tạo bởi AI Meeting to Task", start); payload.put("desc", desc.substring(0, start) + (end >= 0 ? desc.substring(end) : "")); }
                jdbc.update("update task_snapshots set payload_json = ?::jsonb, scrubbed_at = ? where id = ?", payload.toString(), Timestamp.from(now), UUID.fromString(snapshot[0]));
            } catch (Exception e) { log.error("Snapshot scrub failed snapshotId={}", snapshot[0]); }
        }
    }

    /** Scheduled pass (SDS §10.3). Active analysis/sync defers purge for at most the configured grace. */
    public int runRetention() {
        Instant now = Instant.now();
        var due = jdbc.query("""
                select r.id, r.meeting_id, r.expires_at from transcript_revisions r where r.purged_at is null and r.expires_at < ? order by r.expires_at limit 200""",
                (r, n) -> new Object[] {r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getTimestamp(3).toInstant()}, Timestamp.from(now));
        int purged = 0;
        for (var row : due) {
            UUID revision = (UUID) row[0], meeting = (UUID) row[1]; Instant expires = (Instant) row[2];
            boolean graceOver = expires.plus(properties.grace()).isBefore(now);
            boolean busy = analysisActive(meeting) || syncInFlight(meeting);
            if (busy && !graceOver) continue;
            tx.executeWithoutResult(s -> purgeRevision(revision, graceOver));
            purged++;
        }
        jdbc.update("delete from processing_logs where created_at < ?", Timestamp.from(now.minus(properties.logRetention())));
        jdbc.update("delete from auth_sessions where expires_at < ?", Timestamp.from(now.minus(properties.sessionRetention())));
        jdbc.update("delete from authorization_transactions where expires_at < ?", Timestamp.from(now.minus(java.time.Duration.ofDays(1))));
        if (purged > 0) log.info("Retention purged revisions count={}", purged);
        return purged;
    }

    private void audit(UUID owner, UUID meetingId, String type) {
        jdbc.update("insert into audit_events(id, user_id, meeting_id, event_type, details, created_at) values (?, ?, ?, ?, '{}'::jsonb, now())", UUID.randomUUID(), owner, meetingId, type);
    }
}
