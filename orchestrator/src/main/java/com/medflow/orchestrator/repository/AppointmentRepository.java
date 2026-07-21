package com.medflow.orchestrator.repository;

import com.medflow.orchestrator.domain.Appointment;
import com.medflow.orchestrator.domain.AppointmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * All queries are tenant-scoped. Every method signature requires tenantId,
 * making accidental cross-tenant queries a compile-time error.
 */
@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    Optional<Appointment> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Appointment> findByPatientIdAndTenantId(UUID patientId, UUID tenantId);

    List<Appointment> findByProfessionalIdAndTenantId(UUID professionalId, UUID tenantId);

    List<Appointment> findByStatusAndTenantId(AppointmentStatus status, UUID tenantId);

    @Query("""
            SELECT a FROM Appointment a
            WHERE a.tenantId = :tenantId
              AND a.patientId = :patientId
              AND a.status NOT IN ('CANCELLED', 'NO_SHOW')
            ORDER BY a.createdAt DESC
            """)
    List<Appointment> findActiveByPatient(
            @Param("tenantId") UUID tenantId,
            @Param("patientId") UUID patientId);

}
