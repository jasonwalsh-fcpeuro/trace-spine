# ADR-0002: W3C Trace Context Version 00

## Status

Accepted

## Context

The trace-spine library needs a wire format for propagating trace context across
service boundaries. Options considered:

1. **W3C Trace Context** - Industry standard, version 00
2. **B3 (Zipkin)** - Widely adopted, but vendor-specific
3. **X-Cloud-Trace-Context** - GCP-specific
4. **Custom format** - Maximum control, minimum interop

## Decision

We adopt W3C Trace Context version `00` as the sole wire format.

### Wire format

```
traceparent: 00-{trace-id}-{parent-id}-{flags}
             00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
```

### Validation regex

```
^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$
```

### Forbidden values

- trace-id: `00000000000000000000000000000000` (all zeros)
- parent-id: `0000000000000000` (all zeros)

## Consequences

### Positive

- Industry standard with broad tooling support
- OTel SDK propagators work out of the box
- `tracestate` header available for vendor-specific extensions
- Version field allows future evolution

### Negative

- Must reject B3 headers at ingress (requires migration for existing services)
- 55-character string overhead per request (minimal impact at <50µs budget)

### References

- W3C Trace Context: https://www.w3.org/TR/trace-context/
- spec/L1-wire.org: Wire format details
- C-2: Cross-substrate preservation conjecture
