// Package consumer (white-box test, not consumer_test): exponentialBackoff and
// headerValue are unexported, so this file has to live in the same package to
// reach them — unlike slot_service_test.go, which only needs the public API and
// uses the external service_test package.
//
// This file intentionally covers ONLY the two pure functions for now. process(),
// processWithRetry() and routeToDLQ() need dlqWriter to become an interface
// before they're mockable without a real Kafka broker — that refactor + those
// tests are next.
package consumer

import (
	"testing"
	"time"

	"github.com/segmentio/kafka-go"
)

func TestExponentialBackoff(t *testing.T) {
	tests := []struct {
		name       string
		attempt    int
		maxBackoff time.Duration
		want       time.Duration
	}{
		{"first attempt", 1, 30 * time.Second, 100 * time.Millisecond},
		{"second attempt doubles", 2, 30 * time.Second, 200 * time.Millisecond},
		{"third attempt doubles again", 3, 30 * time.Second, 400 * time.Millisecond},
		{"fifth attempt", 5, 30 * time.Second, 1600 * time.Millisecond},
		{"capped when growth would exceed maxBackoff", 10, 5 * time.Second, 5 * time.Second},
		{"cap boundary is inclusive, not off-by-one", 6, 3200 * time.Millisecond, 3200 * time.Millisecond},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := exponentialBackoff(tt.attempt, tt.maxBackoff)
			if got != tt.want {
				t.Errorf("exponentialBackoff(%d, %v) = %v, want %v", tt.attempt, tt.maxBackoff, got, tt.want)
			}
		})
	}
}

func TestHeaderValue(t *testing.T) {
	headers := []kafka.Header{
		{Key: "eventType", Value: []byte("appointment.created")},
		{Key: "correlationId", Value: []byte("abc-123")},
	}

	t.Run("returns the value when the key is present", func(t *testing.T) {
		got := headerValue(headers, "eventType")
		if got != "appointment.created" {
			t.Errorf("headerValue() = %q, want %q", got, "appointment.created")
		}
	})

	t.Run("returns empty string when the key is absent", func(t *testing.T) {
		got := headerValue(headers, "missingKey")
		if got != "" {
			t.Errorf("headerValue() = %q, want empty string", got)
		}
	})

	t.Run("returns empty string for a nil headers slice", func(t *testing.T) {
		got := headerValue(nil, "eventType")
		if got != "" {
			t.Errorf("headerValue() = %q, want empty string", got)
		}
	})

	t.Run("returns empty string for an empty (non-nil) headers slice", func(t *testing.T) {
		got := headerValue([]kafka.Header{}, "eventType")
		if got != "" {
			t.Errorf("headerValue() = %q, want empty string", got)
		}
	})
}
