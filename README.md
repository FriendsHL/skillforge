# SkillForge

Server-side Agentic Assistant Platform with configurable Skills, Agents, and Multi-Agent Collaboration.

## Architecture

```
┌──────────────────────────────────────────────────────┐
│   React + Ant Design Dashboard  │   CLI Module       │
├──────────────────────────────────────────────────────┤
│              Entry Points                             │
│   REST API   │   WebSocket (per-session + per-user)  │
├──────────────────────────────────────────────────────┤
│              Spring Boot Server                       │
│   Chat / Agent / Skill / Memory / Compaction         │
│   SubAgent / Multi-Agent Collab / Traces             │
├──────────────────────────────────────────────────────┤
│              Agent Loop Engine                        │
│   Message → LLM (streaming) → tool_use → Skill →     │
│   loop + cancel + anti-runaway + context-compact     │
│   TraceCollector (per-span observability)             │
├──────────────┬──────────────┬────────────────────────┤
│  LLM Layer   │ Tool & Skill │ Session & Hooks        │
│  Claude      │ System Tools │ Session Mgmt           │
│  OpenAI*     │ System Skills│ SafetyHook             │
│  DeepSeek    │ User Skills  │ AskUser / Compact      │
│  Bailian     │ Team Collab  │ Memory System          │
├──────────────┴──────────────┴────────────────────────┤
│              Storage                                  │
│   H2 (dev) / MySQL (prod)   │   Redis (TODO)         │
└──────────────────────────────────────────────────────┘
* OpenAI provider is OpenAI-compatible: works with DeepSeek,
  DashScope/Bailian, vLLM, Ollama, etc.
```

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 3.2, Java 17, JPA / Hibernate |
| Frontend | React 18, Ant Design 6, Vite, TypeScript |
| Database | H2 embedded (zero-install, switchable to MySQL) |
| Realtime | WebSocket (Spring) — per-session + per-user channels |
| LLM | Multi-provider: Claude, OpenAI-compatible (DeepSeek, DashScope/Bailian, vLLM, Ollama) |
| HTTP Client | OkHttp 4 + SSE streaming, configurable read timeout + retry |
| Build | Maven multi-module |

## Modules

```
skillforge/
├── skillforge-core         # Core engine: LLM abstraction, Agent Loop, Skill system,
│                            #   Hooks, Cancellation, Context Compaction, TraceCollector,
│                            #   Anti-runaway (loop detection, tool result truncation)
├── skillforge-skills       # System Tools: Bash, FileRead, FileWrite, FileEdit,
│                            #   Glob, Grep, Memory, SubAgent
├── skillforge-server       # Spring Boot server: REST API, JPA entities, services,
│                            #   WebSocket handlers, Traces API, SystemSkillLoader,
│                            #   Multi-Agent Collaboration, Memory System,
│                            #   EnvironmentContextProvider, CompactionService
├── skillforge-dashboard    # React + Ant Design dashboard: chat, sessions (live),
│                            #   agents, skills (unified), traces (Langfuse-style),
│                            #   session replay, memories, model usage,
│                            #   collab run panel, peer message feed
├── skillforge-cli          # CLI client: picocli + OkHttp, agent YAML import/export
└── system-skills/          # System Skills (file-based, non-deletable)
    ├── browser/            #   Browser automation via agent-browser CLI
    ├── clawhub/            #   ClawHub marketplace search + install
    ├── github/             #   GitHub API + gh CLI
    └── skillhub/           #   SkillHub marketplace
```

## Features

### Core Agent Loop

- **Agentic loop** with multi-provider LLMs and a built-in skill system, running every chat session asynchronously on a `chatLoopExecutor` thread pool with a 429 backpressure path.
- **Streaming chat** — assistant text and tool_use input JSON stream token-by-token over WebSocket; the dashboard renders deltas live and a tool card shows the partial JSON as the LLM is still emitting it.
- **Loop cancel** — `POST /cancel` flips a flag the engine checks at iteration boundaries; the dashboard's running banner shows a `✕` button.
- **Ask mode vs Auto mode** — agents can be configured per-session; in ask mode the engine injects an `ask_user` tool the LLM can call to halt and request a multiple-choice decision; in auto mode that tool is suppressed.

