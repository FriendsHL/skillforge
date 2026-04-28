# N3-P2 Review A (Backend)

Reviewer: java-reviewer agent (Reviewer A)
Scope: BuiltInMethod, BuiltInMethodRegistry, MethodHandlerRunner, UrlValidator, LogToFileMethod, HttpPostMethod, FeishuNotifyMethod, LifecycleHookController (P2 additions), TraceSpanRepository

---

## CRITICAL

### C1. SSRF bypass via IPv4-mapped IPv6 — `UrlValidator.java` (all lines)

The SSRF protection relies entirely on string-based hostname matching. IPv4-mapped IPv6 addresses like `[::ffff:127.0.0.1]` (loopback) and `[::ffff:0a00:0001]` (10.0.0.1) pass every check:

- `uri.getHost()` returns `::ffff:127.0.0.1` — not in `BLOCKED_HOSTS`
- IPv4 regex patterns (`PRIVATE_10`, `PRIVATE_172`, `PRIVATE_192`) never match an IPv6 string
- Java's `HttpClient` resolves `::ffff:127.0.0.1` to the IPv4 loopback and connects to it

**Exploit path:** `https://[::ffff:127.0.0.1]/internal-api` → `UrlValidator.validate()` returns `null` → `HttpClient` connects to `127.0.0.1`.

Similarly `::ffff:a00:0001` = 10.0.0.1, `::ffff:c0a8:0101` = 192.168.1.1 — the entire RFC1918 block can be reached this way.

**Fix:** After extracting `host`, resolve it to an `InetAddress` and re-check the resolved IP against the blocklist before returning `null`. Alternatively, reject all IPv6 addresses that contain `::ffff:` prefix.

---

### C2. Missing link-local range `169.254.0.0/16` — `UrlValidator.java`

The blocklist omits link-local addresses. The AWS EC2 instance metadata service (`http://169.254.169.254/latest/meta-data/`) is reachable at this address and returns IAM credentials in plaintext. GCP (`169.254.169.254`) and Azure (`169.254.169.254`) use the same range.

An agent owner who can configure a `builtin.http.post` hook with `url = "http://169.254.169.254/latest/meta-data/iam/security-credentials/..."` can exfiltrate cloud credentials from any environment running SkillForge on EC2/GCP/Azure.

**Missing ranges:**
- `169.254.0.0/16` — link-local (CRITICAL: cloud metadata)
- `100.64.0.0/10` — shared address space (RFC 6598, Carrier-grade NAT)
- `fc00::/7` — IPv6 unique-local (equivalent to RFC1918 for IPv6)

**Fix:** Add `Pattern LINK_LOCAL = Pattern.compile("^169\\.254\\..*")` to the block list, and add `fc00:` / `fd00:` prefixes for IPv6.

---

## HIGH

### H1. JPA entity returned directly from hook-history endpoint — `LifecycleHookController.java:249`

```java
return ResponseEntity.ok(spans);   // spans = List<TraceSpanEntity>
```

`TraceSpanEntity` is a JPA entity. Returning it directly:
1. Violates the project rule "Entity exposed in response: use DTO or record projection"
2. Risks `LazyInitializationException` if the entity has any uninitialized lazy associations
3. Serializes all entity fields (including internal JPA metadata) to the client

**Fix:** Introduce a `HookHistoryDto` record and map `TraceSpanEntity → HookHistoryDto` before returning.

---

### H2. Business logic in controller — `LifecycleHookController.java:152–233`

The `dryRunHook` method performs 7 distinct business steps inline: agent load, hook config parse, event resolution, entry validation, runner lookup, context creation, and runner invocation. This is the entire execution path of a dry-run, not controller-level routing.

Per project rules: "Business logic in controllers: Controllers must delegate to the service layer immediately."

**Fix:** Extract to `LifecycleHookService.dryRun(Long agentId, String event, int entryIndex)` returning a `HookRunResult`. The controller becomes a thin adapter.

