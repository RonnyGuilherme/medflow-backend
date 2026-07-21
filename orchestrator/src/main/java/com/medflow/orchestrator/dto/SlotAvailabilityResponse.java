package com.medflow.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from the Availability Engine (Go service).
 * Deserialized from: {"available": true/false, "slotId": "uuid"}
 */
public record SlotAvailabilityResponse(
    @JsonProperty("available") boolean available,
    @JsonProperty("slotId")    String slotId,
    @JsonProperty("reason")    String reason   // present when available=false
) {}
