# trace-spine — Project Instructions

This file provides instructions and context for AI agents working on trace-spine.

## Charter

trace-spine owns the wire-discipline library and CI machinery that enforce one invariant:
**every wire crossing carries a W3C traceparent that preserves trace id throughout its causal cone.**

The team delivers *contract enforcement*, not a tracing system.

## Roles

| Role           | Scope                                                           |
|----------------|------------------------------------------------------------------|
| spec-keeper    | Edit spec, gate L0→L3 climb, sign capability grants             |
| spine-engineer | Maintain libraries, middleware, conformance harness             |
| adversary      | Scheduled chaos against propagation paths (agent-fillable)      |

**Agent scope:** Agents may contribute PRs to libraries and harness. Agents may fill the
adversary role under spine-engineer review. **No agent has authority over the spec itself.**

## L0 → L3 Progressive Hardening

| Level | Content                    | Refutation mechanism        |
|-------|----------------------------|-----------------------------|
| L0    | Named claims (I-spine-*)   | Violation observed          |
| L1    | Wire format + contracts    | Harness test failure        |
| L2    | Property tests + seeds     | Shrunken counterexample     |
| L3    | TLA+ temporal claims       | Model checker counterexample |

Not every claim climbs. L3 earns its place only when an empirical claim demands it.

## Spec editing rules

1. **No agent edits spec/ without spec-keeper review**
2. Spec changes are PRs with ADR reference when they constitute a contract change
3. Exemptions to `.trace-spine.yaml` require an ADR id in the `exemptions` field

## Key files

| Path                        | Purpose                                  |
|-----------------------------|------------------------------------------|
| spec/L0-claims.org          | Named invariants                         |
| spec/L1-wire.org            | Wire format (the contract for consumers) |
| spec/L1-contracts.org       | Function contracts for library           |
| spec/L2-properties.org      | Property tests                           |
| spec/cprr.org               | Conjecture tracking                      |
| conformance/                | Harness and fixtures                     |
| .trace-spine.yaml           | Service configuration schema             |

## Build & Test

```bash
# Validate spec files
make validate-spec

# Run conformance harness locally
cd conformance && ./run.sh

# Run property tests
cd conformance && ./run-properties.sh

# Benchmark adapters
cd conformance && ./bench.sh clj
```

## Conventions

- **Org-mode** for spec documents, not Markdown
- **Mermaid** for all diagrams (no image files)
- **ADRs** for contract changes
- **TOML** for runbooks
- **JSON Schema** for `.trace-spine.yaml` validation

## Working Discipline (REQUIRED READING)

Before starting any work, read these documents in `docs/working/`:

| Document              | Purpose                                       |
|-----------------------|-----------------------------------------------|
| stacking.org          | Branch naming, commit trailers, merge policy  |
| multi-agent.org       | Work-claim protocol via git refs              |
| cprr-notes.org        | Recording reasoning in git notes              |
| push-policy.org       | Dual-remote push discipline                   |
| rebuild.org           | Rebuild-from-refs property                    |

Or read the thin pointer: `skills/agent-working-discipline/SKILL.md`

## Pre-Commit Checklist

Before every commit, verify:

- [ ] **Trace-Id trailer present** — ULID from session start
- [ ] **Branch follows naming** — `<role>/<topic-slug>/NN`
- [ ] **Claim ref pushed** — `refs/agents/<agent-id>/claim/<topic>`
- [ ] **CPRR notes added** — minimum: conjecture + refutation
- [ ] **bin/push-all runs clean** — both origin and archive

```bash
# Generate Trace-Id for session
export TRACE_SPINE_TRACE_ID=$(ulid 2>/dev/null || date +%s | sha256sum | head -c 26 | tr '[:lower:]' '[:upper:]')
echo "Trace-Id: $TRACE_SPINE_TRACE_ID"

# Verify branch name
git rev-parse --abbrev-ref HEAD | grep -qE '^(spec-keeper|spine-engineer|adversary)/[a-z0-9-]+/[0-9]{2}$' && echo "✓ branch ok" || echo "✗ branch name invalid"

# Add CPRR notes
bin/cprr-add conjecture --commit HEAD --statement "..." --refutation "..."

# Push everything
bin/push-all
```

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:ca08a54f -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` for workflow context.

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

## Session Completion

**MANDATORY WORKFLOW:**

1. File issues for remaining work
2. Run quality gates (if code changed)
3. Update issue status
4. **PUSH TO REMOTE** (mandatory):
   ```bash
   git pull --rebase && bd dolt push && git push
   git status  # MUST show "up to date with origin"
   ```
5. Hand off with context for next session
<!-- END BEADS INTEGRATION -->
