# SkillForge

[中文文档](README_CN.md)

**Server-side Agentic AI Platform** — configurable Skills, multi-provider LLMs, multi-agent collaboration, and a self-improving eval pipeline. Built with Spring Boot + React for teams who want full control over their agent infrastructure.

## Why SkillForge?

Most agent frameworks are Python-based, single-provider, and designed for prototyping. SkillForge is built for **production Java/Spring teams** that need:

- **Multi-provider LLM** — swap between Claude, DeepSeek, Bailian/DashScope, vLLM, Ollama without code changes
- **Multi-channel gateway** — one agent answers via Web, CLI, Feishu (WebSocket or webhook), Telegram, and personal WeChat (native iLink adapter: QR login, no public IP); `ChannelAdapter` SPI extends to Discord/Slack/iMessage with zero framework changes
- **Real agent orchestration** — not just chains, but tree (SubAgent) and network (TeamCreate/Send) topologies with persistent state
- **Orchestrate external coding agents** — drive **Claude Code & Codex over ACP** (open Agent Client Protocol) as git-worktree-isolated SubAgents that test their own work and open PRs — channel-driven self-iteration on your real codebase
- **Self-improving agents** — automated eval, prompt A/B testing, and promotion pipelines
- **Full observability** — Langfuse-style traces, session replay, model usage dashboards
- **Safety guardrails** — configurable lifecycle hooks, command blocklists, path-traversal prevention, anti-runaway loop detection

## Screenshots

**Live trace waterfall while the agent works** — every chat streams a per-loop activity rail (LLM calls, tool spans, timing) right beside the conversation, with `SubAgent` / `Team` tabs to watch orchestrated runs.

![Chat with live activity waterfall](.github/screenshots/chat-activity-waterfall.png)

| Agents | Skills |
|:---:|:---:|
| ![Agents](.github/screenshots/agents.png) | ![Skills](.github/screenshots/skills.png) |
| Per-agent model, rules, hooks, skills & tool bindings | Installable, versioned building blocks (system + custom) |

**Traces** — full span waterfall per run (agent → LLM → tool) with latency / tokens / cost and I/O inspection.

![Traces](.github/screenshots/traces.png)

**Channels** — Feishu, Telegram, and personal WeChat connections from one gateway.

![Channels](.github/screenshots/channels.png)

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│ Dashboard │ iOS Companion │ CLI │ Feishu │ Telegram │ WeChat │
├─────────────────────────────────────────────────────────┤
│              Channel Gateway                            │
│   ChannelAdapter SPI │ 3-phase delivery tx │ dedup      │
├─────────────────────────────────────────────────────────┤
│              Entry Points                               │
│   REST API   │   WebSocket (per-session + per-user)     │
├─────────────────────────────────────────────────────────┤
│              Spring Boot Server                         │
│   Chat / Agent / Skill / Memory / Compaction            │
│   SubAgent / Multi-Agent Collab / Eval Pipeline         │
│   Lifecycle Hooks / Behavior Rules / Hook Methods       │
├─────────────────────────────────────────────────────────┤
│              Agent Loop Engine                          │
│   Message → LLM (streaming) → tool_use → Tool → loop    │
│   cancel + anti-runaway + context-compact + traces      │
├──────────────┬──────────────┬───────────────────────────┤
│  LLM Layer   │ Tool & Skill │ Session & Hooks           │
│  Claude      │ System Tools │ Session Mgmt              │
│  OpenAI*     │ System Skills│ Lifecycle Hook Dispatcher │
│  DeepSeek    │ User Skills  │ SafetyHook / AskUser      │
│  Bailian     │ Hook Methods │ Compact / Memory          │
├──────────────┴──────────────┴───────────────────────────┤
│              Storage                                    │
│   Embedded PostgreSQL (dev) │ External PG (prod)        │
│   + pgvector (memory) + Flyway migrations               │
└─────────────────────────────────────────────────────────┘
* OpenAI-compatible: works with DeepSeek, DashScope/Bailian,
  vLLM, Ollama, or any OpenAI-format endpoint.
```

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 3.2, Java 17, JPA / Hibernate, Flyway |
| Frontend | React 19, Ant Design 6, Vite 8, TypeScript 5.9, TanStack Query |
| iOS | SwiftUI, iOS 17+, Swift 6 language mode, XcodeGen, XCTest / XCUITest |
| Database | Embedded PostgreSQL (zero-install dev), external PostgreSQL (prod) |
| Realtime | WebSocket — per-session streaming + per-user notifications |
| LLM | Multi-provider: Claude, OpenAI-compatible (DeepSeek, DashScope/Bailian, vLLM, Ollama) |
| HTTP Client | OkHttp 4 + SSE streaming |
| Build | Maven multi-module |

## Module Structure

```
skillforge/
├── skillforge-core         # Agent Loop engine, LLM abstraction, Skill system,
│                            #   Hooks, Cancellation, Context Compaction, TraceCollector
├── skillforge-tools        # System Tools: Bash, FileRead, FileWrite, FileEdit,
│                            #   Glob, Grep, WebFetch, WebSearch, Memory,
│                            #   SubAgent, Team*, Register*Method
├── skillforge-server       # Spring Boot server: REST API, JPA entities, services,
│                            #   WebSocket, Multi-Agent Collab, Memory, Eval Pipeline
├── skillforge-dashboard    # React dashboard: login, chat, sessions, agents, tools,
│                            #   skills, traces, replay, memories, usage, teams,
│                            #   eval, channels, hook methods
├── skillforge-ios          # Native SwiftUI companion: QR pairing, chat, sessions,
│                            #   schedules, agents, attachments, settings
├── skillforge-cli          # CLI client: picocli + OkHttp, agent YAML import/export
└── system-skills/          # File-based system skills (auto-loaded, non-deletable)
    ├── browser/            #   Browser automation via agent-browser CLI
    ├── clawhub/            #   ClawHub marketplace search + install
    ├── github/             #   GitHub API + gh CLI
    ├── skill-creator/      #   Scaffold / edit / validate SKILL.md packages
    └── skillhub/           #   SkillHub marketplace
