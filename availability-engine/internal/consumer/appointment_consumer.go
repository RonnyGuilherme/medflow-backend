package consumer

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"time"

	"github.com/RonnyGuilherme/medflow-backend/availability-engine/internal/model"
	"github.com/RonnyGuilherme/medflow-backend/availability-engine/internal/service"
	"github.com/segmentio/kafka-go"
)

// AppointmentConsumer reads from medflow.appointments and updates slot state.
// Each event type is dispatched to the SlotService for domain logic.
// Failed booking attempts are retried with exponential backoff, then routed to DLQ.
//
// Consumer group ID "availability-engine-group" ensures this service receives
// ALL events independently of the notification-dispatcher-group.
type AppointmentConsumer struct {
	reader         *kafka.Reader
	dlqWriter      *kafka.Writer
	svc            *service.SlotService
	maxRetries     int
	maxBackoffTime time.Duration
}

func NewAppointmentConsumer(
	brokers []string,
	topic, dlqTopic, groupID string,
	svc *service.SlotService,
) *AppointmentConsumer {
	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:        brokers,
		Topic:          topic,
		GroupID:        groupID,
		MinBytes:       1,
		MaxBytes:       10e6,
		CommitInterval: time.Second,
		StartOffset:    kafka.FirstOffset,
		ErrorLogger: kafka.LoggerFunc(func(msg string, args ...interface{}) {
			slog.Error(msg, args...)
		}),
	})

	dlqWriter := &kafka.Writer{
		Addr:     kafka.TCP(brokers...),
		Topic:    dlqTopic,
		Balancer: &kafka.LeastBytes{},
		Logger: kafka.LoggerFunc(func(msg string, args ...interface{}) {
			slog.Error(msg, args...)
		}),
		ErrorLogger: kafka.LoggerFunc(func(msg string, args ...interface{}) {
			slog.Error(msg, args...)
		}),
	}

	return &AppointmentConsumer{
		reader:         reader,
		dlqWriter:      dlqWriter,
		svc:            svc,
		maxRetries:     5,
		maxBackoffTime: 30 * time.Second,
	}
}

// Start begins consuming messages. Blocks until ctx is cancelled.
func (c *AppointmentConsumer) Start(ctx context.Context) {
	slog.Info("Availability Engine Kafka consumer started",
		"topic", c.reader.Config().Topic,
		"groupId", c.reader.Config().GroupID)

	for {
		msg, err := c.reader.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				slog.Info("Consumer shutting down")
				return
			}
			slog.Error("Failed to fetch Kafka message", "error", err)
			time.Sleep(time.Second)
			continue
		}

		if err := c.processWithRetry(ctx, msg); err != nil {
			slog.Error("Failed to process message (no retry)",
				"offset", msg.Offset,
				"key", string(msg.Key),
				"error", err)
		}

		if err := c.reader.CommitMessages(ctx, msg); err != nil {
			slog.Error("Failed to commit Kafka offset", "error", err)
		}
	}
}

