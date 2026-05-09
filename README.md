# trace-spine

Wire-discipline library and CI harness that enforces W3C traceparent propagation
across every wire crossing in the architecture.

## What this is

trace-spine is the **contract enforcement** layer for distributed tracing.
It is not a tracing backend, not a collector, not a sampling policy.

The invariant: every request, message, span, and event carries a W3C `traceparent`
that resolves to the same trace id throughout its causal cone.

## Quick start

```yaml
# .trace-spine.yaml in your service repo
version: 1
service_name: checkout
ingress_designated: false
crossings:
  http_inbound:
    - path: /api/checkout
      framework: rails
```

## Spec

| Document                | Purpose                              |
|-------------------------|--------------------------------------|
| spec/L0-claims.org      | Named invariants (I-spine-*)        |
| spec/L1-wire.org        | Wire format (W3C Trace Context v00) |
| spec/L1-contracts.org   | Function contracts for adapters     |
| spec/L2-properties.org  | Property tests                      |
| spec/cprr.org           | Conjecture tracking                 |

## Wire format

```
traceparent: 00-{trace-id}-{parent-id}-{flags}
             00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
```

Validation regex: `^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$`

## Adapters

| Language/Substrate | Status  |
|--------------------|---------|
| Clojure (JVM)      | Planned |
| ClojureScript      | Planned |
| Ruby (Rails)       | Planned |
| Babashka           | Planned |
| aq (NDJSON)        | Planned |
| Go                 | Planned |

## Conformance

Services declare their wire crossings in `.trace-spine.yaml` and run the
conformance harness in CI:

```bash
cd conformance && ./run.sh
```

## Documentation

- [ADR-0001: Record decisions](docs/architecture/adr/0001-record-decisions.md)
- [ADR-0002: W3C Trace Context](docs/architecture/adr/0002-w3c-trace-context-version-00.md)
- [ADR-0003: No internal origination](docs/architecture/adr/0003-no-internal-origination.md)
- [ADR-0004: 50µs budget](docs/architecture/adr/0004-50us-propagation-budget.md)
- [ADR-0005: Cross-substrate test](docs/architecture/adr/0005-cross-substrate-chain-property.md)

## Team

| Role           | Scope                                           |
|----------------|------------------------------------------------|
| spec-keeper    | Edit spec, gate L0→L3, sign exemptions         |
| spine-engineer | Libraries, harness, middleware                 |
| adversary      | Chaos testing (agent-fillable under review)    |

## License

TBD
