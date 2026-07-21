import { Kafka, Consumer, EachMessagePayload } from 'kafkajs';
import type { AppointmentEvent } from '../types/appointment.js';
import type { EmailNotifier } from '../notifier/emailNotifier.js';
import type { PushNotifier }  from '../notifier/pushNotifier.js';
import { logger } from '../logger.js';  // Direct import — avoids circular dep with index.ts

/**
 * Kafka consumer for appointment lifecycle events.
 *
 * Consumer group "notification-dispatcher-group" ensures this service
 * receives ALL events independently of the availability-engine-group.
 *
 * Idempotency: Uses appointmentId as a deduplication key. If the same
 * event is delivered twice (at-least-once from the Outbox relay),
 * the second delivery is silently skipped via the deduplication cache.
 */
export class AppointmentConsumer {
  private readonly consumer: Consumer;
  private readonly emailNotifier: EmailNotifier;
  private readonly pushNotifier:  PushNotifier;
  /** Simple in-memory dedup cache. In production: use Redis with TTL. */
  private readonly processedEvents = new Set<string>();

  constructor(
    kafka: Kafka,
    groupId: string,
    emailNotifier: EmailNotifier,
    pushNotifier:  PushNotifier,
  ) {
    this.consumer      = kafka.consumer({ groupId });
    this.emailNotifier = emailNotifier;
    this.pushNotifier  = pushNotifier;
  }

  async start(topic: string): Promise<void> {
    await this.consumer.connect();
    await this.consumer.subscribe({ topic, fromBeginning: false });

    logger.info({ msg: 'Notification Dispatcher consumer started', topic });

    await this.consumer.run({
      eachMessage: async (payload: EachMessagePayload) => {
        await this.processMessage(payload);
      },
    });
  }

  private async processMessage({ message, partition }: EachMessagePayload): Promise<void> {
    const correlationId = headerValue(message.headers, 'correlationId');
    const eventType     = headerValue(message.headers, 'eventType');
    const rawValue      = message.value?.toString();

    if (!rawValue) {
      logger.warn({ msg: 'Empty Kafka message received', partition });
      return;
    }

    let event: AppointmentEvent;
    try {
      event = JSON.parse(rawValue) as AppointmentEvent;
    } catch (err) {
      logger.error({ msg: 'Failed to parse Kafka message', error: err, partition });
      return;
    }

    // Idempotency check — at-least-once delivery may repeat events
    const deduplicationKey = `${event.appointmentId}:${eventType ?? event.status}`;
    if (this.processedEvents.has(deduplicationKey)) {
      logger.debug({ msg: 'Duplicate event skipped', deduplicationKey });
      return;
    }

    logger.info({
      msg:           'Processing appointment event',
      appointmentId: event.appointmentId,
      tenantId:      event.tenantId,
      eventType:     eventType ?? event.status,
      correlationId,
    });

    try {
      const resolvedEventType = eventType ?? this.inferEventType(event);
      await this.dispatch(resolvedEventType, event);
      this.processedEvents.add(deduplicationKey);
    } catch (err) {
      logger.error({
        msg:           'Failed to dispatch notification',
        appointmentId: event.appointmentId,
        error:         (err as Error).message,
      });
      // Don't add to processed set — allow retry on rebalance
      throw err; // Causes Kafka to retry the message
    }
  }

  private async dispatch(eventType: string, event: AppointmentEvent): Promise<void> {
    const [emailResult, pushResult] = await Promise.allSettled([
      this.dispatchEmail(eventType, event),
      this.dispatchPush(eventType, event),
    ]);

    const emailFailed = emailResult.status === 'rejected';
    const pushFailed  = pushResult.status  === 'rejected';

    if (emailFailed) {
      logger.error({ msg: 'Email dispatch failed', error: emailResult.reason });
    }
    if (pushFailed) {
      logger.error({ msg: 'Push dispatch failed', error: pushResult.reason });
    }

    // If BOTH channels failed, throw so processMessage() does NOT add this event
    // to the dedup set — guaranteeing it will be retried on the next Kafka delivery.
    // If only one channel fails, we accept partial delivery (better than no delivery).
    if (emailFailed && pushFailed) {
      throw new Error(
        `Both notification channels failed for appointment ${event.appointmentId}`,
      );
    }
  }

  private async dispatchEmail(eventType: string, event: AppointmentEvent): Promise<void> {
    switch (eventType) {
      case 'appointment.created':
        return this.emailNotifier.sendAppointmentCreated(event);
      case 'appointment.cancelled':
        return this.emailNotifier.sendAppointmentCancelled(event);
      default:
        logger.debug({ msg: 'No email handler for event type', eventType });
    }
  }

  private async dispatchPush(eventType: string, event: AppointmentEvent): Promise<void> {
    switch (eventType) {
      case 'appointment.created':
        return this.pushNotifier.sendAppointmentCreated(event);
      case 'appointment.cancelled':
        return this.pushNotifier.sendAppointmentCancelled(event);
      default:
        logger.debug({ msg: 'No push handler for event type', eventType });
    }
  }

  private inferEventType(event: AppointmentEvent): string {
    const statusMap: Record<string, string> = {
      SCHEDULED: 'appointment.created',
      CANCELLED: 'appointment.cancelled',
      CONFIRMED: 'appointment.confirmed',
      COMPLETED: 'appointment.completed',
    };
    return statusMap[event.status] ?? 'appointment.unknown';
  }

  async shutdown(): Promise<void> {
    logger.info({ msg: 'Shutting down Kafka consumer' });
    await this.consumer.disconnect();
  }
}

function headerValue(
  headers: EachMessagePayload['message']['headers'],
  key: string,
): string | undefined {
  const val = headers?.[key];
  if (Buffer.isBuffer(val)) return val.toString();
  if (typeof val === 'string') return val;
  return undefined;
}
