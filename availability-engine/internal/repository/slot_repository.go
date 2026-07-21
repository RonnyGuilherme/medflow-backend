package repository

import (
	"context"
	"log/slog"

	"github.com/RonnyGuilherme/medflow-backend/availability-engine/internal/model"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type SlotRepository struct {
	db *pgxpool.Pool
}

func NewSlotRepository(db *pgxpool.Pool) *SlotRepository {
	return &SlotRepository{db: db}
}

// FindByID returns a slot by ID, scoped to the given tenant.
func (r *SlotRepository) FindByID(ctx context.Context, slotID, tenantID uuid.UUID) (*model.Slot, error) {
	row := r.db.QueryRow(ctx, `
		SELECT id, tenant_id, professional_id, start_time, end_time, status, created_at, updated_at
		FROM slots
		WHERE id = $1 AND tenant_id = $2
	`, slotID, tenantID)

	slot := &model.Slot{}
	err := row.Scan(
		&slot.ID, &slot.TenantID, &slot.ProfessionalID,
		&slot.StartTime, &slot.EndTime, &slot.Status,
		&slot.CreatedAt, &slot.UpdatedAt,
	)
	if err == pgx.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return slot, nil
}

// FindAvailableByProfessional returns all AVAILABLE slots for a professional within a tenant.
func (r *SlotRepository) FindAvailableByProfessional(
	ctx context.Context, professionalID, tenantID uuid.UUID,
) ([]*model.Slot, error) {
	rows, err := r.db.Query(ctx, `
		SELECT id, tenant_id, professional_id, start_time, end_time, status, created_at, updated_at
		FROM slots
		WHERE professional_id = $1 AND tenant_id = $2 AND status = 'AVAILABLE'
		ORDER BY start_time ASC
	`, professionalID, tenantID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var slots []*model.Slot
	for rows.Next() {
		s := &model.Slot{}
		if err := rows.Scan(
			&s.ID, &s.TenantID, &s.ProfessionalID,
			&s.StartTime, &s.EndTime, &s.Status,
			&s.CreatedAt, &s.UpdatedAt,
		); err != nil {
			return nil, err
		}
		slots = append(slots, s)
	}
	return slots, rows.Err()
}

// MarkBooked attempts to atomically mark a slot as BOOKED.
// Uses optimistic update: only succeeds if status is still AVAILABLE.
// Returns true if the update succeeded, false if the slot was already taken.
func (r *SlotRepository) MarkBooked(ctx context.Context, slotID, tenantID uuid.UUID) (bool, error) {
	tag, err := r.db.Exec(ctx, `
		UPDATE slots
		SET status = 'BOOKED', updated_at = NOW()
		WHERE id = $1 AND tenant_id = $2 AND status = 'AVAILABLE'
	`, slotID, tenantID)

	if err != nil {
		return false, err
	}

	rowsAffected := tag.RowsAffected()
	if rowsAffected == 0 {
		slog.Warn("Slot booking conflict detected — slot already taken",
			"slotId", slotID, "tenantId", tenantID)
		return false, nil
	}

	return true, nil
}

// MarkAvailable returns a slot to AVAILABLE status (used on cancellation).
func (r *SlotRepository) MarkAvailable(ctx context.Context, slotID, tenantID uuid.UUID) error {
	_, err := r.db.Exec(ctx, `
		UPDATE slots
		SET status = 'AVAILABLE', updated_at = NOW()
		WHERE id = $1 AND tenant_id = $2 AND status = 'BOOKED'
	`, slotID, tenantID)
	return err
}
