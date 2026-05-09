# WMS Service

Warehouse Management System handling inventory, fulfillment, and shipping operations.

## Service Overview

The WMS (Warehouse Management System) manages the physical fulfillment of orders:
- Inventory tracking and reservations
- Order allocation to warehouse stock
- Pick, pack, ship workflow orchestration
- Carrier integration for label generation
- Returns and restocking processing

## Local Development

### Prerequisites

- Clojure CLI (1.11+)
- Java 21+
- PostgreSQL 15+
- Kafka (for event consumption/publishing)
- Docker (optional, for dependencies)

### Running Locally

```bash
# Start dependencies
docker compose up -d postgres kafka

# Run database migrations
clj -M:migrate

# Run the service
clj -M:dev -m wms.core

# Run with REPL
clj -M:dev:nrepl
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `PORT` | HTTP server port | `8084` |
| `DATABASE_URL` | PostgreSQL connection string | Required |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker addresses | `localhost:9092` |
| `SHIPSTATION_API_KEY` | ShipStation API key | Required |
| `TRACE_ENABLED` | Enable trace propagation | `true` |

### Running Tests

```bash
clj -M:test
```

## API Endpoints Summary

| Method | Path | Description |
|--------|------|-------------|
| GET | `/inventory/:sku` | Get inventory level |
| POST | `/inventory/:sku/reserve` | Reserve inventory |
| POST | `/inventory/:sku/release` | Release reservation |
| GET | `/allocations/:order-id` | Get order allocation |
| POST | `/shipments` | Create shipment |
| GET | `/shipments/:id` | Get shipment status |
| POST | `/returns` | Initiate return |

## Kafka Topics

### Consumed

| Topic | Purpose |
|-------|---------|
| `order.created` | Trigger order allocation |
| `return.initiated` | Trigger RMA processing |

### Published

| Topic | Purpose |
|-------|---------|
| `order.allocated` | Inventory allocated to order |
| `order.shipped` | Shipment dispatched |
| `inventory.low` | Low stock alert |

## Dependencies

### Upstream Services

| Service | Protocol | Purpose |
|---------|----------|---------|
| Order Service | Kafka | Order events |

### External Systems

| System | Protocol | Purpose |
|--------|----------|---------|
| PostgreSQL | JDBC | Inventory data store |
| Kafka | TCP | Event streaming |
| ShipStation | HTTPS | Shipping labels, tracking |

## Trace Propagation

The WMS Service is a **non-ingress** service. It:
1. Extracts `traceparent` from Kafka message headers
2. Preserves trace context throughout fulfillment workflow
3. Injects `traceparent` into outbound Kafka events

Configuration in `.trace-spine.yaml`:
```yaml
version: 1
service_name: wms
ingress_designated: false
crossings:
  kafka_inbound:
    - topic: order.created
    - topic: return.initiated
  kafka_outbound:
    - topic: order.allocated
    - topic: order.shipped
    - topic: inventory.low
  http_outbound:
    - target: shipstation
      exempt: true
      exemption_adr: ADR-0006
```

## Related Documentation

- [C4 Architecture](/docs/architecture/c4/system-landscape.org) - WMS component diagram
- [spec/L1-wire.org](/spec/L1-wire.org) - Wire format (Kafka headers)
- [spec/L0-claims.org](/spec/L0-claims.org) - I-spine-monotonic invariant
