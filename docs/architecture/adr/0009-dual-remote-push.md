# ADR-0009: Dual-Remote Push Policy

## Status

Accepted

## Context

Work on trace-spine must be durable against:
- GitHub outages
- Account compromise
- Accidental deletion
- Network partitions during agent work

A single remote (origin) is a single point of failure.

## Decision

We maintain two remotes and push to both:

| Remote  | Location                              | Purpose             |
|---------|---------------------------------------|---------------------|
| origin  | github.com/aygp-dr/trace-spine       | Collaboration       |
| archive | ~/ghq/mirror/aygp-dr/trace-spine.git | Local durability    |

### Push Order

1. Branch refs
2. refs/notes/*
3. refs/agents/*

### Implementation

`bin/push-all` pushes to both remotes with explicit refspecs.

### Archive Location

Configurable via `TRACE_SPINE_ARCHIVE` environment variable.
Default: `~/ghq/mirror/aygp-dr/trace-spine.git`

## Consequences

### Positive

- Work survives GitHub outage
- Local archive enables offline work
- Archive can be copied to additional locations
- Rebuild property is testable against archive

### Negative

- Two pushes per save (minor latency)
- Archive must be set up manually
- Archive location varies per developer
- Sync issues if developer forgets bin/push-all

## Refutation Condition

**Revisit this decision if:**

> bin/rebuild-check fails on a clone of the archive remote.

This means the archive is out of sync, indicating:
1. bin/push-all not used consistently
2. Archive remote misconfigured
3. Push to archive failing silently

Response:
1. Fix immediate issue
2. Add archive sync check to CI
3. Consider git hooks to enforce bin/push-all

**Also revisit if:**

> Developer reports dual-push latency impacts workflow.

Measure actual latency. If significant:
1. Make archive push async
2. Or accept single-remote risk with periodic manual sync

## References

- docs/working/push-policy.org: Full protocol
- bin/push-all: Implementation
- docs/working/rebuild.org: Property that depends on this
