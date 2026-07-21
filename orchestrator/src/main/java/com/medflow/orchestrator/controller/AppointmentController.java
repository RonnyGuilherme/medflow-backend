package com.medflow.orchestrator.controller;

import com.medflow.orchestrator.dto.AppointmentResponse;
import com.medflow.orchestrator.dto.CreateAppointmentRequest;
import com.medflow.orchestrator.service.AppointmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/appointments")
@RequiredArgsConstructor
@Tag(name = "Appointments", description = "Appointment lifecycle management")
public class AppointmentController {

    private final AppointmentService appointmentService;

    @PostMapping
    @Operation(
        summary = "Book an appointment",
        description = "Creates a new appointment and publishes an appointment.created event via the Transactional Outbox pattern."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Appointment booked successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request payload"),
        @ApiResponse(responseCode = "409", description = "Slot not available"),
    })
    public ResponseEntity<AppointmentResponse> createAppointment(
            @Valid @RequestBody CreateAppointmentRequest request) {

        AppointmentResponse response = appointmentService.createAppointment(request);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get appointment by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Appointment found"),
        @ApiResponse(responseCode = "404", description = "Appointment not found"),
    })
    public ResponseEntity<AppointmentResponse> getAppointment(
            @Parameter(description = "Appointment UUID") @PathVariable UUID id) {
        return ResponseEntity.ok(appointmentService.getAppointment(id));
    }

    @GetMapping("/patient/{patientId}")
    @Operation(summary = "List active appointments for a patient")
    public ResponseEntity<List<AppointmentResponse>> getPatientAppointments(
            @PathVariable UUID patientId) {
        return ResponseEntity.ok(appointmentService.getPatientAppointments(patientId));
    }

    @PatchMapping("/{id}/cancel")
    @Operation(
        summary = "Cancel an appointment",
        description = "Cancels an appointment and publishes an appointment.cancelled event. Idempotent."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Appointment cancelled"),
        @ApiResponse(responseCode = "404", description = "Appointment not found"),
    })
    public ResponseEntity<AppointmentResponse> cancelAppointment(
            @Parameter(description = "Appointment UUID") @PathVariable UUID id) {
        return ResponseEntity.ok(appointmentService.cancelAppointment(id));
    }
}
