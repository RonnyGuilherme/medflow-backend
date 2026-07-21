package com.medflow.orchestrator.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Source-of-truth for an appointment. Every row is scoped to a tenant_id —
 * all queries issued by the repository layer include a tenant_id filter,
 * making cross-tenant data leakage structurally impossible.
 */
@Entity
@Table(
    name = "appointments",
    indexes = {
        @Index(name = "idx_appt_tenant",        columnList = "tenant_id"),
        @Index(name = "idx_appt_patient",       columnList = "patient_id, tenant_id"),
        @Index(name = "idx_appt_professional",  columnList = "professional_id, tenant_id"),
        @Index(name = "idx_appt_status",        columnList = "status, tenant_id")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Appointment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Clinic that owns this appointment — injected from JWT claim by Kong. */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "professional_id", nullable = false, updatable = false)
    private UUID professionalId;

    @Column(name = "slot_id", nullable = false, updatable = false)
    private UUID slotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AppointmentStatus status;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
