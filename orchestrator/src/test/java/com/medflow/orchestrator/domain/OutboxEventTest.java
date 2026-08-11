package com.medflow.orchestrator.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure domain tests — no Spring context, no mocks. OutboxEvent's isPublished()/
 * isDeadLetter() flags and the of() factory drive the relay + dead-letter logic
 * in OutboxRelayService, so a bug here would silently break retry/DLQ behavior.
 */
class OutboxEventTest {

    @Test
    void isPublished_whenPublishedAtIsNull_returnsFalse() {
        OutboxEvent event = OutboxEvent.of(UUID.randomUUID(), "appointments", "appointment.created", "{}");

        assertThat(event.isPublished()).isFalse();
    }

    @Test
    void isPublished_whenPublishedAtIsSet_returnsTrue() {
        OutboxEvent event = OutboxEvent.of(UUID.randomUUID(), "appointments", "appointment.created", "{}");
        event.setPublishedAt(Instant.now());

        assertThat(event.isPublished()).isTrue();
    }

    @Test
    void isDeadLetter_whenDeadLetterAtIsNull_returnsFalse() {
        OutboxEvent event = OutboxEvent.of(UUID.randomUUID(), "appointments", "appointment.created", "{}");

        assertThat(event.isDeadLetter()).isFalse();
    }

    @Test
    void isDeadLetter_whenDeadLetterAtIsSet_returnsTrue() {
        OutboxEvent event = OutboxEvent.of(UUID.randomUUID(), "appointments", "appointment.created", "{}");
        event.setDeadLetterAt(Instant.now());

        assertThat(event.isDeadLetter()).isTrue();
    }

    @Test
    void of_buildsEventWithGeneratedIdAndZeroedRetryState() {
        UUID aggregateId = UUID.randomUUID();

        OutboxEvent event = OutboxEvent.of(aggregateId, "appointments", "appointment.cancelled", "{\"k\":\"v\"}");

        assertThat(event.getId()).isNotNull();
        assertThat(event.getAggregateId()).isEqualTo(aggregateId);
        assertThat(event.getAggregateType()).isEqualTo("appointments");
        assertThat(event.getEventType()).isEqualTo("appointment.cancelled");
        assertThat(event.getPayload()).isEqualTo("{\"k\":\"v\"}");
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getAttemptCount()).isZero();
        // Fresh events must start "not published" and "not dead-lettered",
        // otherwise a race in OutboxRelayService could skip them on the first poll.
        assertThat(event.isPublished()).isFalse();
        assertThat(event.isDeadLetter()).isFalse();
    }

    @Test
    void of_generatesADifferentIdOnEachCall() {
        OutboxEvent first  = OutboxEvent.of(UUID.randomUUID(), "appointments", "appointment.created", "{}");
        OutboxEvent second = OutboxEvent.of(UUID.randomUUID(), "appointments", "appointment.created", "{}");

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }
}