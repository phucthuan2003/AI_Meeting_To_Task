package vn.aimtt.job;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.*;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import vn.aimtt.llm.*;

@Component
public class AnalysisPipeline {
    private final List<LlmProvider> providers;
    private final LlmProperties properties;
    private final ObjectMapper json;
    private final ExtractionValidator validator;
    public AnalysisPipeline() { this(List.of(), LlmProperties.defaults(), new ObjectMapper(), new ExtractionValidator(new ObjectMapper())); }
    @Autowired
    public AnalysisPipeline(List<LlmProvider> providers, LlmProperties properties, ObjectMapper json, ExtractionValidator validator) {
        this.providers = providers; this.properties = properties; this.json = json; this.validator = validator;
    }
    public Extraction.Result processPrepared(AnalysisJob job, Supplier<List<Extraction.Source>> loadSource, BooleanSupplier keepLease) {
        if (!AnalysisPolicy.LLM_ID.equals(job.policyId())) throw new JobFailure("PROVIDER_NOT_CONFIGURED", false);
        if (!ExtractionPrompt.versionFor(job.providerId()).equals(job.promptVersion()) || !ExtractionPrompt.SCHEMA_VERSION.equals(job.schemaVersion())) throw new JobFailure("POLICY_VERSION_UNAVAILABLE", false);
        var provider = providers.stream().filter(p -> p.id().equals(job.providerId())).findFirst().orElseThrow(() -> new JobFailure("PROVIDER_NOT_CONFIGURED", false));
        if (!properties.credentials(job.providerId()).configured()) throw new JobFailure("PROVIDER_NOT_CONFIGURED", false);
        var source = loadSource.get(); if (source.isEmpty()) throw new JobFailure("SOURCE_UNAVAILABLE", false);
        for (int i = 0; i < source.size(); i++) if (source.get(i).sequence() != i) throw new JobFailure("SOURCE_UNAVAILABLE", false);
        try {
            var data = json.createObjectNode(); data.put("title", job.title()); data.put("meeting_date", job.meetingDate() == null ? null : job.meetingDate().toString());
            data.put("timezone", job.timezone()); data.set("segments", json.valueToTree(source));
            String input = json.writeValueAsString(data); JsonNode schema;
            try (var stream = new ClassPathResource("llm/meeting-events-v1.schema.json").getInputStream()) { schema = json.readTree(stream); }
            long bytes = input.getBytes(StandardCharsets.UTF_8).length;
            long envelope = ExtractionPrompt.SYSTEM.getBytes(StandardCharsets.UTF_8).length + schema.toString().getBytes(StandardCharsets.UTF_8).length + 2048L;
            if (bytes > properties.maxInputBytes() || bytes + envelope + job.reservedTokens() > job.contextTokens()) throw new JobFailure("TRANSCRIPT_OVER_BUDGET", false);
            if (!keepLease.getAsBoolean()) throw new JobFailure("LEASE_LOST", false);
            var output = provider.extract(job, input, schema, keepLease);
            if (!keepLease.getAsBoolean()) throw new JobFailure("LEASE_LOST", false);
            return validator.validate(job, source, output);
        } catch (JobFailure failure) { throw failure; }
        catch (Exception failure) { throw new JobFailure("INTERNAL_JOB_ERROR", true); }
    }
}
