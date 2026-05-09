# Wallet Service

Store credit, gift cards, and loyalty points management.

## Service Overview

The Wallet Service manages customer financial instruments stored on the platform:
- Store credit (refund credits, promotional credits)
- Gift card issuance and redemption
- Loyalty points accrual and redemption
- Balance inquiries and transaction history
- Bank account verification via Plaid (for cash-out)

## Local Development

### Prerequisites

- Clojure CLI (1.11+)
- Java 21+
- PostgreSQL 15+
- Docker (optional, for dependencies)

### Running Locally

```bash
# Start dependencies
docker compose up -d postgres

# Run database migrations
clj -M:migrate

# Run the service
clj -M:dev -m wallet.core

# Run with REPL
clj -M:dev:nrepl
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `PORT` | HTTP server port | `8083` |
| `DATABASE_URL` | PostgreSQL connection string | Required |
| `PLAID_CLIENT_ID` | Plaid client ID | Optional |
| `PLAID_SECRET` | Plaid secret key | Optional |
| `TRACE_ENABLED` | Enable trace propagation | `true` |

### Running Tests

```bash
clj -M:test
```

## API Endpoints Summary

| Method | Path | Description |
|--------|------|-------------|
| GET | `/wallets/:customer-id` | Get wallet summary |
| GET | `/wallets/:customer-id/balance` | Get current balance |
| POST | `/wallets/:customer-id/credit` | Add credit |
| POST | `/wallets/:customer-id/debit` | Deduct balance |
| GET | `/wallets/:customer-id/transactions` | Transaction history |
| POST | `/gift-cards` | Issue gift card |
| POST | `/gift-cards/:code/redeem` | Redeem gift card |
| GET | `/loyalty/:customer-id/points` | Get loyalty points |
| POST | `/loyalty/:customer-id/redeem` | Redeem points |

## Dependencies

### External Systems

| System | Protocol | Purpose |
|--------|----------|---------|
| PostgreSQL | JDBC | Wallet data store |
| Plaid | HTTPS | Bank account verification |

## Trace Propagation

The Wallet Service is a **non-ingress** service. It:
1. Extracts `traceparent` from HTTP headers (from Payment Service)
2. Logs trace context with all wallet operations for audit

Configuration in `.trace-spine.yaml`:
```yaml
version: 1
service_name: wallet
ingress_designated: false
crossings:
  http_inbound:
    - path: /wallets/**
      framework: ring
    - path: /gift-cards/**
      framework: ring
    - path: /loyalty/**
      framework: ring
  http_outbound:
    - target: plaid
      exempt: true
      exemption_adr: ADR-0006
```

## Related Documentation

- [C4 Architecture](/docs/architecture/c4/system-landscape.org) - Container diagram showing wallet position
- [spec/L0-claims.org](/spec/L0-claims.org) - I-spine-no-leak (trace IDs in audit logs)
- [spec/L1-wire.org](/spec/L1-wire.org) - Wire format specification
