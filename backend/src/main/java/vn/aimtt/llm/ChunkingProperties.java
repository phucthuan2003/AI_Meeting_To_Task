package vn.aimtt.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** SDS §5.6/§9.7: chunking stays off until the long-transcript acceptance cases pass for the chosen model. */
@ConfigurationProperties("app.chunking")
public record ChunkingProperties(boolean enabled, int inputBytes, int overlapSegments, int maxChunks, int knownTasksBytes) {
    public ChunkingProperties {
        if (inputBytes < 2048 || inputBytes > 65536 || overlapSegments < 0 || overlapSegments > 10 || maxChunks < 1 || maxChunks > 50
                || knownTasksBytes < 512 || knownTasksBytes >= inputBytes) throw new IllegalArgumentException("Chunking budgets out of bounds.");
    }
    public static ChunkingProperties disabled() { return new ChunkingProperties(false, 24000, 2, 20, 4096); }
}
