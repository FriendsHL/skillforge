# Systematic Debugging

Read this before fixing bugs, failing tests, performance issues, build failures, production incidents, or integration problems.

## Iron Law

No fixes before root-cause investigation.

## Phase 1: Root Cause

- Read the full error, including stack trace, line numbers, exit code, and stderr.
- Reproduce the issue. If it is not stable, collect evidence before guessing.
- Inspect recent changes, dependency changes, config changes, and environment differences.
- Gather evidence at every relevant component boundary.
- Trace the bad value back to its source instead of patching where the symptom appears.

For SkillForge cross-layer issues, check boundaries in order:

1. HTTP request.
2. Service logs.
3. LLM provider or streaming output.
4. SSE delta handling.
5. JPA/database persistence.

## Phase 2: Pattern

- Find similar working code in the repository.
- List all differences, even small ones.
- Read the reference implementation fully before applying the pattern.

## Phase 3: Hypothesis

- Write one clear hypothesis: "I think X is the root cause because Y."
- Make the smallest possible change to test it.
- Change only one variable at a time.
- If it fails, form a new hypothesis instead of stacking changes.

## Phase 4: Implementation

- Write a failing regression test first when practical.
- Apply one focused fix.
- Verify the test, surrounding tests, and actual symptom.
- Avoid unrelated cleanup.

If three fixes fail, stop and reassess the architecture with the user instead of continuing trial-and-error.
