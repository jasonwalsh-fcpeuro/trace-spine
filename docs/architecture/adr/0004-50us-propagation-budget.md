# ADR-0004: 50µs Propagation Budget

## Status

Accepted

## Context

Trace context propagation adds latency to every wire crossing. We need a
concrete budget to make the cost visible and bound.

### Measurements (baseline)

| Operation              | p50    | p99    |
|------------------------|--------|--------|
| String format (55 chars) | 0.5µs  | 1µs    |
| Regex validation       | 0.3µs  | 0.8µs  |
| HTTP header inject     | 2µs    | 8µs    |
| Kafka header inject    | 3µs    | 12µs   |
| Full middleware wrap   | 15µs   | 40µs   |

## Decision

Propagation overhead per crossing MUST be < 50µs at p99.

### Budget allocation

| Component              | Budget (p99) |
|------------------------|--------------|
| extract()              | 10µs         |
| inject()               | 10µs         |
| span creation          | 15µs         |
| span export (async)    | 0µs sync     |
| Total                  | 35µs + margin |

### Enforcement

The conformance harness runs the `budget` property test against every adapter.
Failure at p99 ≥ 50µs blocks the PR.

### Reporting

```
overhead_p50: 18µs
overhead_p99: 42µs
overhead_p999: 78µs   # logged, not gated
budget_status: PASS
```

## Consequences

### Positive

- Performance regression is caught in CI before production
- Teams can reason about trace overhead explicitly
- Adapter implementations have a clear target

### Negative

- Some environments (constrained edge, cold-start serverless) may struggle
- Budget waiver requires ADR (friction by design)

### Budget waiver process

If a service cannot meet the 50µs budget:

1. Profile and optimize first
2. If structural, file an ADR proposing a higher budget
3. ADR must include: why the service is special, proposed budget, refutation condition
4. Approved waivers are listed in `.trace-spine.yaml` `exemptions` with ADR id

### References

- spec/L0-claims.org: I-spine-budget
- spec/L2-properties.org: `budget` property
- C-4: Propagation overhead conjecture
