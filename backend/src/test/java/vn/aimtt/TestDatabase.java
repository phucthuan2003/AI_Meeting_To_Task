package vn.aimtt;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Shared real PostgreSQL for integration classes, or an explicitly supplied separate test DB. */
final class TestDatabase {
    private static final EmbeddedPostgres POSTGRES = System.getenv("TEST_DATABASE_URL") == null ? start() : null;
    private static EmbeddedPostgres start() {
        try {
            var pg = EmbeddedPostgres.builder().setPort(0).setLocaleConfig("locale", "C").start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { pg.close(); } catch (IOException ignored) {} }));
            return pg;
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES == null ? System.getenv("TEST_DATABASE_URL") : POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DATABASE_USER", "postgres"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "postgres"));
        registry.add("app.auth.jwt-secret", () -> "dGVzdC1vbmx5LXNpZ25pbmcta2V5LTMyLWJ5dGVzLW1pbmltdW0=");
        registry.add("app.cors-origins", () -> "chrome-extension://test-extension-id");
        registry.add("app.analysis.worker-enabled", () -> false);
        registry.add("app.llm.openai.api-key", () -> "");
        registry.add("app.llm.gemini.api-key", () -> "");
    }
}
