package database

import (
	"context"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Connect creates a pgx connection pool with sensible defaults.
func Connect(databaseURL string) *pgxpool.Pool {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	cfg, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		slog.Error("Failed to parse database URL", "error", err)
		panic(err)
	}

	cfg.MaxConns = 20
	cfg.MinConns = 5
	cfg.MaxConnLifetime = 30 * time.Minute
	cfg.MaxConnIdleTime = 5 * time.Minute

	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		slog.Error("Failed to connect to database", "error", err)
		panic(err)
	}

	if err = pool.Ping(ctx); err != nil {
		slog.Error("Database ping failed", "error", err)
		panic(err)
	}

	slog.Info("Database connected", "maxConns", cfg.MaxConns)
	return pool
}
