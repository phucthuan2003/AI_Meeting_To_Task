package vn.aimtt.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserAccount {
    @Id private UUID id;
    @Column(nullable = false, unique = true, length = 254) private String email;
    @Column(name = "password_hash", nullable = false) private String passwordHash;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected UserAccount() {}
    public UserAccount(String email, String passwordHash, Instant now) {
        this.id = UUID.randomUUID(); this.email = email; this.passwordHash = passwordHash; this.createdAt = now;
    }
    public UUID id() { return id; }
    public String email() { return email; }
    public String passwordHash() { return passwordHash; }
}
