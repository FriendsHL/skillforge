# N3-P2 Judge Verdict

Reviewed: `docs/n3-p2-review-a.md` (Backend/Java) and `docs/n3-p2-review-b.md` (Frontend/TypeScript).

---

## Overall: BLOCK

Two exploitable SSRF bypasses in `UrlValidator` plus an IDOR that lets any authenticated user trigger another user's side-effectful hook (real HTTP calls, disk writes) with no audit trail. These must be fixed before the feature ships.

---

## Must-Fix Before Merge

### Fix 1: IPv4-mapped IPv6 SSRF bypass
- **Source:** Reviewer A — C1
- **File:** `skillforge-server/src/main/java/com/skillforge/server/hook/method/UrlValidator.java`
- **What to fix:** `uri.getHost()` returns `::ffff:127.0.0.1` which is not in `BLOCKED_HOSTS` and bypasses all IPv4 regexes, yet Java's `HttpClient` connects to the real loopback. Add an `InetAddress`-resolution step after string-based checks: resolve the host, check `inetAddr.isLoopbackAddress() || inetAddr.isSiteLocalAddress() || inetAddr.isLinkLocalAddress()`. Any address that resolves to a private/loopback/link-local range must be rejected regardless of string form.
- **Verdict:** AGREE — textbook SSRF bypass, correctly identified.

### Fix 2: Missing link-local range 169.254.x.x (cloud metadata)
- **Source:** Reviewer A — C2
- **File:** `UrlValidator.java`
- **What to fix:** Add `Pattern LINK_LOCAL = Pattern.compile("^169\\.254\\..*")` to the blocklist. If Fix 1 is implemented (InetAddress resolution + `isLinkLocalAddress()` check), this is covered automatically — but add the explicit pattern anyway as belt-and-suspenders for the string-matching path. Also add `fc00:` / `fd00:` IPv6 unique-local prefix rejection.
- **Verdict:** AGREE — the EC2/GCP/Azure metadata service attack path is real and catastrophic.

### Fix 3: IDOR on dry-run endpoint
- **Source:** Reviewer A — H3
- **File:** `LifecycleHookController.java:152`
- **What to fix:** After `agentRepository.findById(id)` succeeds, add an ownership check before proceeding: if `agent.getOwnerUserId()` does not match the authenticated user's ID, return `403 Forbidden`. Apply the same check to the `hookHistory` endpoint at line 246. The dry-run bypasses the dispatcher's depth guard and leaves no audit trace — an unguarded endpoint means any user can fire real HTTP calls and disk writes on behalf of another user.
- **Verdict:** AGREE — IDOR with real side effects (network egress, filesystem writes) is HIGH-severity correctly.

### Fix 4: JPA entity returned directly from hook-history endpoint
- **Source:** Reviewer A — H1
- **File:** `LifecycleHookController.java:249`
- **What to fix:** Introduce a `HookHistoryDto` record (fields: `id`, `agentId`, `event`, `hookType`, `status`, `durationMs`, `input`, `output`, `errorMessage`, `createdAt`). Map `TraceSpanEntity → HookHistoryDto` before returning. Do not serialize the raw entity.
- **Verdict:** AGREE — project rules explicitly prohibit exposing JPA entities in responses. Also eliminates `LazyInitializationException` risk.

### Fix 5: Business logic in controller — dryRunHook
- **Source:** Reviewer A — H2
- **File:** `LifecycleHookController.java:152–233`
- **What to fix:** Extract the 7-step execution logic into `LifecycleHookService.dryRun(Long agentId, String event, int entryIndex, String callerUserId)`. The service method owns: agent load + ownership check (merged with Fix 3), hook config parse, event resolution, entry validation, runner lookup, context creation, and runner invocation. The controller delegates and maps the result to HTTP response codes.
- **Verdict:** AGREE — this is a fat-controller violation per project rules. Can be addressed in the same PR as Fix 3 since the ownership check should live in the service.

### Fix 6: `HttpClient` created per invocation
- **Source:** Reviewer A — H4
- **File:** `HttpPostMethod.java:103`, `FeishuNotifyMethod.java:107`
- **What to fix:** Declare `private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()` as a class-level constant in both classes. If a shared Spring `@Bean HttpClient` already exists, inject it instead.
- **Verdict:** AGREE — each invocation spawning a new thread pool is a resource leak vector under concurrent hook execution. Correct severity: HIGH.

