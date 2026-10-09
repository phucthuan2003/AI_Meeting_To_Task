package vn.aimtt.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import vn.aimtt.job.*;
import static vn.aimtt.llm.LlmFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisPipelineTest {
    final LlmProvider provider=mock(LlmProvider.class);
    final ObjectMapper json=new ObjectMapper();
    final AnalysisPipeline pipeline=new AnalysisPipeline(List.of(provider),properties(),json,new ExtractionValidator(json));
    Extraction.Result geminiResult(String text) throws Exception {
        byte[] response = json.writeValueAsBytes(Map.of("candidates", List.of(Map.of("finishReason", "STOP", "content",
                Map.of("parts", List.of(Map.of("text", text))))), "usageMetadata", Map.of("promptTokenCount", 42, "candidatesTokenCount", 50)));
        var gemini = new GeminiProvider((request, lease) -> new LlmHttpTransport.Response(200, response), properties(), json);
        return new AnalysisPipeline(List.of(gemini), properties(), json, new ExtractionValidator(json))
                .processPrepared(job("gemini"), () -> List.of(new Extraction.Source(SEGMENT, 0, TEXT)), () -> true);
    }
    @Test void geminiTextAndWholeJsonFenceProduceValidatedReviewCandidates() throws Exception {
        for (String text : List.of(output(), "  ```json\n" + output() + "\n```  ", "```\r\n" + output() + "\r\n```")) {
            var result = geminiResult(text);
            assertThat(result.candidates()).hasSize(1);
            var task = result.candidates().get(0);
            assertThat(task.assigneeRaw()).isEqualTo("Mai");
            assertThat(task.dueAt()).isEqualTo("2026-10-12T10:00:00Z");
            assertThat(task.reviewStatus()).isEqualTo("PENDING_REVIEW");
            assertThat(task.needsConfirmation()).isTrue();
            assertThat(task.evidence()).allMatch(quote -> quote.segmentId().equals(SEGMENT) && quote.quote().equals(TEXT));
            assertThat(result.outputTokens()).isEqualTo(50);
        }
    }
    @Test void geminiFreeTextDoesNotBypassStrictJsonOrSourceValidation() {
        for (String text : List.of("Bằng 2.", "Here is JSON: " + output(), output() + " {}",
                "```json\n" + output() + "\n``` trailing prose", "```json\n" + output(),
                "{\"events\":[],\"events\":[]}", "{\"tasks\":[]}",
                output().replace(SEGMENT.toString(), UUID.randomUUID().toString()), output().replace("Mai", "Lan"))) {
            assertThatThrownBy(() -> geminiResult(text)).isInstanceOfSatisfying(JobFailure.class,
                    failure -> assertThat(failure.code()).isEqualTo("PROVIDER_RESPONSE_INVALID"));
        }
    }
    @Test void oldGeminiPromptSnapshotCannotSilentlyUseNewRequestContract() {
        var old = mock(AnalysisJob.class);
        when(old.policyId()).thenReturn("llm-v1"); when(old.providerId()).thenReturn("gemini");
        when(old.promptVersion()).thenReturn(ExtractionPrompt.VERSION);
        assertThatThrownBy(() -> pipeline.processPrepared(old, () -> { throw new AssertionError("Must not load source"); }, () -> true))
                .isInstanceOfSatisfying(JobFailure.class, failure -> assertThat(failure.code()).isEqualTo("POLICY_VERSION_UNAVAILABLE"));
        verifyNoInteractions(provider);
    }
    @Test void actualPipelineValidatesSourceAndProviderOutputBeforeReturningCandidates() {
        when(provider.id()).thenReturn("openai");when(provider.extract(any(),anyString(),any(),any())).thenReturn(new LlmProvider.Output(output(),42L,50L,10));
        var result=pipeline.processPrepared(job("openai"),()->List.of(new Extraction.Source(SEGMENT,0,TEXT)),()->true);
        assertThat(result.candidates()).hasSize(1);
        verify(provider).extract(any(),contains(SEGMENT.toString()),argThat(schema->schema.path("additionalProperties").asBoolean(true)==false),any());
    }
    @Test void lostLeaseOverBudgetAndUnknownSourceNeverDispatchToProvider() {
        when(provider.id()).thenReturn("openai");
        for (var source:List.of(List.of(new Extraction.Source(SEGMENT,4,TEXT)),List.of(new Extraction.Source(SEGMENT,0,"a".repeat(60000)))))
            assertThatThrownBy(()->pipeline.processPrepared(job("openai"),()->source,()->true)).isInstanceOf(JobFailure.class);
        assertThatThrownBy(()->pipeline.processPrepared(job("openai"),()->List.of(new Extraction.Source(SEGMENT,0,TEXT)),()->false)).isInstanceOf(JobFailure.class);
        verify(provider,never()).extract(any(),anyString(),any(),any());
    }
}
