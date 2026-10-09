package vn.aimtt.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.llm")
public record LlmProperties(Credentials openai, Credentials gemini, Duration requestTimeout,
                            int maxInputBytes, int maxOutputTokens) {
    public record Credentials(String apiKey, String model) {
        public boolean configured() { return apiKey != null && !apiKey.isBlank(); }
        @Override public String toString() { return "Credentials[model=" + model + ", configured=" + configured() + "]"; }
    }
    public LlmProperties {
        if (requestTimeout == null || requestTimeout.toSeconds() < 5 || requestTimeout.toSeconds() > 120
                || maxInputBytes < 4096 || maxInputBytes > 65536 || maxOutputTokens < 1024 || maxOutputTokens > 8192) {
            throw new IllegalArgumentException("LLM timeout/input/output budgets are out of bounds.");
        }
        for (var credentials : java.util.List.of(openai, gemini)) {
            if (credentials.model() == null || !credentials.model().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}"))
                throw new IllegalArgumentException("LLM model must be a model ID, not a URL.");
            if (credentials.configured() && (credentials.apiKey().chars().anyMatch(c -> c < 33 || c > 126)))
                throw new IllegalArgumentException("LLM API key must not contain whitespace or control characters.");
        }
    }
    public static LlmProperties defaults() {
        return new LlmProperties(new Credentials("", "gpt-4.1-mini-2025-04-14"), new Credentials("", "gemini-2.5-flash"),
                Duration.ofSeconds(60), 49152, 4096);
    }
    public Credentials credentials(String provider) {
        return switch (provider) { case "openai" -> openai; case "gemini" -> gemini; default -> throw new IllegalArgumentException("Unknown provider"); };
    }
}
