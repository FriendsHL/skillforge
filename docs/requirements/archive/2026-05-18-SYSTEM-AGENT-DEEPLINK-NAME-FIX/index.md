---
id: SYSTEM-AGENT-DEEPLINK-NAME-FIX
mode: mid
status: resolved
priority: P1
risk: Low
created: 2026-05-18
updated: 2026-05-18
resolved: 2026-05-18
follows: SYSTEM-AGENT-TYPING
---

# SYSTEM-AGENT-DEEPLINK-NAME-FIX — SessionList ?agentId deep-link 显空白

## 2026-05-18 已解决 (commit `7aa8a63`)

**Fix path A** (本 backlog 推荐方案) 已落地:
- `SessionEntity` 加 `@Transient agentName` 字段 + getter/setter (跟现 channelPlatform 同款 @Transient pattern, 不持久化)
- `ChatController` 加 `enrichAgentName(sessions)` helper: distinct agentIds → `agentService.getAgent` loop → Map<Long, String> → setAgentName per session. `listSessions` 内调

**真活验证 (2026-05-18 BE restart 后)**:
- `GET /api/chat/sessions?userId=0&agentType=system` 真返 203 system sessions (之前 0 row)
- 每个 session JSON 含 `agentName: "attribution-curator"` (之前 `<MISSING>`)
- FE `SessionList.normalizeSession` `raw.agentName` 优先 → `s.agent = "attribution-curator"` → filter 比 `"attribution-curator" === "attribution-curator"` **真匹配**
- mvn -pl skillforge-server test: 1838/0/0/104 BUILD SUCCESS
- Iron Law: 核心 7+1 BE 0 diff (改 ChatController + SessionEntity 非核心)

## 痛点

V7 SYSTEM-AGENT-TYPING Phase 2 W2 fix (`/sessions?agentId=N` deep-link) 把 query param translate 成 agent NAME 然后 `setFilterAgent(name)`. **但 BE SessionEntity JSON 没 `agentName` 字段**, FE `SessionList.tsx:61` `normalizeSession` 走 fallback `String(raw.agentId)` → `s.agent = "9"` (数字字符串).

filter 时 `s.agent !== filterAgent` 比较 `"9" !== "attribution-curator"` → **永远不匹配** → 0 row 显示 → **页面空白**.

**真活验证 (2026-05-18)**:
- BE `GET /api/chat/sessions?userId=0&agentType=system` 真返 203 个 system agent session
- 每个 session JSON 字段含 agentId (=9), 但 `agentName` 字段缺失 (BE 没 enrich)
- Dashboard 进 `/agents` → toggle Show system agents → 点 attribution-curator (id=9) View Sessions → 跳 `/sessions?agentId=9` → 看 0 session row (实际 BE 真有 203 个)

## 范围

Mid 档, ~0.5d.

### F1 推荐 fix path A — BE enrich agentName

在 `ChatController.listSessions` (`/api/chat/sessions`) enrich 路径加 setAgentName from `agentRepository.findById(s.getAgentId()).map(Agent::getName)`. SessionEntity 加 `@Transient` agentName 字段 + JSON 透传.

```java
// ChatController.java:617 listSessions 内
final List<SessionEntity> sessions = ...;
enrichChannelPlatform(sessions);
enrichAgentName(sessions);  // ← 新加
return ResponseEntity.ok(sessions);
```

### F2 备选 fix path B — FE normalizeSession 用 agents list 反查

FE `SessionList.tsx` `useQuery(['agents'])` fetch agents list, normalizeSession 接 agentId + agents map 翻 name. 缺点: 多 1 个 fetch, agents list 没 cache 时 race.

**推荐 F1** — BE 端一处 fix, FE 不动.

### F3 测试

- BE: `SessionEntityAgentNameEnrichTest` 验 `/api/chat/sessions` JSON 含 agentName 字段
- FE: SessionList.test.tsx 加 case "deep-link agentId resolves to agent name from BE response"
- E2E: agent-browser navigate `/agents` → toggle system → click attribution-curator View Sessions → 看 SessionList 真显该 agent 的 session

## 不在范围内

- 不动 SessionEntity DB schema (只加 @Transient JSON field, 不持久化)
- 不动 V7 Phase 2 deep-link logic (auto-switch tab / setSearchParams drop param)
- 不动 SessionService.listSessionsByAgentType (BE query 逻辑正确, 只是 JSON enrich 漏)

## 链接

- 前置: [V7 SYSTEM-AGENT-TYPING archive](../../archive/2026-05-17-SYSTEM-AGENT-TYPING/index.md)
- 发现日期: 2026-05-18 (SOP S1.3 dogfood 真活验证)
