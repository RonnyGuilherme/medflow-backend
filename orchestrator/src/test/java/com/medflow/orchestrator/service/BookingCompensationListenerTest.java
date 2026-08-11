package com.medflow.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medflow.orchestrator.domain.Appointment;
import com.medflow.orchestrator.domain.AppointmentStatus;
import com.medflow.orchestrator.domain.OutboxEvent;
import com.medflow.orchestrator.repository.AppointmentRepository;
import com.medflow.orchestrator.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * BookingCompensationListener is the Java-side half of the saga that starts when
 * the Go Availability Engine exhausts its retries and routes a booking failure to
 * the DLQ (see appointment_consumer.go's routeToDLQ). These tests use a real
 * ObjectMapper rather than mocking JSON parsing — the message-shape contract with
 * the Go producer is part of what's actually being verified here.
 */
@ExtendWith(MockitoExtension.class)
class BookingCompensationListenerTest {

    private static final UUID APPOINTMENT_ID   = UUID.randomUUID();
    private static final UUID TENANT_ID        = UUID.randomUUID();
    private static final UUID PATIENT_ID       = UUID.randomUUID();
    private static final UUID PROFESSIONAL_ID  = UUID.randomUUID();
    private static final UUID SLOT_ID          = UUID.randomUUID();

    @Mock AppointmentRepository appointmentRepository;
    @Mock OutboxEventRepository outboxEventRepository;

    BookingCompensationListener listener;

    @BeforeEach
    void setUp() {
        listener = new BookingCompensationListener(appointmentRepository, outboxEventRepository, new ObjectMapper());
    }

    private static String dlqMessage(UUID appointmentId, UUID tenantId) {
        return """
                {
                  "appointmentId": "%s",
                  "tenantId": "%s",
                  "patientId": "00000000-0000-0000-0000-000000000010",
                  "slotId": "00000000-0000-0000-0000-000000000030",
                  "reason": "slot already booked",
                  "attempts": 5,
                  "failedAt": "2026-07-20T14:51:16Z"
                }
                """.formatted(appointmentId, tenantId);
    }

    @Test
    void handleBookingFailed_whenAppointmentIsScheduled_cancelsItAndPublishesCompensationEvent() {
        Appointment scheduled = Appointment.builder()
                .id(APPOINTMENT_ID)
                .tenantId(TENANT_ID)
                .patientId(PATIENT_ID)
                .professionalId(PROFESSIONAL_ID)
                .slotId(SLOT_ID)
                .status(AppointmentStatus.SCHEDULED)
                .build();
        when(appointmentRepository.findByIdAndTenantId(APPOINTMENT_ID, TENANT_ID))
                .thenReturn(Optional.of(scheduled));

        listener.handleBookingFailed(dlqMessage(APPOINTMENT_ID, TENANT_ID));

        assertThat(scheduled.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        verify(appointmentRepository).save(scheduled);

        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(eventCaptor.capture());
        OutboxEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo("appointment.compensation_triggered");
        assertThat(event.getPayload()).contains("slot already booked");
    }

    @Test
    void handleBookingFailed_whenAppointmentNotFound_doesNothingAndDoesNotThrow() {
        when(appointmentRepository.findByIdAndTenantId(APPOINTMENT_ID, TENANT_ID))
                .thenReturn(Optional.empty());

        listener.handleBookingFailed(dlqMessage(APPOINTMENT_ID, TENANT_ID));

        verify(appointmentRepository, never()).save(any());
        verifyNoInteractions(outboxEventRepository);
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = "SCHEDULED", mode = EnumSource.Mode.EXCLUDE)
    void handleBookingFailed_whenAppointmentIsNotScheduled_skipsCompensation(AppointmentStatus currentStatus) {
        // A DLQ message can arrive after the appointment was already cancelled or
        // completed through a different path — compensation must not re-cancel or
        // double-publish in that case.
        Appointment notScheduled = Appointment.builder()
                .id(APPOINTMENT_ID)
                .tenantId(TENANT_ID)
                .status(currentStatus)
                .build();
        when(appointmentRepository.findByIdAndTenantId(APPOINTMENT_ID, TENANT_ID))
                .thenReturn(Optional.of(notScheduled));

        listener.handleBookingFailed(dlqMessage(APPOINTMENT_ID, TENANT_ID));

        assertThat(notScheduled.getStatus()).isEqualTo(currentStatus); // unchanged
        verify(appointmentRepository, never()).save(any());
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void handleBookingFailed_whenMessageIsMalformedJson_throwsRuntimeExceptionSoKafkaRetries() {
        assertThatThrownBy(() -> listener.handleBookingFailed("not valid json"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Compensation processing failed");

        verifyNoInteractions(appointmentRepository);
        verifyNoInteractions(outboxEventRepository);
    }
}