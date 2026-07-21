package com.medflow.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medflow.orchestrator.domain.Appointment;
import com.medflow.orchestrator.domain.AppointmentStatus;
import com.medflow.orchestrator.domain.OutboxEvent;
import com.medflow.orchestrator.repository.AppointmentRepository;
import com.medflow.orchestrator.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * BookingCompensationListener consumes AppointmentBookingFailedEvent from the DLQ
 * (medflow.appointments.dlq) and performs compensatory actions.
 *
 * When the Availability Engine exhausts retries attempting to mark a slot as BOOKED,
 * it routes the event to the DLQ. This listener automatically:
 * 1. Marks the Appointment as CANCELLED (because the slot wasn't actually locked)
 * 2. Publishes an appointment.compensation_triggered event for notifications
 *
 * This ensures the system remains consistent: if the slot booking failed permanently,
 * the patient's appointment is cancelled and they're notified to rebook.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingCompensationListener {

    private final AppointmentRepository appointmentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Consumes failed booking events from the DLQ and triggers compensation.
     * Topic: medflow.appointments.dlq
     * Consumer group: orchestrator-compensation-group
     *
     * Message format (AppointmentBookingFailedEvent):
     * {
     *   "appointmentId": "uuid",
     *   "tenantId": "uuid",
     *   "patientId": "uuid",
     *   "slotId": "uuid",
     *   "reason": "error message",
     *   "attempts": 5,
     *   "failedAt": "2026-07-20T14:51:16Z"
     * }
     */
    @KafkaListener(
        topics = "${kafka.dlq-topic:medflow.appointments.dlq}",
        groupId = "orchestrator-compensation-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void handleBookingFailed(@Payload String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            String appointmentId = node.get("appointmentId").asText();
            String tenantId = node.get("tenantId").asText();
            String reason = node.get("reason").asText();
            int attempts = node.get("attempts").asInt(0);

            UUID apptId = UUID.fromString(appointmentId);
            UUID tntId = UUID.fromString(tenantId);

            log.warn("Booking compensation triggered",
                "appointmentId", apptId,
                "tenantId", tntId,
                "reason", reason,
                "attempts", attempts);

            // 1. Find the appointment
            var appointment = appointmentRepository.findByIdAndTenantId(apptId, tntId)
                .orElse(null);

            if (appointment == null) {
                log.error("Compensation failed — appointment not found",
                    "appointmentId", apptId,
                    "tenantId", tntId);
                return;
            }

            // 2. If it's still SCHEDULED (not already cancelled/completed), cancel it
            if (appointment.getStatus() == AppointmentStatus.SCHEDULED) {
                appointment.setStatus(AppointmentStatus.CANCELLED);
                appointment.setUpdatedAt(Instant.now());
                appointmentRepository.save(appointment);

                // 3. Publish a compensation event for notification
                OutboxEvent event = OutboxEvent.of(
                    appointment.getId(),
                    "appointments",
                    "appointment.compensation_triggered",
                    buildCompensationPayload(appointment, reason, attempts)
                );
                outboxEventRepository.save(event);

                log.info("Appointment cancelled due to booking failure",
                    "appointmentId", apptId,
                    "tenantId", tntId,
                    "reason", reason);
            } else {
                log.debug("Compensation skipped — appointment not in SCHEDULED status",
                    "appointmentId", apptId,
                    "status", appointment.getStatus());
            }

        } catch (Exception e) {
            log.error("Failed to process booking compensation event", e);
            throw new RuntimeException("Compensation processing failed", e);
        }
    }

    private String buildCompensationPayload(Appointment appointment, String reason, int attempts) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                "appointmentId", appointment.getId(),
                "tenantId", appointment.getTenantId(),
                "patientId", appointment.getPatientId(),
                "professionalId", appointment.getProfessionalId(),
                "slotId", appointment.getSlotId(),
                "status", AppointmentStatus.CANCELLED.name(),
                "compensationReason", reason,
                "bookerAttemptsExhausted", attempts,
                "compensatedAt", Instant.now().toString()
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize compensation payload", e);
        }
    }
}
