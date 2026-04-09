# SkillForge

Server-side Agentic Assistant Platform with configurable Skills and Agents.

## Architecture

```
┌──────────────────────────────────────────────────────┐
│   React + Ant Design Dashboard  │   CLI (TODO)       │
├──────────────────────────────────────────────────────┤
│              Entry Points                             │
│   REST API   │   WebSocket (per-session + per-user)  │
├──────────────────────────────────────────────────────┤
│              Spring Boot Server                       │
│   Chat / Agent / Skill / SubAgent / Compaction       │
├──────────────────────────────────────────────────────┤
│              Agent Loop Engine                        │
│   Message → LLM (streaming) → tool_use → Skill →     │
│   loop  +  cancel / context-compact safety net       │
├──────────────┬──────────────┬────────────────────────┤
│  LLM Layer   │ Skill System │ Session & Hooks        │
│  Claude      │ Built-in     │ Session Mgmt           │
│  OpenAI*     │ Skill Zip    │ SafetyHook             │
│  DeepSeek    │ ClawHub Mkt  │ AskUser / Compact      │
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
│                            #   Hooks, Cancellation, Context Compaction (light/full)
├── skillforge-skills       # Built-in Skills: Bash, FileRead, FileWrite, FileEdit,
│                            #   Glob, Grep, Browser (Playwright)
├── skillforge-server       # Spring Boot server: REST API, JPA entities, services,
│                            #   WebSocket handlers, server-only skills (Memory, ClawHub,
│                            #   SubAgent), SubAgent dispatcher + sweeper, CompactionService
└── skillforge-dashboard    # React + Ant Design dashboard: chat, sessions list (live),
                             #   agents, skills, memories, model usage, compaction modal
```

## Features

What works today, end-to-end verified:

