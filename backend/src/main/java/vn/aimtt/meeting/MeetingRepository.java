package vn.aimtt.meeting;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface MeetingRepository extends JpaRepository<Meeting, UUID> {
    Optional<Meeting> findByIdAndUserId(UUID id, UUID userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Meeting m where m.id = :id and m.userId = :owner")
    Optional<Meeting> findOwnedForUpdate(UUID id, UUID owner);
    List<Meeting> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Pageable page);
    @Query("select m from Meeting m where m.userId = :owner and (m.createdAt < :time or (m.createdAt = :time and m.id < :id)) order by m.createdAt desc, m.id desc")
    List<Meeting> afterCursor(UUID owner, Instant time, UUID id, Pageable page);
}