### Fix 7: `data: any` in pre-existing agent/memory API functions
- **Source:** Reviewer B — H1
- **File:** `skillforge-dashboard/src/api/index.ts:53-54, 112-113`
- **What to fix:** Define `CreateAgentRequest`, `UpdateAgentRequest`, `CreateMemoryRequest`, `UpdateMemoryRequest` interfaces matching the actual payloads. Replace `any` parameter types. Project rule: "fix `any` on touch."
- **Verdict:** AGREE — pre-existing `any` types must be resolved when the file is modified. ESLint reports these as errors.

### Fix 8: `editing: useState<any>(null)` in AgentList
- **Source:** Reviewer B — H2
- **File:** `skillforge-dashboard/src/components/AgentList.tsx:57`
- **What to fix:** Replace `useState<any>` with `useState<AgentSchema | null>`. `AgentSchema` is already imported. This eliminates the 18 downstream `no-explicit-any` ESLint errors that cascade from this root.
- **Verdict:** AGREE — untyped root state propagates through 12+ access sites. Project rule violation same as Fix 7.

---

## Should-Fix (MEDIUM findings worth addressing)

### Fix 9: HTTP header injection via user-controlled keys
- **Source:** Reviewer A — M2
- **File:** `HttpPostMethod.java:116–120`
- **What to fix:** Maintain a `BLOCKED_HEADER_NAMES = Set.of("host", "content-type", "content-length", "transfer-encoding", "connection")` denylist. Check incoming header keys against it (case-insensitive) and skip or reject.
- **Verdict:** AGREE — key-override attacks against `Host` and `Content-Type` are real. Should fix in this PR; not a blocker if time-constrained.

### Fix 10: Exception details forwarded to caller
- **Source:** Reviewer A — M3
- **File:** `MethodHandlerRunner.java:67–69`, `HttpPostMethod.java:135`
- **What to fix:** Log full `e.getMessage()` server-side at WARN/ERROR level. Return a sanitized code string (`"network_error"`, `"io_error"`, `"timeout"`) to the client instead of the raw exception message.
- **Verdict:** AGREE — internal topology disclosure via exception messages is a real info-leak. Straightforward fix.

### Fix 11: React key collision in HookHistoryPanel columns
- **Source:** Reviewer B — M1
- **File:** `skillforge-dashboard/src/components/HookHistoryPanel.tsx:234, 275`
- **What to fix:** Add explicit `key: 'handler'` and `key: 'chain'` to the two column definitions that both declare `dataIndex: 'input'`. This eliminates the React duplicate-key warning.
- **Verdict:** AGREE — duplicate React keys cause rendering bugs in edge cases and should always be fixed.

### Fix 12: Add `@Transactional(readOnly = true)` to hook-history read
- **Source:** Reviewer A — M5
- **File:** Service layer after Fix 5 is applied
- **What to fix:** Once hook-history logic moves to the service layer (Fix 5), annotate the read method with `@Transactional(readOnly = true)`. Per project rules, read-only service methods must declare this.
- **Verdict:** AGREE — dependent on Fix 5; implement together.

### Fix 13: HTTP scheme restriction
- **Source:** Reviewer A — M1
- **File:** `UrlValidator.java:26`
- **What to fix:** Change `ALLOWED_SCHEMES` to `Set.of("https")`. If HTTP is needed for internal dev, gate it behind a `lifecycle.hooks.allow-http-scheme` config flag defaulting to `false`.
- **Verdict:** AGREE — any payload (session IDs, hook arguments) sent over HTTP is sniffable. Worth fixing, but lower priority than the SSRF issues.

### Fix 14: Concurrent file write race in LogToFileMethod
- **Source:** Reviewer A — M4
- **File:** `LogToFileMethod.java:105–106`
- **What to fix:** Use a `ConcurrentHashMap<Path, ReentrantLock>` keyed on the resolved path to serialize writes per file. Acquire lock before `Files.writeString`, release in `finally`.
- **Verdict:** AGREE — the atomicity guarantee does not hold for large writes. Can be a follow-up PR if release is time-sensitive.

### Fix 15: Loading state not surfaced in MethodHandlerFields
- **Source:** Reviewer B — M3
- **File:** `MethodHandlerFields.tsx`, `FormMode.tsx`, `useLifecycleHooks.ts:132`
- **What to fix:** Thread `isMethodsLoading` from `useLifecycleHooks` through `FormModeProps` → `MethodHandlerFieldsProps`. Render `<Spin size="small" />` or a disabled skeleton while loading, replacing the misleading "No built-in methods available" empty state.
- **Verdict:** AGREE — misleading permanent-empty message during transient loading is a UX bug worth fixing.