```

## Features

### Core Agent Loop

- **Agentic loop** with multi-provider LLMs and a built-in skill system. Every chat session runs asynchronously on a thread pool with 429 backpressure.
- **Streaming chat** — assistant text and tool_use JSON stream token-by-token over WebSocket; the dashboard renders deltas live with a tool card showing partial JSON as the LLM emits it.
- **Loop cancel** — `POST /cancel` flips a flag checked at iteration boundaries; the dashboard shows a cancel button on the running banner.
- **Ask mode vs Auto mode** — per-session configuration. In ask mode the engine injects an `ask_user` tool for multiple-choice decisions; auto mode suppresses it.

### Multi-file Agent Configuration

Inspired by Claude Code and OpenClaw:

| File | Scope | Purpose |
|------|-------|---------|
| **CLAUDE.md** | Global | Rules and guidelines for all agents |
| **AGENT.md** | Per-agent | Core instructions (`systemPrompt`) |
| **SOUL.md** | Per-agent | Persona & tone (optional) |
| **TOOLS.md** | Per-agent | Custom tool usage rules (optional) |
| **RULES.md** | Per-agent | Behavior rules (structured + free-form) |
| **MEMORY.md** | Per-agent | Auto-injected from Memory system |

### Agent & Model Controls

- **Local-token auth** — the dashboard has a `/login` route backed by `/api/auth/local-token` and Bearer-token verification for the authenticated `/api` surface.
- **Backend-driven model catalog** — `GET /api/llm/models` exposes provider/model options to the dashboard instead of hard-coding model lists in the UI.
- **Per-agent runtime controls** — `modelId`, visibility, execution mode, max loop count, selected tools/skills, behavior rules, lifecycle hooks, and prompt sections can be configured per agent.
- **Thinking mode / reasoning effort** — agents can opt into provider-aware `thinkingMode` and `reasoningEffort`; the OpenAI-compatible adapter preserves or drops `reasoning_content` according to each provider family.
- **YAML import/export** — agents can be versioned as files through the API and CLI.

### Multi-Channel Gateway

One agent, many surfaces — a user message from Feishu, Telegram, WeChat, or the web arrives at the **same** agent loop and the reply is delivered back to the originating channel:

- **Pluggable `ChannelAdapter` SPI** — Spring auto-collects implementations; new platforms drop in with zero framework changes
- **Feishu (Lark)** — both **WebSocket long-polling** (no public IP needed for dev) and webhook mode; SHA-256 event signature verification; mode-switch in the dashboard with graceful reconnect (exponential backoff + jitter)
- **Telegram** — HTML parse mode with 4096-codepoint safe splitting
- **WeChat (personal account)** — native **iLink** adapter (no openclaw/bridge, no extra process): QR-code login, **outbound long-poll** inbound (works behind NAT, zero public callback), text two-way, and file/image send via CDN upload + AES-128-ECB. Per-message `client_id` for dedup-safe delivery; reverse-engineered protocol isolated in its own adapter
- **3-phase delivery transaction** — `claimBatch` via `SELECT FOR UPDATE SKIP LOCKED`, `IN_FLIGHT` guard on first enqueue, `applyPrepared` → `persist` — survives crashes, no duplicate delivery under 30-second race windows
- **Per-turn `platformMessageId` mapping** — a single session reply across multiple turns without unique-constraint collisions
- **Dedup + retry + stale sweeper** — configurable retry policy, exponential backoff, expired-message cleanup
- **Dashboard /channels page** — per-platform config, conversation list, delivery retry panel

### Behavior Rules Layer

Configurable rule library injected into the system prompt per-agent, governing agent conduct (what to do / what not to do):

- **15 built-in rules** — `no-commit`, `no-push-without-approval`, `ask-before-delete`, `prefer-immutable`, and more
- **Preset templates** — preset bundles for common agent shapes (coding / research / ops) in one click
- **Custom rules** — per-agent free-form rules, XML-sandboxed to resist prompt injection
- **Language-aware** — auto-detected locale, rule text localizes
- **Deprecated chains** — old rules redirect to replacements without breaking existing agents

### Lifecycle Hooks

User-configurable hooks that fire at key points in the agent loop — same mental model as Claude Code hooks, plus a multi-entry chain per event:

| Event | Use cases |
|-------|-----------|
| **SessionStart** | Inject context, load prior state, block under quota |
| **UserPromptSubmit** | Enrich prompt with dynamic context, redact secrets, abort |
| **PreToolUse** | Gate a tool call, rewrite arguments, require approval |
| **PostToolUse** | Audit, telemetry, notify on failure |
| **SessionEnd** | Summarize, tear down resources, ship transcripts |

**Four handler types**:

- **Skill** — invoke an existing skill (reuse logic)
- **Script** — inline `bash` / `node` script; sandboxed, process-tree kill, output-size cap, `/tmp` symlink protection
- **BuiltInMethod** — named helpers (`HttpPost`, `FeishuNotify`, `LogToFile`) with structured args; SSRF-hardened URL validator
- **CompiledMethod** — compile a Java class at runtime (`javax.tools`) → approval workflow → `child-first` classloader isolation (delivered via the **Code Agent**)

Hooks form a **chain per event** with independent `timeoutSeconds`, `failurePolicy` (CONTINUE / ABORT), `async`, and `SKIP_CHAIN` semantics. All execution traced to `LIFECYCLE_HOOK` spans for debugging.

### Code Agent (Self-extending Hooks)

An agent that can **write its own hook methods**. Phase 1-3 delivered:

- **CodeSandboxSkill + CodeReviewSkill** — isolated execution sandbox with dangerous-command checker and sandboxed `HOME`
- **ScriptMethod** — bash/node scripts take effect immediately
- **CompiledMethod** — Java classes compiled in-process with a `FORBIDDEN_PATTERNS` safety scan, child-first classloader, and a `submit → compile → approve` workflow
- **Dashboard HookMethods page** — dual tabs (script / compiled), detail drawer, approval actions

### SubAgent Orchestration (Tree Topology)

Parent→child task delegation:

- `SubAgent dispatch` spawns a child session that runs asynchronously
- Child results auto-deliver back as a synthetic user message
- Persistent across server restarts (`t_subagent_run` + startup recovery + sweeper)
- Depth limit (3) + concurrent children limit (5 per parent)

### Multi-Agent Collaboration (Network Topology)

Team-based collaboration where a leader dispatches agents that communicate with each other:

| Tool | Description |
|------|-------------|
| **TeamCreate** | Spawn a team member (fire-and-forget, result auto-delivers) |
| **TeamSend** | Send to peer by handle, to "parent", or "broadcast" |
| **TeamList** | List all team members with status |
| **TeamKill** | Cancel a member or the entire collaboration run |

Key capabilities: CollabRun grouping, handle→session roster, adjacency policy, depth-aware tool filtering, cancel cascade, lightContext (saves ~30-50% tokens), delivery retry with dedup, stale run sweeper.

### Self-Improve Pipeline

Automated eval and prompt optimization:

- **Eval Runner** — runs agent against scenario sets (seed + held-out), with 3-level retry and 90s budget per scenario
- **LLM Judge** — 2×Haiku + Sonnet meta-judge for oracle scoring
- **Attribution Engine** — 7×5 matrix classifying failures (skill_missing, exec_failure, prompt_quality, context_overflow, performance)
- **Prompt A/B Testing** — LLM generates candidate prompts, compared on held-out scenarios, auto-promotes at Δ≥15pp with 4-layer Goodhart safeguards
- **Session→Scenario Extraction** — LLM analyzes completed sessions to generate eval scenarios, with a review UI (Approve/Edit/Discard)
- **Dashboard** — real-time eval run monitoring, detail drawer with per-scenario results

### Skill Evolution (Closed Loop, 5-Phase)

End-to-end skill lifecycle automation — production / evaluation / optimization / promotion / notification:

- **① Production** — `SkillDraftScheduledExtractor` cron @ 03:00 daily scans last-24h sessions, LLM-extracts up to 3 reusable skill drafts per agent (max 10 sessions ingested). `hasPendingDrafts` short-circuit + per-agent try/catch + exact-name skip prevents duplicate drafts.
- **② Evaluation** — `t_skill_eval_history` (V63) stores 5-dimensional scores per skill (composite / quality / efficiency / latency / cost). `POST /api/skills/{id}/evaluate` runs single-skill `runBaselineOnly` synchronously; `SkillScheduledEvaluator` cron @ Mon 04:00 scans all enabled skills with 7-day skip.
- **③ Optimization** — `SkillEvolutionService.callLlmForImprovement` ingests **last 5 EVAL failures** (`composite_score < 60`) of the target skill, feeding them as concrete examples in the LLM prompt instead of blind rewriting (resolves the original "evolve does nothing useful" gap).
- **④ Self-Improve Loop** — `SkillSelfImproveLoop` cron @ Tue 05:00: for each skill, if latest history score `< 60` → auto-trigger `createAndTrigger` (fork → improve → A/B → auto-promote on `delta ≥ 15pp + candidate ≥ 40%`). Spring `@TransactionalEventListener(AFTER_COMMIT, REQUIRES_NEW)` decouples from the A/B run thread.
- **⑤ Notification & Dashboard** — WS event `skill_auto_upgraded` on every promote → notification toast; `Latest Score` Tag + `Trend` sparkline columns in skill table; drawer Eval History 5-dim line chart + Auto-Evolve Runs panel.

**Manual override APIs** (V2.5 polish):
- `POST /api/skills/abrun/{abRunId}/promote-manual` — promote a candidate that did not pass auto-thresholds, after operator review of A/B results
- `POST /api/skills/{id}/rollback` — disable a candidate and re-enable its parent (V64-safe ordering)
- `PUT /api/skills/{id}/skill-md` — manually edit a candidate's SKILL.md content (gated to `parentSkillId != null && !enabled` so active versions can never bypass A/B evaluation)

**Storage invariants**:
- `t_skill` partial unique on `(owner_id, name) WHERE enabled = TRUE` (V64) — disabled candidates can share name with parent without colliding
- `forkSkill` allocates an **isolated** skill directory (`evolution-fork/{owner}/{parent}/{uuid}`) and copies parent SKILL.md, so deleting a candidate never wipes the parent's artifact
- Sibling-aware `deleteSkill` re-registers an enabled sibling into `SkillRegistry` and skips on-disk dir removal when other rows still reference the path

**Skill File Browser** (V2.5) — drawer renders the full package tree (SKILL.md + `references/` + `scripts/` + `assets/` + `hooks/`) instead of only SKILL.md, with click-to-view content. `GET /api/skills/{id}/files` + `/files/content` for both user skills and `system-X` system skills.

**Dashboard skill summary** — `GET /api/dashboard/skill-summary?userId` returns per-user counters (auto-upgraded this week, pending drafts, failed evolutions, total enabled skills, low-score skills) for the "operations at a glance" Dashboard top card.

**Skill Creator with Eval (Phase 1.6, 2026-05)** — `skill-creator` system skill now ships a full eval loop: every new draft (uploaded, extracted-from-session, marketplace, natural-language) auto-runs `with_skill` vs `without_skill` A/B baseline against target-agent SubAgent children, judged by `EvalJudgeTool.judgeMultiTurnConversation`. Operator triggers manual evaluation via SkillDrafts → "Trigger Evaluation" modal (target-agent picker + auto-built ephemeral scenarios from source session); status flips to `evaluated_passed` (≥5pp delta) or `rejected`. `SkillDraftDetailDrawer` → "Evaluation Report" tab renders 5-dim benchmark table + LLM verdict summary.

### Flywheel Observability Panel

Operator-facing workflow DAG of the entire skill / prompt / behavior-rule flywheel (Insights → **Flywheel** tab, 2026-05):

- **15-step DAG** rendered with **React Flow + dagre** LR auto-layout, arrows show data flow direction (`ENTRY → ① annotate → ② cluster → ③ attribute → G1 approve → ④ candidate → G2 review → ⑤ A/B → ⑥ gate → G3 promote → ⑦⑧⑨ canary/metrics/decide`)
- **4 node types** with left-border color encoding: 🤖 **AUTO** (blue, cron-driven), 👤 **USER gate** (orange, operator review), 🔀 **HYBRID** (purple, auto + manual triggerable), 🚪 **ENTRY** (green: chat session / upload skill / extract-from-session / write-prompt), ⏸ **DORMANT** (gray, V87 canary disabled)
- **5 health colors per node** (with H/W/S/D/E letter overlay for color-blind a11y): 🟢 healthy / 🟡 warn / 🔴 stale / ⚪ dormant / ⚫ empty (never lit up)
- **Running pulse animation** — AUTO + HYBRID nodes with `inFlight > 0` or `cron lastRunStatus='running'` show a 1.5s slow green ring (CSS box-shadow keyframe, compositor-friendly); `prefers-reduced-motion` users get a static green outline instead
- **Edge animation** — dashed flow when both source + target have `inFlight > 0` (data actively flowing between stages)
- **Click any node** → right-slide **detail Drawer** with Chinese label + description, 5-dim metrics (in-flight / today aggregate / lag / last activity / 24h error count + pending count for USER gates), recent 24h activity filtered to this step, "在 page 中打开 →" drill-down link footer, Esc / backdrop / close-button to dismiss
- **Dual tab navigation** — agentType (user / system) × surface (skill / prompt / behavior_rule), localStorage-persisted per first-tier tab, ARIA tablist semantics
- **Read-only by design** — no action buttons inside the panel (observability ≠ ops console); all operations live on the existing drill-down pages (1B URL-driven routing in Insights / SkillList / SessionList / SkillDrafts ensures drill-down query params are actually consumed)
- **Lazy-loaded** — reactflow + dagre (~72KB gzipped) ships as a separate chunk and only loads when operator clicks the Flywheel tab

### AutoEvolving V1 (DSL Workflow Engine + Dashboard)

A deterministic DSL workflow engine that orchestrates the self-improvement flywheel as code, plus an `/autoevolving` operator dashboard (2026-05, inspired by Karpathy's autoresearch):

- **DSL workflow engine** — JavaScript workflows on a **Rhino** interpreter inside an **L1 capability sandbox** (no filesystem / no network / whitelisted host calls only), with 6 primitives (`agent` / `tool` / `parallel` / `pipeline` / `humanApprove` / journaling). `WorkflowDefinitionRegistry` registers definitions; `WorkflowRunnerService` executes and journals each step.
- **Human-in-the-loop via journal replay** — `humanApprove()` throws `WorkflowPausedException` → operator approves → the workflow JS re-runs from the top, cached journal entries short-circuit already-completed steps (no token re-spend), the frontier gate resumes. Survives server restart; the operator can approve hours later.
- **OPT-REPORT as a workflow** — the optimization-report generator is rebuilt as a DSL workflow (load → annotate fan-out → aggregate → human-approve), with the agent-driven path retained behind a feature flag.
- **Evolve loop hill-climbing** — the agent-level evolve loop runs an A/B per iteration and carries the winner forward, climbing a `weightedScore` (target-subset pass-rate + harvested bad-case oracle) with deterministic keep / stop gates (min-measured-N guard, win-streak baseline caching, vs-original anchor; infra failures excluded from the denominator).
- **`/autoevolving` dashboard** — KPI header + 3 signal-source panels + workflow DAG visualization (reuses the Flywheel React Flow + dagre layout) + anomaly diagnosis, with live WebSocket updates.

### System Agent Typing

`t_agent.agent_type` column (V89) distinguishes **system agents** (`session-annotator` / `attribution-curator` / `metrics-collector` / `memory-curator` / `user-simulator` — managed by Bootstrap classes, edits overwritten on next restart) from **user agents**:

- **AgentList toggle** — "Show system agents" Switch (localStorage persisted, default off) + purple `<Tag>System</Tag>` chip on system-agent cards
- **Inline `SystemAgentMonitorCard`** per system agent — cron schedule / last_run + status / 7d trigger count / 7d output count (cross-table aggregate: annotations / proposals / metrics / consolidations / trials) / **Run Manually** button / **View Sessions** + **View Schedule** deep-links
- **AgentDrawer system-agent banner** — ⚠️ "Managed by Bootstrap, edits overwritten" + all form inputs `readOnly` + Delete button disabled
- **Chat send gate** — system agents render input + send disabled with Info Alert "System agents are read-only via Chat"
- **Flywheel layer-1 root-cause fix** — `SessionAnnotationSignalService` rewrites annotation queue selection to user-first / system backfill / catch-all orphan fallback (was starvation: 5 system agents' cron output dominated the top-30 createdAt DESC window → user-agent sessions never got annotated → flywheel never had data; real verification: user-agent outcome 0 → 9 within 50s after fix)

### Memory System

Persistent memory across sessions, scoped per user:

- **5 memory types**: preference, knowledge, feedback, project, reference
- **Type-slotted injection** into system prompt (8000 char cap)
- **Hybrid retrieval** — pgvector (`text-embedding-3-small`, 1536-d) + PostgreSQL `tsvector` full-text search, fused via **RRF**; graceful fallback to TF-IDF when pgvector unavailable
- **Two extraction modes**:
  - `rule` — fast heuristic `SessionDigestExtractor` (default)
  - `llm` — `LlmMemoryExtractor` classifies entries into 5 types with importance scoring
- **Idle-window extraction** — an idle scanner extracts recent turns once a session has been quiet long enough, with cooldowns and max-turn bounds
- **Auto-capture** via `ActivityLogHook` — records every tool call
- **Lifecycle management** — `ACTIVE` / `STALE` / `ARCHIVED` statuses, batch archive/restore/delete, capacity limits, stale/archive/delete windows
- **Quality controls** — memory snapshots, visibility/attribution fields, rollback and refresh APIs
- **Dream Consolidation cron** (V2.5) — `MemoryConsolidationScheduler` @ 03:30 daily runs `MemoryConsolidator.consolidate(userId)` for every recently-active user, performing in order:
  1. **Embedding dedup** — pairwise cosine over ACTIVE memories, archive lower-scored side at `cosine ≥ 0.85` with `archived_reason = dedup_merge_with_<winnerId>` (gracefully skipped if pgvector or embedding API is unavailable)
  2. **Score & lifecycle transition** — `0.45·importance + 0.35·freshness + 0.20·usage` rescore; ACTIVE→STALE / non-ARCHIVED→ARCHIVED based on age + recall count
  3. **Capacity enforcement** — demote lowest-scored ACTIVE rows to STALE when user crosses `max-active-per-user` (default 1500)
- **Per-action tracking** — `archived_reason VARCHAR(128)` (V66): `expired_ttl` / `capacity_demote` / `dedup_merge_with_X`. The admin endpoint returns granular phase counts: `dedupArchived / ttlArchived / staleTransitioned / capacityDemoted / expiredDeleted / activeAfter` so operators verify what actually happened without SQL.
- **Manual trigger** — Memory list page "Run Consolidation" button + `POST /api/admin/memory/consolidation/run-once?userId=X`

### Session Message Storage (Row-based, Immutable)

**Core invariant: messages are append-only** — what the UI shows is the full history, compaction never loses old turns:

- `t_session_message` row storage (replaced a single CLOB) with `seq_no` / `role` / `content_json` / `msg_type` / `metadata_json`
- `getFullHistory` (UI) vs `getContextMessages` (LLM, reads `young-gen + summary + new messages` across compact boundaries)
- Compaction inserts `COMPACT_BOUNDARY` + `SUMMARY` rows — old messages are **never deleted**
- Supports checkpoint / branch / restore

### Context Compaction (JVM-GC Style)

- **Light** — rule-based: truncate large outputs, dedup searches, fold failures, **compactable-tool whitelist** (P9-1)
- **Full** — LLM summarization preserving `tool_use ↔ tool_result` pairing, 3-phase split (guard → LLM (no lock) → persist) for concurrency
- **Time-based cold cleanup** — prunes stale tool outputs after session idle threshold (P9-3)
- **Session-memory compact** — zero-LLM fallback using `MemoryService.previewMemoriesForPrompt` (P9-6)
- **6 trigger sources**: token-budget safety net, idle-gap, LLM tool call, engine auto-trigger, manual API, waste detection
- **Context Breakdown API** — `GET /api/chat/sessions/{id}/context-breakdown` returns layered segment sizes (system prompt / tool schemas / messages) against the agent's real context window
- All events recorded in `t_compaction_event` for audit

### Observability

**Traces (Langfuse-style)** — every agent loop as structured trace with hierarchical spans:

```
AGENT_LOOP (root)
├── LLM_CALL (iteration 0 — tokens + duration)
├── TOOL_CALL (Bash — input, output, duration)
├── LLM_CALL (iteration 1)
├── ASK_USER (blocked waiting for user)
├── COMPACT (context compaction)
└── LLM_CALL (final iteration)
```

**Session Replay** — restructures flat message history into Turns → Iterations → Tool calls with timing.

**Chat message timestamps (2026-05)** — every user / agent bubble shows its server-side persisted `t_session_message.created_at` as `HH:MM:SS` on hover (opacity 0 default + `:focus-within` a11y fallback). REST `/api/chat/sessions/{id}/messages` exposes `createdAt`; WS `message_appended` + `messages_snapshot` payloads include envelope-level `createdAt` for live messages.

**Model Usage Dashboard** — daily/by-model/by-agent token consumption and cost tracking.

### MCP Client (Model Context Protocol)

SkillForge as a Model Context Protocol *host* — connect external **stdio and remote HTTP** MCP servers and expose their tools to agents:

- **stdio transport** — NDJSON line-framed JSON-RPC 2.0; `ProcessBuilder` array form (no shell injection); strict `${VAR}` env regex with `Matcher.quoteReplacement` against `$/\` reference attacks
- **HTTP transport (Streamable HTTP, V152)** — connect remote hosted MCP servers. Per-request `POST` with `Accept: application/json, text/event-stream`; response demux by `Content-Type` (direct JSON or SSE `data:` frame matched by request id); captures + echoes `Mcp-Session-Id`; 16 MiB response-body cap; thread-safe (OkHttp + volatile session id). The `McpTransport` interface is a clean extension point — session / adapter / registry layers are transport-agnostic and untouched
- **Transport security** — non-loopback http endpoints must use `https` (a resolved `Authorization` header never travels in clear text); URL hosts are resolved and **rejected if internal / private / link-local (incl. the `169.254.169.254` cloud-metadata endpoint) / IPv6 ULA / CGNAT** (SSRF guard). Header secrets reuse the same `${VAR}` substitution + `"***"` masking as env
- **Per-agent server enable** — `t_agent.mcp_server_ids` comma-list (V61); agents only see tools from enabled servers — both `collectTools` (surface) and dispatch gate MCP tools by `allowedMcpServerNames` rather than the built-in `toolIds` whitelist, so a tool-restricted agent still sees its bound MCP tools
- **Tool prefixing** — every MCP tool is registered as `mcp_<server>_<tool>` to avoid name collisions with built-in tools; per-server name regex `[a-z0-9_]+ ≤ 32`
- **Lifecycle reload** — `@TransactionalEventListener(AFTER_COMMIT) + @Transactional(propagation=REQUIRES_NEW)` reloads the registry on `t_mcp_server` upsert without restart
- **Secret masking** — server config edits return `"***"` for sensitive env **and header** values; backend preserves originals on `***` round-trip
- **Default dogfood servers** — `time` (stdio, Anthropic official `uvx mcp-server-time`) + **AnySearch** (HTTP, structured vertical data — real-time stock / forex / crypto quotes, financial statements, academic citations via Crossref, CVE, patents across 17 domains), bound to the Research Agent + Main Assistant with `tools_prompt` routing guidance (structured queries → AnySearch, general web → WebSearch)
- **Dashboard `/mcp-servers`** — full CRUD (stdio: command / args; http: url / headers key-value editor with `***` masking) + connection status + test-connection dry-run + delete reference check (409 when agents still reference)

### Slash Commands

8 chat slash commands with FE popup completion + channel BE interception:

| Command | Action |
|---------|--------|
| `/new` | Create new session (rebinds channel conversation atomically) |
| `/compact` | Trigger async fullCompact (returns toast in 0.008s, runs in `chatLoopExecutor`) |
| `/model <id>` | Set `t_session.runtime_model_override` (V60), takes effect on next iteration |
| `/models` | Modal listing all available models from `LlmProperties` |
| `/skill` | Modal listing this agent's `skill_ids` |
| `/tool` | Modal listing built-in + agent-bound tools |
| `/context` | Token breakdown by segment (`TokenEstimator` + `ContextBreakdownService`) |
| `/help` | Auto-list registry of all commands |

- **Three displayModes** — `redirect` (FE navigates), `toast` (in-line message), `modal` (full markdown render)
- **Input regex** — only first-character `/` triggers popup (`^/[A-Za-z]*$`); fuzzy match + arrow keys + Enter / Tab / Esc
- **Channel route** — `ChannelSessionRouter` intercepts `/` prefix earlier than `chatService.chatAsync`, so Feishu / Telegram users get the same commands

### Scheduled Tasks (User Cron + One-shot)

User-defined cron / one-shot triggers that run a saved prompt against an agent on schedule:

- **`t_scheduled_task` + `t_scheduled_task_run`** (V59) — task definition + execution history
- **Mutual-exclusive cron / one-shot** with conversion support
- **`UserTaskScheduler`** — `ThreadPoolTaskScheduler` + per-task `ReentrantLock` + skip-if-running guard
- **`ScheduledTaskExecutor`** — reuses or spins a fresh session, listens for `SessionLoopFinishedEvent` to push a channel reply (`waiting_user` ⇒ absolute dashboard URL)
- **5 agent tools** — `Create / Update / Delete / List / GetScheduledTask` (silent results, owner-isolated)
- **Dashboard `/schedules`** — list + edit drawer + cron↔one-shot radio toggle + per-row trigger button + run history drawer

### Prompt Cache (Multi-Provider)

Auto-cached system prompts to save 5-90% input tokens per LLM call:

- **5 LLM provider families** — Anthropic Claude (manual `cache_control` 3-breakpoint markers), DeepSeek (auto cache), DashScope/Qwen (auto), xiaomi-mimo (auto), OpenAI gpt-4o (auto)
- **Stable system prompt** — `SystemPromptBuilder.buildWithBoundary(claudeMd)` splits into deterministic `stable` (agent.systemPrompt + soulPrompt + toolsPrompt + behaviorRules; **no `Instant.now`**) + `dynamic` (Memory / sessionContext / promptSuffix), with SHA-256 byte-stability tested
- **Tool list normalization** — sort by name + `ORDER_MAP_ENTRIES_BY_KEYS` + per-tool SHA hash; cached canonical mapper avoids re-copy per call
- **`UsageNormalizer`** — single point that translates each provider's idiosyncratic cache fields (`cache_read_input_tokens` / `prompt_cache_hit_tokens` / `prompt_tokens_details.cached_tokens`) into uniform `cacheReadInputTokens` + `cacheCreationInputTokens`; OpenAI-family `inputTokens` normalized to "non-cached" semantics across providers
- **Cache break detection** — `CacheBreakDetector` (5% tolerance + 2K min drop) emits `cache_break` attribute on the LLM_CALL trace, surfaced as a red badge in the dashboard
- **V62 migration** — `t_llm_span.cache_creation_tokens` (nullable backward-compat); dashboard shows read / write / hit-rate per LLM call

### Safety & Anti-runaway

**SafetySkillHook**:
- Blocks dangerous bash commands (`rm -rf /`, `sudo`, `mkfs`, `shutdown`, `curl|sh`, fork bombs)
- Blocks unconfirmed marketplace installs
- Blocks writes to system directories and sensitive files
- Path traversal prevention

**Agent Loop Guardrails**:
- Token budget (500K), duration limit (600s), max iterations (25)
- Tool execution timeout (120s per batch), LLM stream timeout (300s)
- Tool frequency warning (≥8 calls), no-progress detection (dual hash)
- Waste detection, compact circuit breaker, tool result truncation (40K chars)

## Quick Start

### Prerequisites

- JDK 17 — must be the **active** JDK (`java -version` → 17). If `JAVA_HOME` points to JDK 8/11, Maven fails with `无效的标记: --release`.
- Maven 3.8+
- Node.js 18+ (for dashboard development)
- An LLM API key (DeepSeek, DashScope/Bailian, OpenAI, Claude, etc.)

```bash
# Example (macOS, Temurin 17)
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
```

### Build & Run (production jar)

```bash
# Set your API key
export DASHSCOPE_API_KEY=sk-your-key-here