### Agent Configuration (Multi-file)

Agents support a multi-file configuration model inspired by Claude Code and OpenClaw:

| File | Scope | Purpose |
|------|-------|---------|
| **CLAUDE.md** | Global (all agents) | Global rules and guidelines |
| **AGENT.md** | Per-agent (`systemPrompt`) | Core agent instructions |
| **SOUL.md** | Per-agent (`soulPrompt`) | Persona & tone (optional) |
| **TOOLS.md** | Per-agent (`toolsPrompt`) | Custom tool usage rules (optional) |
| **MEMORY.md** | Per-agent | Auto-injected from Memory system |

### SubAgent Orchestration (Tree Topology)

Single parent→child task delegation:

- An agent can call `SubAgent dispatch` to spawn a child session that runs asynchronously
- Child results auto-deliver back to the parent as a synthetic user message
- Persistent across server restarts (`t_subagent_run` table + startup recovery + scheduled sweeper)
- Depth limit (3) + concurrent children limit (5 per parent)

### Multi-Agent Collaboration (Network Topology)

Full team-based collaboration where a leader dispatches multiple agents that can communicate with each other:

| Tool | Description |
|------|-------------|
| **TeamCreate** | Spawn a team member (fire-and-forget, result auto-delivers) |
| **TeamSend** | Send a message to a peer by handle, to "parent", or "broadcast" (leader-only) |
| **TeamList** | List all team members with status |
| **TeamKill** | Cancel a specific member or the entire collaboration run |

Key capabilities:
- **CollabRun** — groups all sessions in one collaboration with shared collabRunId
- **AgentRoster** — in-memory handle→sessionId mapping with DB-backed recovery
- **Adjacency policy** — agents can only message parent, children, or siblings
- **Depth-aware tool filtering** — leaf agents cannot spawn further agents
- **Cancel cascade** — killing the leader cancels all members
- **lightContext** — optional stripped-down system prompt for child agents (saves ~30-50% tokens)
- **Delivery retry** — messageId dedup, seqNo ordering, max 3 retries before DELIVERY_FAILED
- **Stale run sweeper** — auto-completes collab runs with no activity for 30+ minutes

### Memory System

Persistent memory across sessions, scoped per user:

- **5 memory types**: preference, knowledge, feedback, project, reference
- **Type-slotted injection** into system prompt (preference/feedback: 10 each, others: 10 shared, 8000 char total cap)
- **TF-IDF search ranking** with 30-day recency decay + recall frequency boost
- **Auto-capture** via ActivityLogHook (SkillHook) — records every tool call
- **Daily extraction** (@Scheduled cron 3am) — extracts memories from completed sessions
- **Consolidation** — dedup by title + stale marking (30 days no recall + recallCount < 3)

### Context Compaction (JVM-GC Style)

- **Light** — rule-based (truncate large outputs, dedup searches, fold failures) — free
- **Full** — LLM-summarization preserving tool_use ↔ tool_result pairing
- 6 trigger sources: token-budget safety net, idle-gap check, LLM tool call, engine auto-trigger, manual API, waste detection
- All events recorded in `t_compaction_event` for audit

### Observability

**Traces (Langfuse-style)** — every agent loop recorded as structured trace with hierarchical spans:

```
AGENT_LOOP (root span)
├── LLM_CALL (iteration 0 — tokens + duration)
├── TOOL_CALL (Bash — input, output, duration)
├── LLM_CALL (iteration 1)
├── ASK_USER (blocked waiting for user)
├── COMPACT (context compaction)
└── LLM_CALL (final iteration)
```

**Session Replay** — restructures flat message history into Turns → Iterations → Tool calls with timing.

### Safety & Anti-runaway

