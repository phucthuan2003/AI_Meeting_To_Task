package vn.aimtt.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import vn.aimtt.common.ApiError;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Configuration
public class SecurityConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean SecretKey signingKey(AuthProperties properties) {
        byte[] key;
        try { key = Base64.getDecoder().decode(properties.jwtSecret()); }
        catch (IllegalArgumentException e) { throw new IllegalStateException("JWT_SIGNING_KEY must be base64."); }
        if (key.length < 32) throw new IllegalStateException("JWT_SIGNING_KEY must decode to at least 32 bytes.");
        if (properties.accessTokenTtl().isNegative() || properties.accessTokenTtl().isZero()
                || properties.accessTokenTtl().compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalStateException("Access token TTL must be between zero and 15 minutes.");
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Bean JwtEncoder jwtEncoder(SecretKey key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }

    @Bean JwtDecoder jwtDecoder(SecretKey key, AuthProperties properties, SessionRepository sessions, Clock clock) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> session = jwt -> {
            try {
                UUID owner = UUID.fromString(jwt.getSubject());
                UUID sessionId = UUID.fromString(jwt.getId());
                if (jwt.getAudience().contains(properties.audience()) && jwt.getExpiresAt() != null
                        && jwt.getExpiresAt().isAfter(clock.instant())
                        && sessions.findById(sessionId).filter(s -> s.validFor(owner, clock.instant())).isPresent()) {
                    return OAuth2TokenValidatorResult.success();
                }
            } catch (IllegalArgumentException | NullPointerException ignored) { }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Session expired or revoked", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()), session));
        return decoder;
    }

    @Bean SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper,
            @Qualifier("extensionCorsConfigurationSource") CorsConfigurationSource corsSource) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsSource))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(jwt -> {}).authenticationEntryPoint((request, response, e) -> {
                    response.setStatus(401); response.setContentType("application/json;charset=UTF-8");
                    mapper.writeValue(response.getOutputStream(), ApiError.of(request, 401, "SESSION_EXPIRED", "Đăng nhập để tiếp tục."));
                }))
                .exceptionHandling(e -> e.accessDeniedHandler((request, response, failure) -> {
                    response.setStatus(403); response.setContentType("application/json;charset=UTF-8");
                    mapper.writeValue(response.getOutputStream(), ApiError.of(request, 403, "ACCESS_DENIED", "Không có quyền thực hiện."));
                }))
                .build();
    }

    @Bean CorsConfigurationSource extensionCorsConfigurationSource(@Value("${app.cors-origins:}") String origins) {
        var config = new CorsConfiguration();
        var allowed = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (allowed.stream().anyMatch(s -> s.contains("*"))) throw new IllegalStateException("CORS wildcard is not allowed.");
        config.setAllowedOrigins(allowed);
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        config.setExposedHeaders(List.of("X-Trace-Id", "Retry-After"));
        config.setAllowCredentials(false);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
