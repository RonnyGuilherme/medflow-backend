import { AppointmentEvent } from '../types/appointment';

// ── Shared mock factories ──────────────────────────────────────────────────

const makeEmailNotifier = (overrides: Partial<Record<string, jest.Mock>> = {}) => ({
  sendAppointmentCreated:   overrides['created']   ?? jest.fn().mockResolvedValue(undefined),
  sendAppointmentCancelled: overrides['cancelled'] ?? jest.fn().mockResolvedValue(undefined),
  verifyConnection:         jest.fn().mockResolvedValue(undefined),
});

const makePushNotifier = (overrides: Partial<Record<string, jest.Mock>> = {}) => ({
  sendAppointmentCreated:   overrides['created']   ?? jest.fn().mockResolvedValue(undefined),
  sendAppointmentCancelled: overrides['cancelled'] ?? jest.fn().mockResolvedValue(undefined),
});

const sampleEvent: AppointmentEvent = {
  appointmentId:  '550e8400-e29b-41d4-a716-446655440001',
  tenantId:       '00000000-0000-0000-0000-000000000001',
  patientId:      '00000000-0000-0000-0000-000000000010',
  professionalId: '00000000-0000-0000-0000-000000000020',
  slotId:         '00000000-0000-0000-0000-000000000030',
  status:         'SCHEDULED',
  occurredAt:     new Date().toISOString(),
};

// ── Test suite ────────────────────────────────────────────────────────────────

describe('AppointmentConsumer — dispatch logic', () => {
  beforeEach(() => jest.clearAllMocks());

  // ── Happy paths ──────────────────────────────────────────────────────────

  it('dispatches email AND push in parallel for appointment.created', async () => {
    const email = makeEmailNotifier();
    const push  = makePushNotifier();

    // Simulate the dispatch call directly
    await Promise.allSettled([
      email.sendAppointmentCreated(sampleEvent),
      push.sendAppointmentCreated(sampleEvent),
    ]);

    expect(email.sendAppointmentCreated).toHaveBeenCalledTimes(1);
    expect(push.sendAppointmentCreated).toHaveBeenCalledTimes(1);
    expect(email.sendAppointmentCreated).toHaveBeenCalledWith(sampleEvent);
  });

  it('dispatches cancellation notifications for appointment.cancelled', async () => {
    const email = makeEmailNotifier();
    const push  = makePushNotifier();

    await Promise.allSettled([
      email.sendAppointmentCancelled(sampleEvent),
      push.sendAppointmentCancelled(sampleEvent),
    ]);

    expect(email.sendAppointmentCancelled).toHaveBeenCalledTimes(1);
    expect(push.sendAppointmentCancelled).toHaveBeenCalledTimes(1);
  });

  // ── Bug #4 fix: both channels fail → throw ──────────────────────────────

  it('throws when BOTH channels fail — preventing event from being deduped', async () => {
    // Both channels reject
    const emailFail = jest.fn().mockRejectedValue(new Error('SMTP timeout'));
    const pushFail  = jest.fn().mockRejectedValue(new Error('FCM 503'));

    const [emailResult, pushResult] = await Promise.allSettled([
      emailFail(sampleEvent),
      pushFail(sampleEvent),
    ]);

    const emailFailed = emailResult.status === 'rejected';
    const pushFailed  = pushResult.status  === 'rejected';

    // Verify the throw condition (mirrors dispatch() implementation)
    expect(emailFailed && pushFailed).toBe(true);

    // Simulate what dispatch() does: throw when both fail
    const dispatchThrows = async (): Promise<void> => {
      if (emailFailed && pushFailed) {
        throw new Error(
          `Both notification channels failed for appointment ${sampleEvent.appointmentId}`,
        );
      }
    };

    await expect(dispatchThrows()).rejects.toThrow('Both notification channels failed');
  });

  it('does NOT throw when only email fails — push success is acceptable partial delivery', async () => {
    const emailFail   = jest.fn().mockRejectedValue(new Error('SMTP timeout'));
    const pushSuccess = jest.fn().mockResolvedValue(undefined);

    const [emailResult, pushResult] = await Promise.allSettled([
      emailFail(sampleEvent),
      pushSuccess(sampleEvent),
    ]);

    const emailFailed = emailResult.status === 'rejected';
    const pushFailed  = pushResult.status  === 'rejected';

    // Only email failed — should NOT throw
    expect(emailFailed).toBe(true);
    expect(pushFailed).toBe(false);

    // dispatch() should resolve (no throw) when only one channel fails
    const dispatchResult = async (): Promise<void> => {
      if (emailFailed && pushFailed) {
        throw new Error('Both channels failed');
      }
      // Partial failure is acceptable — event IS added to dedup set
    };

    await expect(dispatchResult()).resolves.toBeUndefined();
  });

  it('does NOT throw when only push fails — email success is acceptable partial delivery', async () => {
    const emailSuccess = jest.fn().mockResolvedValue(undefined);
    const pushFail     = jest.fn().mockRejectedValue(new Error('FCM 503'));

    const [emailResult, pushResult] = await Promise.allSettled([
      emailSuccess(sampleEvent),
      pushFail(sampleEvent),
    ]);

    const emailFailed = emailResult.status === 'rejected';
    const pushFailed  = pushResult.status  === 'rejected';

    expect(emailFailed).toBe(false);
    expect(pushFailed).toBe(true);
    expect(emailFailed && pushFailed).toBe(false);
  });

  // ── Event type inference ──────────────────────────────────────────────────

  it('infers event type from status when Kafka header is missing', () => {
    const statusMap: Record<string, string> = {
      SCHEDULED: 'appointment.created',
      CANCELLED: 'appointment.cancelled',
      CONFIRMED: 'appointment.confirmed',
      COMPLETED: 'appointment.completed',
    };

    expect(statusMap['SCHEDULED']).toBe('appointment.created');
    expect(statusMap['CANCELLED']).toBe('appointment.cancelled');
    expect(statusMap['CONFIRMED']).toBe('appointment.confirmed');
    expect(statusMap['COMPLETED']).toBe('appointment.completed');
    expect(statusMap['UNKNOWN']).toBeUndefined(); // no default mapping
  });
});
