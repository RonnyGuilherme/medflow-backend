package handler

import (
	"log/slog"
	"net/http"

	"github.com/RonnyGuilherme/medflow-backend/availability-engine/internal/model"
	"github.com/RonnyGuilherme/medflow-backend/availability-engine/internal/service"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type SlotHandler struct {
	svc *service.SlotService
}

func NewSlotHandler(svc *service.SlotService) *SlotHandler {
	return &SlotHandler{svc: svc}
}

// CheckSlot handles GET /internal/slots/:slotId/check
func (h *SlotHandler) CheckSlot(c *gin.Context) {
	slotID, err := uuid.Parse(c.Param("slotId"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid slotId: must be a UUID"})
		return
	}

	tenantID, ok := h.extractTenantID(c)
	if !ok {
		return
	}

	result, err := h.svc.CheckAvailability(c.Request.Context(), slotID, tenantID)
	if err != nil {
		slog.Error("Failed to check slot availability",
			"slotId", slotID, "tenantId", tenantID, "error", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "internal error"})
		return
	}

	c.JSON(http.StatusOK, result)
}

// ListAvailableSlots handles GET /internal/slots/professional/:professionalId/available
func (h *SlotHandler) ListAvailableSlots(c *gin.Context) {
	professionalID, err := uuid.Parse(c.Param("professionalId"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid professionalId: must be a UUID"})
		return
	}

	tenantID, ok := h.extractTenantID(c)
	if !ok {
		return
	}

	slots, err := h.svc.ListAvailableSlots(c.Request.Context(), professionalID, tenantID)
	if err != nil {
		slog.Error("Failed to list available slots",
			"professionalId", professionalID, "tenantId", tenantID, "error", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "internal error"})
		return
	}

	// Always return an array, never null — important for client predictability
	if slots == nil {
		slots = make([]*model.Slot, 0)
	}

	c.JSON(http.StatusOK, gin.H{
		"professionalId": professionalID,
		"slots":          slots,
		"count":          len(slots),
	})
}

// extractTenantID reads and validates the X-Tenant-ID header.
// Returns (uuid, true) on success; writes an error response and returns (_, false) on failure.
func (h *SlotHandler) extractTenantID(c *gin.Context) (uuid.UUID, bool) {
	tenantHeader := c.GetHeader("X-Tenant-ID")
	if tenantHeader == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "X-Tenant-ID header is required"})
		return uuid.Nil, false
	}

	tenantID, err := uuid.Parse(tenantHeader)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "X-Tenant-ID must be a valid UUID"})
		return uuid.Nil, false
	}
	return tenantID, true
}
