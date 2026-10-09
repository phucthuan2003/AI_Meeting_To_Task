package vn.aimtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class FoundationIntegrationTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.properties(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test void migrationAndAuthenticationLifecycle() throws Exception {
        int migrations = new org.springframework.core.io.support.PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql").length;
        assertThat(migrations).isGreaterThanOrEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class)).isEqualTo(migrations);
        String email = newEmail();
        String token = registerAndLogin(email);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(token))).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        String stored = jdbc.queryForObject("select password_hash from users where email = ?", String.class, email);
        assertThat(stored).startsWith("$2").doesNotContain("strong-password");
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", "wrong-password"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(token))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        mvc.perform(get("/api/v1/meetings")).andExpect(status().isUnauthorized());
    }

    @Test void sessionExpiryAndTamperedTokensCannotAccessDraft() throws Exception {
        String token = registerAndLogin(newEmail());
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        mvc.perform(get("/api/v1/meetings").header("Authorization", bearer(tampered))).andExpect(status().isUnauthorized());
        String sessionId = json.readTree(new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)).get("jti").asText();
        jdbc.update("update auth_sessions set expires_at = now() - interval '1 second' where id = ?", UUID.fromString(sessionId));
        mvc.perform(get("/api/v1/meetings").header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
    }

    @Test void pastePreviewSourcePaginationAndOwnerIsolation() throws Exception {
        String owner = registerAndLogin(newEmail());
        String other = registerAndLogin(newEmail());
        var meeting = create(owner, "Nam:  không đổi hạn.\r\nMai: sửa login.");
        String id = meeting.get("meetingId").asText();
        assertThat(meeting.get("analysisStatus").asText()).isEqualTo("NOT_STARTED");
        assertThat(meeting.get("segmentCount").asInt()).isEqualTo(2);
        mvc.perform(get("/api/v1/meetings/{id}", id).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/meetings/{id}/transcript", id).header("Authorization", bearer(other))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/meetings").header("Authorization", bearer(other))).andExpect(jsonPath("$.items").isEmpty());
        var first = body(mvc.perform(get("/api/v1/meetings/{id}/transcript?limit=1", id)
                .header("Authorization", bearer(owner))).andExpect(status().isOk()).andReturn());
        assertThat(first.get("segments").get(0).get("sourceText").asText()).isEqualTo("Nam:  không đổi hạn.");
        assertThat(first.get("segments").get(0).get("text").asText()).isEqualTo("Nam: không đổi hạn.");
        mvc.perform(get("/api/v1/meetings/{id}/transcript?limit=1&cursor={cursor}", id, first.get("nextCursor").asInt())
                .header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.segments[0].text").value("Mai: sửa login."));
    }

    @Test void versionConflictKeepsPriorRevisionAndRejectsOtherOwner() throws Exception {
        String token = registerAndLogin(newEmail());
        String other = registerAndLogin(newEmail());
        var meeting = create(token, "Mai: làm việc A.");
        String id = meeting.get("meetingId").asText();
        String replacement = json.writeValueAsString(Map.of("expectedVersion", meeting.get("inputVersion").asLong(), "transcriptText", "Mai: bỏ A, làm B."));
        mvc.perform(patch("/api/v1/meetings/{id}/input", id).header("Authorization", bearer(other)).contentType(MediaType.APPLICATION_JSON).content(replacement)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/meetings/{id}/input", id).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(replacement)).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/meetings/{id}/input", id).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(replacement))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        assertThat(jdbc.queryForObject("select count(*) from transcript_revisions where meeting_id = ?", Integer.class, UUID.fromString(id))).isEqualTo(2);
    }

    @Test void concurrentInputReplacementCommitsOnlyOneRevision() throws Exception {
        String token = registerAndLogin(newEmail());
        var meeting = create(token, "Mai: làm A.");
        String id = meeting.get("meetingId").asText();
        String replacement = json.writeValueAsString(Map.of("expectedVersion", meeting.get("inputVersion").asLong(), "transcriptText", "Mai: đổi sang B."));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> update = () -> {
                start.await();
                return mvc.perform(patch("/api/v1/meetings/{id}/input", id).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(replacement)).andReturn().getResponse().getStatus();
            };
            var a = pool.submit(update); var b = pool.submit(update); start.countDown();
            assertThat(java.util.List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject("select count(*) from transcript_revisions where meeting_id = ?", Integer.class, UUID.fromString(id))).isEqualTo(2);
        } finally { pool.shutdownNow(); }
    }

    @Test void txtAndDocxUploadPreviewAndExclusiveInput() throws Exception {
        String token = registerAndLogin(newEmail());
        var txt = new MockMultipartFile("file", "meeting.txt", "text/plain", "Mai: sửa API.".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/v1/meetings").file(txt).header("Authorization", bearer(token)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.preview").value("Mai: sửa API."));
        mvc.perform(multipart("/api/v1/meetings").file(txt).param("transcriptText", "extra").header("Authorization", bearer(token))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/meetings").file(txt).file(txt).header("Authorization", bearer(token))).andExpect(status().isBadRequest());
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Đầu.");
            doc.createTable(1, 1).getRow(0).getCell(0).setText("Giữa.");
            doc.createParagraph().createRun().setText("Cuối.");
            doc.write(out);
            var file = new MockMultipartFile("file", "meeting.docx", "application/octet-stream", out.toByteArray());
            mvc.perform(multipart("/api/v1/meetings").file(file).header("Authorization", bearer(token)))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.preview").value("Đầu.\nGiữa.\nCuối."));
        }
    }

    @Test void invalidInputsRollbackAndErrorsDoNotExposeValues() throws Exception {
        String token = registerAndLogin(newEmail());
        String secretMarker = "private-do-not-expose";
        mvc.perform(post("/api/v1/meetings").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("transcriptText", secretMarker, "timezone", "not-a-zone"))))
                .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(secretMarker))));
        mvc.perform(post("/api/v1/meetings").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("transcriptText", " ", "file", "forbidden")))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/meetings?limit=101").header("Authorization", bearer(token))).andExpect(status().isBadRequest());
        var result = mvc.perform(get("/api/v1/meetings?cursor=invalid").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.traceId").exists()).andExpect(header().exists("X-Trace-Id")).andReturn();
        assertThat(body(result).get("traceId").asText()).isEqualTo(result.getResponse().getHeader("X-Trace-Id"));
        mvc.perform(get("/api/v1/meetings").header("Authorization", bearer(token))).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test void cursorHistoryDoesNotSkipOrRepeatMeetingsAndCorsIsExact() throws Exception {
        String token = registerAndLogin(newEmail());
        create(token, "Việc 1."); create(token, "Việc 2."); create(token, "Việc 3.");
        var first = body(mvc.perform(get("/api/v1/meetings?limit=2").header("Authorization", bearer(token))).andExpect(status().isOk()).andReturn());
        var second = body(mvc.perform(get("/api/v1/meetings").param("limit", "2").param("cursor", first.get("nextCursor").asText())
                .header("Authorization", bearer(token))).andExpect(status().isOk()).andReturn());
        assertThat(first.get("items").size()).isEqualTo(2);
        assertThat(second.get("items").size()).isEqualTo(1);
        assertThat(second.get("items").get(0).get("meetingId")).isNotEqualTo(first.get("items").get(0).get("meetingId"));
        mvc.perform(options("/api/v1/meetings").header("Origin", "chrome-extension://test-extension-id")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "chrome-extension://test-extension-id"));
        mvc.perform(options("/api/v1/meetings").header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
    }

    private String newEmail() { return UUID.randomUUID() + "@example.test"; }
    @Test void declaredJsonBodyLimitReturnsStructuredError() throws Exception {
        String token = registerAndLogin(newEmail());
        String content = json.writeValueAsString(Map.of("transcriptText", "a".repeat(1048577)));
        mvc.perform(post("/api/v1/meetings").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
    }

    private String bearer(String token) { return "Bearer " + token; }
    private String registerAndLogin(String email) throws Exception {
        String credentials = json.writeValueAsString(Map.of("email", email, "password", "strong-password-123"));
        // Give each synthetic user a separate source IP, keeping the real rate limiter enabled.
        mvc.perform(post("/api/v1/auth/register").with(r -> { r.setRemoteAddr(email); return r; })
                .contentType(MediaType.APPLICATION_JSON).content(credentials)).andExpect(status().isCreated());
        return body(mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr(email); return r; })
                .contentType(MediaType.APPLICATION_JSON).content(credentials)).andExpect(status().isOk()).andReturn()).get("accessToken").asText();
    }
    private JsonNode create(String token, String text) throws Exception {
        return body(mvc.perform(post("/api/v1/meetings").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("transcriptText", text)))).andExpect(status().isCreated()).andReturn());
    }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsByteArray()); }
}
