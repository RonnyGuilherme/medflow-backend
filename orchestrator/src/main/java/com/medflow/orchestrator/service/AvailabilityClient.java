package com.medflow.orchestrator.service;

import com.medflow.orchestrator.dto.SlotAvailabilityResponse;
import com.medflow.orchestrator.exception.SlotNotAvailableException;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * HTTP client for the Availability Engine (Go service).
 *
 * The check is optimistic — it reads the slot status without locking.
 * Final conflict prevention is handled at the DB level by the Availability Engine's
 * consumer (optimistic UPDATE WHERE status = 'AVAILABLE') and the PostgreSQL
 * GiST exclusion constraint on the slots table.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AvailabilityClient {

    private final RestClient availabilityRestClient;

    @Timed(value = "medflow.availability.check", description = "Slot availability check latency")
    public SlotAvailabilityResponse checkSlot(UUID slotId, String tenantId) {
        log.debug("Checking slot availability: slotId={}, tenant={}", slotId, tenantId);
        try {
            SlotAvailabilityResponse response = availabilityRestClient.get()
                    .uri("/internal/slots/{slotId}/check", slotId)
                    .header("X-Tenant-ID", tenantId)
                    .retrieve()
                    .body(SlotAvailabilityResponse.class);

            if (response == null) {
                throw new SlotNotAvailableException(slotId);
            }

            log.debug("Slot {} availability: {}", slotId, response.available());
            return response;

        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Slot not found in Availability Engine: {}", slotId);
            throw new SlotNotAvailableException(slotId);
        } catch (ResourceAccessException e) {
            // Availability Engine is unreachable — fail safe (deny booking)
            log.error("Availability Engine unreachable: {}", e.getMessage());
            throw new SlotNotAvailableException(slotId);
        }
    }
}
