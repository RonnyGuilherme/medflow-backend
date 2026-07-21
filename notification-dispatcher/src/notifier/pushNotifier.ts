import type { Config } from '../config/config.js';
import type { AppointmentEvent } from '../types/appointment.js';
import { logger } from '../logger.js';

/**
 * FCM Push Notifier.
 *
 * Sends push notifications to patient mobile devices via Firebase Cloud Messaging.
 * Data minimisation: push payload contains only appointmentId and status —
 * the mobile app fetches full details from the API on receipt.
 */
export class PushNotifier {
  private readonly serverKey: string;
  private readonly fcmEndpoint = 'https://fcm.googleapis.com/fcm/send';

  constructor(config: Config['fcm']) {
    this.serverKey = config.serverKey;
  }

  async sendAppointmentCreated(event: AppointmentEvent): Promise<void> {
    await this.send({
      // In production: resolve FCM device token from patient profile service
      to: `/topics/patient-${event.patientId}`,
      notification: {
        title: 'Appointment Booked',
        body:  'Your appointment has been confirmed. Tap to view details.',
      },
      data: {
        // Minimal payload — mobile app fetches full details
        type:          'appointment.created',
        appointmentId: event.appointmentId,
        // No PHI in push payload
      },
    });
  }

  async sendAppointmentCancelled(event: AppointmentEvent): Promise<void> {
    await this.send({
      to: `/topics/patient-${event.patientId}`,
      notification: {
        title: 'Appointment Cancelled',
        body:  'Your appointment has been cancelled. Tap to rebook.',
      },
      data: {
        type:          'appointment.cancelled',
        appointmentId: event.appointmentId,
      },
    });
  }

  private async send(payload: Record<string, unknown>): Promise<void> {
    if (!this.serverKey || this.serverKey === 'dev-placeholder') {
      logger.debug({ msg: 'FCM push skipped — no server key configured', payload });
      return;
    }

    const response = await fetch(this.fcmEndpoint, {
      method:  'POST',
      headers: {
        'Content-Type':  'application/json',
        'Authorization': `key=${this.serverKey}`,
      },
      body: JSON.stringify(payload),
    });

    if (!response.ok) {
      const body = await response.text();
      throw new Error(`FCM error ${response.status}: ${body}`);
    }

    logger.info({ msg: 'FCM push sent', to: payload['to'] });
  }
}
