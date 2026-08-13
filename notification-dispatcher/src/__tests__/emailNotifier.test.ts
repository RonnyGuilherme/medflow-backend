import nodemailer from 'nodemailer';
import { EmailNotifier } from '../notifier/emailNotifier';
import { logger } from '../logger';
import type { AppointmentEvent } from '../types/appointment';

jest.mock('nodemailer');

const SMTP_CONFIG = {
    host: 'localhost',
    port: 1025,
    secure: false,
    from: 'noreply@medflow.io',
};

const EVENT: AppointmentEvent = {
    appointmentId: 'apt-1',
    tenantId: 'tenant-1',
    patientId: 'patient-1',
    professionalId: 'prof-1',
    slotId: 'slot-1',
    status: 'SCHEDULED',
    occurredAt: '2026-08-10T12:00:00Z',
};

describe('EmailNotifier', () => {
    const sendMail = jest.fn();
    const verify = jest.fn();

    beforeEach(() => {
        jest.clearAllMocks();
        (nodemailer.createTransport as jest.Mock).mockReturnValue({ sendMail, verify });
    });

    it('sends a booking confirmation with the derived recipient, subject and appointment details', async () => {
        sendMail.mockResolvedValue(undefined);
        const notifier = new EmailNotifier(SMTP_CONFIG);

        await notifier.sendAppointmentCreated(EVENT);

        expect(sendMail).toHaveBeenCalledTimes(1);
        const mail = sendMail.mock.calls[0]?.[0] as unknown as { from: string; to: string; subject: string; html: string };
        expect(mail.from).toBe('noreply@medflow.io');
        expect(mail.to).toBe('patient-patient-1@example.com');
        expect(mail.subject).toContain('booked');
        expect(mail.html).toContain(EVENT.appointmentId);
        expect(mail.html).toContain(EVENT.status);
    });

    it('sends a cancellation email referencing the appointment id', async () => {
        sendMail.mockResolvedValue(undefined);
        const notifier = new EmailNotifier(SMTP_CONFIG);

        await notifier.sendAppointmentCancelled(EVENT);

        expect(sendMail).toHaveBeenCalledTimes(1);
        const mail = sendMail.mock.calls[0]?.[0] as unknown as { subject: string; html: string };
        expect(mail.subject).toContain('cancelled');
        expect(mail.html).toContain(EVENT.appointmentId);
    });

    it('propagates the error when the SMTP transporter rejects, instead of swallowing it', async () => {
        sendMail.mockRejectedValue(new Error('SMTP connection refused'));
        const notifier = new EmailNotifier(SMTP_CONFIG);

        await expect(notifier.sendAppointmentCreated(EVENT)).rejects.toThrow('SMTP connection refused');
    });

    it('never logs the recipient email address (GDPR: patientEmail is intentionally omitted)', async () => {
        sendMail.mockResolvedValue(undefined);
        const infoSpy = jest.spyOn(logger, 'info').mockImplementation(() => undefined as never);
        const notifier = new EmailNotifier(SMTP_CONFIG);

        await notifier.sendAppointmentCreated(EVENT);

        expect(infoSpy).toHaveBeenCalled();
        const loggedPayload = JSON.stringify(infoSpy.mock.calls[0]);
        expect(loggedPayload).not.toContain('@example.com');
        infoSpy.mockRestore();
    });

    it('verifyConnection delegates to the transporter', async () => {
        verify.mockResolvedValue(true);
        const notifier = new EmailNotifier(SMTP_CONFIG);

        await expect(notifier.verifyConnection()).resolves.toBeUndefined();
        expect(verify).toHaveBeenCalledTimes(1);
    });
});