package vn.aimtt.meeting;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RevisionRepository extends JpaRepository<TranscriptRevision, UUID> {}