**SafetySkillHook**:
- Blocks dangerous bash commands (`rm -rf /`, `sudo`, `mkfs`, `shutdown`, `curl|sh`, fork bombs)
- Blocks `clawhub install` / `skillhub install` without user confirmation
- Blocks writes to system directories and sensitive files
- Normalizes paths to prevent traversal attacks

**Agent Loop Guardrails**:
- Token budget (500K), duration limit (600s), max iterations (25)
- Tool execution timeout (120s per batch), LLM stream timeout (300s)
- Tool frequency warning (≥8 calls), no-progress detection (dual hash)
- Waste detection, compact circuit breaker (3 failures), tool result truncation (40K chars)

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

# Start server (MUST run from project root for correct H2 data path)
cd /path/to/skillforge
java -jar skillforge-server/target/skillforge-server-1.0.0-SNAPSHOT.jar
```

Server starts at `http://localhost:8080`. Dashboard at the same URL.

> **Important**: Always start the server from the project root directory.
> The H2 database uses a relative path (`./data/skillforge`), so the working
> directory determines where your data is stored.

### Dashboard Development

```bash
cd skillforge-dashboard
npm install
npm run dev    # Vite dev server at http://localhost:5173
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
        base-url: https://api.deepseek.com    # any OpenAI-compatible endpoint
        model: deepseek-chat
```

## API Reference

### Agent Management

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
| GET | `/api/chat/sessions?userId=1` | List user's top-level sessions |
| GET | `/api/chat/sessions/{id}?userId=1` | Get session detail |
| GET | `/api/chat/sessions/{id}/messages?userId=1` | Get session messages |
| POST | `/api/chat/{sessionId}` | Send message (async, 202) |
| POST | `/api/chat/{sessionId}/cancel?userId=1` | Cancel running loop |
| POST | `/api/chat/{sessionId}/answer` | Answer `ask_user` question |
| PATCH | `/api/chat/sessions/{id}/mode?userId=1` | Switch execution mode |
| POST | `/api/chat/sessions/{id}/compact?userId=1` | Manual context compact |
| GET | `/api/chat/sessions/{id}/compactions?userId=1` | Compaction history |
| GET | `/api/chat/sessions/{id}/replay?userId=1` | Structured session replay |

### SubAgent & Collaboration

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/chat/sessions/{id}/children?userId=1` | List child sessions |
| GET | `/api/chat/sessions/{id}/subagent-runs?userId=1` | List SubAgent runs |
| GET | `/api/collab-runs/{collabRunId}/members` | List collab run members with status |

### Skills & Tools

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/skills` | List all skills (system + user) |
| GET | `/api/skills/builtin` | List system tools |
| GET | `/api/skills/{id}/detail` | Skill detail |
| POST | `/api/skills/upload` | Upload skill zip package |
| DELETE | `/api/skills/{id}` | Delete user skill |
| PUT | `/api/skills/{id}/toggle?enabled=true` | Toggle skill |

### Observability

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/traces` | List traces (optional `?sessionId=` filter) |
| GET | `/api/traces/{traceId}/spans` | Span tree for a trace |
| GET | `/api/dashboard/overview` | Dashboard overview stats |
| GET | `/api/dashboard/usage/daily?days=30` | Daily usage |
| GET | `/api/dashboard/usage/by-model` | Usage by model |
| GET | `/api/dashboard/usage/by-agent` | Usage by agent |

### Memory

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/memories?userId=1` | List memories (optional `&type=`) |
| GET | `/api/memories/search?userId=1&keyword=` | Search with TF-IDF ranking |
| POST | `/api/memories` | Create memory |
| PUT | `/api/memories/{id}` | Update memory |
| DELETE | `/api/memories/{id}` | Delete memory |

### WebSocket

| Endpoint | Description |
|----------|-------------|
| `/ws/chat/{sessionId}` | Per-session: status, message_appended, text_delta, tool_use_delta, ask_user, collab events |
| `/ws/users/{userId}` | Per-user: session_created, session_updated, session_deleted |

