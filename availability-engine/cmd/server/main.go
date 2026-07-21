package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/RonnyGuilherme/medflow-backend/availability-engine/internal/config"
	"github.com/RonnyGuilherme/medflow-backend/availability-engine/internal/consumer"
	"github.com/RonnyGuilherme/medflow-backend/availability-engine/internal/database"
	"github.com/RonnyGuilherme/medflow-backend/availability-engine/internal/handler"
	"github.com/RonnyGuilherme/medflow-backend/availability-engine/internal/repository"
	"github.com/RonnyGuilherme/medflow-backend/availability-engine/internal/service"
	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

func main() {
	// Structured JSON logging — compatible with ELK/Loki ingestion
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelInfo,
		ReplaceAttr: func(_ []string, a slog.Attr) slog.Attr {
			if a.Key == slog.TimeKey {
				a.Key = "timestamp"
			}
			return a
		},
	}))
	slog.SetDefault(logger)

	cfg := config.Load()

	// ── Database ──────────────────────────────────────────────────────────────
	db := database.Connect(cfg.DatabaseURL)
	defer db.Close()

	// ── Dependency wiring ─────────────────────────────────────────────────────
	slotRepo := repository.NewSlotRepository(db)
	slotSvc := service.NewSlotService(slotRepo)
	slotHandler := handler.NewSlotHandler(slotSvc)

	// ── Kafka Consumer (background goroutine) ─────────────────────────────────
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	appointmentConsumer := consumer.NewAppointmentConsumer(
		cfg.KafkaBrokers, cfg.KafkaTopic, cfg.KafkaDLQTopic, cfg.KafkaGroupID, slotSvc)
	go appointmentConsumer.Start(ctx)
	defer func() {
		if err := appointmentConsumer.Close(); err != nil {
			slog.Error("Error closing Kafka consumer", "error", err)
		}
	}()

	// ── HTTP Server ───────────────────────────────────────────────────────────
	gin.SetMode(gin.ReleaseMode)
	router := gin.New()
	router.Use(gin.Recovery())
	router.Use(requestLogger())

	// Public endpoints
	router.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"service": "availability-engine",
			"status":  "ok",
		})
	})
	router.GET("/metrics", gin.WrapH(promhttp.Handler()))
	router.GET("/:professionalId/slots", slotHandler.ListAvailableSlots)

	// Internal endpoints — only reachable from Kong/Orchestrator via internal network
	internal := router.Group("/internal")
	{
		internal.GET("/slots/:slotId/check", slotHandler.CheckSlot)
		internal.GET("/slots/professional/:professionalId/available", slotHandler.ListAvailableSlots)
	}

	srv := &http.Server{
		Addr:         ":" + cfg.Port,
		Handler:      router,
		ReadTimeout:  5 * time.Second,
		WriteTimeout: 10 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	// ── Graceful shutdown ─────────────────────────────────────────────────────
	go func() {
		slog.Info("Availability Engine starting", "port", cfg.Port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("Server error", "error", err)
			os.Exit(1)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	slog.Info("Shutting down gracefully...")
	cancel() // Stop Kafka consumer

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()

	if err := srv.Shutdown(shutdownCtx); err != nil {
		slog.Error("Forced shutdown", "error", err)
	}
	slog.Info("Server stopped")
}

func requestLogger() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()
		slog.Info("HTTP request",
			"method", c.Request.Method,
			"path", c.Request.URL.Path,
			"status", c.Writer.Status(),
			"latency_ms", time.Since(start).Milliseconds(),
			"tenant", c.GetHeader("X-Tenant-ID"),
			"correlation", c.GetHeader("X-Correlation-ID"),
		)
	}
}
