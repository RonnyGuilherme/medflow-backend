import nodemailer from 'nodemailer';
import type { Config } from '../config/config.js';
import type { AppointmentEvent } from '../types/appointment.js';
import { logger } from '../logger.js';

export class EmailNotifier {
  private transporter: nodemailer.Transporter;
  private readonly from: string;

  constructor(config: Config['smtp']) {
    this.from = config.from;
    this.transporter = nodemailer.createTransport({
      host:   config.host,
      port:   config.port,
      secure: config.secure,
    });
  }

  async sendAppointmentCreated(event: AppointmentEvent): Promise<void> {
    // In production: resolve patient email from patient service
    // For dev/demo: use placeholder
    const patientEmail = `patient-${event.patientId}@example.com`;

    await this.transporter.sendMail({
      from:    this.from,
      to:      patientEmail,
      subject: 'Your appointment has been booked — MedFlow',
      html:    this.buildBookingTemplate(event),
    });

    logger.info({
      msg:           'Booking confirmation email sent',
      appointmentId: event.appointmentId,
      tenantId:      event.tenantId,
      // patientEmail intentionally omitted from logs (GDPR)
    });
  }

  async sendAppointmentCancelled(event: AppointmentEvent): Promise<void> {
    const patientEmail = `patient-${event.patientId}@example.com`;

    await this.transporter.sendMail({
      from:    this.from,
      to:      patientEmail,
      subject: 'Your appointment has been cancelled — MedFlow',
      html:    this.buildCancellationTemplate(event),
    });

    logger.info({
      msg:           'Cancellation email sent',
      appointmentId: event.appointmentId,
      tenantId:      event.tenantId,
    });
  }

  private buildBookingTemplate(event: AppointmentEvent): string {
    return `
      <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
        <h2 style="color: #2563eb;">Appointment Confirmed</h2>
        <p>Your appointment has been successfully booked.</p>
        <table style="border-collapse: collapse; width: 100%;">
          <tr>
            <td style="padding: 8px; font-weight: bold;">Appointment ID:</td>
            <td style="padding: 8px;">${event.appointmentId}</td>
          </tr>
          <tr style="background: #f8fafc;">
            <td style="padding: 8px; font-weight: bold;">Status:</td>
            <td style="padding: 8px;">${event.status}</td>
          </tr>
          <tr>
            <td style="padding: 8px; font-weight: bold;">Booked at:</td>
            <td style="padding: 8px;">${new Date(event.occurredAt).toLocaleString()}</td>
          </tr>
        </table>
        <p style="color: #64748b; font-size: 12px;">
          This is an automated message from MedFlow. Please do not reply to this email.
        </p>
      </div>
    `;
  }

  private buildCancellationTemplate(event: AppointmentEvent): string {
    return `
      <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
        <h2 style="color: #dc2626;">Appointment Cancelled</h2>
        <p>Your appointment (ID: ${event.appointmentId}) has been cancelled.</p>
        <p>Please log in to MedFlow to rebook at a convenient time.</p>
        <p style="color: #64748b; font-size: 12px;">
          This is an automated message from MedFlow.
        </p>
      </div>
    `;
  }

  async verifyConnection(): Promise<void> {
    await this.transporter.verify();
    logger.info('SMTP connection verified');
  }
}
