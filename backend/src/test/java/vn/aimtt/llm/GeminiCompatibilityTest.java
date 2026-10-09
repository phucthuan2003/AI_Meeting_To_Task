package vn.aimtt.llm;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GeminiCompatibilityTest {
    final ObjectMapper json = new ObjectMapper();
    @Test void nullableEnumUsesSeparateNullBranchWithoutChangingCanonicalSchema() throws Exception {
        JsonNode schema;
        try (var stream = new org.springframework.core.io.ClassPathResource("llm/meeting-events-v1.schema.json").getInputStream()) { schema = json.readTree(stream); }
        String original = schema.toString(); var adapted = GeminiSchema.adapt(schema);
        var priority = adapted.at("/properties/events/items/properties/priority/anyOf");
        assertThat(priority.isArray()).isTrue(); assertThat(priority.get(0).get("enum").toString()).isEqualTo("[\"LOW\",\"MEDIUM\",\"HIGH\"]");
        assertThat(priority.get(1).get("type").asText()).isEqualTo("null");
        assertThat(adapted.at("/properties/events/items/properties/task_name/type")).isEqualTo(schema.at("/properties/events/items/properties/task_name/type"));
        assertThat(adapted.at("/properties/events/items/required")).isEqualTo(schema.at("/properties/events/items/required"));
        assertThat(adapted.at("/properties/events/items/properties/task_name").has("maxLength")).isFalse();
        assertThat(schema.toString()).isEqualTo(original);
    }
    @Test void invalidKeyReasonIsRecognizedEvenWhenHttpStatusIs400() throws Exception {
        var result = ProviderDiagnostics.read(json.readTree("{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"message\":\"secret transcript\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}"));
        assertThat(result.reason()).isEqualTo("API_KEY_INVALID");
        assertThat(result.toString()).doesNotContain("secret transcript");
    }
    @Test void fieldViolationHintsAndUnknownFieldsNeverEchoFreeFormText() throws Exception {
        var result = ProviderDiagnostics.read(json.readTree("{\"error\":{\"message\":\"Invalid value at generation_config.response_format.text.mime_type: secret-key\",\"details\":[{\"reason\":\"private transcript\",\"fieldViolations\":[{\"field\":\"generation_config.response_format.text.mime_type\",\"description\":\"private transcript\"}]}]}}"));
        assertThat(result.reason()).isEqualTo("INVALID_FIELD_VALUE"); assertThat(result.fields()).contains("responseFormat", "mimeType");
        assertThat(result.toString()).doesNotContain("private transcript", "secret-key");
        var unknown = ProviderDiagnostics.read(json.readTree("{\"error\":{\"message\":\"Unknown name responseFormat private transcript\"}}"));
        assertThat(unknown.reason()).isEqualTo("UNKNOWN_FIELD"); assertThat(unknown.fields()).contains("responseFormat");
    }
    @Test void mixedEnumErrorGetsFixedHintRatherThanReturningVendorMessage() throws Exception {
        var result = ProviderDiagnostics.read(json.readTree("{\"error\":{\"message\":\"Non-string value in enum at private location\"}}"));
        assertThat(result.reason()).isEqualTo("SCHEMA_ENUM_VALUE"); assertThat(result.fields()).contains("enum");
        assertThat(result.toString()).doesNotContain("private location");
    }
}
