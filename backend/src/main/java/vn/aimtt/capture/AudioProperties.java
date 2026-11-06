package vn.aimtt.capture;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.audio")
public record AudioProperties(String apiKey, String model, boolean workerEnabled,
                              URI intelligenceUrl, String intelligenceApiKey) {
    static final String INTELLIGENCE_MODEL = "meeting-intelligence";

    public AudioProperties {
        apiKey = apiKey == null ? "" : apiKey;
        intelligenceApiKey = intelligenceApiKey == null ? "" : intelligenceApiKey;
        model = model == null || model.isBlank() ? "whisper-large-v3-turbo" : model;
        if (!Set.of("whisper-large-v3-turbo", "whisper-large-v3").contains(model)) {
            throw new IllegalArgumentException("Unsupported Groq STT model");
        }
        if (intelligenceUrl != null && (intelligenceUrl.getHost() == null
                || !Set.of("http", "https").contains(intelligenceUrl.getScheme())
                || intelligenceUrl.getUserInfo() != null || intelligenceUrl.getQuery() != null
                || intelligenceUrl.getFragment() != null
                || !Set.of("", "/").contains(intelligenceUrl.getPath()))) {
            throw new IllegalArgumentException("INTELLIGENCE_STT_URL must be an HTTP(S) origin");
        }
    }

    public String captureModel() { return intelligenceUrl == null ? model : INTELLIGENCE_MODEL; }

    public boolean configured() {
        return intelligenceUrl == null ? !apiKey.isBlank() : !intelligenceApiKey.isBlank();
    }

    @Override public String toString() {
        return "AudioProperties[configured=" + configured() + ",model=" + captureModel() + "]";
    }
}
