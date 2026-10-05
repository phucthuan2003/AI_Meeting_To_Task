package vn.aimtt.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class RequestBodyLimitFilterTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test void declaredOversizeDoesNotReadOrPassBody() throws Exception {
        var request = new MockHttpServletRequest();
        request.setContentType("application/json"); request.setContent("123456789".getBytes());
        var response = new MockHttpServletResponse();
        var calls = new AtomicInteger();
        new RequestBodyLimitFilter(8, mapper).doFilter(request, response, (req, res) -> calls.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(mapper.readTree(response.getContentAsByteArray()).get("code").asText()).isEqualTo("REQUEST_TOO_LARGE");
        assertThat(calls.get()).isZero();
    }

    @Test void unknownLengthStreamCannotBypassLimit() {
        var request = new MockHttpServletRequest() {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setContentType("application/json"); request.setContent("123456789".getBytes(StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> new RequestBodyLimitFilter(8, mapper).doFilter(request, response,
                (req, res) -> ((HttpServletRequest) req).getInputStream().readAllBytes()))
                .isInstanceOf(RequestBodyLimitFilter.BodyLimitExceeded.class);
    }
}