# Build all modules — run from repo root, packages every sub-module in order
mvn clean package -DskipTests

# Start server (embedded PostgreSQL starts automatically)
cd /path/to/skillforge
java -jar skillforge-server/target/skillforge-server-1.0.0-SNAPSHOT.jar
```

Server starts at `http://localhost:8080` with embedded PostgreSQL on port 15432.

### Dev Mode (hot-reload with `spring-boot:run`)

```bash
# From repo root — FIRST install upstream modules into your local ~/.m2,
# otherwise skillforge-server can't resolve skillforge-core / skillforge-tools.
# Faster: only install the upstream modules the server actually depends on.
mvn -pl skillforge-core,skillforge-tools -am install -DskipTests

# (Slower alternative, installs every module:)
# mvn install -DskipTests

# Then run server from the server module
cd skillforge-server
mvn spring-boot:run
```

> Running `mvn spring-boot:run` directly inside `skillforge-server` **without the
> upstream `mvn install` step** fails with one of these (same root cause, different
> phase):
> - Dependency resolution: `Could not resolve dependencies ... Could not find artifact com.skillforge:skillforge-tools:jar:1.0.0-SNAPSHOT`
> - Compilation: `找不到符号: 类 BehaviorRuleRegistry` (or similar)
>
> Both mean the sibling modules haven't been published to your local Maven repo
> yet. One-shot alternative that does the whole graph in a single command:
> `mvn -pl skillforge-server -am spring-boot:run` from root.

