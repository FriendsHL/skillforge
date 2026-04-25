# SkillForge

[中文文档](README_CN.md)

**Server-side Agentic AI Platform** — configurable Skills, multi-provider LLMs, multi-agent collaboration, and a self-improving eval pipeline. Built with Spring Boot + React for teams who want full control over their agent infrastructure.

## Why SkillForge?

Most agent frameworks are Python-based, single-provider, and designed for prototyping. SkillForge is built for **production Java/Spring teams** that need:

- **Multi-provider LLM** — swap between Claude, DeepSeek, Bailian/DashScope, vLLM, Ollama without code changes
- **Multi-channel gateway** — one agent answers via Web, CLI, Feishu (WebSocket or webhook), Telegram; `ChannelAdapter` SPI extends to WeChat/Discord/Slack/iMessage with zero framework changes
- **Real agent orchestration** — not just chains, but tree (SubAgent) and network (TeamCreate/Send) topologies with persistent state
- **Self-improving agents** — automated eval, prompt A/B testing, and promotion pipelines
- **Full observability** — Langfuse-style traces, session replay, model usage dashboards
- **Safety guardrails** — configurable lifecycle hooks, command blocklists, path-traversal prevention, anti-runaway loop detection

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│   Dashboard   │   CLI    │   Feishu    │   Telegram     │
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
| Frontend | React 18, Ant Design, Vite, TypeScript, TanStack Query |
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
│                            #   Glob, Grep, Memory, SubAgent, Team*
├── skillforge-server       # Spring Boot server: REST API, JPA entities, services,
│                            #   WebSocket, Multi-Agent Collab, Memory, Eval Pipeline
├── skillforge-dashboard    # React dashboard: chat, sessions, agents, skills, traces,
│                            #   session replay, memories, model usage, teams, eval
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

### Multi-Channel Gateway

One agent, many surfaces — a user message from Feishu, Telegram, or the web arrives at the **same** agent loop and the reply is delivered back to the originating channel:

- **Pluggable `ChannelAdapter` SPI** — Spring auto-collects implementations; new platforms drop in with zero framework changes
- **Feishu (Lark)** — both **WebSocket long-polling** (no public IP needed for dev) and webhook mode; SHA-256 event signature verification; mode-switch in the dashboard with graceful reconnect (exponential backoff + jitter)
- **Telegram** — HTML parse mode with 4096-codepoint safe splitting
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

### Memory System

Persistent memory across sessions, scoped per user:

- **5 memory types**: preference, knowledge, feedback, project, reference
- **Type-slotted injection** into system prompt (8000 char cap)
- **Hybrid retrieval** — pgvector (`text-embedding-3-small`, 1536-d) + PostgreSQL `tsvector` full-text search, fused via **RRF**; graceful fallback to TF-IDF when pgvector unavailable
- **Two extraction modes**:
  - `rule` — fast heuristic `SessionDigestExtractor` (default)
  - `llm` — `LlmMemoryExtractor` classifies entries into 5 types with importance scoring
- **Session-end `@Async` extraction** — runs the moment a session completes (with `@Scheduled` daily cron as a safety net for orphans)
- **Auto-capture** via `ActivityLogHook` — records every tool call
- **Consolidation** — dedup + stale marking (30 days no recall + recallCount < 3)

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

**Model Usage Dashboard** — daily/by-model/by-agent token consumption and cost tracking.

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
# otherwise skillforge-server can't resolve skillforge-core / skillforge-tools
mvn install -DskipTests

# Then run server from the server module
cd skillforge-server
mvn spring-boot:run
```

> Running `mvn spring-boot:run` directly inside `skillforge-server` **without the
> root `mvn install` step** will fail with `找不到符号: 类 BehaviorRuleRegistry`
> (or similar) because the other modules haven't been published to your local
> Maven repo yet. One-shot alternative: `mvn -pl skillforge-server -am spring-boot:run` from root.

### Dashboard Development

```bash
cd skillforge-dashboard
npm install
npm run dev    # Vite dev server at http://localhost:3000, proxied to :8080
```

### Configuration

Edit `skillforge-server/src/main/resources/application.yml`:

```yaml
skillforge:
  llm:
    default-provider: bailian            # or "claude", "openai"
    providers:
      bailian:                           # 通义千问 / DashScope
        type: openai                     # OpenAI-compatible endpoint
        api-key: ${DASHSCOPE_API_KEY:}
        base-url: https://coding.dashscope.aliyuncs.com
        model: qwen3.5-plus              # default model for this provider
        models:                          # surfaced as dashboard model options
          - qwen3.5-plus
          - qwen3-max-2026-01-23
          - qwen3-coder-next
      claude:
        type: claude
        api-key: ${ANTHROPIC_API_KEY:}
        base-url: https://api.anthropic.com
        model: claude-sonnet-4-20250514
        models:
          - claude-sonnet-4-20250514
        context-window-tokens: 200000
      openai:                            # works with DeepSeek, vLLM, Ollama, etc.
        type: openai
        api-key: ${DEEPSEEK_API_KEY:}
        base-url: https://api.deepseek.com
        model: deepseek-chat
        models:
          - deepseek-chat

  # Memory extraction: "rule" (fast heuristic) | "llm" (semantic, 5-type classification)
  memory:
    extraction-mode: rule

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
| POST | `/api/agents` | Create agent |
| PUT | `/api/agents/{id}` | Update agent |
| DELETE | `/api/agents/{id}` | Delete agent |

