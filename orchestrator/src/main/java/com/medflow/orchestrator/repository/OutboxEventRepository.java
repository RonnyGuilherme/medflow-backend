package com.medflow.orchestrator.repository;

import com.medflow.orchestrator.domain.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Returns events eligible for publishing:
     * not yet published, not dead-lettered, and backoff window elapsed.
     */
    @Query("""
            SELECT e FROM OutboxEvent e
            WHERE e.publishedAt   IS NULL
              AND e.deadLetterAt  IS NULL
              AND (e.nextRetryAt IS NULL OR e.nextRetryAt <= CURRENT_TIMESTAMP)
            ORDER BY e.createdAt ASC
            """)
    List<OutboxEvent> findPendingEvents(Pageable pageable);
}
