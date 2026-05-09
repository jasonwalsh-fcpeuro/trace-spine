# API Gateway

REST/GraphQL entry point for the Petstore Commerce Platform with trace-spine middleware.

## Service Overview

The API Gateway is the unified entry point for all client requests. It handles:
- REST and GraphQL request routing (Reitit)
- JWT authentication and session management
- Rate limiting (per-user/IP)
- W3C traceparent extraction and injection via trace-spine middleware
- CORS configuration

## Local Development

### Prerequisites

- Clojure CLI (1.11+)
- Java 21+
- Redis (for rate limiting)
- Docker (optional, for dependencies)

### Running Locally

```bash
# Start dependencies
docker compose up -d redis

# Run the service
clj -M:dev -m api.core

# Run with REPL
clj -M:dev:nrepl
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `PORT` | HTTP server port | `8080` |
| `REDIS_URL` | Redis connection string | `redis://localhost:6379` |
| `JWT_SECRET` | JWT signing key | Required |
| `TRACE_ENABLED` | Enable trace propagation | `true` |

### Running Tests

```bash
clj -M:test
```

## API Endpoints Summary

### REST

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/products` | List products |
| GET | `/api/products/:id` | Get product |
| GET | `/api/cart` | Get current cart |
| POST | `/api/cart/items` | Add item to cart |
| POST | `/api/checkout` | Create order |
| GET | `/api/orders` | List orders |
| GET | `/api/orders/:id` | Get order |

### GraphQL

| Endpoint | Schema |
|----------|--------|
| `/graphql` | `resources/schema.graphql` |
| `/graphql/playground` | GraphQL Playground (dev only) |

## Dependencies

### Upstream Services

| Service | Protocol | Purpose |
|---------|----------|---------|
| Catalog Service | HTTP | Product queries |
| Cart Service | HTTP | Cart management |
| Order Service | HTTP | Order lifecycle |
| Feature Flags | HTTP | Flag evaluation |

### External Systems

| System | Protocol | Purpose |
|--------|----------|---------|
| Redis | TCP | Rate limiting, session cache |

## Trace Propagation

The API Gateway is an **ingress-designated** service. It:
1. Extracts incoming `traceparent` header if present
2. Creates new trace context if absent (designated ingress)
3. Injects `traceparent` into all outbound requests to internal services

Configuration in `.trace-spine.yaml`:
```yaml
version: 1
service_name: api-gateway
ingress_designated: true
crossings:
  http_inbound:
    - path: /api/**
      framework: ring
    - path: /graphql
      framework: ring
  http_outbound:
    - target: catalog
    - target: cart
    - target: order
    - target: feature-flags
```

## Related Documentation

- [C4 Architecture](/docs/architecture/c4/system-landscape.org) - API Gateway component diagram
- [spec/L1-wire.org](/spec/L1-wire.org) - Wire format specification
- [ADR-0002](/docs/architecture/adr/0002-w3c-trace-context-version-00.md) - W3C Trace Context
