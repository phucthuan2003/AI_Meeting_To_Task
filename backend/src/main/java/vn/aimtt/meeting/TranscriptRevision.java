package vn.aimtt.meeting;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vn.aimtt.transcript.ParsedTranscript;

@Entity
@Table(name = "transcript_revisions")
public class TranscriptRevision {
    @Id private UUID id;
    @Column(name = "meeting_id", nullable = false) private UUID meetingId;
    @Column(nullable = false) private int revision;
    @Column(name = "source_type", nullable = false, length = 8) private String sourceType;
    @Column(name = "content_hash", nullable = false, length = 64) private String contentHash;
    @Column(name = "raw_content", columnDefinition = "text") private String rawContent;
    @Column(name = "normalized_content", columnDefinition = "text") private String normalizedContent;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "parser_warnings", nullable = false, columnDefinition = "jsonb")
    private List<String> warnings;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected TranscriptRevision() {}
    public TranscriptRevision(UUID meetingId, int revision, ParsedTranscript input, String hash, Instant now, Instant expires) {
        this.id = UUID.randomUUID(); this.meetingId = meetingId; this.revision = revision;
        this.sourceType = input.sourceType(); this.contentHash = hash; this.rawContent = input.rawText();
        this.normalizedContent = input.normalizedText(); this.warnings = input.warnings();
        this.createdAt = now; this.expiresAt = expires;
    }
    public UUID id() { return id; }
    public int revision() { return revision; }
    public String rawContent() { return rawContent; }
    public String normalizedContent() { return normalizedContent; }
    public List<String> warnings() { return warnings; }
    public Instant expiresAt() { return expiresAt; }
}
