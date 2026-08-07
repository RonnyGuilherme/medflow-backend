import { AppointmentConsumer } from '../consumer/appointmentConsumer';
import type { AppointmentEvent, AppointmentStatus } from '../types/appointment';
import type { Kafka as KafkaType, EachMessagePayload, ConsumerRunConfig } from 'kafkajs';
import type { EmailNotifier as RealEmailNotifier } from '../notifier/emailNotifier';
import type { PushNotifier as RealPushNotifier } from '../notifier/pushNotifier';
import { logger } from '../logger';
import * as kafkajs from 'kafkajs';

// ── Mocks ────────────────────────────────────────────────────────────
jest.mock('../logger', () => ({
  logger: {
    info: jest.fn(),
    debug: jest.fn(),
    warn: jest.fn(),
    error: jest.fn(),
  },
}));

jest.mock('kafkajs', () => ({
  Kafka: jest.fn(),
}));

const { Kafka: KafkaMock } = kafkajs as unknown as { Kafka: jest.Mock };

// Criação dos mocks SEM encadear .mockResolvedValue (evita unsafe assignment)
const mockConnect = jest.fn<Promise<void>, []>();
const mockSubscribe = jest.fn<Promise<void>, [{ topic: string; fromBeginning?: boolean }]>();
const mockRun = jest.fn<Promise<void>, [ConsumerRunConfig]>();
const mockDisconnect = jest.fn<Promise<void>, []>();

const mockConsumer = {
  connect: mockConnect,
  subscribe: mockSubscribe,
  run: mockRun,
  disconnect: mockDisconnect,
};

const kafkaInstanceMock = {
  consumer: jest.fn().mockReturnValue(mockConsumer),
};

KafkaMock.mockImplementation(() => kafkaInstanceMock);

const mockLogger = logger as jest.Mocked<typeof logger>;

// ── Helpers ────────────────────────────────────────────────────────────
type MockedEmailNotifier = jest.Mocked<RealEmailNotifier>;
type MockedPushNotifier = jest.Mocked<RealPushNotifier>;

const makeConsumer = (
    emailOverrides: Partial<Record<keyof RealEmailNotifier, jest.Mock>> = {},
    pushOverrides: Partial<Record<keyof RealPushNotifier, jest.Mock>> = {},
) => {
  const email = {
    sendAppointmentCreated: jest.fn().mockResolvedValue(undefined),
    sendAppointmentCancelled: jest.fn().mockResolvedValue(undefined),
    verifyConnection: jest.fn().mockResolvedValue(undefined),
    ...emailOverrides,
  };

  const push = {
    sendAppointmentCreated: jest.fn().mockResolvedValue(undefined),
    sendAppointmentCancelled: jest.fn().mockResolvedValue(undefined),
    ...pushOverrides,
  };

  const consumer = new AppointmentConsumer(
      kafkaInstanceMock as unknown as KafkaType,
      'test-group',
      email as unknown as RealEmailNotifier,
      push as unknown as RealPushNotifier,
  );

  return {
    consumer,
    email: email as unknown as MockedEmailNotifier,
    push: push as unknown as MockedPushNotifier,
  };
};

const sampleEvent: AppointmentEvent = {
  appointmentId: '550e8400-e29b-41d4-a716-446655440001',
  tenantId: '00000000-0000-0000-0000-000000000001',
  patientId: '00000000-0000-0000-0000-000000000010',
  professionalId: '00000000-0000-0000-0000-000000000020',
  slotId: '00000000-0000-0000-0000-000000000030',
  status: 'SCHEDULED',
  occurredAt: new Date().toISOString(),
};

function captureEachMessage() {
  let eachMessage!: (payload: EachMessagePayload) => Promise<void>;

  mockRun.mockImplementationOnce(async (opts: ConsumerRunConfig) => {
    if (opts.eachMessage) {
      eachMessage = opts.eachMessage;
    }
  });

  return { getEachMessage: () => eachMessage };
}

const buildPayload = (
    value: Buffer | null,
    headers: Record<string, Buffer | string | undefined> = {},
): EachMessagePayload =>
    ({
      topic: 'medflow.appointments',
      partition: 0,
      message: { value, headers, offset: '0' },
      heartbeat: jest.fn(),
      pause: jest.fn(),
    }) as unknown as EachMessagePayload;

