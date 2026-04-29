# Think Before Coding

Read this before non-trivial tasks and whenever the request is ambiguous.

## Before Starting

- Expose unclear assumptions before implementation. If the request has two or more reasonable interpretations, present them and get direction.
- If the user proposes a complex approach and a simpler equivalent approach is available, point that out before proceeding.
- If you are confused, stop and state what is blocking progress instead of guessing through the change.
- Do not clean unrelated dead code, stale comments, unused imports, or naming issues while doing another task.
- Remove only orphaned code caused by your own edit; mention pre-existing cleanup separately.

## Major Design Hard Gate

Require a design document and user approval before implementation when:

- The brief is over roughly 800 words.
- A Full pipeline red light is triggered.
- Core files are involved.

Minimum design documentation:

- Goals and acceptance points.
- Two or three implementation paths with tradeoffs.
- Recommended path with rationale.
- Likely edge cases or hidden invariants.

After writing a PRD, technical design, or plan, self-review for:

- Placeholders such as `TBD`, `TODO`, `待定`, or unresolved question marks.
- Internal contradictions.
- Scope that is too large for one plan.
- Ambiguous requirements with more than one reasonable interpretation.
