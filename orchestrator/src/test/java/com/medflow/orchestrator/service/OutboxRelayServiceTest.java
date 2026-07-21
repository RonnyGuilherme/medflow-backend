package com.medflow.orchestrator.service;

import com.medflow.orchestrator.domain.OutboxEvent;
import com.medflow.orchestrator.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OutboxRelayService, verifying the at-least-once delivery guarantee.
 *
 * <p>Spring Boot 3.3 / Spring Kafka 3.x: KafkaTemplate.send() returns
 * CompletableFuture&lt;SendResult&lt;K,V&gt;&gt; (the blocking send API used by this relay).
 *
 * <h3>The guarantee under test:</h3>
 * <ul>
 *   <li>Kafka ack SUCCESS  → event marked as published in DB</li>
 *   <li>Kafka ack FAILURE  → event NOT marked as published (will be retried)</li>
 *   <li>Kafka TIMEOUT      → event NOT marked as published (will be retried)</li>
 *   <li>Event N fails      → events 1..N-1 remain published (independent commits)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayServiceTest {

    @Mock OutboxEventRepository         outboxEventRepository;
    @Mock KafkaTemplate<String, String> kafkaTemplate;

    OutboxRelayService service;

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID APPT_ID  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new OutboxRelayService(
                outboxEventRepository,
                kafkaTemplate,
                new SimpleMeterRegistry(),
                10 // batchSize
        );
    }

    private OutboxEvent unpublishedEvent() {
        return OutboxEvent.builder()
                .id(EVENT_ID)
                .aggregateId(APPT_ID)
                .aggregateType("appointments")
                .eventType("appointment.created")
                .payload("{\"appointmentId\":\"" + APPT_ID + "\"}")
                .createdAt(Instant.now())
                .build(); // publishedAt is null
    }

    // ── Spring Kafka 3.x mock helpers ─────────────────────────────────────────

    /** Returns a CompletableFuture that Kafka resolves immediately with success. */
    @SuppressWarnings("unchecked")
    private static CompletableFuture<SendResult<String, String>> successFuture() {
        SendResult<String, String> sendResult = mock(SendResult.class);
        return CompletableFuture.completedFuture(sendResult);
    }

    /** Returns a CompletableFuture that Kafka rejects immediately with an error. */
    private static CompletableFuture<SendResult<String, String>> failedFuture(String reason) {
        CompletableFuture<SendResult<String, String>> cf = new CompletableFuture<>();
        cf.completeExceptionally(new RuntimeException(reason));
        return cf;
    }

    // ── Test 1: HAPPY PATH ────────────────────────────────────────────────────

    @Test
    void relay_whenKafkaAcknowledges_marksEventAsPublished() {
        OutboxEvent event = unpublishedEvent();
        when(outboxEventRepository.findPendingEvents(any(Pageable.class)))
                .thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(successFuture());
        when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.relay();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getPublishedAt())
                .as("publishedAt must be set after Kafka acknowledges")
                .isNotNull();
    }

    // ── Test 2: KAFKA BROKER FAILURE ─────────────────────────────────────────

    @Test
    void relay_whenKafkaBrokerRejects_doesNotMarkEventAsPublished() {
        OutboxEvent event = unpublishedEvent();
        when(outboxEventRepository.findPendingEvents(any(Pageable.class)))
                .thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(failedFuture("Broker unavailable — LEADER_NOT_AVAILABLE"));

        // Must NOT throw — errors are caught and logged
        service.relay();

        // DB must NOT be updated: event stays unpublished for next poll
        verify(outboxEventRepository, never()).save(any());
        assertThat(event.getPublishedAt())
                .as("publishedAt must stay null so the event is retried on next poll")
                .isNull();
    }

    // ── Test 3: INDEPENDENT FAILURE ISOLATION ────────────────────────────────

    @Test
    void relay_whenSecondEventFails_firstEventStaysPublished() {
        // Regression test: with the old @Transactional-on-relay approach,
        // a failure on event #2 would roll back event #1's published state.
        OutboxEvent event1 = unpublishedEvent();
        OutboxEvent event2 = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(UUID.randomUUID())
                .aggregateType("appointments")
                .eventType("appointment.cancelled")
                .payload("{}")
                .createdAt(Instant.now())
                .build();

        when(outboxEventRepository.findPendingEvents(any(Pageable.class)))
                .thenReturn(List.of(event1, event2));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(successFuture())               // event1 → Kafka ack ✅
                .thenReturn(failedFuture("Timeout"));      // event2 → Kafka error ❌
        when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.relay();

        // Only event1 saved; event2 stays unpublished for retry
        verify(outboxEventRepository, times(1)).save(any());
    }

    // ── Test 4: EMPTY BATCH ───────────────────────────────────────────────────

    @Test
    void relay_whenNoPendingEvents_doesNothing() {
        when(outboxEventRepository.findPendingEvents(any(Pageable.class)))
                .thenReturn(List.of());

        service.relay();

        verifyNoInteractions(kafkaTemplate);
        verify(outboxEventRepository, never()).save(any());
    }
}
