package com.medflow.orchestrator.service;

import com.medflow.orchestrator.dto.SlotAvailabilityResponse;
import com.medflow.orchestrator.exception.SlotNotAvailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * AvailabilityClient wraps calls to the Go Availability Engine. The three catch
 * blocks (null body, 404, unreachable) all deliberately fail CLOSED — i.e. deny
 * the booking — because an optimistic "assume available" default here would
 * reopen exactly the race condition the rest of the system defends against.
 * RestClient's fluent interface is mocked step-by-step to match the exact chain
 * used in the implementation (.get().uri().header().retrieve().body()).
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class AvailabilityClientTest {

    private static final UUID   SLOT_ID   = UUID.randomUUID();
    private static final String TENANT_ID = "00000000-0000-0000-0000-000000000001";

    @Mock RestClient restClient;
    @Mock RestClient.RequestHeadersUriSpec requestHeadersUriSpec;
    @Mock RestClient.RequestHeadersSpec requestHeadersSpec;
    @Mock RestClient.ResponseSpec responseSpec;

    AvailabilityClient client;

    @BeforeEach
    void setUp() {
        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString(), any(Object[].class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.header(anyString(), anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);

        client = new AvailabilityClient(restClient);
    }

    @Test
    void checkSlot_whenAvailable_returnsTheResponseAsIs() {
        when(responseSpec.body(SlotAvailabilityResponse.class))
                .thenReturn(new SlotAvailabilityResponse(true, SLOT_ID.toString(), null));

        SlotAvailabilityResponse result = client.checkSlot(SLOT_ID, TENANT_ID);

        assertThat(result.available()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    void checkSlot_sendsTheTenantIdAsAHeaderOnEveryRequest() {
        when(responseSpec.body(SlotAvailabilityResponse.class))
                .thenReturn(new SlotAvailabilityResponse(true, SLOT_ID.toString(), null));

        client.checkSlot(SLOT_ID, TENANT_ID);

        org.mockito.Mockito.verify(requestHeadersSpec).header("X-Tenant-ID", TENANT_ID);
    }

    @Test
    void checkSlot_whenResponseBodyIsNull_throwsSlotNotAvailableException() {
        when(responseSpec.body(SlotAvailabilityResponse.class)).thenReturn(null);

        assertThatThrownBy(() -> client.checkSlot(SLOT_ID, TENANT_ID))
                .isInstanceOf(SlotNotAvailableException.class)
                .hasMessageContaining(SLOT_ID.toString());
    }

    @Test
    void checkSlot_when404FromAvailabilityEngine_throwsSlotNotAvailableException() {
        // HttpClientErrorException.NotFound has no public no-arg constructor, so it can't
        // be handed to Mockito as a class literal — .create(...) is Spring's own factory
        // for building the correctly-typed subclass from a status code.
        HttpClientErrorException notFound = HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null);
        when(responseSpec.body(SlotAvailabilityResponse.class)).thenThrow(notFound);

        assertThatThrownBy(() -> client.checkSlot(SLOT_ID, TENANT_ID))
                .isInstanceOf(SlotNotAvailableException.class);
    }

    @Test
    void checkSlot_whenAvailabilityEngineIsUnreachable_failsSafeByDenyingTheBooking() {
        when(responseSpec.body(SlotAvailabilityResponse.class))
                .thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> client.checkSlot(SLOT_ID, TENANT_ID))
                .isInstanceOf(SlotNotAvailableException.class);
    }
}