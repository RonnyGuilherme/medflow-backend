package com.medflow.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medflow.orchestrator.config.TenantContext;
import com.medflow.orchestrator.domain.Appointment;
import com.medflow.orchestrator.domain.AppointmentStatus;
import com.medflow.orchestrator.domain.OutboxEvent;
import com.medflow.orchestrator.dto.AppointmentResponse;
import com.medflow.orchestrator.dto.CreateAppointmentRequest;
import com.medflow.orchestrator.dto.SlotAvailabilityResponse;
import com.medflow.orchestrator.exception.AppointmentNotFoundException;
import com.medflow.orchestrator.exception.SlotNotAvailableException;
import com.medflow.orchestrator.repository.AppointmentRepository;
import com.medflow.orchestrator.repository.OutboxEventRepository;
import io.micrometer.core.annotation.Counted;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AppointmentService {

    private final AppointmentRepository  appointmentRepository;
    private final OutboxEventRepository  outboxEventRepository;
    private final AvailabilityClient     availabilityClient;
    private final ObjectMapper           objectMapper;
    
    /**
     * Books an appointment.
     *
     * Key guarantee: Steps A and B execute in a single DB transaction.
     * If the process crashes between A and Kafka publish, the OutboxRelayService
     * will re-publish the event on the next poll cycle (at-least-once delivery).
     *
     * @throws SlotNotAvailableException if the slot is already taken
     */
    @Transactional
    @Timed(value = "medflow.appointments.create", description = "Appointment creation latency")
    @Counted(value = "medflow.appointments.created.total", description = "Total appointments created")
    public AppointmentResponse createAppointment(CreateAppointmentRequest request) {
        String tenantId = TenantContext.getTenantId();

        // 1. Optimistic availability check (no DB lock — fast read path via Go)
        SlotAvailabilityResponse availability = availabilityClient.checkSlot(request.slotId(), tenantId);
        if (!availability.available()) {
            log.info("Booking rejected — slot unavailable: slotId={}, reason={}",
                    request.slotId(), availability.reason());
            throw new SlotNotAvailableException(request.slotId());
        }

        // 2-A. Persist the Appointment (command side write)
        Appointment appointment = Appointment.builder()
                .id(UUID.randomUUID())
                .tenantId(UUID.fromString(tenantId))
                .patientId(request.patientId())
                .professionalId(request.professionalId())
                .slotId(request.slotId())
                .status(AppointmentStatus.SCHEDULED)
                .notes(request.notes())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        appointmentRepository.save(appointment);

        // 2-B. Persist the OutboxEvent — same transaction as above
        // Payload intentionally excludes PHI (no patient notes, no clinical data)
        String payload = buildEventPayload(appointment);
        OutboxEvent event = OutboxEvent.of(
                appointment.getId(),
                "appointments",
                "appointment.created",
                payload
        );
        outboxEventRepository.save(event);

        // Transaction commits here. OutboxRelayService publishes to Kafka asynchronously.
        log.info("Appointment created: id={}, tenant={}, slot={}",
                appointment.getId(), tenantId, request.slotId());

        return AppointmentResponse.from(appointment);
    }

    @Transactional(readOnly = true)
    public AppointmentResponse getAppointment(UUID id) {
        String tenantId = TenantContext.getTenantId();
        return appointmentRepository
                .findByIdAndTenantId(id, UUID.fromString(tenantId))
                .map(AppointmentResponse::from)
                .orElseThrow(() -> new AppointmentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> getPatientAppointments(UUID patientId) {
        String tenantId = TenantContext.getTenantId();
        return appointmentRepository
                .findActiveByPatient(UUID.fromString(tenantId), patientId)
                .stream()
                .map(AppointmentResponse::from)
                .toList();
    }

    @Transactional
    @Counted(value = "medflow.appointments.cancelled.total")
    public AppointmentResponse cancelAppointment(UUID id) {
        String tenantId = TenantContext.getTenantId();

        Appointment appointment = appointmentRepository
                .findByIdAndTenantId(id, UUID.fromString(tenantId))
                .orElseThrow(() -> new AppointmentNotFoundException(id));

        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            return AppointmentResponse.from(appointment);  // idempotent
        }

        appointment.setStatus(AppointmentStatus.CANCELLED);
        appointment.setUpdatedAt(Instant.now());
        appointmentRepository.save(appointment);

        // Publish cancellation event via outbox
        OutboxEvent event = OutboxEvent.of(
                appointment.getId(),
                "appointments",
                "appointment.cancelled",
                buildEventPayload(appointment)
        );
        outboxEventRepository.save(event);

        log.info("Appointment cancelled: id={}, tenant={}", id, tenantId);
        return AppointmentResponse.from(appointment);
    }

    /** Builds the Kafka event payload — only IDs, no PHI. */
    private String buildEventPayload(Appointment a) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                "appointmentId",   a.getId(),
                "tenantId",        a.getTenantId(),
                "patientId",       a.getPatientId(),
                "professionalId",  a.getProfessionalId(),
                "slotId",          a.getSlotId(),
                "status",          a.getStatus().name(),
                "occurredAt",      Instant.now().toString()
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize event payload", e);
        }
    }
}
