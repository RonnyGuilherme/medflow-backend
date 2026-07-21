package model

import (
	"time"

	"github.com/google/uuid"
)

type SlotStatus string

const (
	StatusAvailable SlotStatus = "AVAILABLE"
	StatusBooked    SlotStatus = "BOOKED"
	StatusBlocked   SlotStatus = "BLOCKED"
)

// Slot represents a time slot in a professional's schedule.
type Slot struct {
	ID             uuid.UUID  `json:"id"`
	TenantID       uuid.UUID  `json:"tenantId"`
	ProfessionalID uuid.UUID  `json:"professionalId"`
	StartTime      time.Time  `json:"startTime"`
	EndTime        time.Time  `json:"endTime"`
	Status         SlotStatus `json:"status"`
	CreatedAt      time.Time  `json:"createdAt"`
	UpdatedAt      time.Time  `json:"updatedAt"`
}

// SlotAvailabilityResponse is returned by the check endpoint.
type SlotAvailabilityResponse struct {
	Available bool   `json:"available"`
	SlotID    string `json:"slotId"`
	Reason    string `json:"reason,omitempty"` // present when available=false
}

// AppointmentCreatedEvent is the Kafka event payload published by the Orchestrator.
type AppointmentCreatedEvent struct {
	AppointmentID  string `json:"appointmentId"`
	TenantID       string `json:"tenantId"`
	PatientID      string `json:"patientId"`
	ProfessionalID string `json:"professionalId"`
	SlotID         string `json:"slotId"`
	Status         string `json:"status"`
	OccurredAt     string `json:"occurredAt"`
}

// AppointmentBookingFailedEvent is sent to DLQ when booking conflicts occur and all retries are exhausted.
type AppointmentBookingFailedEvent struct {
	AppointmentID string `json:"appointmentId"`
	TenantID      string `json:"tenantId"`
	PatientID     string `json:"patientId"`
	SlotID        string `json:"slotId"`
	Reason        string `json:"reason"`
	Attempts      int    `json:"attempts"`
	FailedAt      string `json:"failedAt"`
}
