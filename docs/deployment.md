# Production Deployment Notes

## Kong JWT Secret

In `kong/kong.yml`, the JWT secret is hard-coded to a dev value.
For production, use one of these approaches:

### Option A: deck env-var templating (recommended)
```bash
# Install deck: https://docs.konghq.com/deck/
KONG_JWT_SECRET=$(vault read -field=jwt_secret secret/medflow) \
  deck sync --kong-addr http://kong:8001
```

### Option B: Kubernetes Secret + init container
```yaml
initContainers:
  - name: kong-config
    image: alpine
    command: ["sh", "-c", "envsubst < /templates/kong.yml > /kong/kong.yml"]
    env:
      - name: JWT_SECRET
        valueFrom:
          secretKeyRef:
            name: medflow-secrets
            key: jwt-secret
```

## GDPR — Data Retention

Configure Kafka topic retention before going live:
```bash
kafka-configs.sh --bootstrap-server kafka:9092 \
  --alter --entity-type topics --entity-name medflow.appointments \
  --add-config retention.ms=2592000000  # 30 days
```

For audit log (compacted topic, infinite retention):
```bash
kafka-configs.sh --bootstrap-server kafka:9092 \
  --alter --entity-type topics --entity-name medflow.appointments.audit \
  --add-config cleanup.policy=compact,retention.ms=-1
```

## PostgreSQL Row Level Security (Production Hardening)

```sql
-- Enable RLS on appointments table
ALTER TABLE appointments ENABLE ROW LEVEL SECURITY;

-- Policy: only rows matching the current tenant are visible
CREATE POLICY tenant_isolation ON appointments
  USING (tenant_id = current_setting('app.current_tenant', true)::uuid  -- 'true' = return NULL if unset, not an error);
```

Then set the tenant in your DB session:
```java
// In a Spring Hibernate interceptor:
entityManager.createNativeQuery("SET app.current_tenant = ?")
    .setParameter(1, tenantId).executeUpdate();
```
