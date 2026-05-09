# ADR-0003: No Internal Trace Origination

## Status

Accepted

## Context

When a service receives a request without `traceparent`, two options exist:

1. **Generate a new trace id** - Creates trace, but masks origin-detection bugs
2. **Reject the request** - Strict, but forces upstream fix

The choice depends on whether the service is at an ingress boundary.

## Decision

Internal services MUST NOT originate trace ids.

### Rules

1. **Ingress-designated services** (e.g., API gateway, edge proxy): May generate
   a new trace id when no `traceparent` is present.

2. **All other services**: MUST reject requests without `traceparent` with a
   structured error citing this ADR.

### Implementation

```clojure
(defn continue-or-start [carrier ingress-designated?]
  (if-let [ctx (extract carrier)]
    (create-child ctx)
    (if ingress-designated?
      (create-new-trace)
      (throw (ex-info "Internal service must not originate trace"
                      {:adr "ADR-0003"
                       :carrier (sanitize carrier)})))))
```

### Configuration

Each service declares its designation in `.trace-spine.yaml`:

```yaml
ingress_designated: false  # default; only edge gateway is true
```

## Consequences

### Positive

- Missing `traceparent` is immediately detected as a bug
- Audit log shows exactly where traces originate
- C-3 conjecture is testable via conformance harness

### Negative

- Services cannot operate without upstream trace context
- Requires migration plan for existing services without instrumentation
- Local development needs mock trace context injection

### Mitigation

Development environments can set `TRACE_SPINE_DEV_MODE=true` to generate
trace ids locally while logging a warning. Production MUST NOT set this flag.

### References

- spec/L0-claims.org: I-spine-no-fabrication
- spec/L1-contracts.org: `continue_or_start` contract
- C-3: No internal origination conjecture
