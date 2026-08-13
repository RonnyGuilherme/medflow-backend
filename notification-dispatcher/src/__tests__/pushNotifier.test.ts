import { PushNotifier } from '../notifier/pushNotifier';
import type { AppointmentEvent } from '../types/appointment';

const EVENT: AppointmentEvent = {
    appointmentId: 'apt-1',
    tenantId: 'tenant-1',
    patientId: 'patient-1',
    professionalId: 'prof-1',
    slotId: 'slot-1',
    status: 'SCHEDULED',
    occurredAt: '2026-08-10T12:00:00Z',
};

describe('PushNotifier', () => {
    let fetchMock: jest.Mock;

    beforeEach(() => {
        fetchMock = jest.fn();
        global.fetch = fetchMock as unknown as typeof fetch;
    });

    describe('when no real FCM server key is configured', () => {
        it.each([['', 'empty string'], ['dev-placeholder', 'the literal dev placeholder']])(
            'skips the HTTP call entirely for serverKey=%s (%s)',
            async (serverKey) => {
                const notifier = new PushNotifier({ serverKey });

                await notifier.sendAppointmentCreated(EVENT);

                expect(fetchMock).not.toHaveBeenCalled();
            },
        );
    });

    describe('when a real FCM server key is configured', () => {
        const notifier = () => new PushNotifier({ serverKey: 'real-server-key' });

        it('POSTs to the FCM endpoint with the auth header and a created-event payload', async () => {
            fetchMock.mockResolvedValue({ ok: true, status: 200, text: async () => '' });

            await notifier().sendAppointmentCreated(EVENT);

            expect(fetchMock).toHaveBeenCalledTimes(1);
            const [url, options] = fetchMock.mock.calls[0] as [string, { method: string; headers: Record<string, string>; body: string }];
            expect(url).toBe('https://fcm.googleapis.com/fcm/send');
            expect(options.method).toBe('POST');
            expect(options.headers['Authorization']).toBe('key=real-server-key');

            const body = JSON.parse(options.body) as { to: string; data: { type: string; appointmentId: string } };
            expect(body.to).toBe(`/topics/patient-${EVENT.patientId}`);
            expect(body.data.type).toBe('appointment.created');
            expect(body.data.appointmentId).toBe(EVENT.appointmentId);
        });

        it('sends the cancellation notification with the right type and title', async () => {
            fetchMock.mockResolvedValue({ ok: true, status: 200, text: async () => '' });

            await notifier().sendAppointmentCancelled(EVENT);

            const [, options] = fetchMock.mock.calls[0] as [string, { body: string }];
            const body = JSON.parse(options.body) as { data: { type: string }; notification: { title: string } };
            expect(body.data.type).toBe('appointment.cancelled');
            expect(body.notification.title).toBe('Appointment Cancelled');
        });

        it('never sends patient PHI beyond appointmentId in the push data payload', async () => {
            fetchMock.mockResolvedValue({ ok: true, status: 200, text: async () => '' });

            await notifier().sendAppointmentCreated(EVENT);

            const [, options] = fetchMock.mock.calls[0] as [string, { body: string }];
            const body = JSON.parse(options.body) as Record<string, unknown>;
            expect(Object.keys(body.data as Record<string, unknown>).sort()).toEqual(['appointmentId', 'type']);
        });

        it('throws with the FCM status and response body when the request is not OK', async () => {
            fetchMock.mockResolvedValue({
                ok: false,
                status: 401,
                text: async () => 'Invalid server key',
            });

            await expect(notifier().sendAppointmentCreated(EVENT)).rejects.toThrow(
                'FCM error 401: Invalid server key',
            );
        });

        it('resolves without throwing when FCM responds ok', async () => {
            fetchMock.mockResolvedValue({ ok: true, status: 200, text: async () => '' });

            await expect(notifier().sendAppointmentCreated(EVENT)).resolves.toBeUndefined();
        });
    });
});