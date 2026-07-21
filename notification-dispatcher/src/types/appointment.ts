/**
 * Appointment event payload from the medflow.appointments Kafka topic.
 *
 * GDPR note: This payload intentionally contains only IDs, status, and
 * timestamps. No PHI (patient notes, clinical data) is present. The
 * Notification Dispatcher resolves patient contact details from the
 * patient service at delivery time — not stored in the event log.
 */
export interface AppointmentEvent {
  appointmentId:  string;
  tenantId:       string;
  patientId:      string;
  professionalId: string;
  slotId:         string;
  status:         AppointmentStatus;
  occurredAt:     string;
}

export type AppointmentStatus =
  | 'SCHEDULED'
  | 'CONFIRMED'
  | 'CANCELLED'
  | 'COMPLETED'
  | 'NO_SHOW';

export type EventType =
  | 'appointment.created'
  | 'appointment.cancelled'
  | 'appointment.confirmed'
  | 'appointment.completed';

export interface KafkaMessageHeaders {
  eventType?: string;
  correlationId?: string;
  tenantId?: string;
}
