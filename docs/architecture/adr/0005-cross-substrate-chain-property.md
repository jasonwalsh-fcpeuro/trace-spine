# ADR-0005: Cross-Substrate Chain Property Test

## Status

Accepted

## Context

The architecture uses multiple substrates: HTTP, Kafka, aq (NDJSON queues),
outbox tables. Trace context must be preserved across the full chain.

Testing each substrate in isolation is insufficient; the chain test exercises
the complete propagation path.

## Decision

A cross-substrate chain property test runs nightly against `main` and on-demand
for PRs touching adapter code.

### Test topology

```
HTTP ingress → Service A
    → Kafka produce (topic: a.events.v1)
    → Kafka consume (Service B)
    → aq publish (channel: relay)
    → aq consume (Service C)
    → outbox insert
    → outbox drain → Kafka produce (topic: b.events.v1)
    → Kafka consume (Service D)
    → HTTP egress
```

### Assertions

1. trace_id is identical at every crossing
2. span_id differs at each hop (child spans created)
3. parent_id chain is connected (no orphans)
4. Central collector shows one trace tree with all spans

### Infrastructure

Docker-compose stack with:
- 4 minimal services (clj adapters)
- Kafka (single broker, ephemeral)
- aq daemon
- PostgreSQL (outbox table)
- OTel collector → local file exporter

## Consequences

### Positive

- C-2 conjecture is directly testable
- Adapter bugs surface before production
- Full-chain visibility in a single test

### Negative

- Expensive test (substrates spin up for minutes)
- Flaky if resource-constrained (retry logic required)
- Adds CI dependency on docker-compose

### Test schedule

| Trigger         | Runs chain test? |
|-----------------|------------------|
| PR (any)        | No               |
| PR (adapter/*) | Yes              |
| Nightly main    | Yes              |
| Manual dispatch | Yes              |

### Failure handling

Chain test failure:
1. Identify crossing where trace_id diverged
2. Check adapter logs at that crossing
3. File bug against specific adapter
4. Block adapter PR until fixed

### References

- spec/L2-properties.org: `cross_substrate` property
- C-2: Cross-substrate preservation conjecture
- conformance/harness/: Test runner (not yet implemented)
