package com.medflow.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional Outbox Pattern — see OutboxRelayService for delivery guarantees.
 *
 * <h3>Retry and dead-letter policy</h3>
 * <ul>
 *   <li>On each failed publish: {@code attemptCount++}, {@code nextRetryAt} set to
 *       {@code NOW() + 2^attempt} seconds (exponential backoff).</li>
 *   <li>After {@code MAX_ATTEMPTS} failures: {@code deadLetterAt} is set; the event
 *       is excluded from polling and requires manual intervention.</li>
 * </ul>
 */
@Entity
@Table(
    name = "outbox_events",
    indexes = {
        @Index(name = "idx_outbox_pending", columnList = "next_retry_at, created_at")
        // Partial WHERE clause (published_at IS NULL AND dead_letter_at IS NULL)
        // is defined in the migration SQL — JPA @Index cannot express partial indexes.
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /** JSON payload. No PHI — only IDs, status, timestamps (GDPR data minimisation). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Null = pending. Set by OutboxRelayService after Kafka acks the message. */
    @Column(name = "published_at")
    private Instant publishedAt;

    /** Number of failed publish attempts. Drives exponential backoff calculation. */
    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    /** Error message from the last failed attempt. Aids incident investigation. */
    @Column(name = "last_error")
    private String lastError;

    /**
     * Earliest time the relay should retry this event.
     * {@code null} = retry immediately on next poll.
     * Set to {@code NOW() + 2^attemptCount seconds} on failure.
     */
    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    /**
     * Non-null = max attempts exhausted; event is a dead letter.
     * Excluded from relay polling. Requires manual inspection or a DLQ consumer.
     */
    @Column(name = "dead_letter_at")
    private Instant deadLetterAt;

    public boolean isPublished()   { return publishedAt   != null; }
    public boolean isDeadLetter()  { return deadLetterAt  != null; }

    public static OutboxEvent of(UUID aggregateId, String aggregateType,
                                  String eventType, String jsonPayload) {
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(aggregateId)
                .aggregateType(aggregateType)
                .eventType(eventType)
                .payload(jsonPayload)
                .createdAt(Instant.now())
                .attemptCount(0)
                .build();
    }
}