### Dashboard Development

```bash
cd skillforge-dashboard
npm install
npm run dev    # Vite dev server at http://localhost:3000, proxied to :8080
```

### Install the iOS Companion

The current distribution path is an Xcode development build installed directly on an iPhone. TestFlight and
App Store distribution are not configured yet.

Requirements:

- macOS with Xcode and Xcode Command Line Tools.
- An iPhone running iOS 17 or later, connected to the Mac once by cable and trusted/paired with Xcode.
- An Apple ID added under Xcode **Settings > Accounts**. A free Personal Team works for local development.
- The SkillForge server and dashboard running at an address the phone can reach.

Generate and open the project:

```bash
brew install xcodegen             # one-time, if xcodegen is not installed
cd skillforge-ios
xcodegen generate                 # project.yml is the source of truth
open SkillForge.xcodeproj
```

In Xcode, select the `SkillForge` target, open **Signing & Capabilities**, choose your development team, and
change the bundle identifier if Xcode reports that `com.skillforge.companion.dev` is unavailable. Select the
paired iPhone as the run destination and press Run. On the phone, enable **Developer Mode** if iOS asks for it.

Configure the pairing endpoints before generating a QR:

```bash
cd skillforge-dashboard
cp .env.example .env.local
# Edit .env.local: keep the reachable LAN URL first and an optional Tailscale HTTPS URL second.
npm run dev
```

