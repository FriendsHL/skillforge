# TypeScript Review Rules

Read this for TypeScript/JavaScript review, especially changed `*.ts`, `*.tsx`,
frontend API wrappers, hooks, WebSocket UI, and dashboard state.

## Review Setup

- Establish scope from `git diff` and changed files. If reviewing a PR, use the
  actual merge base rather than assuming `main`.
- Run the canonical typecheck/build command when practical. For this dashboard,
  prefer `cd skillforge-dashboard && npx tsc --noEmit`.
- If TypeScript or lint checks fail, report that before deeper style review.
- Read surrounding code and call sites, not only diff hunks.

## High-Priority Checks

- No unjustified `any`; use precise types or `unknown` plus narrowing.
- No unsafe casts that hide real mismatches.
- Public/shared functions have explicit parameter and return types.
- No floating promises or `forEach(async ...)`.
- Independent async work uses `Promise.all` when safe.
- Errors are not swallowed; `JSON.parse` on untrusted input is guarded.
- React effects have correct dependencies and cleanup.
- WebSocket, event listener, timer, and ECharts subscriptions dispose on unmount.
- Dynamic lists use stable keys, not indexes when reorder is possible.
- API wrappers and test mocks match the backend's real outer envelope shape.
- No stray `console.log`.

## Security And Web Checks

- No unsanitized `innerHTML` or `dangerouslySetInnerHTML`.
- No user-controlled paths, URLs, or shell input without validation/allowlists.
- No secrets in frontend code other than intentionally public values.

## Severity

- Blocker: typecheck failure, runtime crash risk, auth/security issue, API shape
  mismatch, unhandled async failure, or missing explicit requirement.
- Warning: maintainability, performance, or thin tests.
- Nit: local style and naming only.