---

### H3. IDOR on dry-run endpoint — `LifecycleHookController.java:152`

```java
@PostMapping("/agents/{id}/hooks/test")
public ResponseEntity<?> dryRunHook(@PathVariable Long id, @RequestBody Map<String, Object> body) {
    Optional<AgentEntity> agentOpt = agentRepository.findById(id);
    // ← no check that the caller owns agent `id`
```

Any authenticated user with a valid Bearer token can:
1. Trigger a dry-run of another user's agent hooks — including `builtin.http.post` (makes real HTTP calls) and `builtin.log.file` (writes to disk)
2. View hook execution results from another user's configuration

The dry-run bypasses the dispatcher's depth guard and trace system (`// bypass dispatcher — no trace, no depth guard`), so there's no audit trail.

**Fix:** Add an agent-ownership check (e.g., `agent.getOwnerUserId().equals(ctx.getUserId())`). The same check is needed on `hookHistory`.

---

### H4. `HttpClient` created per invocation — `HttpPostMethod.java:103`, `FeishuNotifyMethod.java:107`

```java
HttpClient client = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build();
```

`HttpClient` is documented as "should be reused." Each `newBuilder().build()` creates:
- A new `Executor` (thread pool)
- A new connection pool (no connection reuse across calls)

Under concurrent hook execution (e.g., 10 simultaneous async hooks), this spawns 10 independent thread pools. With no bound on concurrent hooks, this is an unbounded thread creation vector.

**Fix:** Declare `private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(...).build();` as a class-level constant in both classes (or inject a shared `@Bean HttpClient`).

---

## MEDIUM

### M1. HTTP scheme accepted for external webhooks — `UrlValidator.java:26`

```java
private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
```

`builtin.http.post` and `builtin.feishu.notify` can send webhook payloads (session IDs, user data, hook inputs) over unencrypted HTTP. A passive network observer on any hop between the server and the target can capture these.

**Fix:** Restrict to `Set.of("https")`. If HTTP is needed for local dev, add a config flag `lifecycle.hooks.allow-http-scheme=false`.

---

### M2. HTTP header injection via user-controlled headers — `HttpPostMethod.java:116–120`

```java
for (Map.Entry<?, ?> entry : headerMap.entrySet()) {
    if (entry.getKey() != null && entry.getValue() != null) {
        reqBuilder.header(entry.getKey().toString(), entry.getValue().toString());
    }
}
```

No validation is performed on header keys. A caller can:
- Override `Host` — potential request misdirection
- Override `Content-Type` — can affect server-side parsing
- Inject sensitive headers like `Authorization` pointing to attacker-controlled tokens

Java's `HttpClient` will throw `IllegalArgumentException` for CRLF sequences in header values, so HTTP response splitting is blocked. But key-name override attacks are not.

**Fix:** Maintain a `BLOCKED_HEADER_NAMES = Set.of("host", "content-type", "content-length", "transfer-encoding", "connection")` denylist. Check `entry.getKey().toString().toLowerCase()` against it and skip or return failure.

---

### M3. Exception details forwarded to caller — `MethodHandlerRunner.java:67–69`, `HttpPostMethod.java:135`

```java
return HookRunResult.failure(
    "exception:" + e.getClass().getSimpleName() + ":" + e.getMessage(), dur);
```

`e.getMessage()` from `IOException`, `ConnectException`, etc. can contain:
- Internal host/IP addresses and port numbers
- Filesystem paths (e.g., from `LogToFileMethod`)
- Connection refused details that confirm internal service topology

Since `dryRunHook` forwards `HookRunResult.errorMessage()` verbatim to the HTTP response, these details reach the caller.

**Fix:** Log the full message server-side; return a sanitized code like `"network_error"` or `"io_error"` to the client.

---

### M4. Concurrent file write race in `LogToFileMethod` — `LogToFileMethod.java:105–106`

```java
Files.writeString(resolved, line, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
```

