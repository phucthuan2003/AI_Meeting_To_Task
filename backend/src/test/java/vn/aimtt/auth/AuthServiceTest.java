package vn.aimtt.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import vn.aimtt.common.ApiException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private final Clock clock = Clock.fixed(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), ZoneOffset.UTC);
    private final SecurityConfiguration security = new SecurityConfiguration();
    private final AuthProperties props = new AuthProperties("dGVzdC1vbmx5LXNpZ25pbmcta2V5LTMyLWJ5dGVzLW1pbmltdW0=",
            "ai-meeting-to-task", "ai-mtt-extension", Duration.ofMinutes(15));
    private final UserRepository users = mock(UserRepository.class);
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final Map<UUID, AuthSession> savedSessions = new HashMap<>();
    private AuthService service;
    private JwtEncoder encoder;
    private JwtDecoder decoder;

    @BeforeEach void setup() {
        SecretKey key = security.signingKey(props);
        encoder = security.jwtEncoder(key);
        decoder = security.jwtDecoder(key, props, sessions, clock);
        when(sessions.save(any())).thenAnswer(invocation -> {
            AuthSession session = invocation.getArgument(0); savedSessions.put(session.id(), session); return session;
        });
        when(sessions.findById(any())).thenAnswer(invocation -> Optional.ofNullable(savedSessions.get(invocation.getArgument(0))));
        service = new AuthService(users, sessions, security.passwordEncoder(), encoder, props, clock);
    }

    @Test void passwordIsHashedAndEmailCanonicalized() {
        when(users.findByEmail("mai@example.test")).thenReturn(Optional.empty());
        when(users.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        var view = service.register("MAI@example.test", "strong-password-123");
        var captor = org.mockito.ArgumentCaptor.forClass(UserAccount.class);
        verify(users).saveAndFlush(captor.capture());
        assertThat(view.email()).isEqualTo("mai@example.test");
        assertThat(captor.getValue().passwordHash()).startsWith("$2");
        assertThat(security.passwordEncoder().matches("strong-password-123", captor.getValue().passwordHash())).isTrue();
    }

    @Test void actualSignedTokenContainsIdentityAndSessionOnly() {
        var user = user();
        when(users.findByEmail(user.email())).thenReturn(Optional.of(user));
        var login = service.login(user.email(), "strong-password-123");
        var token = decoder.decode(login.accessToken());
        assertThat(token.getSubject()).isEqualTo(user.id().toString());
        assertThat(token.getId()).isEqualTo(login.sessionId().toString());
        assertThat(token.getClaims().keySet()).containsExactlyInAnyOrder("iss", "aud", "sub", "jti", "iat", "exp");
        assertThat(login.expiresAt()).isEqualTo(clock.instant().plusSeconds(900));
        savedSessions.clear();
        assertThatThrownBy(() -> decoder.decode(login.accessToken())).isInstanceOf(JwtValidationException.class);
        service.logout(user.id(), login.sessionId());
        verify(sessions).revoke(login.sessionId(), user.id(), clock.instant());
    }

    @Test void wrongAndUnknownCredentialsReturnSameError() {
        var user = user();
        when(users.findByEmail(user.email())).thenReturn(Optional.of(user));
        when(users.findByEmail("missing@example.test")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.login(user.email(), "wrong-password"))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("INVALID_CREDENTIALS");
        assertThatThrownBy(() -> service.login("missing@example.test", "wrong-password"))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test void weakKeyLongUtf8PasswordAndExcessiveTtlAreRejected() {
        assertThatThrownBy(() -> security.signingKey(new AuthProperties("YQ==", "issuer", "audience", Duration.ofMinutes(15))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> security.signingKey(new AuthProperties(props.jwtSecret(), "issuer", "audience", Duration.ofMinutes(16))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.register("mai@example.test", "á".repeat(37)))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("INVALID_INPUT");
        verify(users, never()).saveAndFlush(any());
    }

    @Test void validSignatureDoesNotBypassAudienceOwnerOrExpiry() {
        var user = user();
        when(users.findByEmail(user.email())).thenReturn(Optional.of(user));
        var login = service.login(user.email(), "strong-password-123");
        for (var claims : List.of(
                claims(user.id(), login.sessionId(), "other-audience", clock.instant().plusSeconds(900)),
                claims(UUID.randomUUID(), login.sessionId(), props.audience(), clock.instant().plusSeconds(900)),
                claims(user.id(), login.sessionId(), props.audience(), clock.instant().minusSeconds(120)))) {
            String signed = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
            assertThatThrownBy(() -> decoder.decode(signed)).isInstanceOf(JwtValidationException.class);
        }
    }

    private JwtClaimsSet claims(UUID owner, UUID session, String audience, Instant expires) {
        return JwtClaimsSet.builder().issuer(props.issuer()).audience(List.of(audience)).subject(owner.toString())
                .id(session.toString()).issuedAt(clock.instant().minusSeconds(180)).expiresAt(expires).build();
    }

    private UserAccount user() { return new UserAccount("mai@example.test", security.passwordEncoder().encode("strong-password-123"), clock.instant()); }
}
