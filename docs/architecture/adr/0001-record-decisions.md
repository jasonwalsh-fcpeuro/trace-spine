# ADR-0001: Record Architecture Decisions

## Status

Accepted

## Context

We need to record the architectural decisions made on the trace-spine project.

## Decision

We will use Architecture Decision Records, as described by Michael Nygard in
[Documenting Architecture Decisions](http://thinkrelevance.com/blog/2011/11/15/documenting-architecture-decisions).

ADRs will be stored in `docs/architecture/adr/` and named with a sequential
four-digit prefix.

## Consequences

- Decisions are captured with context and rationale
- Future team members can understand why decisions were made
- Each ADR is immutable once accepted; superseded by new ADRs
- ADR index maintained in `docs/architecture/adr/index.yml`
