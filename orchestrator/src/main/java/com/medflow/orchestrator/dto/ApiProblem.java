package com.medflow.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * RFC 7807 Problem Details for HTTP APIs.
 * Used for all error responses to provide consistent, machine-readable errors.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "RFC 7807 Problem Details error response")
public record ApiProblem(

    @Schema(description = "URI reference that identifies the problem type")
    String type,

    @Schema(description = "Short, human-readable summary")
    String title,

    @Schema(description = "HTTP status code")
    int status,

    @Schema(description = "Human-readable explanation for this occurrence")
    String detail,

    @Schema(description = "URI reference that identifies the specific occurrence")
    String instance,

    @Schema(description = "Correlation ID for distributed tracing")
    String correlationId,

    @Schema(description = "Timestamp of the error")
    Instant timestamp
) {
    public static ApiProblem of(String type, String title, int status, String detail, String instance) {
        return new ApiProblem(type, title, status, detail, instance, null, Instant.now());
    }
}
