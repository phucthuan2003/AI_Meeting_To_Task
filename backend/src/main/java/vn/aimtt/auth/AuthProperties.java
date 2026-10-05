package vn.aimtt.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.auth")
public record AuthProperties(String jwtSecret, String issuer, String audience, Duration accessTokenTtl) {}