Then open Dashboard **Mobile Devices**, create a new QR code, scan it in the app, verify the six-digit setup
code, and pair. The app probes the saved endpoints in order: it prefers LAN and falls back to Tailscale/HTTPS.
Both devices must be on the same LAN for the LAN URL, or signed into the same tailnet for the Tailscale URL.
The QR secret is short-lived and one-time; create a new QR if it expires or if endpoint settings change.

The iOS Simulator cannot use the Mac camera to scan the Dashboard QR. Use the pairing screen's manual fallback
to paste the QR JSON and enter its setup code. Simulator tests do not validate device signing, camera access,
Keychain fidelity, LAN/Tailscale routing, background execution, attachments, or APNs.

See [`skillforge-ios/README.md`](skillforge-ios/README.md) for build and test commands.

### Configuration

Edit `skillforge-server/src/main/resources/application.yml`:

```yaml
skillforge:
  llm:
    default-provider: bailian            # or "claude", "deepseek", "openai"
    providers:
      bailian:                           # Qwen / DashScope
        type: openai                     # OpenAI-compatible endpoint
        api-key: ${DASHSCOPE_API_KEY:}
        base-url: https://coding.dashscope.aliyuncs.com
        model: qwen3.5-plus              # default model for this provider
        models:                          # surfaced as dashboard model options
          - qwen3.5-plus
          - qwen3-max-2026-01-23
          - qwen3-coder-next
          - qwen3.6-plus
          - glm-5
          - kimi-k2.5
          - MiniMax-M2.5
      claude:
        type: claude
        api-key: ${ANTHROPIC_API_KEY:}
        base-url: https://api.anthropic.com
        model: claude-sonnet-4-20250514
        models:
          - claude-sonnet-4-20250514
        context-window-tokens: 200000
      deepseek:
        type: deepseek
        api-key: ${DEEPSEEK_API_KEY:}
        base-url: https://api.deepseek.com
        model: deepseek-chat
        models:
          - deepseek-chat
          - deepseek-v4-pro
      openai:                            # works with vLLM, Ollama, etc.
        type: openai
        api-key: ${OPENAI_API_KEY:}
        base-url: https://api.openai.com
        model: gpt-4.1
        models:
          - gpt-4.1

  # Memory extraction: "rule" (fast heuristic) | "llm" (semantic, 5-type classification)
  memory:
    extraction-mode: rule
    extraction:
      idle-window-minutes: 30
      idle-scanner-interval-minutes: 10
    eviction:
      stale-after-days: 30
      archive-after-days: 60
      delete-after-days: 90
      max-active-per-user: 1500

  # pgvector-backed vector search (off by default; enable with an embedding API key)
  embedding:
    enabled: false
    api-key: ${EMBEDDING_API_KEY:}
    base-url: https://api.openai.com
    model: text-embedding-3-small
    dimension: 1536

# Lifecycle-hook script sandbox (only bash + node allowed; output capped)
lifecycle:
  hooks:
    forbidden-skills: [SubAgent, TeamCreate, TeamSend, TeamKill]
    script:
      allowed-langs: [bash, node]
      max-output-bytes: 65536
      max-script-body-chars: 4096

clawhub:
  enabled: true
  require-ask-user: true
  install-dir: ./data/skills/clawhub
```

