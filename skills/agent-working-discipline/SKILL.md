# Agent Working Discipline — trace-spine

This is the entry point for any agent working on trace-spine.
Read this file first. It points to the full documentation.

## Session Start

1. Generate a Trace-Id for your session:
   ```bash
   export TRACE_SPINE_TRACE_ID=$(ulid 2>/dev/null || date +%s | sha256sum | head -c 26 | tr '[:lower:]' '[:upper:]')
   ```

2. Check for existing claims on your intended topic:
   ```bash
   git fetch origin 'refs/agents/*:refs/agents/*'
   git ls-remote origin 'refs/agents/*/claim/*'
   ```

3. Create your claim ref before starting work:
   ```bash
   AGENT_ID="claude-$(echo $TRACE_SPINE_TRACE_ID | head -c 12 | tr '[:upper:]' '[:lower:]')"
   TOPIC="your-topic-here"
   git update-ref "refs/agents/${AGENT_ID}/claim/${TOPIC}" "$(git rev-parse main)"
   git push origin "refs/agents/${AGENT_ID}/claim/${TOPIC}"
   ```

## Required Reading

| Document                        | What You Learn                          |
|---------------------------------|-----------------------------------------|
| docs/working/stacking.org       | Branch naming: `<role>/<topic>/NN`      |
| docs/working/multi-agent.org    | Claim protocol via git refs             |
| docs/working/cprr-notes.org     | Recording reasoning in git notes        |
| docs/working/push-policy.org    | Push to both origin and archive         |
| docs/working/rebuild.org        | Why all refs must be pushed             |

## Pre-Commit Checklist

Before every commit:

- [ ] Trace-Id trailer in commit message
- [ ] Branch follows `<role>/<topic-slug>/NN` pattern
- [ ] Claim ref pushed
- [ ] CPRR notes added (conjecture + refutation minimum)
- [ ] bin/push-all runs clean

## Commit Message Format

```
<type>(<scope>): <description>

<optional body>

Trace-Id: 01HZ3QKXF8J7NWVB5T2M6P4C9R
Conjecture: <what this commit supports>
Refutation: <what would falsify it>
Refs: <spec section or CPRR id>
```

## Key Commands

```bash
# Add CPRR notes
bin/cprr-add conjecture --commit HEAD \
  --statement "Your hypothesis" \
  --refutation "What would falsify it"

# Show notes
bin/cprr-show HEAD

# Push everything
bin/push-all

# Check rebuild property
bin/rebuild-check
```

## ADRs Governing This Discipline

- ADR-0006: aq deferred (pure git coordination)
- ADR-0007: Stacked-diff discipline
- ADR-0008: CPRR-in-git-notes
- ADR-0009: Dual-remote push

## Session End

1. Add final CPRR notes
2. Run `bin/push-all`
3. Delete claim ref:
   ```bash
   git push origin --delete "refs/agents/${AGENT_ID}/claim/${TOPIC}"
   ```
4. Update beads: `bd close <id> && bd dolt push`
