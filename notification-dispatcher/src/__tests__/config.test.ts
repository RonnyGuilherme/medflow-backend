import { loadConfig } from '../config/config';

// ── Snapshot & restore process.env around every test ───────────────────
const ORIGINAL_ENV = { ...process.env };

const ENV_KEYS = [
    'PORT', 'KAFKA_BROKERS', 'KAFKA_TOPIC', 'KAFKA_GROUP_ID',
    'SMTP_HOST', 'SMTP_PORT', 'SMTP_SECURE', 'SMTP_FROM', 'FCM_SERVER_KEY',
] as const;

function clearRelevantEnv(): void {
    for (const key of ENV_KEYS) {
        delete process.env[key];
    }
}

afterEach(() => {
    process.env = { ...ORIGINAL_ENV };
});

describe('loadConfig', () => {
    it('falls back to defaults when no env vars are set', () => {
        clearRelevantEnv();

        const config = loadConfig();

        expect(config.port).toBe(3001);
        expect(config.kafkaBrokers).toEqual(['localhost:9092']);
        expect(config.kafkaTopic).toBe('medflow.appointments');
        expect(config.kafkaGroupId).toBe('notification-dispatcher-group');
        expect(config.smtp).toEqual({
            host: 'localhost',
            port: 1025,
            secure: false,
            from: 'noreply@medflow.io',
        });
        expect(config.fcm.serverKey).toBe('');
    });

    it('reads and parses values from env vars when present', () => {
        clearRelevantEnv();
        process.env['PORT'] = '4000';
        process.env['KAFKA_BROKERS'] = 'broker1:9092,broker2:9092';
        process.env['KAFKA_TOPIC'] = 'custom.topic';
        process.env['SMTP_HOST'] = 'smtp.sendgrid.net';
        process.env['SMTP_PORT'] = '587';
        process.env['SMTP_FROM'] = 'no-reply@example.com';
        process.env['FCM_SERVER_KEY'] = 'real-server-key';

        const config = loadConfig();

        expect(config.port).toBe(4000);
        expect(config.kafkaBrokers).toEqual(['broker1:9092', 'broker2:9092']);
        expect(config.kafkaTopic).toBe('custom.topic');
        expect(config.smtp.host).toBe('smtp.sendgrid.net');
        expect(config.smtp.port).toBe(587);
        expect(config.smtp.from).toBe('no-reply@example.com');
        expect(config.fcm.serverKey).toBe('real-server-key');
    });

    it('parses SMTP_SECURE=true (exact string) as true', () => {
        clearRelevantEnv();
        process.env['SMTP_SECURE'] = 'true';

        expect(loadConfig().smtp.secure).toBe(true);
    });

    it.each(['false', 'yes', '1', 'TRUE', ''])(
        'treats SMTP_SECURE=%s as false — only the exact string "true" enables it',
        (value) => {
            clearRelevantEnv();
            process.env['SMTP_SECURE'] = value;

            expect(loadConfig().smtp.secure).toBe(false);
        },
    );
});