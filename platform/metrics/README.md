# Metrics Platform

Impressions, clicks, A/B experiments, and analytics infrastructure.

## Service Overview

The Metrics Platform provides analytics and experimentation capabilities:
- Event collection (impressions, clicks, conversions)
- Real-time aggregation and rollups
- A/B experiment engine (assignment, analysis)
- Analytics query API for dashboards
- External metrics export to Datadog

## Local Development

### Prerequisites

- Clojure CLI (1.11+)
- Java 21+
- ClickHouse (for analytics OLAP)
- Kafka (for event streaming)
- Docker (optional, for dependencies)

### Running Locally

```bash
# Start dependencies
docker compose up -d clickhouse kafka

# Run the service
clj -M:dev -m metrics.core

# Run with REPL
clj -M:dev:nrepl
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `PORT` | HTTP server port | `8086` |
| `CLICKHOUSE_URL` | ClickHouse connection string | Required |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker addresses | `localhost:9092` |
| `DATADOG_API_KEY` | Datadog API key | Optional |
| `TRACE_ENABLED` | Enable trace propagation | `true` |

### Running Tests

```bash
clj -M:test
```

## API Endpoints Summary

### Event Collection

| Method | Path | Description |
|--------|------|-------------|
| POST | `/events` | Ingest event batch |
| POST | `/events/impression` | Track impression |
| POST | `/events/click` | Track click |
| POST | `/events/conversion` | Track conversion |

### Experiments

| Method | Path | Description |
|--------|------|-------------|
| GET | `/experiments` | List experiments |
| POST | `/experiments` | Create experiment |
| GET | `/experiments/:id` | Get experiment |
| GET | `/experiments/:id/assignment` | Get user assignment |
| GET | `/experiments/:id/results` | Get experiment results |

### Analytics

| Method | Path | Description |
|--------|------|-------------|
| POST | `/query` | Execute analytics query |
| GET | `/dashboards/:id/data` | Get dashboard data |

## Kafka Topics

### Consumed

| Topic | Purpose |
|-------|---------|
| `events.raw` | Raw event stream |

### Published

| Topic | Purpose |
|-------|---------|
| `events.enriched` | Enriched events with session data |
| `metrics.aggregated` | Real-time metric rollups |

## Dependencies

### Upstream Services

| Service | Protocol | Purpose |
|---------|----------|---------|
| Feature Flags | HTTP | Experiment assignments |

### External Systems

| System | Protocol | Purpose |
|--------|----------|---------|
| ClickHouse | TCP | Analytics data store |
| Kafka | TCP | Event streaming |
| Datadog | HTTPS | External metrics export |

## Trace Propagation

The Metrics Platform is a **non-ingress** service. It:
1. Extracts `traceparent` from HTTP headers (from SPA/Admin)
2. Records trace_id with events for session correlation
3. Propagates trace context to Feature Flags service

Configuration in `.trace-spine.yaml`:
```yaml
version: 1
service_name: metrics
ingress_designated: false
crossings:
  http_inbound:
    - path: /events/**
      framework: ring
    - path: /experiments/**
      framework: ring
    - path: /query
      framework: ring
  http_outbound:
    - target: feature-flags
  kafka_inbound:
    - topic: events.raw
  kafka_outbound:
    - topic: events.enriched
    - topic: metrics.aggregated
```

## Related Documentation

- [C4 Architecture](/docs/architecture/c4/system-landscape.org) - Metrics Platform component diagram
- [spec/L1-wire.org](/spec/L1-wire.org) - Kafka header format
- [ADR-0005](/docs/architecture/adr/0005-cross-substrate-chain-property.md) - Cross-substrate tracing
