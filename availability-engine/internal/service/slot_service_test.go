package service_test

import (
	"context"
	"errors"
	"testing"

	"github.com/RonnyGuilherme/medflow-backend/availability-engine/internal/model"
	"github.com/RonnyGuilherme/medflow-backend/availability-engine/internal/repository"
	"github.com/RonnyGuilherme/medflow-backend/availability-engine/internal/service"
	"github.com/google/uuid"
)

// ── Mock repository (satisfies SlotRepositoryPort) ────────────────────────────

type mockSlotRepo struct {
	findByIDFn                    func(ctx context.Context, slotID, tenantID uuid.UUID) (*model.Slot, error)
	findAvailableByProfessionalFn func(ctx context.Context, professionalID, tenantID uuid.UUID) ([]*model.Slot, error)
	markBookedFn                  func(ctx context.Context, slotID, tenantID uuid.UUID) (bool, error)
	markAvailableFn               func(ctx context.Context, slotID, tenantID uuid.UUID) error
}

func (m *mockSlotRepo) FindByID(ctx context.Context, slotID, tenantID uuid.UUID) (*model.Slot, error) {
	return m.findByIDFn(ctx, slotID, tenantID)
}
func (m *mockSlotRepo) FindAvailableByProfessional(ctx context.Context, professionalID, tenantID uuid.UUID) ([]*model.Slot, error) {
	if m.findAvailableByProfessionalFn != nil {
		return m.findAvailableByProfessionalFn(ctx, professionalID, tenantID)
	}
	return nil, nil
}
func (m *mockSlotRepo) MarkBooked(ctx context.Context, slotID, tenantID uuid.UUID) (bool, error) {
	if m.markBookedFn != nil {
		return m.markBookedFn(ctx, slotID, tenantID)
	}
	return false, nil
}
func (m *mockSlotRepo) MarkAvailable(ctx context.Context, slotID, tenantID uuid.UUID) error {
	if m.markAvailableFn != nil {
		return m.markAvailableFn(ctx, slotID, tenantID)
	}
	return nil
}

// Compile-time check: mockSlotRepo must satisfy SlotRepositoryPort
var _ repository.SlotRepositoryPort = (*mockSlotRepo)(nil)

// ── Tests ─────────────────────────────────────────────────────────────────────

func TestCheckAvailability_SlotAvailable(t *testing.T) {
	slotID := uuid.New()
	tenantID := uuid.New()

	repo := &mockSlotRepo{
		findByIDFn: func(_ context.Context, s, tn uuid.UUID) (*model.Slot, error) {
			if s != slotID || tn != tenantID {
				t.Errorf("unexpected IDs: got slot=%v tenant=%v", s, tn)
			}
			return &model.Slot{ID: slotID, TenantID: tenantID, Status: model.StatusAvailable}, nil
		},
	}
	svc := service.NewSlotService(repo)

	result, err := svc.CheckAvailability(context.Background(), slotID, tenantID)

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !result.Available {
		t.Error("expected Available=true, got false")
	}
	if result.Reason != "" {
		t.Errorf("expected empty reason, got %q", result.Reason)
	}
}

func TestCheckAvailability_SlotBooked_ReturnsFalseWithReason(t *testing.T) {
	slotID := uuid.New()
	tenantID := uuid.New()

	repo := &mockSlotRepo{
		findByIDFn: func(_ context.Context, _, _ uuid.UUID) (*model.Slot, error) {
			return &model.Slot{Status: model.StatusBooked}, nil
		},
	}
	svc := service.NewSlotService(repo)

	result, err := svc.CheckAvailability(context.Background(), slotID, tenantID)

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result.Available {
		t.Error("expected Available=false, got true")
	}
	if result.Reason != "already_BOOKED" {
		t.Errorf("expected reason %q, got %q", "already_BOOKED", result.Reason)
	}
}

func TestCheckAvailability_SlotNotFound_ReturnsFalse(t *testing.T) {
	repo := &mockSlotRepo{
		findByIDFn: func(_ context.Context, _, _ uuid.UUID) (*model.Slot, error) {
			return nil, nil // not found
		},
	}
	svc := service.NewSlotService(repo)

	result, err := svc.CheckAvailability(context.Background(), uuid.New(), uuid.New())

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result.Available {
		t.Error("expected Available=false for missing slot")
	}
	if result.Reason != "slot_not_found" {
		t.Errorf("expected reason %q, got %q", "slot_not_found", result.Reason)
	}
}

func TestCheckAvailability_DBError_PropagatesError(t *testing.T) {
	repo := &mockSlotRepo{
		findByIDFn: func(_ context.Context, _, _ uuid.UUID) (*model.Slot, error) {
			return nil, errors.New("connection refused")
		},
	}
	svc := service.NewSlotService(repo)

	_, err := svc.CheckAvailability(context.Background(), uuid.New(), uuid.New())

	if err == nil {
		t.Error("expected error to propagate, got nil")
	}
}

func TestHandleAppointmentCreated_MarksSlotBooked(t *testing.T) {
	calledWith := uuid.Nil
	repo := &mockSlotRepo{
		markBookedFn: func(_ context.Context, slotID, _ uuid.UUID) (bool, error) {
			calledWith = slotID
			return true, nil
		},
	}
	svc := service.NewSlotService(repo)

	slotID := uuid.New()
	tenantID := uuid.New()
	event := &model.AppointmentCreatedEvent{
		AppointmentID: uuid.New().String(),
		SlotID:        slotID.String(),
		TenantID:      tenantID.String(),
	}

	err := svc.HandleAppointmentCreated(context.Background(), event)

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if calledWith != slotID {
		t.Errorf("MarkBooked called with wrong slotID: %v", calledWith)
	}
}

func TestHandleAppointmentCreated_InvalidSlotUUID_ReturnsError(t *testing.T) {
	svc := service.NewSlotService(&mockSlotRepo{})

	err := svc.HandleAppointmentCreated(context.Background(), &model.AppointmentCreatedEvent{
		SlotID:   "not-a-uuid",
		TenantID: uuid.New().String(),
	})

	if err == nil {
		t.Error("expected error for invalid UUID, got nil")
	}
}

func TestHandleAppointmentCreated_SlotAlreadyBooked_ReturnsError(t *testing.T) {
	repo := &mockSlotRepo{
		markBookedFn: func(_ context.Context, _, _ uuid.UUID) (bool, error) {
			return false, nil // Slot already taken
		},
	}
	svc := service.NewSlotService(repo)

	slotID := uuid.New()
	tenantID := uuid.New()
	event := &model.AppointmentCreatedEvent{
		AppointmentID: uuid.New().String(),
		SlotID:        slotID.String(),
		TenantID:      tenantID.String(),
	}

	err := svc.HandleAppointmentCreated(context.Background(), event)

	if err == nil {
		t.Error("expected ErrSlotAlreadyBooked, got nil")
	}
	if !errors.Is(err, service.ErrSlotAlreadyBooked) {
		t.Errorf("expected ErrSlotAlreadyBooked, got %v", err)
	}
}
