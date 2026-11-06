package vn.aimtt.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import vn.aimtt.job.JobFailure;
import vn.aimtt.llm.LlmHttpTransport;

@Component
public class HttpSttProvider implements SttProvider {
    private final AudioProperties properties;
    private final LlmHttpTransport transport;
    private final ObjectMapper json;

    public HttpSttProvider(AudioProperties properties, LlmHttpTransport transport, ObjectMapper json) {
        this.properties = properties;
        this.transport = transport;
        this.json = json;
    }

    @Override
    public List<Segment> transcribe(byte[] audio, String suffix, String model, BooleanSupplier keepLease) {
        boolean intelligence = AudioProperties.INTELLIGENCE_MODEL.equals(model);
        String apiKey = intelligence ? properties.intelligenceApiKey() : properties.apiKey();
        if (apiKey.isBlank() || (intelligence && properties.intelligenceUrl() == null)) {
            throw new JobFailure("STT_NOT_CONFIGURED", false);
        }
        URI endpoint = intelligence ? properties.intelligenceUrl().resolve("/api/v1/transcribe/file")
                : URI.create("https://api.groq.com/openai/v1/audio/transcriptions");
        Map<String, String> fields = intelligence ? Map.of()
                : Map.of("model", model, "response_format", "verbose_json", "temperature", "0");
        try {
            String boundary = "aimtt" + UUID.randomUUID().toString().replace("-", "");
            var header = new ByteArrayOutputStream();
            for (var field : fields.entrySet()) {
                header.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey()
                        + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            header.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio." + suffix
                    + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            var multipart = HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofByteArray(header.toByteArray()),
                    HttpRequest.BodyPublishers.ofByteArray(audio),
                    HttpRequest.BodyPublishers.ofByteArray(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)));
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(multipart);
            // Uvicorn uses HTTP/1.1 and can reject the Java client's cleartext HTTP/2 upgrade.
            if (intelligence) request.version(HttpClient.Version.HTTP_1_1);
            var response = transport.send(request.build(), keepLease);
            if (response.status() != 200) {
                throw new JobFailure(switch (response.status()) {
                    case 401, 403 -> "STT_AUTH_ERROR";
                    case 429 -> "STT_RATE_LIMIT";
                    default -> "STT_REQUEST_FAILED";
                }, response.status() == 429 || response.status() >= 500);
            }

            var body = json.readTree(response.body());
            var durationNode = body.path(intelligence ? "duration_seconds" : "duration");
            double duration = durationNode.asDouble();
            if (!durationNode.isNumber() || !Double.isFinite(duration) || duration > 3600 || duration <= 0) {
                throw new JobFailure("STT_DURATION_LIMIT", false);
            }
            var segments = body.path("segments");
            if (!segments.isArray() || segments.size() > 10000) {
                throw new JobFailure("STT_RESPONSE_INVALID", false);
            }

            var result = new ArrayList<Segment>();
            long previous = -1;
            int chars = 0;
            for (var row : segments) {
                double start = row.path("start").asDouble(-1);
                double end = row.path("end").asDouble(-1);
                var textNode = row.path("text");
                if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end <= start
                        || end > duration + 1 || end > 3601 || !textNode.isTextual()) {
                    throw new JobFailure("STT_RESPONSE_INVALID", false);
                }
                long from = Math.round(start * 1000);
                long to = Math.round(end * 1000);
                if (from < previous) throw new JobFailure("STT_RESPONSE_INVALID", false);
                previous = from;
                String text = Normalizer.normalize(textNode.asText(), Normalizer.Form.NFC)
                        .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "").strip();
                chars += text.length();
                if (chars > 200000) throw new JobFailure("TRANSCRIPT_OVER_BUDGET", false);
                if (!text.isBlank()) result.add(new Segment(from, to, text));
            }
            return List.copyOf(result);
        } catch (JobFailure error) {
            throw error;
        } catch (Exception error) {
            throw new JobFailure("STT_RESPONSE_INVALID", false);
        }
    }
}
