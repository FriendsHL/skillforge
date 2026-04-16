# SkillForge

[中文文档](README_CN.md)

**Server-side Agentic AI Platform** — configurable Skills, multi-provider LLMs, multi-agent collaboration, and a self-improving eval pipeline. Built with Spring Boot + React for teams who want full control over their agent infrastructure.

## Why SkillForge?

Most agent frameworks are Python-based, single-provider, and designed for prototyping. SkillForge is built for **production Java/Spring teams** that need:

- **Multi-provider LLM** — swap between Claude, DeepSeek, Bailian/DashScope, vLLM, Ollama without code changes
- **Real agent orchestration** — not just chains, but tree and network topologies with persistent state
- **Self-improving agents** — automated eval, prompt A/B testing, and promotion pipelines
- **Full observability** — Langfuse-style traces, session replay, model usage dashboards
- **Safety guardrails** — command blocklists, path traversal prevention, anti-runaway loop detection

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│   React + Ant Design Dashboard    │    CLI Module       │
├─────────────────────────────────────────────────────────┤
│              Entry Points                               │
│   REST API   │   WebSocket (per-session + per-user)     │
├─────────────────────────────────────────────────────────┤
│              Spring Boot Server                         │
│   Chat / Agent / Skill / Memory / Compaction            │
│   SubAgent / Multi-Agent Collab / Eval Pipeline         │
├─────────────────────────────────────────────────────────┤
│              Agent Loop Engine                           │
│   Message → LLM (streaming) → tool_use → Skill → loop  │
│   cancel + anti-runaway + context-compact + traces      │
├──────────────┬──────────────┬───────────────────────────┤
│  LLM Layer   │ Tool & Skill │ Session & Hooks           │
│  Claude      │ System Tools │ Session Mgmt              │
│  OpenAI*     │ System Skills│ SafetyHook                │
│  DeepSeek    │ User Skills  │ AskUser / Compact         │
│  Bailian     │ Team Collab  │ Memory System             │
├──────────────┴──────────────┴───────────────────────────┤
│              Storage                                    │
│   Embedded PostgreSQL (dev) │ External PG (prod)        │
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
├── skillforge-skills       # System Tools: Bash, FileRead, FileWrite, FileEdit,
│                            #   Glob, Grep, Memory, SubAgent, Team*
├── skillforge-server       # Spring Boot server: REST API, JPA entities, services,
│                            #   WebSocket, Multi-Agent Collab, Memory, Eval Pipeline
├── skillforge-dashboard    # React dashboard: chat, sessions, agents, skills, traces,
│                            #   session replay, memories, model usage, teams, eval
├── skillforge-cli          # CLI client: picocli + OkHttp, agent YAML import/export
└── system-skills/          # File-based system skills (non-deletable)
    ├── browser/            #   Browser automation via agent-browser CLI
    ├── clawhub/            #   ClawHub marketplace search + install
    ├── github/             #   GitHub API + gh CLI
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
| **MEMORY.md** | Per-agent | Auto-injected from Memory system |

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
- **TF-IDF search ranking** with 30-day recency decay + recall frequency boost
- **Auto-capture** via ActivityLogHook — records every tool call
- **Daily extraction** (@Scheduled cron) — extracts memories from completed sessions
- **Consolidation** — dedup + stale marking (30 days no recall + recallCount < 3)

### Context Compaction (JVM-GC Style)

- **Light** — rule-based: truncate large outputs, dedup searches, fold failures (free)
- **Full** — LLM-summarization preserving tool_use ↔ tool_result pairing
- 6 trigger sources: token-budget safety net, idle-gap, LLM tool call, engine auto-trigger, manual API, waste detection
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

- JDK 17+
- Maven 3.8+
- Node.js 18+ (for dashboard development)
- An LLM API key (DeepSeek, DashScope/Bailian, OpenAI, Claude, etc.)

### Build & Run

```bash
# Set your API key
export DASHSCOPE_API_KEY=sk-your-key-here

# Build all modules
mvn clean package -DskipTests

# Start server (embedded PostgreSQL starts automatically)
cd /path/to/skillforge
java -jar skillforge-server/target/skillforge-server-1.0.0-SNAPSHOT.jar
```

