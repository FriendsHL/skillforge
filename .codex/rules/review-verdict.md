# Review Verdict Template

Use this for Mid/Full pipeline review reports and judge summaries.

## Reviewer Report

```markdown
# <Area> Review - <task> <round>

## Stage 1 - Spec Compliance
- [ ] Acceptance point 1: PASS/FAIL with evidence.
- [ ] Acceptance point 2: PASS/FAIL with evidence.

Verdict: PASS / FAIL

## Stage 2 - Code Quality
Only run this stage if Stage 1 passes.

### Blockers
### Warnings
### Nits

## Verification Evidence
- Commands run:
- Files/lines spot-checked:

## Overall
PASS / PASS_WITH_WARNINGS / NEEDS_FIX / BLOCKED
```

Stage 1 failures are blockers. Do not praise code quality when the requested
behavior is missing or when the implementation includes scope creep.

## Round 2+ Prior Items

For follow-up review, first verify prior blockers/warnings:

```markdown
## Prior Items Verification
- r1 BLOCKER-1: FIXED / NOT FIXED / PARTIAL with evidence.
- r1 W-1: FIXED / NOT FIXED / PARTIAL with evidence.
```

Only review new issues introduced by the latest changes plus unresolved prior
items. Do not move goalposts with unrelated nits.

## Judge Summary

```markdown
# Judge Ruling - <task> <round>

Reviewer reports:
- Backend: PASS/FAIL path
- Frontend: PASS/FAIL path
- Specialty: PASS/FAIL path

## Verdict
PASS / NEEDS_FIX / BLOCKED

## Consolidated Blockers
## Consolidated Warnings
## Folded Nits

## Decision
Proceed / fix once / upgrade to Full / ask user.
```
