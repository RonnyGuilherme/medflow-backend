package com.medflow.orchestrator.integration;

import com.medflow.orchestrator.domain.Appointment;
import com.medflow.orchestrator.domain.AppointmentStatus;
import com.medflow.orchestrator.dto.AppointmentResponse;
import com.medflow.orchestrator.dto.CreateAppointmentRequest;
import com.medflow.orchestrator.dto.SlotAvailabilityResponse;
import com.medflow.orchestrator.repository.AppointmentRepository;
import com.medflow.orchestrator.repository.OutboxEventRepository;
import com.medflow.orchestrator.service.AvailabilityClient;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Full integration test:
 *   - Testcontainers PostgreSQL 16 (real DB + Flyway migrations)
 *   - @EmbeddedKafka (real in-process Kafka)
 *   - @MockBean AvailabilityClient (avoids external HTTP call to Go service)
 *   - Awaitility for async outbox relay assertion
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EmbeddedKafka(
    partitions = 1,
    topics     = {"medflow.appointments"},
    brokerProperties = {"listeners=PLAINTEXT://localhost:9099", "port=9099"}
)
class AppointmentControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("medflow_test")
            .withUsername("medflow")
            .withPassword("test-secret");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",           postgres::getJdbcUrl);
        registry.add("spring.datasource.username",      postgres::getUsername);
        registry.add("spring.datasource.password",      postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers",  () -> "localhost:9099");
        registry.add("availability.engine.url",         () -> "http://localhost:8089");
    }

    @Autowired TestRestTemplate       restTemplate;
    @Autowired AppointmentRepository  appointmentRepository;
    @Autowired OutboxEventRepository  outboxEventRepository;

    /** MockBean — prevents real HTTP call to the Go Availability Engine */
    @MockBean AvailabilityClient availabilityClient;

    private static final String TENANT_ID       = "00000000-0000-0000-0000-000000000001";
    private static final UUID   PATIENT_ID      = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID   PROFESSIONAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID   SLOT_ID         = UUID.fromString("00000000-0000-0000-0000-000000000030");

    @BeforeEach
    void setUp() {
        appointmentRepository.deleteAll();
        outboxEventRepository.deleteAll();
    }

    @Test
    void POST_appointments_withAvailableSlot_returns201AndRelaysOutboxEvent() {
        // Arrange — mock availability check to return available
        when(availabilityClient.checkSlot(eq(SLOT_ID), eq(TENANT_ID)))
                .thenReturn(new SlotAvailabilityResponse(true, SLOT_ID.toString(), null));

        var request = new CreateAppointmentRequest(PATIENT_ID, PROFESSIONAL_ID, SLOT_ID, "Test note");
        var entity  = new HttpEntity<>(request, tenantHeaders());

        // Act
        ResponseEntity<AppointmentResponse> response = restTemplate.exchange(
                "/api/v1/appointments", HttpMethod.POST, entity, AppointmentResponse.class);

        // Assert HTTP
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNotNull();

        AppointmentResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(AppointmentStatus.SCHEDULED);
        assertThat(body.patientId()).isEqualTo(PATIENT_ID);
        assertThat(body.tenantId().toString()).isEqualTo(TENANT_ID);

        // Assert DB — both appointment and outbox event written atomically
        assertThat(appointmentRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);

        var event = outboxEventRepository.findAll().getFirst();
        assertThat(event.getEventType()).isEqualTo("appointment.created");
        assertThat(event.getPublishedAt()).isNull(); // not yet relayed

        // Assert outbox relay publishes to Kafka within 5 seconds
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var relayed = outboxEventRepository.findAll().getFirst();
                    assertThat(relayed.getPublishedAt()).isNotNull();
                });
    }

    @Test
    void POST_appointments_withUnavailableSlot_returns409() {
        when(availabilityClient.checkSlot(any(), any()))
                .thenReturn(new SlotAvailabilityResponse(false, SLOT_ID.toString(), "already_BOOKED"));

        var request = new CreateAppointmentRequest(PATIENT_ID, PROFESSIONAL_ID, SLOT_ID, null);
        var entity  = new HttpEntity<>(request, tenantHeaders());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/appointments", HttpMethod.POST, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(appointmentRepository.count()).isZero(); // nothing persisted
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void GET_appointment_withoutTenantHeader_returns400() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/appointments/" + UUID.randomUUID(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void PATCH_cancel_existingAppointment_returns200WithCancelledStatus() {
        // Arrange — persist directly via repository (skipping booking flow)
        when(availabilityClient.checkSlot(any(), any()))
                .thenReturn(new SlotAvailabilityResponse(true, SLOT_ID.toString(), null));

        Appointment appointment = Appointment.builder()
                .id(UUID.randomUUID())
                .tenantId(UUID.fromString(TENANT_ID))
                .patientId(PATIENT_ID)
                .professionalId(PROFESSIONAL_ID)
                .slotId(SLOT_ID)
                .status(AppointmentStatus.SCHEDULED)
                .notes("test")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        appointmentRepository.save(appointment);

        // Act
        ResponseEntity<AppointmentResponse> response = restTemplate.exchange(
                "/api/v1/appointments/" + appointment.getId() + "/cancel",
                HttpMethod.PATCH,
                new HttpEntity<>(tenantHeaders()),
                AppointmentResponse.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(AppointmentStatus.CANCELLED);

        // Cancellation event should be in outbox
        assertThat(outboxEventRepository.findAll())
                .anyMatch(e -> e.getEventType().equals("appointment.cancelled"));
    }

    @Test
    void GET_appointment_notFound_returns404() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/appointments/" + UUID.randomUUID(),
                HttpMethod.GET,
                new HttpEntity<>(tenantHeaders()),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private HttpHeaders tenantHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-ID", TENANT_ID);
        headers.set("X-Correlation-ID", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
