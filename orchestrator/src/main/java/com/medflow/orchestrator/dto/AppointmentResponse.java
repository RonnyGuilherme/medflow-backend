package com.medflow.orchestrator.dto;

import com.medflow.orchestrator.domain.Appointment;
import com.medflow.orchestrator.domain.AppointmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Appointment response payload")
public record AppointmentResponse(

    @Schema(description = "Unique appointment ID")
    UUID id,

    @Schema(description = "Tenant (clinic) ID")
    UUID tenantId,

    @Schema(description = "Patient ID")
    UUID patientId,

    @Schema(description = "Healthcare professional ID")
    UUID professionalId,

    @Schema(description = "Booked slot ID")
    UUID slotId,

    @Schema(description = "Current appointment status")
    AppointmentStatus status,

    @Schema(description = "Creation timestamp (UTC)")
    Instant createdAt,

    @Schema(description = "Last update timestamp (UTC)")
    Instant updatedAt
) {
    public static AppointmentResponse from(Appointment a) {
        return new AppointmentResponse(
            a.getId(),
            a.getTenantId(),
            a.getPatientId(),
            a.getProfessionalId(),
            a.getSlotId(),
            a.getStatus(),
            a.getCreatedAt(),
            a.getUpdatedAt()
        );
    }
}
