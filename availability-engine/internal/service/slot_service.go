package service

import (
	"context"
	"errors"
	"log/slog"

	"github.com/RonnyGuilherme/medflow-backend/availability-engine/internal/model"
	"github.com/RonnyGuilherme/medflow-backend/availability-engine/internal/repository"
	"github.com/google/uuid"
)

// ErrSlotAlreadyBooked is a deterministic conflict: the slot is permanently occupied.
// Callers must NOT retry — route directly to DLQ for saga compensation.
var ErrSlotAlreadyBooked = errors.New("slot_already_booked")

// SlotService depends on an interface, not the concrete repository.
// This makes it testable without a real database connection.
type SlotService struct {
	repo repository.SlotRepositoryPort
}

// NewSlotService accepts any type satisfying SlotRepositoryPort (real DB or mock).
func NewSlotService(repo repository.SlotRepositoryPort) *SlotService {
	return &SlotService{repo: repo}
}

func (s *SlotService) CheckAvailability(
	ctx context.Context, slotID, tenantID uuid.UUID,
) (*model.SlotAvailabilityResponse, error) {

	slot, err := s.repo.FindByID(ctx, slotID, tenantID)
	if err != nil {
		return nil, err
	}
	if slot == nil {
		return &model.SlotAvailabilityResponse{
			Available: false,
			SlotID:    slotID.String(),
			Reason:    "slot_not_found",
		}, nil
	}

	available := slot.Status == model.StatusAvailable
	reason := ""
	if !available {
		reason = "already_" + string(slot.Status)
	}

	slog.Debug("Slot availability checked",
		"slotId", slotID, "tenantId", tenantID,
		"status", slot.Status, "available", available)

	return &model.SlotAvailabilityResponse{
		Available: available,
		SlotID:    slotID.String(),
		Reason:    reason,
	}, nil
}

func (s *SlotService) ListAvailableSlots(
	ctx context.Context, professionalID, tenantID uuid.UUID,
) ([]*model.Slot, error) {
	return s.repo.FindAvailableByProfessional(ctx, professionalID, tenantID)
}

func (s *SlotService) HandleAppointmentCreated(
	ctx context.Context, event *model.AppointmentCreatedEvent,
) error {
	slotID, err := uuid.Parse(event.SlotID)
	if err != nil {
		return err
	}
	tenantID, err := uuid.Parse(event.TenantID)
	if err != nil {
		return err
	}

	booked, err := s.repo.MarkBooked(ctx, slotID, tenantID)
	if err != nil {
		return err
	}
	if !booked {
		// Slot was already taken by another appointment. This is a retryable failure.
		// The consumer will retry up to maxRetries times, then route to DLQ.
		slog.Warn("Slot booking conflict — will retry",
			"slotId", event.SlotID,
			"appointmentId", event.AppointmentID,
			"tenantId", event.TenantID)
		return ErrSlotAlreadyBooked
	}
	slog.Info("Slot marked as BOOKED",
		"slotId", event.SlotID,
		"appointmentId", event.AppointmentID,
		"tenantId", event.TenantID)
	return nil
}

func (s *SlotService) HandleAppointmentCancelled(
	ctx context.Context, event *model.AppointmentCreatedEvent,
) error {
	slotID, err := uuid.Parse(event.SlotID)
	if err != nil {
		return err
	}
	tenantID, err := uuid.Parse(event.TenantID)
	if err != nil {
		return err
	}
	return s.repo.MarkAvailable(ctx, slotID, tenantID)
}
