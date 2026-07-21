package com.medflow.orchestrator.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "Request payload for booking an appointment")
public record CreateAppointmentRequest(

    @NotNull(message = "patientId is required")
    @Schema(description = "UUID of the patient", example = "00000000-0000-0000-0000-000000000010")
    UUID patientId,

    @NotNull(message = "professionalId is required")
    @Schema(description = "UUID of the healthcare professional", example = "00000000-0000-0000-0000-000000000020")
    UUID professionalId,

    @NotNull(message = "slotId is required")
    @Schema(description = "UUID of the availability slot to book", example = "00000000-0000-0000-0000-000000000030")
    UUID slotId,

    @Size(max = 2000, message = "notes must not exceed 2000 characters")
    @Schema(description = "Optional clinical notes (not stored in event payloads)", example = "First consultation")
    String notes
) {}
