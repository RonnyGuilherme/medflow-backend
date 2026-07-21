package com.medflow.orchestrator.exception;

import java.util.UUID;

public class SlotNotAvailableException extends RuntimeException {

    private final UUID slotId;

    public SlotNotAvailableException(UUID slotId) {
        super("Slot %s is not available".formatted(slotId));
        this.slotId = slotId;
    }

    public UUID getSlotId() {
        return slotId;
    }
}
