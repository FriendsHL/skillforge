# Requirement Docs Reading Rules

Read this before any non-trivial requirement, implementation, or review task.

- Start with `docs/README.md` to find the relevant requirement package.
- Read `docs/todo.md` only to confirm the current queue, blocker, priority, and linked requirement package. Do not treat `todo.md` as the source of detailed requirements.
- For a linked requirement package, open `index.md` first. It is the package entry and points to the exact MRD, PRD, and technical design files needed for the task.
- Before implementation, read `prd.md` and `tech-design.md` for that requirement. Read `mrd.md` only when original user intent, constraints, or unresolved product questions are unclear.
- For Lite requirements, `index.md` is sufficient if it contains user request, acceptance points, implementation notes, and verification.
- Do not read every archived requirement by default. Only open `docs/requirements/archive/<yyyy-MM-dd>-<ID>-<slug>/` when the current requirement links to it, the code area depends on it, or the user explicitly asks.
- Completed delivery facts live in `docs/delivery-index.md`; use it to verify dates, commits, migrations, and shipped scope.
- If `docs/README.md`, `docs/todo.md`, and a requirement package disagree, prefer the requirement package for scope details, prefer `delivery-index.md` for completed facts, and flag the inconsistency before implementing.
