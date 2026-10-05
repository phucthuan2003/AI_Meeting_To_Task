package vn.aimtt.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_sessions")
public class AuthSession {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected AuthSession() {}
    public AuthSession(UUID userId, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID(); this.userId = userId; this.createdAt = now; this.expiresAt = expiresAt;
    }
    public UUID id() { return id; }
    public boolean validFor(UUID owner, Instant now) {
        return userId.equals(owner) && revokedAt == null && expiresAt.isAfter(now);
    }
}
