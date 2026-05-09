# ADR-0008: CPRR-in-Git-Notes as Durable Reasoning Record

## Status

Accepted

## Context

CPRR (Conjecture-Prediction-Result-Refutation) reasoning must survive:
- Rebases and force-pushes
- Repository reconstruction from archives
- Team member transitions
- Tool migrations

Options considered:

1. **Markdown files** - Checked into tree, versioned with code
2. **External database** - SQLite, PostgreSQL, or cloud service
3. **Git notes** - Attached to commits, pushed to refs/notes/*
4. **Issue tracker** - GitHub Issues or beads

## Decision

We use git notes for CPRR records, with five dedicated refs:

| Ref                    | Purpose                          |
|------------------------|----------------------------------|
| refs/notes/conjecture  | Hypotheses about behavior        |
| refs/notes/refutation  | Evidence falsifying conjectures  |
| refs/notes/experiment  | Reproducible test runs           |
| refs/notes/deviation   | Intentional spec departures      |
| refs/notes/test        | Property test seeds/results      |

### JSON Schema

Each note is a JSON object with schema version:

```json
{
  "schema": "cprr-<dimension>/v1",
  "trace_id": "...",
  "commit": "...",
  "timestamp": "...",
  "<dimension>": { ... }
}
```

### Push Policy

Notes refs are pushed alongside branches to both origin and archive.

## Consequences

### Positive

- Notes survive rebases (attached to commit, not tree)
- Notes included in bundles and clones
- No external dependency
- Structured JSON enables tooling
- Trace-Id links notes to work sessions

### Negative

- Notes refs not fetched by default (requires explicit fetch)
- JSON editing in notes is awkward
- No built-in search (requires custom tooling)
- Notes can be orphaned if commits are garbage-collected

## Refutation Condition

**Revisit this decision if:**

> Any notes ref is empty after one week of active work.

If this occurs:
1. Determine cause: tooling friction, unclear value, or forgotten
2. If tooling: improve bin/cprr-add UX
3. If unclear value: document concrete examples of notes helping
4. If forgotten: add pre-commit reminder

**Also revisit if:**

> bin/rebuild-check fails because notes were not pushed.

This indicates push discipline breakdown, not notes design failure.

## References

- docs/working/cprr-notes.org: Full protocol
- schema/notes/: JSON Schema definitions
- bin/cprr-add, bin/cprr-show: CLI tools
- git-notes(1) man page