- **Agentic loop** with multi-provider LLMs and a built-in skill system, running every chat session asynchronously on a `chatLoopExecutor` thread pool with a 429 backpressure path.
- **Streaming chat** — assistant text and tool_use input JSON stream token-by-token over WebSocket; the dashboard renders deltas live and a tool card shows the partial JSON as the LLM is still emitting it.
- **Loop cancel** — `POST /cancel` flips a flag the engine checks at iteration boundaries; the dashboard's running banner shows a `✕` button.
- **Ask mode vs Auto mode** — agents can be configured per-session; in ask mode the engine injects an `ask_user` tool the LLM can call to halt and request a multiple-choice decision; in auto mode that tool is suppressed.
- **SubAgent async dispatch** — an agent can call `compact_context` and `subagent dispatch` tools to spawn child sessions; child runs on its own thread, results auto-deliver back to the parent as a synthetic user message that wakes the parent loop. Persistent across server restarts (`t_subagent_run` table + startup recovery + scheduled sweeper for orphaned runs).
- **Context compaction (light + full, JVM-GC style)** — `light` is rule-based and free (truncate large tool outputs, dedup duplicate searches, fold failure retries); `full` is LLM-summarization that preserves tool_use ↔ tool_result pairing and replaces older history with a single synthetic summary message. Triggered automatically by token-budget safety net at iteration top, by an idle-gap check on `chatAsync` entry, by the LLM via the built-in `compact_context` tool, or manually from the dashboard. All events recorded in `t_compaction_event` for audit.
- **Session list realtime refresh** — a per-user `/ws/users/{userId}` channel streams session state changes; the list shows a live status dot and updates without page reload.
- **Endpoint ownership scoping** — every session-keyed REST endpoint validates `userId` against the session owner (400 / 403 / 404 differentiated).
- **Skill marketplace integration** — install skills from [ClawHub](https://clawhub.ai) directly via the `ClawHub` skill or the dashboard's Skills page.

## Quick Start

### Prerequisites
- JDK 17+
- Maven 3.8+
- An LLM API key (DeepSeek, OpenAI, Claude, etc.)

### Run

```bash
# Set your API key
export DEEPSEEK_API_KEY=sk-your-key-here

# Build
mvn install -DskipTests

# Start server
mvn spring-boot:run -pl skillforge-server
```

Server starts at `http://localhost:8080`.

### Configuration

Edit `skillforge-server/src/main/resources/application.yml`:

```yaml
skillforge:
  llm:
    default-provider: openai    # or "claude"
    providers:
      openai:
        api-key: ${DEEPSEEK_API_KEY:}
        base-url: https://api.deepseek.com    # or any OpenAI-compatible endpoint
        model: deepseek-chat
      claude:
        api-key: ${ANTHROPIC_API_KEY:}
        base-url: https://api.anthropic.com
        model: claude-sonnet-4-20250514
```

## API Usage

### 1. Create an Agent

```bash
curl -X POST http://localhost:8080/api/agents \
  -H "Content-Type: application/json" \
  -d '{
    "name": "General Assistant",
    "modelId": "deepseek-chat",
    "systemPrompt": "You are a helpful assistant that can execute commands and read/write files.",
    "skillIds": "[\"Bash\",\"FileRead\",\"FileWrite\",\"FileEdit\",\"Glob\",\"Grep\"]",
    "status": "active"
  }'
```

### 2. Create a Session

```bash
curl -X POST http://localhost:8080/api/chat/sessions \
  -H "Content-Type: application/json" \
  -d '{"userId": 1, "agentId": 1}'
```

### 3. Chat

```bash
curl -X POST http://localhost:8080/api/chat/{sessionId} \
  -H "Content-Type: application/json" \
  -d '{"message": "List files in /tmp", "userId": 1}'
```

The LLM will autonomously decide which Skills to invoke and return a structured response with tool call records.

### 4. Dashboard

```bash
curl http://localhost:8080/api/dashboard/overview
```

### Other Endpoints

All session-scoped endpoints require `userId` (query param or body) and return 400 / 403 / 404 / 409 as appropriate.

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/agents` | List all agents |
| GET | `/api/agents/{id}` | Get agent detail |
| PUT | `/api/agents/{id}` | Update agent |
| DELETE | `/api/agents/{id}` | Delete agent |
| GET | `/api/chat/sessions?userId=1` | List user's top-level sessions (filters out SubAgent children) |
| GET | `/api/chat/sessions/{id}?userId=1` | Get session detail incl. runtime status, compact counters |
| GET | `/api/chat/sessions/{id}/messages?userId=1` | Get session messages |
| POST | `/api/chat/{sessionId}` | Send a chat message (async, 202) |
| POST | `/api/chat/{sessionId}/cancel?userId=1` | Cancel a running loop |
| POST | `/api/chat/{sessionId}/answer` | Answer an `ask_user` question |
| PATCH | `/api/chat/sessions/{sessionId}/mode?userId=1` | Switch session execution mode (`ask` / `auto`) |
| GET | `/api/chat/sessions/{id}/children?userId=1` | List SubAgent child sessions |
| GET | `/api/chat/sessions/{id}/subagent-runs?userId=1` | List SubAgent dispatch runs |
| POST | `/api/chat/sessions/{id}/compact?userId=1` | Manual `full` context compact (409 if running) |
| GET | `/api/chat/sessions/{id}/compactions?userId=1` | List compaction history for the session |
| WS | `/ws/chat/{sessionId}` | Per-session events: status, message_appended, text_delta, tool_use_delta, ask_user |
| WS | `/ws/users/{userId}` | Per-user events: session_created, session_updated, session_deleted |
| GET | `/api/skills` | List all skills |
| POST | `/api/skills/upload` | Upload skill zip package |
| GET | `/h2-console` | H2 database console |

## Built-in Skills

| Skill | Description | Read-only |
|-------|-------------|-----------|
| **Bash** | Execute shell commands | No |
| **FileRead** | Read files with line numbers, offset/limit | Yes |
| **FileWrite** | Write/create files | No |
| **FileEdit** | Exact string replacement in files | No |
| **Glob** | Find files by glob pattern | Yes |
| **Grep** | Search file contents by regex | Yes |
| **Browser** | Headed/headless Playwright (`goto`, `click`, `type`, `evaluate`, `screenshot`, `login` for stateful login flows) | No |
| **Memory** | Persistent key/value memory across sessions, scoped per user | No |
| **ClawHub** | Search + install community skills from [clawhub.ai](https://clawhub.ai) | No |
| **SubAgent** | Dispatch a task to another agent asynchronously; result auto-delivers back | No |

Two engine-internal tools the LLM can also call (registered as tools but handled by the engine, not skills):

| Tool | When | Description |
|------|------|-------------|
| **`ask_user`** | session in `ask` mode | LLM presents a 2–4 option multiple-choice question, latch-blocks until the user replies (or 30 min timeout) |
| **`compact_context`** | always | LLM requests a `light` (rule-based) or `full` (LLM summary) compaction of its own history |

## Safety

`SafetySkillHook` provides baseline security:

- **Bash**: Blocks `rm -rf /`, `sudo`, `mkfs`, `shutdown`, `curl|sh`, fork bombs, etc.
- **File write/edit**: Blocks system directories (`/etc/`, `/usr/`, `/bin/`) and sensitive files (`~/.ssh/`)
- **File read**: Blocks SSH private keys
- **Path traversal**: Normalizes paths to prevent `../` attacks
- **Audit log**: Every skill execution is logged

## Hook System

Extensible hook interfaces for customization:

- **LoopHook**: `beforeLoop()` / `afterLoop()` — intercept the entire agent loop
- **SkillHook**: `beforeSkillExecute()` / `afterSkillExecute()` — intercept individual skill calls

## Skill Packages

Custom skills can be installed via the `ClawHub` marketplace integration or uploaded directly as zip packages:

```
my-skill.zip
├── skill.yaml       # Metadata: name, description, triggers, required tools
└── SKILL.md         # Prompt template loaded into agent context
```

## Roadmap

### ✅ Delivered
- **Skill system** — built-in skills, zip packages, ClawHub marketplace integration
- **Dashboard** — React + Ant Design pages for chat, sessions (live), agents, skills, memories, model usage
- **Streaming chat** — per-token assistant text + tool_use input JSON via WebSocket
- **Session lifecycle visibility** — runtime status, runtime step, ask_user blocking, error banner
- **Loop cancel** — `POST /cancel` endpoint + dashboard ✕ button
- **Ask mode / Auto mode** — per-agent default with per-session override
- **Session list realtime refresh** — per-user WebSocket channel + auto reconnect with backoff
- **Endpoint ownership scoping** — userId-keyed validation on all session endpoints
- **SubAgent orchestration** — async dispatch, child sessions, depth/concurrency limits, persistence, restart recovery, scheduled sweeper, dashboard child tree
- **Auto-compact context compression** — light (rule-based) + full (LLM summary), 6 trigger sources (agent-tool / engine-soft / engine-hard / engine-gap / user-manual), recorded in `t_compaction_event`
- **LLM client tuning** — per-provider read timeout + retry on `SocketTimeoutException` (non-streaming path)

### 📋 Planned
- **Agent YAML import/export** — version-controlled agent definitions
- **Session replay** — step through historical loop iterations in the dashboard
- **JWT auth** — proper user identity (today's userId scoping is a placeholder)
- **Redis session sharing** — multi-instance deployment + replace JVM stripe locks with distributed locks
- **Elasticsearch conversation search** — full-text search across session history
- **CLI module** — headless agent runner for scripting and CI
- **Loop cancel during streaming** — interrupt the in-flight LLM SSE read for instant single-turn cancel

## License

MIT
