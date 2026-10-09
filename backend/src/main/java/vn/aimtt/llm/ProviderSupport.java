package vn.aimtt.llm;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import vn.aimtt.job.JobFailure;
import vn.aimtt.job.AnalysisJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Set;

final class ProviderSupport {
    private static final Logger log = LoggerFactory.getLogger(ProviderSupport.class);
    private static final Set<String> ERROR_STATUSES = Set.of("INVALID_ARGUMENT", "NOT_FOUND", "PERMISSION_DENIED", "UNAUTHENTICATED", "FAILED_PRECONDITION", "RESOURCE_EXHAUSTED", "UNAVAILABLE", "INTERNAL", "DEADLINE_EXCEEDED");
    private ProviderSupport() {}
    static JsonNode body(ObjectMapper json, LlmHttpTransport.Response response, AnalysisJob job) {
        int status = response.status();
        if (status < 200 || status >= 300) {
            // Only fixed enums and validated model IDs are logged. Vendor messages can echo source or credentials.
            String vendorStatus = "UNSPECIFIED";
            var diagnostic = new ProviderDiagnostics.View("UNSPECIFIED", java.util.List.of());
            try {
                var error = json.readTree(response.body());
                String value = error == null ? "" : error.path("error").path("status").asText();
                if (ERROR_STATUSES.contains(value)) vendorStatus = value;
                diagnostic = ProviderDiagnostics.read(error);
            } catch (Exception ignored) { }
            String model = job.model() != null && job.model().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}") ? job.model() : "INVALID_MODEL_ID";
            String provider = "gemini".equals(job.providerId()) ? "gemini" : "openai".equals(job.providerId()) ? "openai" : "UNKNOWN";
            log.warn("LLM request rejected jobId={} provider={} model={} httpStatus={} vendorStatus={} reason={} fieldHints={}", job.id(), provider, model, status, vendorStatus, diagnostic.reason(), diagnostic.fields());
        }
        if (status == 401 || status == 403) throw new JobFailure("PROVIDER_AUTH_ERROR", false);
        if (status == 429) throw new JobFailure("PROVIDER_RATE_LIMIT", true);
        if (status >= 500) throw new JobFailure("PROVIDER_UNAVAILABLE", true);
        if (status < 200 || status >= 300) throw new JobFailure("PROVIDER_REQUEST_REJECTED", false);
        try {
            var body = json.readTree(new String(response.body(), StandardCharsets.UTF_8));
            if (body == null || !body.isObject()) throw new JobFailure("PROVIDER_RESPONSE_INVALID", false);
            return body;
        }
        catch (Exception failure) { throw new JobFailure("PROVIDER_RESPONSE_INVALID", false); }
    }
    static String encode(ObjectMapper json, Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new JobFailure("INTERNAL_JOB_ERROR", false); }
    }
    static Long count(JsonNode value) { return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0 ? value.asLong() : null; }
}
