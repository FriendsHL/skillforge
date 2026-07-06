# Performance Review Rules

Read this for performance incidents, slow pages, slow queries, bundle-size work,
memory leaks, render optimization, or any change suspected to affect runtime cost.

## Root Cause First

Use `systematic-debugging.md` before optimizing. Profile or measure enough to
identify the bottleneck. Do not guess.

## Frontend Performance

- Core targets: LCP < 2.5s, INP < 200ms, CLS < 0.1, TBT < 200ms.
- Route-level code split heavy pages and lazy-load expensive visualizations.
- Avoid importing whole libraries when small named imports or lighter libraries
  work.
- Use explicit image dimensions and lazy-load below-fold media.
- Virtualize lists over roughly 100 rows.
- Memoize expensive computations and frequently re-rendered pure components.
- Do not create unstable objects/functions in render when passed to memoized
  children.
- Clean up listeners, timers, WebSocket subscriptions, and chart instances.

## Backend And Database Performance

- Avoid O(n^2) loops over the same data. Use `Map`/`Set` indexing when needed.
- Batch independent API/database work instead of serial loops.
- Avoid N+1 repository calls; use joins, projections, or batch fetches.
- Add indexes for hot WHERE/JOIN/ORDER BY paths.
- Use pagination/limits for large result sets.
- Do not hold transactions across external IO.

## LLM Cost And Latency

- Avoid escalating to higher-cost models unless the task requires reasoning.
- Keep prompts and tool exposure scoped.
- Avoid workflows that repeatedly retry without changing state or evidence.

## Evidence

Report before/after metrics when claiming improvement: timings, query plans,
bundle sizes, render counts, or memory snapshots.
