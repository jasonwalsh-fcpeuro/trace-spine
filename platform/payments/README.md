# Payment Service

Payment orchestration with idempotency, retry logic, and multi-processor support.

## Service Overview

The Payment Service handles all payment processing for the platform:
- Payment orchestration across multiple processors (Stripe, PayPal)
- Idempotency layer preventing duplicate charges
- Retry engine with exponential backoff and circuit breaker
- Wallet/store credit application
- Fraud check integration pre-authorization
- Transactional outbox for reliable event publishing

## Local Development

### Prerequisites

- Clojure CLI (1.11+)
- Java 21+
- PostgreSQL 15+
- Kafka (for outbox drain)
- Docker (optional, for dependencies)

### Running Locally

```bash
# Start dependencies
docker compose up -d postgres kafka

# Run database migrations
clj -M:migrate

# Run the service
clj -M:dev -m payments.core

# Run with REPL
clj -M:dev:nrepl
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `PORT` | HTTP server port | `8082` |
| `DATABASE_URL` | PostgreSQL connection string | Required |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker addresses | `localhost:9092` |
| `STRIPE_API_KEY` | Stripe secret key | Required |
| `STRIPE_WEBHOOK_SECRET` | Stripe webhook signing | Required |
| `PAYPAL_CLIENT_ID` | PayPal client ID | Required |
| `PAYPAL_CLIENT_SECRET` | PayPal client secret | Required |
| `TRACE_ENABLED` | Enable trace propagation | `true` |

### Running Tests

```bash
clj -M:test
```

## API Endpoints Summary

| Method | Path | Description |
|--------|------|-------------|
| POST | `/charge` | Process payment |
| POST | `/refund` | Issue refund |
| GET | `/transactions/:id` | Get transaction |
| POST | `/webhooks/stripe` | Stripe webhook handler |
| POST | `/webhooks/paypal` | PayPal webhook handler |

## Dependencies

### Upstream Services

| Service | Protocol | Purpose |
|---------|----------|---------|
| Wallet Service | HTTP | Apply store credits |
| Fraud Service | HTTP | Pre-auth risk check |

### External Systems

| System | Protocol | Purpose |
|--------|----------|---------|
| PostgreSQL | JDBC | Transaction records, idempotency keys |
| Kafka | TCP | Outbox event publishing |
| Stripe | HTTPS | Primary payment processor |
| PayPal | HTTPS | Alternative payment processor |

## Kafka Topics

### Published (via outbox)

| Topic | Purpose |
|-------|---------|
| `payment.completed` | Payment succeeded |
| `payment.failed` | Payment failed |
| `refund.completed` | Refund issued |

## Trace Propagation

The Payment Service is a **non-ingress** service. It:
1. Extracts `traceparent` from HTTP headers (from Order Service)
2. Propagates trace context to Wallet and Fraud services
3. Records `traceparent` in outbox table for async event tracing

Configuration in `.trace-spine.yaml`:
```yaml
version: 1
service_name: payments
ingress_designated: false
crossings:
  http_inbound:
    - path: /charge
      framework: ring
    - path: /refund
      framework: ring
  http_outbound:
    - target: wallet
    - target: fraud
    - target: stripe
      exempt: true
      exemption_adr: ADR-0006
    - target: paypal
      exempt: true
      exemption_adr: ADR-0006
  outbox:
    - table: payment_outbox
```

## Related Documentation

- [C4 Architecture](/docs/architecture/c4/system-landscape.org) - Payment Service component diagram
- [spec/L1-wire.org](/spec/L1-wire.org) - Outbox table schema
- [ADR-0003](/docs/architecture/adr/0003-no-internal-origination.md) - No internal origination
