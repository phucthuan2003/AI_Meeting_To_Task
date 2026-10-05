package vn.aimtt.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public record Credentials(@NotBlank @Email @Size(max = 254) String email,
                              @NotBlank @Size(min = 10, max = 72) String password) {}
    private final AuthService service;
    private final LoginRateLimiter limiter;
    public AuthController(AuthService service, LoginRateLimiter limiter) { this.service = service; this.limiter = limiter; }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    AuthService.UserView register(@Valid @RequestBody Credentials body, HttpServletRequest request) {
        limiter.check(request.getRemoteAddr());
        return service.register(body.email(), body.password());
    }

    @PostMapping("/login")
    AuthService.LoginView login(@Valid @RequestBody Credentials body, HttpServletRequest request) {
        limiter.check(request.getRemoteAddr());
        return service.login(body.email(), body.password());
    }

    @GetMapping("/me")
    AuthService.UserView me(@AuthenticationPrincipal Jwt jwt) { return service.me(UUID.fromString(jwt.getSubject())); }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@AuthenticationPrincipal Jwt jwt) {
        service.logout(UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getId()));
    }
}
