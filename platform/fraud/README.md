# Fraud Service

Risk scoring, rule engine, and fraud detection for payment authorization.

## Service Overview

The Fraud Service provides real-time risk assessment for transactions:
- Risk scorer aggregating multiple signals into a single score
- Configurable rule engine for fraud detection
- Velocity checking (rate-based fraud detection)
- ML feature extraction for model-based scoring
- IP, email, and device blocklist management

## Local Development

### Prerequisites

- Clojure CLI (1.11+)
- Java 21+
- PostgreSQL 15+ (for rules storage)
- Redis (for velocity counters and cached blocklists)
- Docker (optional, for dependencies)

### Running Locally

```bash
# Start dependencies
docker compose up -d postgres redis

# Run database migrations
clj -M:migrate

# Run the service
clj -M:dev -m fraud.core

# Run with REPL
clj -M:dev:nrepl
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `PORT` | HTTP server port | `8085` |
| `DATABASE_URL` | PostgreSQL connection string | Required |
| `REDIS_URL` | Redis connection string | `redis://localhost:6379` |
| `ML_MODEL_PATH` | Path to ML model file | Optional |
| `TRACE_ENABLED` | Enable trace propagation | `true` |

### Running Tests

```bash
clj -M:test
```

## API Endpoints Summary

| Method | Path | Description |
|--------|------|-------------|
| POST | `/score` | Get risk score for transaction |
| GET | `/rules` | List active rules |
| POST | `/rules` | Create rule |
| PUT | `/rules/:id` | Update rule |
| DELETE | `/rules/:id` | Delete rule |
| GET | `/blocklist/:type` | Get blocklist (ip/email/device) |
| POST | `/blocklist/:type` | Add to blocklist |
| DELETE | `/blocklist/:type/:value` | Remove from blocklist |

## Risk Scoring

### Score Response

```json
{
  "score": 0.23,
  "signals": {
    "velocity": 0.1,
    "blocklist": 0.0,
    "rules": 0.05,
    "ml": 0.08
  },
  "decision": "allow",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

### Decision Thresholds

| Score Range | Decision |
|-------------|----------|
| 0.0 - 0.3 | `allow` |
| 0.3 - 0.7 | `review` |
| 0.7 - 1.0 | `deny` |

## Dependencies

### External Systems

| System | Protocol | Purpose |
|--------|----------|---------|
| PostgreSQL | JDBC | Rules storage |
| Redis | TCP | Velocity counters, blocklist cache |

## Trace Propagation

The Fraud Service is a **non-ingress** service. It:
1. Extracts `traceparent` from HTTP headers (from API Gateway or Payment Service)
2. Includes trace_id in risk response for correlation
3. Logs trace context with all scoring decisions for audit

Configuration in `.trace-spine.yaml`:
```yaml
version: 1
service_name: fraud
ingress_designated: false
crossings:
  http_inbound:
    - path: /score
      framework: ring
    - path: /rules/**
      framework: ring
    - path: /blocklist/**
      framework: ring
```

## Related Documentation

- [C4 Architecture](/docs/architecture/c4/system-landscape.org) - Fraud Service component diagram
- [spec/L0-claims.org](/spec/L0-claims.org) - I-spine-no-leak (trace IDs in responses)
- [spec/L1-contracts.org](/spec/L1-contracts.org) - extract/inject contracts
