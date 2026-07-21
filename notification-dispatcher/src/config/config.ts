export interface Config {
  port:         number;
  kafkaBrokers: string[];
  kafkaTopic:   string;
  kafkaGroupId: string;
  smtp: {
    host:   string;
    port:   number;
    secure: boolean;
    from:   string;
  };
  fcm: {
    serverKey: string;
  };
}

export function loadConfig(): Config {
  return {
    port:         parseInt(process.env['PORT'] ?? '3001', 10),
    kafkaBrokers: (process.env['KAFKA_BROKERS'] ?? 'localhost:9092').split(','),
    kafkaTopic:   process.env['KAFKA_TOPIC']    ?? 'medflow.appointments',
    kafkaGroupId: process.env['KAFKA_GROUP_ID'] ?? 'notification-dispatcher-group',
    smtp: {
      host:   process.env['SMTP_HOST']   ?? 'localhost',
      port:   parseInt(process.env['SMTP_PORT'] ?? '1025', 10),
      secure: process.env['SMTP_SECURE'] === 'true',
      from:   process.env['SMTP_FROM']   ?? 'noreply@medflow.io',
    },
    fcm: {
      serverKey: process.env['FCM_SERVER_KEY'] ?? '',
    },
  };
}