### Fix 16: Wrap HookHistoryPanel columns in useMemo
- **Source:** Reviewer B — M2
- **File:** `HookHistoryPanel.tsx:213–293`
- **What to fix:** `const columns = useMemo(() => [...], [])` — empty dep array since column definitions are static.
- **Verdict:** AGREE — with 15s refetch interval the pointless recreation is real. Simple fix.

### Fix 17: Span type filter placement in Traces.tsx
- **Source:** Reviewer B — M4
- **File:** `Traces.tsx:309–319`
- **What to fix:** Either move the filter control to the span detail card header where it applies, or add a label/tooltip that makes the scope explicit ("Filter spans in waterfall").
- **Verdict:** AGREE — UX confusion is real; users will expect trace-level filtering.

### Fix 18: Reset dryRunResult on modal close
- **Source:** Reviewer B — M5
- **File:** `FormMode.tsx:304–305`
- **What to fix:** `onClose={() => { setDryRunModalOpen(false); setDryRunResult(null); }}`
- **Verdict:** AGREE — stale state is harmless today but a trap for future logic. One-liner fix; do it now.

---

## Dismissed

### Dismissed 1: L3 — regex applied to `host` vs `hostLower`
- **Source:** Reviewer A — L3
- **Reason:** IPv4 addresses are decimal-only; the inconsistency is cosmetic and cannot produce a functional difference. Fix 1 (InetAddress resolution) makes this entire code path irrelevant for the security case. Not worth touching.

### Dismissed 2: L2 — `protected doPost` antipattern
- **Source:** Reviewer A — L2
- **Reason:** Valid design observation, but refactoring `HttpPostMethod`/`FeishuNotifyMethod` inheritance is a separate cleanup task. Fix 6 (shared `HTTP_CLIENT`) is the load-bearing change; visibility of `doPost` is cosmetic in this PR.

### Dismissed 3: L4 — HandlerTypeSelector redundant disabled guard
- **Source:** Reviewer B — L4
- **Reason:** Not a bug. The `if (nextType === value) return` guard is correct defensive code and does no harm. Removing it for cosmetic consistency is more churn than it's worth.

---

## Fix Priority Order

1. **Fix 1** — IPv4-mapped IPv6 SSRF bypass (CRITICAL, exploitable today)
2. **Fix 2** — Link-local 169.254.x.x SSRF / cloud metadata (CRITICAL, cloud credentials at risk)
3. **Fix 3 + Fix 5** — IDOR on dry-run + extract to service (implement together; ownership check lives in service)
4. **Fix 4** — Entity DTO projection (unblock serialization safety before merge)
5. **Fix 6** — Static HttpClient (resource leak under concurrency)
6. **Fix 9** — Header injection denylist (security, low effort)
7. **Fix 10** — Sanitize exception messages (security, low effort)
8. **Fix 7 + Fix 8** — Frontend `any` types (ESLint errors, project rule)
9. **Fix 11** — React key collision (rendering correctness)
10. **Fix 12** — `@Transactional(readOnly = true)` (implement with Fix 5)
11. **Fix 13** — HTTPS-only scheme (can be feature-flagged)
12. **Fix 15** — Loading state in MethodHandlerFields (UX bug)
13. **Fix 16** — useMemo for columns (performance)
14. **Fix 17** — Span filter placement (UX clarity)
15. **Fix 18** — Reset dryRunResult on close (defensive hygiene)
16. **Fix 14** — File write lock (concurrent write race, can be follow-up PR)

---

## Summary

| Severity | Count (A+B) | Disposition |
|----------|-------------|-------------|
| CRITICAL | 2 (A)       | BLOCK — all valid, must fix |
| HIGH     | 6 (4A + 2B) | Must fix — 3 security, 1 architectural, 2 type-safety |
| MEDIUM   | 10 (5A+5B)  | Should fix — 9 valid, Fix 14 can follow-up |
| LOW      | 7 (3A+4L)   | 3 dismissed, 4 accepted (L1 A, L1-L3 B) |

**Verdict: BLOCK — fix C1+C2+H3 first (security), then H1+H2+H4 (architecture), then frontend type errors. Re-review C1/C2/H3 after fixes before merge.**