## Tools & Skills

> **Tool vs Skill**: a **Tool** is the unit an agent can *invoke* (has a schema + `execute` method); a **Skill** is a packaged capability (Java class or file-based SKILL.md) that may register one or more Tools. Every Skill is a Tool, but user-defined Skills can also be loaded without being bound to a specific Tool schema. Tool semantics were formalized in P13-11.

### System Tools (Java, always available)

| Tool | Description |
|------|-------------|
| **Bash** | Shell commands with safety rules and timeout |
| **FileRead** | Read files with line numbers, offset/limit |
| **FileWrite** | Write/create files |
| **FileEdit** | Exact string replacement |
| **Glob** | Find files by pattern |
| **Grep** | Search contents by regex |
| **WebFetch** | Fetch URL content (size-capped) |
| **WebSearch** | Web search |
| **Memory** | Persistent memory CRUD (5 types) |
| **MemorySearch** | Hybrid pgvector + FTS search over memories |
| **MemoryDetail** | On-demand full memory body fetch |
| **TodoWrite** | Per-session todo list for plan-driven agents |
| **SubAgent** | Dispatch task to another agent (tree) |
| **TeamCreate** | Spawn team member (network) |
| **TeamSend** | Message peer / parent / broadcast |
| **TeamList** | List team members |
| **TeamKill** | Cancel member or team |
| **RegisterScriptMethod** | Agent registers a new bash/node hook method |
| **RegisterCompiledMethod** | Agent submits a Java hook method for compile + approval |

### System Skills (file-based, non-deletable)

| Skill | Description |
|-------|-------------|
| **Browser** | Web automation via `npx agent-browser` CLI |
| **ClawHub** | Marketplace search + install |
| **GitHub** | GitHub API + `gh` CLI |
| **SkillHub** | SkillHub marketplace |
| **skill-creator** | Agent-authored skills: scaffold, edit, validate SKILL.md packages |

### Engine-internal Tools

| Tool | Description |
|------|-------------|
| **ask_user** | Multiple-choice question (ask mode only) |
| **compact_context** | Request light or full context compaction |

### Lifecycle Hook Built-In Methods

| Method | Description |
|--------|-------------|
| **HttpPost** | POST to a URL with SSRF-hardened validator (blocks loopback / link-local / IPv6 traps) |
| **FeishuNotify** | Push to a Feishu group / bot |
| **LogToFile** | Append to a project-scoped log file (per-path lock) |

## API Reference

### Agents

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/agents` | List all agents |
| GET | `/api/agents/{id}` | Get agent detail |
| GET | `/api/agents/{id}/hooks` | Effective system / user / agent-authored hooks |
| POST | `/api/agents` | Create agent |
| POST | `/api/agents/import` | Import agent YAML |
| PUT | `/api/agents/{id}` | Update agent |
| PUT | `/api/agents/{id}/hooks/user` | Replace user hook JSON for an agent |
| DELETE | `/api/agents/{id}` | Delete agent |
| GET | `/api/agents/{id}/export` | Export agent YAML |
| GET | `/api/agents/{id}/hook-history` | Recent lifecycle hook execution spans |
| POST | `/api/agents/{id}/hooks/test` | Dry-run a lifecycle hook entry |
| POST | `/api/agents/{id}/prompt-improve` | Start prompt A/B improvement |
| GET | `/api/agents/{id}/prompt-versions` | List prompt versions |
| POST | `/api/agents/{id}/prompt-versions/{versionId}/rollback` | Roll back prompt version |
| POST | `/api/agents/{id}/scenario-drafts` | Extract eval scenario drafts |
| GET | `/api/agents/{id}/scenario-drafts` | List scenario drafts |
| PATCH | `/api/agents/scenario-drafts/{id}` | Approve / discard scenario draft |
| POST | `/api/agents/{id}/skill-drafts` | Extract skill drafts |

### Chat & Sessions

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/chat/sessions` | Create session |
| GET | `/api/chat/sessions?userId=1` | List user sessions |
| GET | `/api/chat/sessions/{id}` | Session detail |
| GET | `/api/chat/sessions/{id}/messages` | Full message history |
| DELETE | `/api/chat/sessions/{id}` | Delete session |
| DELETE | `/api/chat/sessions` | Batch delete (`{ids: [...]}`, max 100) |
| POST | `/api/chat/{sessionId}` | Send message (async, 202) |
| POST | `/api/chat/{sessionId}/cancel` | Cancel running loop |
| POST | `/api/chat/{sessionId}/answer` | Answer ask_user question |
| POST | `/api/chat/{sessionId}/confirmation` | Approve / deny confirmation prompts |
| PATCH | `/api/chat/sessions/{id}/mode` | Switch execution mode |
| POST | `/api/chat/sessions/{id}/compact` | Manual context compact |
| GET | `/api/chat/sessions/{id}/compactions` | Compaction event history |
| GET | `/api/chat/sessions/{id}/checkpoints` | List compact checkpoints |
| POST | `/api/chat/sessions/{id}/checkpoints/{checkpointId}/branch` | Branch from checkpoint |
| POST | `/api/chat/sessions/{id}/checkpoints/{checkpointId}/restore` | Restore checkpoint |
| POST | `/api/chat/sessions/{id}/prune-tools` | Prune old tool outputs |
| GET | `/api/chat/sessions/{id}/replay` | Structured session replay |
| GET | `/api/chat/sessions/{id}/context-breakdown` | Live segment sizes vs context window |

