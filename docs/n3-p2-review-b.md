# N3-P2 Review B (Frontend)

TypeScript check: **PASS** (no tsc errors).
ESLint: **19 errors** — all `@typescript-eslint/no-explicit-any`, spread across `api/index.ts`, `AgentList.tsx`, and `Traces.tsx`. Details below.

---

## CRITICAL

_None._

---

## HIGH

### H1 — `api/index.ts:53-54,112-113` — `data: any` in pre-existing agent/memory APIs not cleaned up on file touch

Project `frontend.md` rule: _"已有函数在触碰时补类型"_. This file was modified to add the N3-P2 lifecycle hooks APIs (all of which are properly typed — good). The pre-existing `createAgent(data: any)`, `updateAgent(id, data: any)`, `createMemory(data: any)`, `updateMemory(id, data: any)` were left untyped. ESLint flags all four as errors.

Minimal fix: define `CreateAgentRequest` / `UpdateAgentRequest` interfaces and apply them.

### H2 — `AgentList.tsx:57` — `editing: useState<any>(null)` pollutes all downstream accesses

`editing` is read on 12+ lines — `editing.id`, `editing.lifecycleHooks`, `editing.behaviorRules`, spread into payload, etc. — all losing type safety. `AgentSchema` (imported on the same file from `../api/schemas`) defines the shape; infer or derive the type from it.

ESLint reports 18 `no-explicit-any` errors in `AgentList.tsx` — most trace back to this untyped root.

---

## MEDIUM

### M1 — `HookHistoryPanel.tsx:234,275` — Duplicate `dataIndex: 'input'` causes React key collision warning

Both the "Handler" column (line 234) and the "Chain" column (line 275) declare `dataIndex: 'input'` with no explicit `key`. Ant Design Table uses `dataIndex` as the React list key fallback; two identical keys produce the `"Encountered two children with the same key"` warning. Fix: add `key: 'handler'` and `key: 'chain'` to the respective column definitions.

### M2 — `HookHistoryPanel.tsx:213-293` — `columns` array rebuilt on every render without `useMemo`

With `refetchInterval: 15000` the component re-renders every 15 seconds even when nothing changed. The entire `columns` array (six objects, each with an inline `render` arrow) is recreated on each render. Wrap in `useMemo(() => [...], [])` — no deps needed because the column definitions are static.

### M3 — `MethodHandlerFields.tsx` + `useLifecycleHooks.ts:132` — Loading state for methods never surfaced in UI

`useLifecycleHooks` returns `isMethodsLoading` (line 132) but no caller ever reads it. `FormMode` → `MethodHandlerFields` receive `methods: BuiltInMethodDto[]` — when empty while loading, `MethodHandlerFields` shows:

```
No built-in methods available. The server may not expose any yet.
```

This message is correct for a _permanent_ empty state but misleading during the initial fetch. Add `isMethodsLoading` to `FormModeProps` / `MethodHandlerFieldsProps` and render a `<Spin>` or disabled skeleton while loading.

### M4 — `Traces.tsx:309-319` — Span type filter placed in trace-list card but only filters span waterfall

The `<Select mode="multiple" ... value={spanTypeFilter} onChange={setSpanTypeFilter}>` sits in the left-side "Traces" card `extra`. Users will naturally assume it filters the trace list. It actually only filters `SpanWaterfall` in the right panel. Move the filter control to the span detail card header, or add a label/tooltip making the scope explicit.

### M5 — `FormMode.tsx:304-305` — `dryRunResult` not reset on modal close; stale result persists across test runs

`EntryRow` sets `dryRunResult` on mutation success but never clears it:

```ts
onSuccess: (res) => {
  setDryRunResult(res.data);
  setDryRunModalOpen(true);
},
// onClose:
() => setDryRunModalOpen(false)  // dryRunResult stays
```

If a second dry-run fails (network error → only `message.error`, modal stays closed), `dryRunResult` still holds the previous run's result. On the _next_ success the result is overwritten, so there is no visual bug in the happy path. But if a developer adds logic that checks `dryRunResult !== null` to infer "a test has run", the stale state is misleading. Fix: `onClose={() => { setDryRunModalOpen(false); setDryRunResult(null); }}`.

---

## LOW

### L1 — `MethodHandlerFields.tsx:93` — Redundant `as Record<string, unknown>` cast

`handler.args` is already typed as `Record<string, unknown> | undefined` by Zod inference (`z.record(z.string(), z.unknown()).optional()`). The cast is a no-op and can be removed:

```ts
// before
String((handler.args as Record<string, unknown>)?.[argName] ?? '')
// after
String(handler.args?.[argName] ?? '')
```

### L2 — `DryRunResultModal.tsx` — Missing `React.memo` on a pure display component

Every `EntryRow` re-render causes `DryRunResultModal` to re-render even when `open` is false and `result` is unchanged. `PromptHistoryPanel.tsx` and `HookHistoryPanel.tsx`'s own `DetailModal` both use `React.memo`. Apply it here for consistency:

```ts
export default React.memo(DryRunResultModal);
```

### L3 — `useLifecycleHooks.ts:132` — `isMethodsLoading` dead return value until M3 is addressed

Until `MethodHandlerFields` consumes it (see M3), this return value is unused. Keep it in the interface but mark the issue here so it doesn't get dropped.

### L4 — `FormMode.tsx:541-558` — `HandlerTypeSelector`'s `handleChange` redundantly guards `disabled` buttons

`Radio.Button` with `disabled={disabled}` already prevents clicks from firing. The manual `if (nextType === value) return` early-guard at line 497 is defensive-correct but the `disabled` check at the button level is the authoritative guard. No functional issue; cosmetic inconsistency only.

---

## Summary

0 critical, 2 high, 5 medium, 4 low findings.

**New N3-P2 code quality** (HookHistoryPanel, DryRunResultModal, MethodHandlerFields, api additions, useLifecycleHooks) is generally well-structured: discriminated union typing is correct, Zod schema covers all three handler variants, TanStack Query usage is idiomatic, and error handling on the dry-run mutation is present.

The HIGH issues are in **pre-existing code** that was modified in-place — they must be addressed per the project's "fix `any` on touch" convention.

Overall assessment: **APPROVE_WITH_FIXES** — merge after resolving H1, H2, and M1 (key collision). M2–M5 can be a follow-up if release is time-sensitive.
