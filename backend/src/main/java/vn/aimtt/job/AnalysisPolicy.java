package vn.aimtt.job;

import org.springframework.stereotype.Component;
import vn.aimtt.common.ApiException;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import vn.aimtt.llm.*;

@Component
public class AnalysisPolicy {
    // Accept the original preparation provider for older clients/jobs, but do not offer it in the UI.
    public static final String PROVIDER = "unconfigured";
    public static final String ID = "foundation-v1";
    public static final String LLM_ID = "llm-v1";
    private final LlmProperties properties;
    public AnalysisPolicy() { this(LlmProperties.defaults()); }
    @Autowired public AnalysisPolicy(LlmProperties properties) { this.properties = properties; }
    public void validate(String provider, String policy) {
        if ((!PROVIDER.equals(provider) && !"openai".equals(provider) && !"gemini".equals(provider))
                || (!ID.equals(policy) && !LLM_ID.equals(policy)) || (LLM_ID.equals(policy) && PROVIDER.equals(provider))) {
            throw ApiException.invalid("Chọn provider/policy do backend công bố; không gửi model, prompt hoặc API key.");
        }
    }
    public record Definition(String providerId, String model, String policyId, String promptVersion, String schemaVersion, int contextTokens, int reservedTokens) {}
    public Definition resolve(String provider, String policy, AnalysisProperties analysis) {
        validate(provider, policy);
        if (ID.equals(policy)) return new Definition(provider, "NOT_CONFIGURED", ID, "pending-llm-v1", "action-items-v1", analysis.contextTokens(), analysis.reservedTokens());
        if (!properties.credentials(provider).configured()) throw ApiException.invalid("Provider chưa được cấu hình API key ở backend. Tải lại danh sách AI.");
        return new Definition(provider, properties.credentials(provider).model(), LLM_ID, ExtractionPrompt.versionFor(provider), ExtractionPrompt.SCHEMA_VERSION, 65536, properties.maxOutputTokens());
    }
    public record View(String providerId, String displayName, String processingPolicyId, boolean providerReady, String description) {}
    public List<View> views() {
        return List.of(view("openai", "OpenAI"), view("gemini", "Gemini"));
    }
    private View view(String id, String name) {
        boolean ready = properties.credentials(id).configured();
        return new View(id, name, ready ? LLM_ID : ID, ready,
                ready ? "Bấm Phân tích sẽ gửi transcript và metadata cuộc họp đến " + name + ". Kết quả AI cần bạn kiểm tra trước khi dùng."
                        : "Chưa cấu hình API key của " + name + "; hiện chỉ chuẩn bị input, chưa gửi transcript tới provider.");
    }
}