### SubAgent & Collaboration

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/chat/sessions/{id}/children` | List child sessions |
| GET | `/api/chat/sessions/{id}/subagent-runs` | List SubAgent runs |
| GET | `/api/collab-runs` | List collaboration runs |
| GET | `/api/collab-runs/{id}/members` | List collab members |
| GET | `/api/collab-runs/{id}/messages` | Collab message feed |
| GET | `/api/collab-runs/{id}/traces` | Collab trace list |
| GET | `/api/collab-runs/{id}/summary` | Collab summary |

### Eval Pipeline

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/eval/runs` | Trigger eval run |
| GET | `/api/eval/runs` | List eval runs |
| GET | `/api/eval/runs/{id}` | Eval run detail with sessions |
| GET | `/api/eval/scenarios` | List scenarios |

### Skills

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/skills` | List all skills |
| GET | `/api/skills/builtin` | List built-in system skills |
| GET | `/api/skills/{id}/detail` | Skill detail |
| GET | `/api/skills/{id}/versions` | Version chain |
| GET | `/api/skills/{id}/version-tree` | Tree (`?format=tree` returns root-recursive `{ children }`) |
| GET | `/api/skills/{id}/skill-md` | Read SKILL.md (with `updatedAt` mtime) |
| PUT | `/api/skills/{id}/skill-md` | **V2.5** — manually edit SKILL.md (gated to disabled candidate) |
| GET | `/api/skills/{id}/files` | **V2.5** — recursive file tree of the skill package |
| GET | `/api/skills/{id}/files/content?path=` | **V2.5** — read a specific file's content (path-traversal guarded) |
| POST | `/api/skills/upload` | Upload skill zip |
| POST | `/api/skills/{id}/fork` | Fork a skill (allocates isolated dir + copies SKILL.md) |
| POST | `/api/skills/{id}/abtest` | Start skill A/B validation |
| POST | `/api/skills/{id}/evolve` | Start skill evolution (uses recent EVAL failures) |
| POST | `/api/skills/abrun/{abRunId}/promote-manual` | **V1 polish** — promote a non-passing candidate manually |
| POST | `/api/skills/{id}/rollback` | **V1 polish** — disable candidate + re-enable parent |
| POST | `/api/skills/{id}/evaluate?userId=&agentId=&datasetId=` | **EVOLVE-LOOP** — single-skill EVAL run, writes to `t_skill_eval_history` |
| GET | `/api/skills/{id}/eval-history?userId=&limit=` | **EVOLVE-LOOP** — paginated eval history (drives 5-dim line chart) |
| POST | `/api/skills/{id}/usage` | Record skill usage outcome |
| GET | `/api/skill-drafts` | List generated skill drafts |
| PATCH | `/api/skill-drafts/{id}` | Approve / discard / rename (`{ action, newName? }`) |
| POST | `/api/skill-drafts/{id}/merge?targetSkillId=` | **V2** — merge draft content into existing skill (resolves 409 NAME_CONFLICT) |
| GET | `/api/skills/drafts?userId=&status=&page=&pageSize=` | **V2.5** — paged draft list |
| GET | `/api/skills/drafts/count?userId=&status=` | **V2.5** — pending count for badge |
| POST | `/api/skills/drafts/{id}/approve` | **V2.5 alias** — POST style approve |
| POST | `/api/skills/drafts/{id}/reject` | **V2.5 alias** — POST style reject |
| DELETE | `/api/skills/{id}` | Delete user skill (sibling-aware: re-registers active sibling, skips shared-dir wipe) |
| PUT | `/api/skills/{id}/toggle` | Toggle skill |
| POST | `/api/admin/skill-evolve-loop/extract/run-once` | Admin manual trigger for the daily extract cron |
| POST | `/api/admin/skill-evolve-loop/evaluate/run-once` | Admin manual trigger for the weekly evaluator cron |
| POST | `/api/admin/skill-evolve-loop/self-improve/run-once` | Admin manual trigger for the weekly self-improve cron |

### Observability

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/traces` | List traces |
| GET | `/api/traces/{id}/spans` | Span tree |
| GET | `/api/dashboard/overview` | Dashboard stats |
| GET | `/api/dashboard/usage/daily` | Daily usage |
| GET | `/api/dashboard/usage/by-model` | Usage by model |
| GET | `/api/dashboard/usage/by-agent` | Usage by agent |

### Memory

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/memories` | List memories |
| GET | `/api/memories/search` | Hybrid (pgvector + FTS) search with RRF |
| GET | `/api/memories/stats` | Active / stale / archived counts |
| POST | `/api/memories` | Create memory |
| POST | `/api/memories/batch-archive` | Archive selected memories |
| POST | `/api/memories/batch-restore` | Restore selected memories |
| POST | `/api/memories/batch-status` | Set lifecycle status in bulk |
| POST | `/api/memories/rollback` | Roll back memory state |
| POST | `/api/memories/refresh` | Refresh memory extraction state |
| PUT | `/api/memories/{id}` | Update memory |
| PATCH | `/api/memories/{id}/status` | Update lifecycle status |
| DELETE | `/api/memories/{id}` | Delete memory |
| DELETE | `/api/memories/batch` | Delete selected memories |
| POST | `/api/admin/memory/consolidation/run-once?userId=` | **Dream Cron** — manual trigger; returns `{ totals: { dedupArchived, ttlArchived, staleTransitioned, capacityDemoted, expiredDeleted, activeAfter } }` |
| GET | `/api/dashboard/skill-summary?userId=` | **Dashboard** — aggregated counters (auto-upgraded / pending drafts / failed evolve / total enabled / low-score) |

### Channels (Multi-Platform Gateway)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/channel-configs` | List channel configs (Feishu / Telegram / …) |
| GET | `/api/channel-configs/platforms` | List registered channel adapters |
| GET | `/api/channel-configs/{id}/test` | Test channel credentials |
| POST | `/api/channel-configs` | Create channel config |
| PATCH | `/api/channel-configs/{id}` | Update config (Feishu mode changes require restart) |
| DELETE | `/api/channel-configs/{id}` | Delete channel config |
| POST | `/api/channel-deliveries/{id}/retry` | Requeue a failed / pending delivery |
| POST | `/api/channel-deliveries/{id}/drop` | Mark a delivery as terminally failed |
| POST | `/api/channels/{platform}/webhook` | Incoming channel webhook |
| POST | `/api/channels/feishu/card-action` | Feishu interactive card callback |

### Lifecycle Hooks & Hook Methods

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/lifecycle-hooks/events` | Available hook events + presets |
| GET | `/api/lifecycle-hooks/presets` | Hook preset configs |
| GET | `/api/lifecycle-hooks/methods` | List registered built-in / script / compiled methods |
| GET | `/api/script-methods` | List script hook methods |
| POST | `/api/script-methods` | Register a bash/node script method |
| PUT | `/api/script-methods/{id}` | Update script method |
| POST | `/api/script-methods/{id}/enable` | Enable / disable script method |
| GET | `/api/compiled-methods` | List compiled hook methods |
| POST | `/api/compiled-methods` | Submit Java source for review |
| POST | `/api/compiled-methods/{id}/compile` | Compile submitted Java source |
| POST | `/api/compiled-methods/{id}/approve` | Approve compiled method |
| POST | `/api/compiled-methods/{id}/reject` | Reject compiled method |
| POST | `/api/agent-authored-hooks/{id}/approve` | Approve agent-authored hook binding |
| POST | `/api/agent-authored-hooks/{id}/reject` | Reject agent-authored hook binding |
| PATCH | `/api/agent-authored-hooks/{id}/enabled` | Enable / disable approved agent-authored hook |
| GET | `/api/behavior-rules` | List built-in behavior rules |
| GET | `/api/behavior-rules/presets` | List preset rule bundles |

### Auth, Tools & User Config

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/auth/local-token` | Issue local development token |
| POST | `/api/auth/verify` | Verify Bearer token |
| GET | `/api/tools` | List Java function-calling tools |
| GET | `/api/user-config/claude-md` | Read user-level CLAUDE.md |
| PUT | `/api/user-config/claude-md` | Save user-level CLAUDE.md |