### Chat & Sessions

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/chat/sessions` | Create session |
| GET | `/api/chat/sessions?userId=1` | List user sessions |
| DELETE | `/api/chat/sessions/{id}` | Delete session |
| DELETE | `/api/chat/sessions` | Batch delete (`{ids: [...]}`, max 100) |
| POST | `/api/chat/{sessionId}` | Send message (async, 202) |
| POST | `/api/chat/{sessionId}/cancel` | Cancel running loop |
| POST | `/api/chat/{sessionId}/answer` | Answer ask_user question |
| PATCH | `/api/chat/sessions/{id}/mode` | Switch execution mode |
| POST | `/api/chat/sessions/{id}/compact` | Manual context compact |
| GET | `/api/chat/sessions/{id}/replay` | Structured session replay |
| GET | `/api/chat/sessions/{id}/context-breakdown` | Live segment sizes vs context window |

### SubAgent & Collaboration

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/chat/sessions/{id}/children` | List child sessions |
| GET | `/api/chat/sessions/{id}/subagent-runs` | List SubAgent runs |
| GET | `/api/collab-runs/{id}/members` | List collab members |

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
| POST | `/api/skills/upload` | Upload skill zip |
| DELETE | `/api/skills/{id}` | Delete user skill |
| PUT | `/api/skills/{id}/toggle` | Toggle skill |

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
| POST | `/api/memories` | Create memory |
| PUT | `/api/memories/{id}` | Update memory |
| DELETE | `/api/memories/{id}` | Delete memory |

### Channels (Multi-Platform Gateway)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/channels` | List channel configs (Feishu / Telegram / …) |
| POST | `/api/channels` | Create channel config |
| PATCH | `/api/channels/{id}` | Update (e.g. switch Feishu mode: ws ↔ webhook) |
| GET | `/api/channels/{id}/conversations` | List conversations routed to a channel |
| POST | `/api/channels/deliveries/{id}/retry` | Retry a failed delivery |

### Lifecycle Hooks & Hook Methods

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/lifecycle-hooks/events` | Available hook events + presets |
| GET | `/api/lifecycle-hooks/methods` | List registered built-in / script / compiled methods |
| POST | `/api/hook-methods/script` | Register a bash/node script method |
| POST | `/api/hook-methods/compiled` | Submit a Java class for compile + approval |
| POST | `/api/hook-methods/compiled/{id}/approve` | Approve a compiled method |
| GET | `/api/behavior-rules` | List built-in behavior rules |
| GET | `/api/behavior-rules/presets` | List preset rule bundles |

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
- **Dashboard** — chat, sessions, agents, skills, traces, replay, memories, usage, teams, eval, channels, hook methods, schedules
- **SubAgent orchestration** — tree topology, persistent across restarts, recovery + sweeper
- **Multi-Agent Collaboration** — network topology, roster, adjacency policy, cascade cancel, lightContext (~30-50% token save)
- **Context compaction** — light + full + time-based cold cleanup + session-memory compact + 6 trigger sources (JVM-GC style)
- **Memory system** — 5 types, **pgvector + FTS hybrid retrieval (RRF)**, session-end `@Async` extraction, **LLM semantic extraction mode**, consolidation
- **Self-Improve Pipeline** — eval runner, LLM judge (2×Haiku + Sonnet meta), 7×5 attribution matrix, prompt A/B auto-promotion (Δ≥15pp + 4-layer Goodhart safeguards), session→scenario extraction
- **Session message storage** — row-based (`t_session_message`), append-only, checkpoint / branch / restore
- **Skill self-evolution** — session → skill extraction, version management, A/B validation, auto-promotion/rollback
- **Multi-channel gateway** — `ChannelAdapter` SPI, Feishu (WebSocket + webhook), Telegram, 3-phase delivery tx, retry/dedup, `/channels` dashboard
- **Lifecycle Hooks** — SessionStart / UserPromptSubmit / PreToolUse / PostToolUse / SessionEnd with Skill / Script / BuiltInMethod / CompiledMethod handler types, chain execution, discriminated-union editor
- **Behavior Rules** — 15 built-in rules + preset templates + per-agent custom rules (XML-sandboxed)
- **Code Agent** — self-extending agent that writes its own hook methods, with compile + approval workflow
- **Safety guardrails** — SafetySkillHook (command blocklist, path-traversal prevention) + agent-loop anti-runaway (token / duration / iteration budgets, no-progress detection, waste detection)
- **Observability** — Langfuse-style traces, session replay, model usage dashboard, context breakdown API, channel visualization
- **Auth MVP** — local token auto-generation
- **CLI module** — picocli + OkHttp, YAML import/export, one-shot chat

### Planned

- **Memory quality evals (P3)** — extraction snapshots, memory-attribution signals, auto-rollback on negative Δ
- **Tool output fine-grained trimming (P9-2/4/5/7)** — per-message aggregate budget with on-disk archival, partial compaction (head/tail), post-compact context restoration, `jtokkit` local token counter
- **Slash commands (P10)** — `/new`, `/compact`, `/clear`, `/model`, `/help` in the chat input
- **Agent discovery + cross-agent calls (P11)** — `AgentDiscoverySkill`, call-by-name, visibility, cycle detection
- **Scheduled tasks (P12)** — user-defined cron / one-shot triggers, system-job registry, unified `/schedules` UI, run history
- **JWT authentication** and Redis-backed multi-instance deployment

## License

MIT
