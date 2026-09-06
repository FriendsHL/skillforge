# TypeScript Review Additions

Apply `frontend.md` for API contracts, state, effects, styling, and cleanup, and
`code-review.md` for scope, severity, and output. Do not repeat those checklists.

- Trace changed API consumers and mocks to the backend's real outer response
  envelope; precise TypeScript types alone do not prove the wire contract.
- Check floating promises, `forEach(async ...)`, guarded parsing of untrusted
  JSON, and whether casts hide runtime mismatches.
- Inspect session switches, unmounts, and late responses for stale state or
  duplicate subscriptions, not just dependency-array syntax.
- Apply `security-review.md` for HTML/markdown rendering, user-controlled URLs
  or paths, credentials, and authorization-sensitive changes.
- Run dashboard gates from `verification-before-completion.md`. Report type/build
  failures and critical browser-interaction gaps before style observations.
