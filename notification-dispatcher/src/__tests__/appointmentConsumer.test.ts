import { AppointmentConsumer } from '../consumer/appointmentConsumer';
import type { AppointmentEvent, AppointmentStatus } from '../types/appointment';

// ── Mocks ─────────────────────────────────────────────────────────────────────

jest.mock('../logger', () => ({
  logger: { info: jest.fn(), debug: jest.fn(), warn: jest.fn(), error: jest.fn() },
}));

const mockKafkaConsumer = {
  connect:    jest.fn().mockResolvedValue(undefined),
  subscribe:  jest.fn().mockResolvedValue(undefined),
  run:        jest.fn().mockResolvedValue(undefined),
  disconnect: jest.fn().mockResolvedValue(undefined),
};

jest.mock('kafkajs', () => ({
  Kafka: jest.fn().mockImplementation(() => ({
    consumer: jest.fn().mockReturnValue(mockKafkaConsumer),
  })),
}));

// ── Helpers ───────────────────────────────────────────────────────────────────

const { Kafka } = jest.requireMock('kafkajs');

const makeConsumer = (emailOverrides = {}, pushOverrides = {}) => {
  const email = {
    sendAppointmentCreated:   jest.fn().mockResolvedValue(undefined),
    sendAppointmentCancelled: jest.fn().mockResolvedValue(undefined),
    verifyConnection:         jest.fn().mockResolvedValue(undefined),
    ...emailOverrides,
  };
  const push = {
    sendAppointmentCreated:   jest.fn().mockResolvedValue(undefined),
    sendAppointmentCancelled: jest.fn().mockResolvedValue(undefined),
    ...pushOverrides,
  };

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const consumer = new AppointmentConsumer(new Kafka({}), 'test-group', email as any, push as any);
  return { consumer, email, push };
};

