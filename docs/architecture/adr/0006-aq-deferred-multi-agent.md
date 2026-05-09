# ADR-0006: aq Deferred for Multi-Agent Coordination

## Status

Accepted

## Context

Multiple agents working on trace-spine need coordination to avoid conflicting
changes. Two approaches were considered:

1. **aq (ambient queue)** - Message-based coordination via NDJSON channels
2. **Git refs** - Pure git coordination via refs/agents/*/claim/*

aq provides richer semantics (broadcast, subscribe, temporal queries) but
introduces a dependency outside git. Git refs are simpler and self-contained.

## Decision

Phase 1 uses pure git-ref coordination. aq integration is deferred.

### What Phase 1 Does

- Agents create claim refs: `refs/agents/<agent-id>/claim/<topic>`
- Agents check claims via: `git ls-remote origin 'refs/agents/*/claim/*'`
- Claim refs are pushed alongside branches
- Stale claims (>24h, no PR) are reaped by spec-keeper

### What Phase 1 Does Not Do

- No automatic conflict notification
- No claim subscription/broadcast
- No temporal queries ("who claimed what when")
- No lock semantics (claims are advisory)

## Consequences

### Positive

- Zero external dependencies
- Works offline
- Refs survive in bundles and archives
- Simple mental model

### Negative

- No notification when someone claims your intended topic
- Manual polling required
- No audit trail beyond ref history
- Collision detection is best-effort

## Refutation Condition

**Revisit this decision if:**

> Two agents create claims pointing at the same commit twice within one sprint.

If this occurs:
1. Document both incidents in an issue
2. Evaluate whether aq would have prevented them
3. If yes, promote this ADR to superseded and implement aq coordination
4. If no, add tiebreaker logic to the git-ref protocol

## References

- docs/working/multi-agent.org: Protocol details
- aq documentation (out of scope for phase 1)
