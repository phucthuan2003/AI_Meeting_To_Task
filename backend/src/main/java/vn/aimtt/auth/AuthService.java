package vn.aimtt.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.aimtt.common.ApiException;

@Service
public class AuthService {
    public record UserView(UUID id, String email) {}
    public record LoginView(String accessToken, Instant expiresAt, UUID sessionId) {}
    private final UserRepository users;
    private final SessionRepository sessions;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(UserRepository users, SessionRepository sessions, PasswordEncoder passwords,
                       JwtEncoder encoder, AuthProperties properties, Clock clock) {
        this.users = users; this.sessions = sessions; this.passwords = passwords;
        this.encoder = encoder; this.properties = properties; this.clock = clock;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public UserView register(String email, String password) {
        validatePassword(password);
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (users.findByEmail(normalized).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "Email đã được đăng ký.");
        }
        var user = users.saveAndFlush(new UserAccount(normalized, passwords.encode(password), clock.instant()));
        return new UserView(user.id(), user.email());
    }

    @Transactional
    public LoginView login(String email, String password) {
        validatePassword(password);
        var user = users.findByEmail(email.strip().toLowerCase(Locale.ROOT));
        boolean matches = passwords.matches(password, user.map(UserAccount::passwordHash).orElse(dummyHash));
        if (!matches || user.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email hoặc mật khẩu không đúng.");
        }
        Instant now = clock.instant();
        Instant expires = now.plus(properties.accessTokenTtl());
        var session = sessions.save(new AuthSession(user.get().id(), now, expires));
        var claims = JwtClaimsSet.builder().issuer(properties.issuer()).audience(java.util.List.of(properties.audience()))
                .subject(user.get().id().toString()).id(session.id().toString()).issuedAt(now).expiresAt(expires).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginView(token, expires, session.id());
    }

    @Transactional(readOnly = true)
    public UserView me(UUID owner) {
        var user = users.findById(owner).orElseThrow(ApiException::notFound);
        return new UserView(user.id(), user.email());
    }

    @Transactional
    public void logout(UUID owner, UUID sessionId) { sessions.revoke(sessionId, owner, clock.instant()); }

    private void validatePassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.invalid("Mật khẩu tối đa 72 byte UTF-8.");
        }
    }
}
