package vn.aimtt.auth;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SessionRepository extends JpaRepository<AuthSession, UUID> {
    @Modifying
    @Query("update AuthSession s set s.revokedAt = :now where s.id = :id and s.userId = :owner")
    void revoke(UUID id, UUID owner, Instant now);
}
