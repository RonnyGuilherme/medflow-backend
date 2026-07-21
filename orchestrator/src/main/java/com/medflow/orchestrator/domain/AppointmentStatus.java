package com.medflow.orchestrator.domain;

public enum AppointmentStatus {
    SCHEDULED,    // Created but not yet confirmed
    CONFIRMED,    // Confirmed by professional
    CANCELLED,    // Cancelled by patient or professional
    COMPLETED,    // Appointment took place
    NO_SHOW       // Patient did not attend
}