### Other

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/user-config/claude-md?userId=1` | Get global CLAUDE.md |
| PUT | `/api/user-config/claude-md?userId=1` | Save global CLAUDE.md |
| GET | `/h2-console` | H2 database console |

## Tools & Skills

### System Tools (Java, always available)

| Tool | Description | Read-only |
|------|-------------|-----------|
| **Bash** | Execute shell commands with safety rules, timeout, chaining guidelines | No |
| **FileRead** | Read files with line numbers, offset/limit | Yes |
| **FileWrite** | Write/create files | No |
| **FileEdit** | Exact string replacement in files | No |
| **Glob** | Find files by glob pattern | Yes |
| **Grep** | Search file contents by regex | Yes |
| **Memory** | Persistent memory across sessions (5 types, search with ranking) | No |
| **SubAgent** | Dispatch a single task to another agent (tree topology) | No |
| **TeamCreate** | Spawn a team member in a collaboration run (network topology) | No |
| **TeamSend** | Send a message to a team peer, parent, or broadcast | No |
| **TeamList** | List team members with status | Yes |
| **TeamKill** | Cancel a team member or entire collaboration run | No |

### System Skills (file-based, non-deletable)

| Skill | Description |
|-------|-------------|
| **Browser** | Web automation via `npx agent-browser` CLI |
| **ClawHub** | Search + install skills from [clawhub.ai](https://clawhub.ai) |
| **GitHub** | GitHub API + `gh` CLI for repos, issues, PRs, CI |
| **SkillHub** | Search + install skills from SkillHub marketplace |

### Engine-internal Tools

| Tool | When | Description |
|------|------|-------------|
| **`ask_user`** | session in `ask` mode | Multiple-choice question, blocks until reply |
| **`compact_context`** | always | Request `light` or `full` context compaction |

## CLI

The `skillforge-cli` module ships a one-shot command-line client for the
server's REST API. Thin OkHttp wrapper with picocli commands — no Spring, starts in ~200ms.

### Install

```bash
mvn -pl skillforge-cli -am install -DskipTests

alias skillforge='java -jar /path/to/skillforge-cli/target/skillforge-cli-1.0.0-SNAPSHOT-shaded.jar'
```

### Examples

```bash
skillforge agents list
skillforge agents create -f examples/agents/general-assistant.yaml
skillforge agents export 1 > my-agent.yaml
skillforge chat 1 "List 3 interesting files in /tmp"
skillforge sessions list
skillforge compact <session-id> --level full --reason "end of task"
```

## Skill Packages

Custom skills uploaded as zip packages or installed from marketplaces:

```
my-skill.zip
├── SKILL.md         # Required: YAML frontmatter + prompt content
├── references/      # Optional: reference docs
├── scripts/         # Optional: executable scripts
└── docs/            # Optional: extended documentation
```

System skills loaded from `system-skills/` at startup (configurable via `skillforge.system-skills-dir`).

## Roadmap

### Delivered
- Tool & Skill system (Java tools + file-based skills + marketplace install)
- Dashboard (chat, sessions, agents, skills, traces, replay, memories, usage)
- Streaming chat with per-token WebSocket delivery
- Agent Loop guardrails (token budget, duration, frequency, no-progress, waste detection)
- LLM observability (TraceCollector with 5 span types)
- Session lifecycle (runtime status, ask_user, cancel, auto-compact)
- SubAgent orchestration (async dispatch, persistence, recovery, sweeper)
- Multi-Agent Collaboration (TeamCreate/Send/List/Kill, roster, adjacency policy, cancel cascade, lightContext)
- Context compaction (light + full, 6 triggers, JVM-GC style)
- Memory system (5 types, injection, auto-capture, daily extraction, consolidation, TF-IDF search)
- Multi-file agent config (CLAUDE.md + AGENT.md + SOUL.md + TOOLS.md + MEMORY.md)
- CLI module (YAML import/export, one-shot chat)
- User message queuing during agent runs

### Planned
- JWT authentication (replace userId placeholder)
- Redis for multi-instance deployment
- Elasticsearch conversation search
- Prompt cache optimization
- Unit test coverage expansion

## License

MIT
