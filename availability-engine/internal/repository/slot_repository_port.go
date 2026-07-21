package repository

import (
	"context"

	"github.com/RonnyGuilherme/medflow-backend/availability-engine/internal/model"
	"github.com/google/uuid"
)

// SlotRepositoryPort is the interface that SlotService depends on.
// Defined in the repository package so both the real impl and test mocks can satisfy it.
// This follows the Go idiom: "accept interfaces, return structs."
type SlotRepositoryPort interface {
	FindByID(ctx context.Context, slotID, tenantID uuid.UUID) (*model.Slot, error)
	FindAvailableByProfessional(ctx context.Context, professionalID, tenantID uuid.UUID) ([]*model.Slot, error)
	MarkBooked(ctx context.Context, slotID, tenantID uuid.UUID) (bool, error)
	MarkAvailable(ctx context.Context, slotID, tenantID uuid.UUID) error
}
