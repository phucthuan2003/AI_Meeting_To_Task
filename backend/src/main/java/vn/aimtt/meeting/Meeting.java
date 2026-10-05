package vn.aimtt.meeting;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "meetings")
public class Meeting {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(length = 255) private String title;
    @Column(name = "meeting_date") private LocalDate meetingDate;
    @Column(length = 64) private String timezone;
    @Version @Column(name = "input_version", nullable = false) private long inputVersion;
    @Column(name = "current_revision_id") private UUID currentRevisionId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected Meeting() {}
    public Meeting(UUID userId, String title, LocalDate date, String timezone, Instant now) {
        this.id = UUID.randomUUID(); this.userId = userId; this.createdAt = now;
        replaceMetadata(title, date, timezone, now);
    }
    public void replaceMetadata(String title, LocalDate date, String timezone, Instant now) {
        this.title = title; this.meetingDate = date; this.timezone = timezone; this.updatedAt = now;
    }
    public void attachRevision(UUID revision) { this.currentRevisionId = revision; }
    public UUID id() { return id; }
    public String title() { return title; }
    public LocalDate meetingDate() { return meetingDate; }
    public String timezone() { return timezone; }
    public long inputVersion() { return inputVersion; }
    public UUID currentRevisionId() { return currentRevisionId; }
    public Instant createdAt() { return createdAt; }
}