// processWithRetry attempts to process a message, differentiating:
// - Deterministic failures (ErrSlotAlreadyBooked): route to DLQ immediately
// - Transient failures (DB, network): retry with exponential backoff, then DLQ
func (c *AppointmentConsumer) processWithRetry(ctx context.Context, msg kafka.Message) error {
	retryCount := 0
	for retryCount <= c.maxRetries {
		err := c.process(ctx, msg)
		if err == nil {
			return nil
		}

		// Deterministic error: slot is permanently booked by another appointment.
		// No amount of retry will fix this. Route to DLQ immediately.
		if errors.Is(err, service.ErrSlotAlreadyBooked) {
			slog.Warn("Slot booking conflict (deterministic) — routing to DLQ immediately",
				"offset", msg.Offset,
				"key", string(msg.Key),
				"error", err)
			return c.routeToDLQ(ctx, msg, err, 0)
		}

		// Transient error: database, network, or temporary service failure.
		// Retry with exponential backoff up to maxRetries.
		retryCount++
		if retryCount > c.maxRetries {
			slog.Error("Message exhausted retries — routing to DLQ",
				"offset", msg.Offset,
				"key", string(msg.Key),
				"error", err,
				"retries", retryCount-1)
			return c.routeToDLQ(ctx, msg, err, retryCount-1)
		}

		backoff := exponentialBackoff(retryCount, c.maxBackoffTime)
		slog.Warn("Transient processing failure, retrying",
			"offset", msg.Offset,
			"key", string(msg.Key),
			"error", err,
			"attempt", retryCount,
			"backoff", backoff)
		time.Sleep(backoff)
	}
	return nil
}
    func (c *AppointmentConsumer) process(ctx context.Context, msg kafka.Message) error {
    	eventType := headerValue(msg.Headers, "eventType")
    	if eventType == "" {
    		var partial map[string]string
    		if jsonErr := json.Unmarshal(msg.Value, &partial); jsonErr != nil {
    			slog.Debug("Could not parse payload for event type inference", "offset", msg.Offset)
    		}
    		statusToEventType := map[string]string{
    			"SCHEDULED": "appointment.created",
    			"CONFIRMED": "appointment.created",
    			"CANCELLED": "appointment.cancelled",
    			"COMPLETED": "appointment.completed",
    			"NO_SHOW":   "appointment.no_show",
    		}
    		eventType = statusToEventType[partial["status"]]
    	}

    	slog.Debug("Processing Kafka message",
    		"offset", msg.Offset,
    		"key", string(msg.Key),
    		"eventType", eventType)

    	switch eventType {
    	case "appointment.created":
    		var event model.AppointmentCreatedEvent
    		if err := json.Unmarshal(msg.Value, &event); err != nil {
    			return err
    		}
    		return c.svc.HandleAppointmentCreated(ctx, &event)

    	case "appointment.cancelled":
    		var event model.AppointmentCreatedEvent
    		if err := json.Unmarshal(msg.Value, &event); err != nil {
    			return err
    		}
    		return c.svc.HandleAppointmentCancelled(ctx, &event)

    	default:
    		slog.Debug("Skipping unknown event type", "eventType", eventType)
    		return nil
    	}
    }

// routeToDLQ sends a failure notification to the DLQ.
func (c *AppointmentConsumer) routeToDLQ(
	ctx context.Context,
	msg kafka.Message,
	err error,
	attempts int,
) error {
	var event model.AppointmentCreatedEvent
	if jsonErr := json.Unmarshal(msg.Value, &event); jsonErr != nil {
		slog.Error("Could not unmarshal original event for DLQ", "error", jsonErr)
		return jsonErr
	}

	failedEvent := model.AppointmentBookingFailedEvent{
		AppointmentID: event.AppointmentID,
		TenantID:      event.TenantID,
		PatientID:     event.PatientID,
		SlotID:        event.SlotID,
		Reason:        err.Error(),
		Attempts:      attempts,
		FailedAt:      time.Now().Format(time.RFC3339),
	}

	payload, err := json.Marshal(failedEvent)
	if err != nil {
		return err
	}

	dlqMsg := kafka.Message{
		Key:   msg.Key,
		Value: payload,
		Headers: []kafka.Header{
			{Key: "originalOffset", Value: []byte(strconv.FormatInt(msg.Offset, 10))},
            {Key: "originalTopic",  Value: []byte(msg.Topic)},
			{Key: "reason", Value: []byte(err.Error())},
		},
	}

	return c.dlqWriter.WriteMessages(ctx, dlqMsg)
}

// exponentialBackoff returns a duration with exponential backoff capped at maxBackoff.
func exponentialBackoff(attempt int, maxBackoff time.Duration) time.Duration {
	baseDuration := time.Millisecond * 100
	backoff := baseDuration * time.Duration(1<<uint(attempt-1))
	if backoff > maxBackoff {
		backoff = maxBackoff
	}
	return backoff
}

func (c *AppointmentConsumer) Close() error {
	if err := c.reader.Close(); err != nil {
		return err
	}
	return c.dlqWriter.Close()
}

func headerValue(headers []kafka.Header, key string) string {
	for _, h := range headers {
		if h.Key == key {
			return string(h.Value)
		}
	}
	return ""
}