Server starts at `http://localhost:8080` with embedded PostgreSQL on port 15432.

### Dashboard Development

```bash
cd skillforge-dashboard
npm install
npm run dev    # Vite dev server at http://localhost:5173, proxied to :8080
```

### Configuration

Edit `skillforge-server/src/main/resources/application.yml`:

```yaml
skillforge:
  llm:
    default-provider: bailian    # or "claude", "openai"
    providers:
      bailian:
        api-key: ${DASHSCOPE_API_KEY:}
        base-url: https://coding.dashscope.aliyuncs.com
        model: qwen3.5-plus
      claude:
        api-key: ${ANTHROPIC_API_KEY:}
        base-url: https://api.anthropic.com
        model: claude-sonnet-4-20250514
      openai:
        api-key: ${DEEPSEEK_API_KEY:}
        base-url: https://api.deepseek.com
        model: deepseek-chat
```

## Tools & Skills

### System Tools (Java, always available)

| Tool | Description |
|------|-------------|
| **Bash** | Shell commands with safety rules and timeout |
| **FileRead** | Read files with line numbers, offset/limit |
| **FileWrite** | Write/create files |
| **FileEdit** | Exact string replacement |
| **Glob** | Find files by pattern |
| **Grep** | Search contents by regex |
| **Memory** | Persistent memory (5 types, TF-IDF search) |
| **SubAgent** | Dispatch task to another agent (tree) |
| **TeamCreate** | Spawn team member (network) |
| **TeamSend** | Message peer/parent/broadcast |
| **TeamList** | List team members |
| **TeamKill** | Cancel member or team |

### System Skills (file-based)

| Skill | Description |
|-------|-------------|
| **Browser** | Web automation via `npx agent-browser` CLI |
| **ClawHub** | Marketplace search + install |
| **GitHub** | GitHub API + `gh` CLI |
| **SkillHub** | SkillHub marketplace |

### Engine-internal Tools

| Tool | Description |
|------|-------------|
| **ask_user** | Multiple-choice question (ask mode only) |
| **compact_context** | Request light or full context compaction |

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
| POST | `/api/chat/{sessionId}` | Send message (async, 202) |
| POST | `/api/chat/{sessionId}/cancel` | Cancel running loop |
| POST | `/api/chat/{sessionId}/answer` | Answer ask_user question |
| PATCH | `/api/chat/sessions/{id}/mode` | Switch execution mode |
| POST | `/api/chat/sessions/{id}/compact` | Manual context compact |
| GET | `/api/chat/sessions/{id}/replay` | Structured session replay |

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
| GET | `/api/memories/search` | TF-IDF search |
| POST | `/api/memories` | Create memory |
| PUT | `/api/memories/{id}` | Update memory |
| DELETE | `/api/memories/{id}` | Delete memory |

### WebSocket

| Endpoint | Description |
|----------|-------------|
| `/ws/chat/{sessionId}` | Session streaming: status, deltas, ask_user, collab events |
| `/ws/users/{userId}` | User notifications: session CRUD events |

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

- Agent Loop engine with multi-provider LLM streaming
- Tool & Skill system (Java tools + file-based skills + marketplace)
- Dashboard (chat, sessions, agents, skills, traces, replay, memories, usage, teams, eval)
- SubAgent orchestration (tree topology, persistent, recovery)
- Multi-Agent Collaboration (network topology, roster, adjacency, cancel cascade)
- Context compaction (light + full, 6 triggers, JVM-GC style)
- Memory system (5 types, TF-IDF search, auto-extraction, consolidation)
- Self-Improve Pipeline (eval runner, LLM judge, attribution, prompt A/B, scenario extraction)
- Safety guardrails (command blocklist, path traversal, anti-runaway)
- Observability (traces, session replay, model usage dashboard)
- Auth MVP (local token auto-generation)
- CLI module (YAML import/export, one-shot chat)

### Planned

- Memory vector search (pgvector + FTS hybrid retrieval)
- Agent behavioral rules (configurable rule library)
- Lifecycle hooks (session_start / post_tool_use / session_end)
- Skill auto-generation from session analysis
- Feishu/Lark messaging gateway
- JWT authentication
- Redis for multi-instance deployment

## License

MIT