const sampleEvent: AppointmentEvent = {
  appointmentId:  '550e8400-e29b-41d4-a716-446655440001',
  tenantId:       '00000000-0000-0000-0000-000000000001',
  patientId:      '00000000-0000-0000-0000-000000000010',
  professionalId: '00000000-0000-0000-0000-000000000020',
  slotId:         '00000000-0000-0000-0000-000000000030',
  status:         'SCHEDULED',
  occurredAt:     new Date().toISOString(),
};

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('AppointmentConsumer', () => {
  beforeEach(() => jest.clearAllMocks());

  describe('start', () => {
    it('connects consumer and subscribes to topic', async () => {
      const {consumer} = makeConsumer();
      await consumer.start('medflow.appointments');

      expect(mockKafkaConsumer.connect).toHaveBeenCalledTimes(1);
      expect(mockKafkaConsumer.subscribe).toHaveBeenCalledWith({
        topic: 'medflow.appointments',
        fromBeginning: false,
      });
      expect(mockKafkaConsumer.run).toHaveBeenCalledTimes(1);
    });

    it('processes a valid message end-to-end via eachMessage callback', async () => {
      const {consumer, email} = makeConsumer();

      let eachMessage!: (payload: any) => Promise<void>;
      mockKafkaConsumer.run.mockImplementationOnce(async ({eachMessage: fn}: any) => {
        eachMessage = fn;
      });

      await consumer.start('medflow.appointments');

      await eachMessage({
        topic: 'medflow.appointments',
        partition: 0,
        message: {
          value: Buffer.from(JSON.stringify(sampleEvent)),
          headers: {eventType: Buffer.from('appointment.created')},
          offset: '0',
        },
        heartbeat: jest.fn(),
        pause: jest.fn(),
      });

      expect(email.sendAppointmentCreated).toHaveBeenCalledWith(
          expect.objectContaining({appointmentId: sampleEvent.appointmentId}),
      );
    });

    it('skips processing when message value is null', async () => {
      const {consumer, email} = makeConsumer();

      let eachMessage!: (payload: any) => Promise<void>;
      mockKafkaConsumer.run.mockImplementationOnce(async ({eachMessage: fn}: any) => {
        eachMessage = fn;
      });

      await consumer.start('medflow.appointments');

      await eachMessage({
        topic: 'medflow.appointments',
        partition: 0,
        message: {value: null, headers: {}, offset: '0'},
        heartbeat: jest.fn(),
        pause: jest.fn(),
      });

      expect(email.sendAppointmentCreated).not.toHaveBeenCalled();
    });

    it('skips duplicate events — deduplication cache prevents double delivery', async () => {
      const {consumer, email} = makeConsumer();

      let eachMessage!: (payload: any) => Promise<void>;
      mockKafkaConsumer.run.mockImplementationOnce(async ({eachMessage: fn}: any) => {
        eachMessage = fn;
      });

      await consumer.start('medflow.appointments');

      const message = {
        topic: 'medflow.appointments',
        partition: 0,
        message: {
          value: Buffer.from(JSON.stringify(sampleEvent)),
          headers: {eventType: Buffer.from('appointment.created')},
          offset: '0',
        },
        heartbeat: jest.fn(),
        pause: jest.fn(),
      };

      await eachMessage(message);   // primeira entrega — processa
      await eachMessage(message);   // segunda entrega — dedup ignora

      expect(email.sendAppointmentCreated).toHaveBeenCalledTimes(1); // só uma vez
    });

    it('logs error and returns when message is not valid JSON', async () => {
      const {consumer, email} = makeConsumer();

      let eachMessage!: (payload: any) => Promise<void>;
      mockKafkaConsumer.run.mockImplementationOnce(async ({eachMessage: fn}: any) => {
        eachMessage = fn;
      });

      await consumer.start('medflow.appointments');

      await eachMessage({
        topic: 'medflow.appointments',
        partition: 0,
        message: {
          value: Buffer.from('not-valid-json'),
          headers: {eventType: Buffer.from('appointment.created')},
          offset: '0',
        },
        heartbeat: jest.fn(),
        pause: jest.fn(),
      });

      expect(email.sendAppointmentCreated).not.toHaveBeenCalled();
    });
  });

  describe('dispatch — happy paths', () => {
    it('sends email AND push in parallel for appointment.created', async () => {
      const {consumer, email, push} = makeConsumer();
      await (consumer as any).dispatch('appointment.created', sampleEvent);

      expect(email.sendAppointmentCreated).toHaveBeenCalledWith(sampleEvent);
      expect(push.sendAppointmentCreated).toHaveBeenCalledWith(sampleEvent);
      expect(email.sendAppointmentCreated).toHaveBeenCalledTimes(1);
    });

    it('sends cancellation to both channels for appointment.cancelled', async () => {
      const {consumer, email, push} = makeConsumer();
      const cancelled: AppointmentEvent = {...sampleEvent, status: 'CANCELLED' as AppointmentStatus};
      await (consumer as any).dispatch('appointment.cancelled', cancelled);

      expect(email.sendAppointmentCancelled).toHaveBeenCalledWith(cancelled);
      expect(push.sendAppointmentCancelled).toHaveBeenCalledWith(cancelled);
    });

    it('does not throw when only email fails — partial delivery acceptable', async () => {
      const {consumer} = makeConsumer({
        sendAppointmentCreated: jest.fn().mockRejectedValue(new Error('SMTP timeout')),
      });
      await expect(
          (consumer as any).dispatch('appointment.created', sampleEvent),
      ).resolves.toBeUndefined();
    });

    it('does not throw when only push fails — partial delivery acceptable', async () => {
      const {consumer} = makeConsumer({}, {
        sendAppointmentCreated: jest.fn().mockRejectedValue(new Error('FCM 503')),
      });
      await expect(
          (consumer as any).dispatch('appointment.created', sampleEvent),
      ).resolves.toBeUndefined();
    });
  });

  describe('dispatch — both channels fail', () => {
    it('throws when BOTH channels fail', async () => {
      const {consumer} = makeConsumer(
          {sendAppointmentCreated: jest.fn().mockRejectedValue(new Error('SMTP timeout'))},
          {sendAppointmentCreated: jest.fn().mockRejectedValue(new Error('FCM 503'))},
      );
      await expect(
          (consumer as any).dispatch('appointment.created', sampleEvent),
      ).rejects.toThrow('Both notification channels failed');
    });
  });

  describe('processMessage — error handling', () => {
    it('rethrows when both channels fail during message processing', async () => {
      const {consumer} = makeConsumer(
          {
            sendAppointmentCreated: jest.fn().mockRejectedValue(new Error('SMTP')),
          },
          {
            sendAppointmentCreated: jest.fn().mockRejectedValue(new Error('FCM')),
          },
      );

      let eachMessage!: (payload: any) => Promise<void>;

      mockKafkaConsumer.run.mockImplementationOnce(async ({eachMessage: fn}: any) => {
        eachMessage = fn;
      });

      await consumer.start('medflow.appointments');

      await expect(
          eachMessage({
            topic: 'medflow.appointments',
            partition: 0,
            message: {
              value: Buffer.from(JSON.stringify(sampleEvent)),
              headers: {
                eventType: Buffer.from('appointment.created'),
              },
              offset: '0',
            },
            heartbeat: jest.fn(),
            pause: jest.fn(),
          }),
      ).rejects.toThrow('Both notification channels failed');
    });
  });

  describe('dispatchEmail', () => {
    it('ignores unknown email event', async () => {
      const {consumer, email} = makeConsumer();

      await (consumer as any).dispatchEmail(
          'appointment.xyz',
          sampleEvent,
      );

      expect(email.sendAppointmentCreated).not.toHaveBeenCalled();
      expect(email.sendAppointmentCancelled).not.toHaveBeenCalled();
    });
  });

  describe('dispatchPush', () => {
    it('ignores unknown push event', async () => {
      const {consumer, push} = makeConsumer();

      await (consumer as any).dispatchPush(
          'appointment.xyz',
          sampleEvent,
      );

      expect(push.sendAppointmentCreated).not.toHaveBeenCalled();
      expect(push.sendAppointmentCancelled).not.toHaveBeenCalled();
    });
  });

  describe('inferEventType', () => {
    it.each([
      ['SCHEDULED', 'appointment.created'],
      ['CANCELLED', 'appointment.cancelled'],
      ['CONFIRMED', 'appointment.confirmed'],
      ['COMPLETED', 'appointment.completed'],
    ])('maps %s correctly', (status, expected) => {
      const {consumer} = makeConsumer();

      const result = (consumer as any).inferEventType({
        ...sampleEvent,
        status,
      });

      expect(result).toBe(expected);
    });

    it('returns unknown for unsupported status', () => {
      const {consumer} = makeConsumer();

      const result = (consumer as any).inferEventType({
        ...sampleEvent,
        status: 'WHATEVER',
      });

      expect(result).toBe('appointment.unknown');
    });
  });

  describe('shutdown', () => {
    it('disconnects kafka consumer', async () => {
      const {consumer} = makeConsumer();

      await consumer.shutdown();

      expect(mockKafkaConsumer.disconnect).toHaveBeenCalledTimes(1);
    });
  });
});