// ── Tests ────────────────────────────────────────────────────────────
describe('AppointmentConsumer', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockConnect.mockResolvedValue(undefined);
    mockSubscribe.mockResolvedValue(undefined);
    mockRun.mockResolvedValue(undefined);
    mockDisconnect.mockResolvedValue(undefined);
  });

  describe('start', () => {
    it('connects consumer and subscribes to topic', async () => {
      const { consumer } = makeConsumer();
      await consumer.start('medflow.appointments');

      expect(mockConnect.mock.calls).toHaveLength(1);
      expect(mockSubscribe.mock.calls[0][0]).toEqual({
        topic: 'medflow.appointments',
        fromBeginning: false,
      });
      expect(mockRun.mock.calls).toHaveLength(1);
    });

    it('processes a valid message end-to-end via eachMessage callback', async () => {
      const { consumer, email } = makeConsumer();
      const { getEachMessage } = captureEachMessage();
      await consumer.start('medflow.appointments');
      const eachMessage = getEachMessage();

      const payload = buildPayload(
          Buffer.from(JSON.stringify(sampleEvent)),
          { eventType: Buffer.from('appointment.created') },
      );

      await eachMessage(payload);

      expect(email.sendAppointmentCreated.mock.calls).toHaveLength(1);
      expect(email.sendAppointmentCreated.mock.calls[0][0]).toEqual(
          expect.objectContaining({ appointmentId: sampleEvent.appointmentId }),
      );
    });

    it('skips processing when message value is null', async () => {
      const { consumer, email } = makeConsumer();
      const { getEachMessage } = captureEachMessage();
      await consumer.start('medflow.appointments');
      const eachMessage = getEachMessage();

      await eachMessage(buildPayload(null));

      expect(email.sendAppointmentCreated.mock.calls).toHaveLength(0);
      expect(mockLogger.warn.mock.calls.length).toBeGreaterThanOrEqual(1);
    });

    it('skips duplicate events — deduplication cache prevents double delivery', async () => {
      const { consumer, email } = makeConsumer();
      const { getEachMessage } = captureEachMessage();
      await consumer.start('medflow.appointments');
      const eachMessage = getEachMessage();

      const payload = buildPayload(
          Buffer.from(JSON.stringify(sampleEvent)),
          { eventType: Buffer.from('appointment.created') },
      );

      await eachMessage(payload);
      await eachMessage(payload);

      expect(email.sendAppointmentCreated.mock.calls).toHaveLength(1);
      expect(mockLogger.debug.mock.calls.length).toBeGreaterThanOrEqual(1);
    });

    it('logs error and returns when message is not valid JSON', async () => {
      const { consumer, email } = makeConsumer();
      const { getEachMessage } = captureEachMessage();
      await consumer.start('medflow.appointments');
      const eachMessage = getEachMessage();

      const payload = buildPayload(
          Buffer.from('not-valid-json'),
          { eventType: Buffer.from('appointment.created') },
      );

      await eachMessage(payload);

      expect(email.sendAppointmentCreated.mock.calls).toHaveLength(0);
      expect(mockLogger.error.mock.calls.length).toBeGreaterThanOrEqual(1);
    });
  });

  describe('dispatch flows via processMessage', () => {
    it('sends email AND push in parallel for appointment.created', async () => {
      const { consumer, email, push } = makeConsumer();
      const { getEachMessage } = captureEachMessage();
      await consumer.start('medflow.appointments');
      const eachMessage = getEachMessage();

      const payload = buildPayload(
          Buffer.from(JSON.stringify(sampleEvent)),
          { eventType: Buffer.from('appointment.created') },
      );

      await eachMessage(payload);

      expect(email.sendAppointmentCreated.mock.calls).toHaveLength(1);
      expect(push.sendAppointmentCreated.mock.calls).toHaveLength(1);
    });

    it('sends cancellation to both channels for appointment.cancelled', async () => {
      const { consumer, email, push } = makeConsumer();
      const cancelled = { ...sampleEvent, status: 'CANCELLED' as AppointmentStatus };
      const { getEachMessage } = captureEachMessage();
      await consumer.start('medflow.appointments');
      const eachMessage = getEachMessage();

      const payload = buildPayload(
          Buffer.from(JSON.stringify(cancelled)),
          { eventType: Buffer.from('appointment.cancelled') },
      );

      await eachMessage(payload);

      expect(email.sendAppointmentCancelled.mock.calls).toHaveLength(1);
      expect(push.sendAppointmentCancelled.mock.calls).toHaveLength(1);
    });

    it('does not throw when only email fails — partial delivery acceptable', async () => {
      const { consumer } = makeConsumer({
        sendAppointmentCreated: jest.fn().mockRejectedValue(new Error('SMTP timeout')),
      });
      const { getEachMessage } = captureEachMessage();
      await consumer.start('medflow.appointments');
      const eachMessage = getEachMessage();

      const payload = buildPayload(
          Buffer.from(JSON.stringify(sampleEvent)),
          { eventType: Buffer.from('appointment.created') },
      );

      await expect(eachMessage(payload)).resolves.toBeUndefined();
      expect(mockLogger.error.mock.calls.length).toBeGreaterThanOrEqual(1);
    });

    it('does not throw when only push fails — partial delivery acceptable', async () => {
      const { consumer } = makeConsumer({}, {
        sendAppointmentCreated: jest.fn().mockRejectedValue(new Error('FCM 503')),
      });
      const { getEachMessage } = captureEachMessage();
      await consumer.start('medflow.appointments');
      const eachMessage = getEachMessage();

      const payload = buildPayload(
          Buffer.from(JSON.stringify(sampleEvent)),
          { eventType: Buffer.from('appointment.created') },
      );

      await expect(eachMessage(payload)).resolves.toBeUndefined();
      expect(mockLogger.error.mock.calls.length).toBeGreaterThanOrEqual(1);
    });
  });

  describe('both channels fail during message processing', () => {
    it('rethrows when both channels fail during message processing', async () => {
      const { consumer } = makeConsumer(
          { sendAppointmentCreated: jest.fn().mockRejectedValue(new Error('SMTP')) },
          { sendAppointmentCreated: jest.fn().mockRejectedValue(new Error('FCM')) },
      );
      const { getEachMessage } = captureEachMessage();
      await consumer.start('medflow.appointments');
      const eachMessage = getEachMessage();

      const payload = buildPayload(
          Buffer.from(JSON.stringify(sampleEvent)),
          { eventType: Buffer.from('appointment.created') },
      );

      await expect(eachMessage(payload)).rejects.toThrow('Both notification channels failed');
      expect(mockLogger.error.mock.calls.length).toBeGreaterThanOrEqual(1);
    });
  });

  describe('unknown events are ignored by channel-specific dispatchers', () => {
    it('email dispatcher ignores unknown event', async () => {
      const { consumer, email } = makeConsumer();
      const { getEachMessage } = captureEachMessage();
      await consumer.start('medflow.appointments');
      const eachMessage = getEachMessage();

      const payload = buildPayload(
          Buffer.from(JSON.stringify(sampleEvent)),
          { eventType: Buffer.from('appointment.xyz') },
      );

      await eachMessage(payload);

      expect(email.sendAppointmentCreated.mock.calls).toHaveLength(0);
      expect(email.sendAppointmentCancelled.mock.calls).toHaveLength(0);
      expect(mockLogger.debug.mock.calls.length).toBeGreaterThanOrEqual(1);
    });

    it('push dispatcher ignores unknown event', async () => {
      const { consumer, push } = makeConsumer();
      const { getEachMessage } = captureEachMessage();
      await consumer.start('medflow.appointments');
      const eachMessage = getEachMessage();

      const payload = buildPayload(
          Buffer.from(JSON.stringify(sampleEvent)),
          { eventType: Buffer.from('appointment.xyz') },
      );

      await eachMessage(payload);

      expect(push.sendAppointmentCreated.mock.calls).toHaveLength(0);
      expect(push.sendAppointmentCancelled.mock.calls).toHaveLength(0);
    });
  });

  describe('inferEventType via processMessage (status→event mapping)', () => {
    it.each([
      ['SCHEDULED', 'appointment.created'],
      ['CANCELLED', 'appointment.cancelled'],
      ['CONFIRMED', 'appointment.confirmed'],
      ['COMPLETED', 'appointment.completed'],
    ])('maps %s correctly', async (status, expected) => {
      const { consumer, email } = makeConsumer();
      const event = { ...sampleEvent, status: status as AppointmentStatus };
      const { getEachMessage } = captureEachMessage();
      await consumer.start('medflow.appointments');
      const eachMessage = getEachMessage();

      const payload = buildPayload(Buffer.from(JSON.stringify(event)));
      await eachMessage(payload);

      if (expected === 'appointment.created') {
        expect(email.sendAppointmentCreated.mock.calls).toHaveLength(1);
      } else if (expected === 'appointment.cancelled') {
        expect(email.sendAppointmentCancelled.mock.calls).toHaveLength(1);
      } else {
        expect(mockLogger.debug.mock.calls.length).toBeGreaterThanOrEqual(1);
      }
    });

    it('returns unknown for unsupported status (no handlers invoked)', async () => {
      const { consumer, email, push } = makeConsumer();
      const event = { ...sampleEvent, status: 'WHATEVER' as AppointmentStatus };
      const { getEachMessage } = captureEachMessage();
      await consumer.start('medflow.appointments');
      const eachMessage = getEachMessage();

      const payload = buildPayload(Buffer.from(JSON.stringify(event)));
      await eachMessage(payload);

      expect(email.sendAppointmentCreated.mock.calls).toHaveLength(0);
      expect(push.sendAppointmentCreated.mock.calls).toHaveLength(0);
      expect(mockLogger.debug.mock.calls.length).toBeGreaterThanOrEqual(1);
    });
  });

  describe('shutdown', () => {
    it('disconnects kafka consumer', async () => {
      const { consumer } = makeConsumer();
      await consumer.shutdown();
      expect(mockDisconnect.mock.calls).toHaveLength(1);
    });
  });
});