### LLM

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/llm/models` | List available models per provider (backend-driven) |

### WebSocket

| Endpoint | Description |
|----------|-------------|
| `/ws/chat/{sessionId}` | Session streaming: status, deltas, ask_user, collab events |
| `/ws/users/{userId}` | User notifications: session CRUD + collab run events |

## CLI

```bash
# Install
mvn -pl skillforge-cli -am install -DskipTests
alias skillforge='java -jar skillforge-cli/target/skillforge-cli-1.0.0-SNAPSHOT-shaded.jar'

# Usage
skillforge agents list
skillforge agents create -f examples/agents/general-assistant.yaml
skillforge agents export 1 > my-agent.yaml
skillforge chat 1 "List 3 interesting files in /tmp"
skillforge sessions list
skillforge compact <session-id> --level full --reason "end of task"
```

## Skill Packages

Custom skills as zip packages:

```
my-skill.zip
├── SKILL.md         # Required: YAML frontmatter + prompt
├── references/      # Optional: reference docs
├── scripts/         # Optional: executable scripts
└── docs/            # Optional: extended docs
```

## Roadmap

### Delivered

- **Agent Loop engine** with multi-provider LLM streaming (Claude, OpenAI-compatible, DeepSeek, Bailian, vLLM, Ollama)
- **Tool & Skill system** — Java tools + file-based SKILL.md packages + marketplace (ClawHub / SkillHub)
- **Dashboard** — login, chat, sessions, agents, tools, skills, traces, replay, memories, usage, teams, eval, channels, hook methods, scheduled tasks, MCP servers, drafts
- **SubAgent orchestration** — tree topology, persistent across restarts, recovery + sweeper
- **Multi-Agent Collaboration** — network topology, roster, adjacency policy, cascade cancel, lightContext (~30-50% token save)
- **Context compaction** — light + full + time-based cold cleanup + session-memory compact + 6 trigger sources (JVM-GC style)
- **Memory system** — 5 types, **pgvector + FTS hybrid retrieval (RRF)**, idle-window extraction, **LLM semantic extraction mode**, lifecycle status, batch operations, rollback/refresh, **Dream Consolidation cron + embedding dedup + per-action archive reason** (V2.5)
- **Self-Improve Pipeline** — eval runner, LLM judge (2×Haiku + Sonnet meta), 7×5 attribution matrix, prompt A/B auto-promotion (Δ≥15pp + 4-layer Goodhart safeguards), session→scenario extraction
- **Session message storage** — row-based (`t_session_message`), append-only, checkpoint / branch / restore
- **Skill self-evolve closed loop** (5-phase, SKILL-EVOLVE-LOOP) — daily extract cron, single-skill EVAL endpoint + history table, weekly scheduled evaluator, evolve-with-failures, weekly self-improve cron with A/B + auto-promote, WS push, dashboard sparkline + history curve + auto-evolve runs panel
- **Skill dashboard polish** (V1+V2+V2.5) — aggregate by name, evolution detail tab (Reasoning / Diff / per-scenario A/B), A/B threshold tooltip, manual promote / rollback, Drafts top-level entry + sidebar badge, exact-name draft skip, dashboard 5-stat summary card, real merge UX (Update existing / Rename / Reject), version tree (default + `?format=tree`), sibling-aware delete, isolated fork path, manual SKILL.md edit, generic file browser (references / scripts / assets / hooks)
- **MCP Client (P11 + HTTP transport)** — stdio **and Streamable HTTP** transport + per-agent tool gating + tool name prefixing + lifecycle reload (V61 / V152); https + SSRF URL guard; dogfood `time` (stdio) + **AnySearch** (HTTP, structured vertical search) servers + dashboard `/mcp-servers` CRUD
- **AutoEvolving V1** — DSL workflow engine (Rhino + L1 capability sandbox + 6 primitives + human-approve journal replay), OPT-REPORT rebuilt as a workflow, agent-level evolve loop hill-climbing (`weightedScore` + winner carry-forward), `/autoevolving` dashboard
- **ACP external agents** — orchestrate **Claude Code & Codex** via ACP (Agent Client Protocol) as SubAgents (`agentName=claude-code` / `codex`); each run executes in an **isolated git worktree** off `origin/main`, self-tests, and opens a PR; per-agent adapter selection (`skillforge.acp.adapters`), the agent's system prompt is folded into the ACP prompt, tool input/result captured into the session, and the final result is delivered back to the origin channel. Self-iteration loop: channel → cc/codex edits the repo → PR → you review/merge
- **Slash Commands (P10)** — 8 commands (`/new` `/compact` `/model` `/models` `/skill` `/tool` `/context` `/help`) with FE popup + channel intercept (V60)
- **Scheduled Tasks (P12)** — user-defined cron / one-shot, agent tools (Create/Update/Delete/List/Get), dashboard `/schedules` + run history (V59)
- **Prompt Cache (P13)** — 5-provider auto-cache, stable-prompt SHA stability, `cache_break` detection, dashboard hit-rate badge (V62)
- **Multi-channel gateway** — `ChannelAdapter` SPI, Feishu (WebSocket + webhook), Telegram, 3-phase delivery tx, retry/dedup, `/channels` dashboard
- **Lifecycle Hooks** — SessionStart / UserPromptSubmit / PreToolUse / PostToolUse / SessionEnd with Skill / Script / BuiltInMethod / CompiledMethod handler types, chain execution, discriminated-union editor
- **Behavior Rules** — 15 built-in rules + preset templates + per-agent custom rules (XML-sandboxed)
- **Code Agent** — self-extending agent that writes its own hook methods, with compile + approval workflow
- **Safety guardrails** — SafetySkillHook (command blocklist, path-traversal prevention) + agent-loop anti-runaway (token / duration / iteration budgets, no-progress detection, waste detection)
- **Observability** — Langfuse-style traces, session replay, model usage dashboard, context breakdown API, channel visualization
- **Auth MVP** — local token login, Bearer-token verification, protected dashboard routes
- **CLI module** — picocli + OkHttp, YAML import/export, one-shot chat

### Planned

- **Embedding cosine activation** — flip `embedding.enabled: true` + install pgvector → activate L2 (write-time `≥0.95 update / ≥0.85 merge`) + L4 (cron 0.85 dedup) memory dedup. **Code is shipped**; only awaits external embedding API key + DB extension.
- **Stream tool dispatch** — early-dispatch tool_use as soon as `content_block_stop` fires (Claude) or `tool_calls.index` transitions (OpenAI), saving ~1-3s per loop. **Research done** (`/tmp/stream-tool-research.md`); held until SkillForge has Claude API access (current default-provider is bailian/qwen).
- **Pre-tool-use permission hook** — `PRE_TOOL_USE` event + `Tool.checkPermissions` + 3-tier rules (allow / ask / deny) for unknown-user channel deployment safety
- **Multimodal MVP** — image / PDF / Word / Excel upload + vision-provider fallback (chat upload entry currently absent)
- **Memory quality evals (P3)** — broader quality scoring and auto-rollback policy after negative Δ
- **Tool output fine-grained trimming (P9-2/4/5/7)** — per-message aggregate budget with on-disk archival, partial compaction (head/tail), post-compact context restoration, `jtokkit` local token counter
- **MCP Server (reverse)** — expose SkillForge as an MCP server to Claude Desktop / Cursor / VS Code
- **Skill main-detail refactor** — split `t_skill` into `t_skill_main` (business identity) + `t_skill_version` (version snapshot) when multi-tenant audit pressure arrives
- **JWT authentication** and Redis-backed multi-instance deployment

## License

MIT
<!-- ACP self-iterate smoke -->
