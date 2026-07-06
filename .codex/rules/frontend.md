# Frontend And TypeScript Rules

Read this before touching `*.ts`, `*.tsx`, frontend API calls, React components, hooks, or dashboard styles.

## TypeScript And API

- New API functions must not use `data: any`; define request/response interfaces.
- API response types must match the backend outer envelope shape, not just inner DTO fields. If the backend returns `{items, ...}`, frontend code must read `r.data.items`; if the backend returns a bare list, frontend code may read `r.data`.
- Test mocks for API calls must mirror the real backend response shape.
- Prefer `interface` for object shapes and props; use `type` for unions, intersections, tuples, mapped types, and utility types.
- Public/exported functions and shared utilities should have explicit parameter and return types.
- Avoid `any`; use `unknown` at untrusted boundaries and narrow safely.
- Do not use `@ts-ignore`. If unavoidable, use `@ts-expect-error` with a reason.
- Avoid double assertions like `as unknown as X` unless there is a documented reason.
- API calls live in `src/api/index.ts`; components should not call `axios` directly.
- Every API flow handles loading and error states.
- Fetch independent data with `Promise.all`.

## React

- Component files use `PascalCase.tsx`; utilities/hooks use `camelCase.ts`.
- One main exported component per component file. Small private subcomponents may live in the same file.
- Extract complex logic into custom hooks with `use` prefix. Keep JSX readable.
- Use `React.memo` for pure presentation components where parent churn matters.
- Local state uses `useState`/`useReducer`; shared navigation state should use context or URL params, not localStorage hacks.
- Use `useCallback`/`useMemo` only for child callbacks or expensive derived values.
- Frontend WebSocket subscriptions must close in `useEffect` cleanup.
- Streaming UI updates must be throttled; do not `setState` for every delta.
- `useImperativeHandle` refs do not trigger parent re-render. Use state for UI state, refs only for save-time snapshots.
- Avoid stale closures in event handlers and effects; keep dependency arrays accurate or use refs intentionally.
- Do not use array index as key for lists that can reorder.
- Compute derived state during render or memoization instead of `useEffect` when possible.

## Ant Design And Styling

- Use Ant Design forms, modals, messages, and table loading props consistently.
- Do not override `.ant-*` internals directly; use theme tokens/config where possible.
- Prefer CSS variables already present in the project.
- Static styles go in CSS/CSS Modules; inline styles only for dynamic computed values.
- Do not use `!important`.
- Long lists over roughly 100 rows need virtualization.
- Dispose ECharts instances on unmount.
- No stray `console.log` in committed code.

## Review Cross-Checks

- For TypeScript review, read `typescript-review.md`.
- For performance-sensitive UI work, read `performance-review.md`.
- For dashboard visual/design changes, also read `design.md`.
