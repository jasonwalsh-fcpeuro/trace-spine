# AGENTS.md — trace-spine

## What Is This

trace-spine is the wire-discipline library and CI harness that enforces W3C traceparent
propagation across every wire crossing in the architecture. It is the contract, not the
tracing backend.

## Quick Reference

```bash
# Spec validation
make validate-spec

# Conformance harness
cd conformance && ./run.sh

# Property tests with specific seed
cd conformance && ./run-properties.sh --property roundtrip --seed 42

# Benchmark adapter
cd conformance && ./bench.sh clj

# ADR management
ls docs/architecture/adr/
cat docs/architecture/adr/index.yml
```

## Build & Test

No build step for spec files. Library adapters have per-language build:

```bash
# Clojure adapter
cd lib/clj && clj -M:test

# Ruby adapter
cd lib/rb && bundle exec rspec

# Go adapter
cd lib/go && go test ./...
```

## Conventions

- Spec files are org-mode, not Markdown
- Wire format in spec/L1-wire.org is the source of truth
- Function contracts in spec/L1-contracts.org define adapter behavior
- CPRR table in spec/cprr.org tracks conjectures
- ADRs required for any contract change
- Exemptions require ADR id in `.trace-spine.yaml`

## What NOT to Do

### Spec editing

- **Do NOT edit spec/ files without spec-keeper review**
- Do NOT add L3 claims without empirical justification
- Do NOT remove CPRR conjectures (mark refuted instead)

### Library code

- Do NOT implement auto-instrumentation (explicit middleware only)
- Do NOT bypass OTel SDK propagators where they exist (wrap, don't replace)
- Do NOT use ambient context except where framework requires it
- Do NOT fail closed in production (log and continue)

### Configuration

- Do NOT add exemptions to `.trace-spine.yaml` without ADR id
- Do NOT change the 50µs budget without ADR-0004 update
- Do NOT modify the validation regex without ADR-0002 update

### CI

- Do NOT skip the cross-substrate chain test for adapter PRs
- Do NOT approve waivers without spec-keeper sign-off

## Role Boundaries

| Role           | Can do                                      | Cannot do                          |
|----------------|---------------------------------------------|-----------------------------------|
| spec-keeper    | Edit spec, gate L0→L3, sign exemptions     | —                                 |
| spine-engineer | Edit lib/, conformance/, docs/             | Edit spec/ without review         |
| adversary      | Chaos testing within budget                | Production ingress, spec changes  |
| agent          | PRs to lib/, conformance/                  | Spec edits, exemption grants      |

## Working Discipline (REQUIRED)

Read before ANY work: `skills/agent-working-discipline/SKILL.md`

Full docs in `docs/working/`:
- stacking.org — branch naming, commit trailers
- multi-agent.org — work-claim protocol
- cprr-notes.org — reasoning in git notes
- push-policy.org — dual-remote push
- rebuild.org — rebuild property

## Pre-Commit Checklist

```
[ ] Trace-Id trailer in commit message
[ ] Branch: <role>/<topic-slug>/NN
[ ] Claim ref pushed: refs/agents/<id>/claim/<topic>
[ ] CPRR notes: bin/cprr-add conjecture + refutation
[ ] Push: bin/push-all (both remotes)
```

Quick commands:
```bash
export TRACE_SPINE_TRACE_ID=$(ulid || date +%s | sha256sum | head -c 26 | tr a-z A-Z)
bin/cprr-add conjecture --commit HEAD --statement "..." --refutation "..."
bin/push-all
```

## Non-Interactive Shell Commands

**ALWAYS use non-interactive flags:**

```bash
cp -f source dest    # NOT: cp source dest
mv -f source dest    # NOT: mv source dest
rm -f file           # NOT: rm file
rm -rf directory     # NOT: rm -r directory
```

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:ca08a54f -->
## Beads Issue Tracker

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
bd dolt push          # Push beads data
```

### Rules

- Use `bd` for ALL task tracking
- Run `bd prime` for detailed workflow context
- Use `bd remember` for persistent knowledge

## Session Completion

```bash
git pull --rebase && bd dolt push && git push
git status  # MUST show "up to date with origin"
```
<!-- END BEADS INTEGRATION -->
