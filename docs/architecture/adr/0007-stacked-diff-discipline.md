# ADR-0007: Stacked-Diff Discipline as Merge Policy

## Status

Accepted

## Context

trace-spine work involves layered changes: spec refinements, adapter
implementations, harness updates, integration tests. These naturally form
dependency chains where later work builds on earlier work.

Options considered:

1. **Feature branches** - Single branch per feature, squash on merge
2. **Stacked diffs** - Chain of small branches, rebase-merge to main
3. **Trunk-based** - Direct commits to main with feature flags

## Decision

We adopt stacked-diff discipline with rebase-merge only.

### Branch Naming

```
<role>/<topic-slug>/NN
```

Example chain:
- `spine-engineer/clj-adapter/01` - extract function
- `spine-engineer/clj-adapter/02` - inject function
- `spine-engineer/clj-adapter/03` - middleware

### Commit Trailers

Every commit includes:

```
Trace-Id: <ULID>
Conjecture: <what this commit supports>
Refutation: <what would falsify it>
```

### Merge Policy

- Rebase-merge only
- main has no merge commits
- Each stack layer lands as linear commits
- Bottom of stack references spec section
- Top of stack references acceptance criterion

## Consequences

### Positive

- Small, reviewable changes
- Clear dependency chain
- Trace-Id enables session correlation
- Conjecture/Refutation enables CPRR integration
- Linear history simplifies bisect

### Negative

- Rebasing overhead when base changes
- Requires discipline to keep stacks small
- Tool learning curve (Graphite, git-spr, etc.)

## Refutation Condition

**Revisit this decision if:**

> A developer reports the stacking overhead exceeds the review benefit for
> three consecutive PRs.

If this occurs:
1. Measure: time spent rebasing vs time saved in review
2. If rebasing dominates, consider hybrid approach (stack for large changes,
   single branch for small fixes)
3. Document findings in new ADR

## References

- docs/working/stacking.org: Full protocol
- Graphite: https://graphite.dev
- git-spr: https://github.com/ejoffe/spr
