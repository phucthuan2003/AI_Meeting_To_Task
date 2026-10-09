package vn.aimtt.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import vn.aimtt.job.*;
import static org.assertj.core.api.Assertions.*;

class LlmConfigurationTest {
    @Test void applicationComponentsBindAndConstructWithoutDatabaseOrSendingRequests() {
        new ApplicationContextRunner().withBean(ObjectMapper.class,ObjectMapper::new)
                .withBean(LlmProperties.class,LlmProperties::defaults)
                .withBean(AnalysisProperties.class,()->new AnalysisProperties(false,Duration.ofSeconds(30),3,3,2,100,131072,8192))
                .withUserConfiguration(AnalysisPolicy.class,AnalysisPipeline.class,OpenAiProvider.class,GeminiProvider.class,JdkLlmHttpTransport.class,ExtractionValidator.class)
                .run(context->{assertThat(context).hasNotFailed();assertThat(context.getBeansOfType(LlmProvider.class)).hasSize(2);});
    }
    @Test void readinessReflectsConfiguredProviderAndPolicyFreezesModelPromptAndOutputBudget() {
        var policy=new AnalysisPolicy(LlmFixtures.properties()); var analysis=new AnalysisProperties(false,Duration.ofSeconds(30),3,3,2,100,131072,8192);
        assertThat(policy.views()).allMatch(p->p.providerReady() && p.processingPolicyId().equals("llm-v1"));
        var frozen=policy.resolve("openai","llm-v1",analysis);assertThat(frozen.model()).isEqualTo("gpt-4.1-mini-2025-04-14");
        assertThat(frozen.promptVersion()).isEqualTo(ExtractionPrompt.VERSION);assertThat(frozen.reservedTokens()).isEqualTo(4096);
        assertThat(policy.resolve("gemini", "llm-v1", analysis).promptVersion()).isEqualTo(ExtractionPrompt.GEMINI_VERSION);
        assertThat(policy.resolve("openai","foundation-v1",analysis).model()).isEqualTo("NOT_CONFIGURED");
        var defaults=LlmProperties.defaults();var one=new AnalysisPolicy(new LlmProperties(LlmFixtures.properties().openai(),defaults.gemini(),defaults.requestTimeout(),defaults.maxInputBytes(),defaults.maxOutputTokens()));
        assertThat(one.views().get(0).providerReady()).isTrue();assertThat(one.views().get(1).providerReady()).isFalse();
    }
    @Test void secretsCannotAppearInConfigurationToStringAndInvalidModelOrBudgetIsRejected() {
        assertThat(LlmFixtures.properties().toString()).doesNotContain("synthetic-openai-key","synthetic-gemini-key");
        var defaults=LlmProperties.defaults();
        assertThatThrownBy(()->new LlmProperties(new LlmProperties.Credentials("key","https://malicious.example"),defaults.gemini(),defaults.requestTimeout(),defaults.maxInputBytes(),4096)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new LlmProperties(defaults.openai(),defaults.gemini(),Duration.ofSeconds(1000),defaults.maxInputBytes(),4096)).isInstanceOf(IllegalArgumentException.class);
    }
}
