package dev.ayush.nexusq.job;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    // -2 is Hibernate's LockOptions.SKIP_LOCKED — generates FOR UPDATE SKIP LOCKED
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT j FROM Job j WHERE j.status = :status ORDER BY j.createdAt ASC")
    List<Job> findByStatusForUpdateSkipLocked(@Param("status") JobStatus status, Pageable pageable);
}
