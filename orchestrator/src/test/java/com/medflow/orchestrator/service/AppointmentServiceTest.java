package com.medflow.orchestrator.service;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppointmentServiceTest {

    @Mock AppointmentRepository  appointmentRepository;
    @Mock OutboxEventRepository  outboxEventRepository;
    @Mock AvailabilityClient     availabilityClient;

    AppointmentService service;

    private static final String TENANT_ID       = "00000000-0000-0000-0000-000000000001";
    private static final UUID   PATIENT_ID      = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID   PROFESSIONAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID   SLOT_ID         = UUID.fromString("00000000-0000-0000-0000-000000000030");

    @BeforeEach
    void setUp() {
        service = new AppointmentService(
                appointmentRepository, outboxEventRepository,
                availabilityClient, new ObjectMapper());
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        // Always clear ThreadLocal to prevent test isolation issues in thread-pooled runners
        TenantContext.clear();
    }

    @Test
    void createAppointment_whenSlotAvailable_persistsAppointmentAndOutboxEvent() {
        // Arrange
        var request = new CreateAppointmentRequest(PATIENT_ID, PROFESSIONAL_ID, SLOT_ID, "First visit");
        when(availabilityClient.checkSlot(SLOT_ID, TENANT_ID))
                .thenReturn(new SlotAvailabilityResponse(true, SLOT_ID.toString(), null));
        when(appointmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        AppointmentResponse response = service.createAppointment(request);

        // Assert — appointment fields
        assertThat(response.patientId()).isEqualTo(PATIENT_ID);
        assertThat(response.professionalId()).isEqualTo(PROFESSIONAL_ID);
        assertThat(response.slotId()).isEqualTo(SLOT_ID);
        assertThat(response.status()).isEqualTo(AppointmentStatus.SCHEDULED);
        assertThat(response.tenantId().toString()).isEqualTo(TENANT_ID);

        // Assert — outbox event written in same transaction
        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(eventCaptor.capture());

        OutboxEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo("appointment.created");
        assertThat(event.getAggregateType()).isEqualTo("appointments");
        assertThat(event.getPublishedAt()).isNull();   // Not yet published to Kafka
        assertThat(event.getPayload()).contains("appointmentId");
        assertThat(event.getPayload()).doesNotContain("notes"); // PHI not in payload
    }

    @Test
    void createAppointment_whenSlotNotAvailable_throwsSlotNotAvailableException() {
        // Arrange
        var request = new CreateAppointmentRequest(PATIENT_ID, PROFESSIONAL_ID, SLOT_ID, null);
        when(availabilityClient.checkSlot(SLOT_ID, TENANT_ID))
                .thenReturn(new SlotAvailabilityResponse(false, SLOT_ID.toString(), "already_booked"));

        // Act & Assert
        assertThatThrownBy(() -> service.createAppointment(request))
                .isInstanceOf(SlotNotAvailableException.class)
                .hasMessageContaining(SLOT_ID.toString());

        // Verify NO writes happened
        verifyNoInteractions(appointmentRepository);
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void cancelAppointment_whenExists_updatesStatusAndPublishesCancellationEvent() {
        // Arrange
        UUID appointmentId = UUID.randomUUID();
        Appointment existing = Appointment.builder()
                .id(appointmentId)
                .tenantId(UUID.fromString(TENANT_ID))
                .patientId(PATIENT_ID)
                .professionalId(PROFESSIONAL_ID)
                .slotId(SLOT_ID)
                .status(AppointmentStatus.SCHEDULED)
                .build();

        when(appointmentRepository.findByIdAndTenantId(appointmentId, UUID.fromString(TENANT_ID)))
                .thenReturn(Optional.of(existing));
        when(appointmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        AppointmentResponse response = service.cancelAppointment(appointmentId);

        // Assert
        assertThat(response.status()).isEqualTo(AppointmentStatus.CANCELLED);

        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("appointment.cancelled");
    }

    @Test
    void getAppointment_whenNotFound_throwsAppointmentNotFoundException() {
        UUID id = UUID.randomUUID();
        when(appointmentRepository.findByIdAndTenantId(id, UUID.fromString(TENANT_ID)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAppointment(id))
                .isInstanceOf(AppointmentNotFoundException.class);
    }
}
