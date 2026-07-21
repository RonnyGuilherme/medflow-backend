import 'dotenv/config';
import http from 'http';
import { Kafka, logLevel } from 'kafkajs';
import { loadConfig } from './config/config.js';
import { AppointmentConsumer } from './consumer/appointmentConsumer.js';
import { EmailNotifier }       from './notifier/emailNotifier.js';
import { PushNotifier }        from './notifier/pushNotifier.js';
import { logger }              from './logger.js';

export { logger } from './logger.js';

const config = loadConfig();

async function main(): Promise<void> {
  logger.info({
    msg: 'Notification Dispatcher starting',
    kafkaBrokers: config.kafkaBrokers,
    topic:        config.kafkaTopic,
    groupId:      config.kafkaGroupId,
    smtpHost:     config.smtp.host,
  });

  const kafka = new Kafka({
    clientId: 'notification-dispatcher',
    brokers:  config.kafkaBrokers,
    logLevel: logLevel.WARN,
    retry: { initialRetryTime: 300, retries: 10 },
  });

  const emailNotifier = new EmailNotifier(config.smtp);
  const pushNotifier  = new PushNotifier(config.fcm);

  try {
    await emailNotifier.verifyConnection();
  } catch (err) {
    logger.warn({ msg: 'SMTP connection failed — email notifications may not work', error: err });
  }

  const appointmentConsumer = new AppointmentConsumer(
    kafka, config.kafkaGroupId, emailNotifier, pushNotifier,
  );

  const server = http.createServer((req, res) => {
    if (req.url === '/health' && req.method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ service: 'notification-dispatcher', status: 'ok' }));
    } else {
      res.writeHead(404);
      res.end();
    }
  });

  server.listen(config.port, () => {
    logger.info({ msg: 'Health check server listening', port: config.port });
  });

  await appointmentConsumer.start(config.kafkaTopic);

  const shutdown = async (signal: string): Promise<void> => {
    logger.info({ msg: 'Shutdown signal received', signal });
    await appointmentConsumer.shutdown();
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(1), 5000);
  };

  process.on('SIGTERM', () => { void shutdown('SIGTERM'); });
  process.on('SIGINT',  () => { void shutdown('SIGINT');  });
  process.on('unhandledRejection', (reason) => {
    logger.error({ msg: 'Unhandled promise rejection', reason });
    process.exit(1);
  });
}

main().catch((err) => {
  logger.error({ msg: 'Fatal startup error', error: err });
  process.exit(1);
});
