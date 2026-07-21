package config

import (
	"log/slog"
	"os"
	"strconv"
	"strings"
)

type Config struct {
	Port           string
	DatabaseURL    string
	KafkaBrokers   []string // slice — supports comma-separated multi-broker addresses
	KafkaTopic     string
	KafkaGroupID   string
	KafkaDLQTopic  string   // Dead Letter Queue topic for failed booking events
	ConsumerMaxRetries int   // Maximum retries before routing to DLQ (default: 5)
}

func Load() *Config {
	maxRetries := 5
	if mru := os.Getenv("CONSUMER_MAX_RETRIES"); mru != "" {
		if parsed, err := parseInt(mru, maxRetries); err == nil {
			maxRetries = parsed
		}
	}

	cfg := &Config{
		Port:           getEnv("PORT", "8081"),
		DatabaseURL:    getEnv("DATABASE_URL", "postgres://medflow:secret@localhost:5432/medflow?sslmode=disable"),
		KafkaBrokers:   getEnvSlice("KAFKA_BROKERS", "localhost:9092"),
		KafkaTopic:     getEnv("KAFKA_TOPIC", "medflow.appointments"),
		KafkaGroupID:   getEnv("KAFKA_GROUP_ID", "availability-engine-group"),
		KafkaDLQTopic:  getEnv("KAFKA_DLQ_TOPIC", "medflow.appointments.dlq"),
		ConsumerMaxRetries: maxRetries,
	}

	slog.Info("Config loaded",
		"port", cfg.Port,
		"kafkaBrokers", cfg.KafkaBrokers,
		"kafkaTopic", cfg.KafkaTopic,
		"kafkaDLQTopic", cfg.KafkaDLQTopic,
		"maxRetries", cfg.ConsumerMaxRetries,
	)
	return cfg
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func getEnvSlice(key, fallback string) []string {
	v := os.Getenv(key)
	if v == "" {
		v = fallback
	}
	parts := strings.Split(v, ",")
	result := make([]string, 0, len(parts))
	for _, p := range parts {
		if trimmed := strings.TrimSpace(p); trimmed != "" {
			result = append(result, trimmed)
		}
	}
	return result
}

func parseInt(s string, fallback int) (int, error) {
	parsed, err := strconv.Atoi(s)
	if err != nil {
		return fallback, err
	}
	return parsed, nil
}
