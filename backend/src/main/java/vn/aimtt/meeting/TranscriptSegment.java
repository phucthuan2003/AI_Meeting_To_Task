package vn.aimtt.meeting;

import jakarta.persistence.*;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vn.aimtt.transcript.ParsedTranscript;

@Entity
@Table(name = "transcript_segments")
public class TranscriptSegment {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "revision_id", nullable = false) private UUID revisionId;
    @Column(nullable = false) private int sequence;
    @Column(columnDefinition = "text") private String speaker;
    @Column(name = "timestamp_raw", length = 16) private String timestamp;
    @Column(nullable = false, columnDefinition = "text") private String text;
    @Column(name = "normalized_start", nullable = false) private int normalizedStart;
    @Column(name = "normalized_end", nullable = false) private int normalizedEnd;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "source_locator", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> sourceLocator;

    protected TranscriptSegment() {}
    public TranscriptSegment(UUID revisionId, ParsedTranscript.Segment s) {
        this.revisionId = revisionId; this.sequence = s.sequence(); this.speaker = s.speaker();
        this.timestamp = s.timestamp(); this.text = s.text(); this.normalizedStart = s.normalizedStart();
        this.normalizedEnd = s.normalizedEnd(); this.sourceLocator = s.sourceLocator();
    }
    public UUID id() { return id; }
    public int sequence() { return sequence; }
    public String speaker() { return speaker; }
    public String timestamp() { return timestamp; }
    public String text() { return text; }
    public int normalizedStart() { return normalizedStart; }
    public int normalizedEnd() { return normalizedEnd; }
    public Map<String, Object> sourceLocator() { return sourceLocator; }
}
