package vn.aimtt.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Converts vendor diagnostics to fixed labels without echoing error messages or input values. */
final class ProviderDiagnostics {
    private static final Set<String> REASONS = Set.of("API_KEY_INVALID", "API_KEY_EXPIRED", "API_KEY_SERVICE_BLOCKED", "API_KEY_HTTP_REFERRER_BLOCKED", "API_KEY_IP_ADDRESS_BLOCKED", "SERVICE_DISABLED", "BILLING_DISABLED", "CONSUMER_INVALID", "ACCESS_TOKEN_EXPIRED", "IAM_PERMISSION_DENIED");
    private static final List<String> FIELDS = List.of("responseFormat", "responseJsonSchema", "responseMimeType", "mimeType", "thinkingConfig", "thinkingBudget", "thinkingLevel", "maxOutputTokens", "systemInstruction", "contents", "enum", "maxLength", "additionalProperties", "required");
    record View(String reason, List<String> fields) {}
    static View read(JsonNode body) {
        if (body == null) return new View("UNSPECIFIED", List.of());
        var error = body.path("error"); String reason = "UNSPECIFIED";
        var fields = new LinkedHashSet<String>();
        StringBuilder messages = new StringBuilder(error.path("message").asText(""));
        for (var detail : error.path("details")) {
            String candidate = detail.path("reason").asText();
            if (REASONS.contains(candidate)) reason = candidate;
            for (var violation : detail.path("fieldViolations")) {
                messages.append(' ').append(violation.path("field").asText("")).append(' ').append(violation.path("description").asText(""));
            }
        }
        // These are hints from fixed known names, never raw paths or free-form vendor text.
        String compact = messages.toString().replace("_", "").toLowerCase(Locale.ROOT);
        for (String field : FIELDS) if (compact.contains(field.toLowerCase(Locale.ROOT))) fields.add(field);
        if (reason.equals("UNSPECIFIED")) {
            if (compact.contains("api key not valid") || compact.contains("api key invalid")) reason = "API_KEY_INVALID";
            else if (compact.contains("non-string value in enum") || compact.contains("nonstring value in enum")) reason = "SCHEMA_ENUM_VALUE";
            else if (compact.contains("unknown name")) reason = "UNKNOWN_FIELD";
            else if (compact.contains("invalid value at")) reason = "INVALID_FIELD_VALUE";
            else if (compact.contains("too many states") || compact.contains("schema is too complex")) reason = "SCHEMA_COMPLEXITY";
        }
        return new View(reason, List.copyOf(fields));
    }
}
