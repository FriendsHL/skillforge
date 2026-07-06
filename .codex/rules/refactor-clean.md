# Refactor And Dead Code Cleanup

Read this only when the task is explicitly cleanup/refactor/dead-code removal.
Do not clean unrelated dead code during feature work.

## Workflow

1. Detect candidates with available tools:
   - TypeScript: `npx knip`, `npx depcheck`, `npx ts-prune`.
   - Generic: `rg` references and import/export search.
2. Categorize risk:
   - Safe: internal unused utilities, unused imports, private helpers.
   - Caution: components, routes, middleware, exported APIs.
   - Danger: configs, entry points, type declarations, public package API.
3. Establish a green baseline with relevant tests/build.
4. Remove one category or small batch at a time.
5. Re-run tests/build after each batch.
6. If verification fails, revert only the cleanup batch and skip that item.

## Safety Checks

- Search dynamic imports and string references.
- Check public exports and external consumers.
- Do not combine cleanup with behavior changes.
- Be conservative when coverage is weak.
