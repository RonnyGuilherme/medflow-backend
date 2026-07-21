import pino from 'pino';

export const logger = pino({
  level: process.env['LOG_LEVEL'] ?? 'info',
  base: { service: 'notification-dispatcher' },
  timestamp: pino.stdTimeFunctions.isoTime,
  redact: {
    paths: ['email', 'phone', 'patientEmail', '*.email', '*.phone'],
    censor: '[REDACTED]',
  },
});
