# Feature Flags Service

Flag evaluation, user targeting, and rollout management.

## Service Overview

The Feature Flags Service provides feature management capabilities:
- Flag evaluation for user/request context
- User and segment targeting rules
- Percentage rollouts and schedules
- Optional bidirectional sync with LaunchDarkly
- Admin API for flag CRUD operations

## Local Development

### Prerequisites

- Clojure CLI (1.11+)
- Java 21+
- PostgreSQL 15+ (for flag persistence)
- Redis (for flag cache)
- Docker (optional, for dependencies)

### Running Locally

```bash
# Start dependencies
docker compose up -d postgres redis

# Run database migrations
clj -M:migrate

# Run the service
clj -M:dev -m feature-flags.core

# Run with REPL
clj -M:dev:nrepl
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `PORT` | HTTP server port | `8087` |
| `DATABASE_URL` | PostgreSQL connection string | Required |
| `REDIS_URL` | Redis connection string | `redis://localhost:6379` |
| `LAUNCHDARKLY_SDK_KEY` | LaunchDarkly SDK key | Optional |
| `LAUNCHDARKLY_SYNC_ENABLED` | Enable LD sync | `false` |
| `TRACE_ENABLED` | Enable trace propagation | `true` |

### Running Tests

```bash
clj -M:test
```

## API Endpoints Summary

### Evaluation

| Method | Path | Description |
|--------|------|-------------|
| POST | `/evaluate` | Evaluate single flag |
| POST | `/evaluate/batch` | Evaluate multiple flags |
| POST | `/evaluate/all` | Evaluate all flags for context |

### Admin

| Method | Path | Description |
|--------|------|-------------|
| GET | `/flags` | List all flags |
| POST | `/flags` | Create flag |
| GET | `/flags/:key` | Get flag |
| PUT | `/flags/:key` | Update flag |
| DELETE | `/flags/:key` | Archive flag |
| GET | `/segments` | List segments |
| POST | `/segments` | Create segment |
| PUT | `/segments/:id` | Update segment |

### Rollouts

| Method | Path | Description |
|--------|------|-------------|
| POST | `/flags/:key/rollout` | Set rollout percentage |
| POST | `/flags/:key/schedule` | Schedule rollout |

## Flag Evaluation

### Request

```json
{
  "flag_key": "new-checkout-flow",
  "context": {
    "user_id": "user-123",
    "email": "user@example.com",
    "country": "US",
    "plan": "premium"
  }
}
```

### Response

```json
{
  "flag_key": "new-checkout-flow",
  "value": true,
  "variation": "treatment",
  "reason": "rule_match",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

## Dependencies

### External Systems

| System | Protocol | Purpose |
|--------|----------|---------|
| PostgreSQL | JDBC | Flag and segment persistence |
| Redis | TCP | Flag evaluation cache |
| LaunchDarkly | HTTPS | External sync (optional) |

## Trace Propagation

The Feature Flags Service is a **non-ingress** service. It:
1. Extracts `traceparent` from HTTP headers (from API Gateway, services)
2. Includes trace_id in evaluation response for correlation
3. Logs trace context with flag evaluations for debugging

Configuration in `.trace-spine.yaml`:
```yaml
version: 1
service_name: feature-flags
ingress_designated: false
crossings:
  http_inbound:
    - path: /evaluate/**
      framework: ring
    - path: /flags/**
      framework: ring
    - path: /segments/**
      framework: ring
  http_outbound:
    - target: launchdarkly
      exempt: true
      exemption_adr: ADR-0006
```

## Related Documentation

- [C4 Architecture](/docs/architecture/c4/system-landscape.org) - Feature Flags component diagram
- [spec/L1-contracts.org](/spec/L1-contracts.org) - extract/inject contracts
- [ADR-0002](/docs/architecture/adr/0002-w3c-trace-context-version-00.md) - W3C Trace Context
