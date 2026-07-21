package com.medflow.orchestrator.service;

import com.medflow.orchestrator.config.KafkaConfig;
import com.medflow.orchestrator.domain.OutboxEvent;
import com.medflow.orchestrator.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Transactional Outbox Relay.
 *
 * <p>At-least-once delivery: synchronous .get(timeout) blocks until Kafka acks.
 * markPublished() only runs after acknowledgement. If Kafka is down, publishedAt
 * stays null and the event is retried on the next poll.
 *
 * <p>Exponential backoff: on failure, attemptCount++ and
 * nextRetryAt = NOW() + 2^attempt seconds (2s, 4s, 8s, 16s...).
 * After MAX_ATTEMPTS consecutive failures, the event is dead-lettered.
 *
 * <p>relay() is intentionally NOT @Transactional. Each event is committed
 * independently via SimpleJpaRepository.save().
 */
@Service
@Slf4j
public class OutboxRelayService {

    static final int MAX_ATTEMPTS = 5;
    private static final int KAFKA_TIMEOUT_SECONDS = 5;

    private final OutboxEventRepository         outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Counter                        publishedCounter;
    private final Counter                        failedCounter;
    private final Counter                        deadLetterCounter;
    private final int                            batchSize;

    public OutboxRelayService(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            MeterRegistry meterRegistry,
            @Value("${medflow.outbox.batch-size:100}") int batchSize
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate         = kafkaTemplate;
        this.batchSize             = batchSize;
        this.publishedCounter  = Counter.builder("medflow.outbox.published")
                .description("Events published to Kafka").register(meterRegistry);
        this.failedCounter     = Counter.builder("medflow.outbox.failed")
                .description("Events retrying with backoff").register(meterRegistry);
        this.deadLetterCounter = Counter.builder("medflow.outbox.dead_letter")
                .description("Events dead-lettered after max attempts").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${medflow.outbox.relay-interval-ms:1000}")
    public void relay() {
        List<OutboxEvent> pending = outboxEventRepository.findPendingEvents(
                PageRequest.of(0, batchSize));
        if (pending.isEmpty()) return;
        log.debug("Relaying {} outbox events", pending.size());
        pending.forEach(this::publishOne);
    }

    private void publishOne(OutboxEvent event) {
        try {
            kafkaTemplate.send(
                    KafkaConfig.TOPIC_APPOINTMENTS,
                    event.getAggregateId().toString(),
                    event.getPayload()
            ).get(KAFKA_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            markPublished(event);
            publishedCounter.increment();
            log.debug("Event published: {} ({})", event.getId(), event.getEventType());
        } catch (TimeoutException e) {
            recordFailure(event, "Kafka timed out after " + KAFKA_TIMEOUT_SECONDS + "s");
        } catch (ExecutionException e) {
            recordFailure(event, "Broker error: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(event, "Relay thread interrupted");
        }
    }

    protected void markPublished(OutboxEvent event) {
        event.setPublishedAt(Instant.now());
        outboxEventRepository.save(event);
    }

    protected void recordFailure(OutboxEvent event, String error) {
        int attempts = event.getAttemptCount() + 1;
        event.setAttemptCount(attempts);
        event.setLastError(error);
        if (attempts >= MAX_ATTEMPTS) {
            event.setDeadLetterAt(Instant.now());
            event.setNextRetryAt(null);
            outboxEventRepository.save(event);
            deadLetterCounter.increment();
            log.error("Event {} dead-lettered after {} attempts. Error: {}.",
                    event.getId(), attempts, error);
        } else {
            long backoffSecs = (long) Math.pow(2, attempts);
            event.setNextRetryAt(Instant.now().plusSeconds(backoffSecs));
            outboxEventRepository.save(event);
            failedCounter.increment();
            log.warn("Event {} retry in {}s (attempt {}/{})",
                    event.getId(), backoffSecs, attempts, MAX_ATTEMPTS);
        }
    }
}
