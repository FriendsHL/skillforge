# Planning And Clarification

Read when requirements, migration scope, or a significant design choice are unclear.

- Resolve routine, reversible implementation choices using local patterns and
  the user's existing instructions. State material assumptions concisely.
- Ask when alternatives change user-visible behavior, scope, compatibility, data
  retention, security, or an external commitment and intent cannot be established.
  Continue independent work while waiting; do not guess through the blocked choice.
- For a significant architecture choice or destructive migration, prepare a
  concise reviewable design before requesting a decision: goal, acceptance points,
  relevant alternatives/tradeoffs, recommendation, invariants, and verification.
- Do not require a new design approval for an already authorized approach,
  ordinary bug fix, established-pattern extension, or merely touching a core file.
  Full specifies review depth, not an automatic approval gate.
- Keep plans proportional to uncertainty. Do not invent alternatives to meet a
  quota. Check for contradictions and unresolved acceptance decisions before coding.
- Follow `docs-reading.md` for requirement packages; update design records when
  scope changes rather than leaving conflicting plans.