POSIX `O_APPEND` atomicity is only guaranteed for writes ≤ `PIPE_BUF` (typically 4096 bytes). A JSON log line with a large `args` map can exceed this. Two concurrent hook executions writing to the same file on NFS or Windows can interleave and corrupt the JSONL format.

**Fix:** Use a `FileLock` (or a `ConcurrentHashMap<Path, ReentrantLock>` keyed on path) to serialize writes per file, or use a dedicated log-appender that batches writes.

---

### M5. Missing `@Transactional(readOnly = true)` for hook-history — `LifecycleHookController.java:246`

```java
List<TraceSpanEntity> spans = traceSpanRepository.findHookHistoryByAgentId(
        id, PageRequest.of(0, clampedLimit));
```

The repository query runs without an explicit transaction. Per project rules: "Read-only service methods must declare `@Transactional(readOnly = true)`." Without it, Hibernate creates a new session per load, disabling first-level cache and preventing lazy-loading optimization.

After H2 is fixed (moving logic to service), add `@Transactional(readOnly = true)` to the service method.

---

## LOW

### L1. Missing test for `HttpPostMethod` — no test file

All other built-in methods have unit tests (`LogToFileMethodTest`, `FeishuNotifyMethodTest`, `UrlValidatorTest`). `HttpPostMethod` has none. At minimum, the following should be covered:
- SSRF rejection (same patterns as `UrlValidator` tests)
- Custom header override attempts (blocked key names)
- Body serialization fallback (no `body` arg → serialize full `args`)
- Non-2xx response returns failure

---

### L2. `protected doPost` signals subclassing but no subclass exists — `HttpPostMethod.java:86`

```java
protected HookRunResult doPost(String url, Map<String, Object> args, ...) {
```

`FeishuNotifyMethod` does not extend `HttpPostMethod`; it duplicates the HTTP call logic. The `protected` method implies subclassing intent against a Spring `@Component` singleton, which conflicts with CGLib proxy behavior and is an anti-pattern.

**Fix:** Either have `FeishuNotifyMethod extends HttpPostMethod` and call `doPost(...)`, or extract the shared HTTP logic to a `package-private` `HttpHelper` class that both delegate to. Remove `protected` if neither is done.

---

### L3. `UrlValidator` private-range regexes applied to `host` (not `hostLower`) — `UrlValidator.java:78–81`

The `BLOCKED_HOSTS` check uses `hostLower`, but the private-range patterns are matched against `host` (original case):

```java
if (PRIVATE_10.matcher(host).matches() ...
```

IPv4 addresses are always decimal digits and dots, so this is not a functional bug. However it's a readability inconsistency — a future maintainer who adds a hex-based pattern might not notice the inconsistency.

---

## Summary

| Severity | Count | Key findings |
|----------|-------|--------------|
| CRITICAL | 2 | IPv4-mapped IPv6 SSRF bypass, missing link-local range (169.254.x.x) |
| HIGH     | 4 | Entity exposed in response, business logic in controller, IDOR on dry-run, HttpClient per invocation |
| MEDIUM   | 5 | HTTP scheme allowed, header injection, exception leak, file write race, missing @Transactional |
| LOW      | 3 | Missing HttpPostMethod test, protected doPost antipattern, regex inconsistency |

**Overall assessment: BLOCK**

The two CRITICAL SSRF bypasses (IPv4-mapped IPv6 and 169.254.x.x) must be fixed before any deployment. The `UrlValidator` is the single security boundary for all outbound HTTP from hooks; a bypass there is a full SSRF.

The H3 IDOR issue (dry-run triggering another user's hook side effects without ownership check) should also be resolved before this ships.

H1 (entity exposure) and H2 (business logic in controller) are architectural violations per project rules that should be fixed in this PR.

H4 (HttpClient per invocation) is a correctness-adjacent resource issue that should be fixed now before high-concurrency usage causes thread exhaustion.
