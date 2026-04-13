# SkillForge

Server-side Agentic Assistant Platform with configurable Skills and Agents.

## Architecture

```
┌──────────────────────────────────────────────────────┐
│   React + Ant Design Dashboard  │   CLI Module       │
├──────────────────────────────────────────────────────┤
│              Entry Points                             │
│   REST API   │   WebSocket (per-session + per-user)  │
├──────────────────────────────────────────────────────┤
│              Spring Boot Server                       │
│   Chat / Agent / Skill / SubAgent / Compaction       │
│   Traces (LLM Observability)                         │
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
│                            #   EnvironmentContextProvider, CompactionService
├── skillforge-dashboard    # React + Ant Design dashboard: chat, sessions (live),
│                            #   agents, skills (unified), traces (Langfuse-style),
│                            #   session replay, memories, model usage
├── skillforge-cli          # CLI client: picocli + OkHttp, agent YAML import/export
└── system-skills/          # System Skills (file-based, non-deletable)
    ├── browser/            #   Browser automation via agent-browser CLI
    ├── clawhub/            #   ClawHub marketplace search + install
    ├── github/             #   GitHub API + gh CLI
    └── skillhub/           #   SkillHub marketplace
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

> The Browser skill uses `npx agent-browser` CLI (not Playwright Java API),
> so it works with both `mvn spring-boot:run` and `java -jar` deployment.
> Make sure `npx` is available in the server's PATH.

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
| GET | `/api/skills` | List all skills (system + user, unified) |
| GET | `/api/skills/builtin` | List system tools (Bash, FileRead, etc.) |
| GET | `/api/skills/{id}/detail` | Skill detail (supports system- prefix IDs) |
| POST | `/api/skills/upload` | Upload skill zip package |
| GET | `/api/traces` | List traces (AGENT_LOOP spans), optional `?sessionId=` filter |
| GET | `/api/traces/{traceId}/spans` | Get span tree for a trace |
| GET | `/api/traces/session/{sessionId}` | All spans for a session |
| GET | `/api/chat/sessions/{id}/replay` | Structured replay (turns → iterations → tool calls) |
| GET | `/h2-console` | H2 database console |

## CLI

The `skillforge-cli` module ships a one-shot command-line client for the
server's REST API. It's a thin OkHttp wrapper with picocli commands —
no Spring, starts in ~200ms.

### Install

```bash
# build the shaded jar (once)
mvn -pl skillforge-cli -am install -DskipTests

# add a convenience alias
alias skillforge='java -jar /absolute/path/to/skillforge/skillforge-cli/target/skillforge-cli-1.0.0-SNAPSHOT-shaded.jar'
```

Configuration resolution order (highest first):
1. CLI flag (`--server`, `--user-id`)
2. Environment variable (`SKILLFORGE_SERVER`, `SKILLFORGE_USER_ID`)
3. `~/.skillforge/config.yaml` (optional, schema `{server: ..., userId: ...}`)
4. Defaults: `http://localhost:8080`, `userId=1`

### Examples

```bash
# list agents
skillforge agents list

# import an agent from a YAML file
skillforge agents create -f examples/agents/general-assistant.yaml

# export an agent back to YAML (so you can version it in git)
skillforge agents export 1 > my-agent.yaml

# one-shot chat: creates a fresh session in auto mode, polls until idle,
# prints the assistant reply to stdout and the new session id to stderr
skillforge chat 1 "List 3 interesting files in /tmp"

# list recent sessions
skillforge sessions list

# manually compact a long session
skillforge compact <session-id> --level full --reason "end of task"
```

See `examples/agents/` for starter YAML files and `examples/agents/README.md`
for more on the schema.

## Tools & Skills

SkillForge separates **Tools** (Java implementations, system-level operations) from **Skills** (file-based SKILL.md, higher-level capabilities).

### System Tools (Java, always available)

| Tool | Description | Read-only |
|------|-------------|-----------|
| **Bash** | Execute shell commands (with safety rules, timeout, command chaining guidelines) | No |
| **FileRead** | Read files with line numbers, offset/limit | Yes |
| **FileWrite** | Write/create files | No |
| **FileEdit** | Exact string replacement in files | No |
| **Glob** | Find files by glob pattern | Yes |
| **Grep** | Search file contents by regex | Yes |
| **Memory** | Persistent key/value memory across sessions, scoped per user | No |
| **SubAgent** | Dispatch a task to another agent asynchronously; result auto-delivers back | No |

### System Skills (file-based, non-deletable)

| Skill | Description |
|-------|-------------|
| **Browser** | Web automation via `npx agent-browser` CLI (goto, snapshot, click, type, eval, screenshot, login) |
| **ClawHub** | Search + install skills from [clawhub.ai](https://clawhub.ai) marketplace |
| **GitHub** | GitHub API + `gh` CLI for repo search, issues, PRs, CI |
| **SkillHub** | Search + install skills from SkillHub marketplace |

### User Skills (uploadable, manageable)

Custom skills installed from ClawHub, SkillHub, or uploaded as zip packages. Managed via the Skills page.

### Engine-internal Tools

| Tool | When | Description |
|------|------|-------------|
| **`ask_user`** | session in `ask` mode | LLM presents a 2–4 option multiple-choice question, blocks until reply |
| **`compact_context`** | always | LLM requests `light` (rule-based) or `full` (LLM summary) compaction |

## Safety & Anti-runaway

### SafetySkillHook

- **Bash**: Blocks `rm -rf /`, `sudo`, `mkfs`, `shutdown`, `curl|sh`, fork bombs, etc.
- **Bash**: Blocks `clawhub install` / `skillhub install` commands (requires user confirmation)
- **File write/edit**: Blocks system directories (`/etc/`, `/usr/`, `/bin/`) and sensitive files (`~/.ssh/`)
- **File read**: Blocks SSH private keys
- **Path traversal**: Normalizes paths to prevent `../` attacks

### Agent Loop Guardrails

- **Token budget**: Cumulative input token limit (default 500K, configurable via `max_input_tokens`)
- **Duration limit**: Loop wall-clock timeout (default 600s, configurable via `max_duration_seconds`)
- **Max iterations**: Default 25 loops (configurable via `max_loops`)
- **Tool execution timeout**: 120s per batch, auto-generates error tool_result on timeout
- **LLM stream timeout**: 300s overall guard
- **Tool frequency warning**: System prompt guidance injected when any tool called ≥8 times
- **No-progress detection**: Dual hash (call params + outcome), detects when same tool produces same result 3+ times
- **Waste detection**: Triggers light compaction on 3+ consecutive errors, large tool_result, or repeated identical calls
- **Compact circuit breaker**: Stops retrying after 3 consecutive compact failures
- **Tool result truncation**: 40K char limit with smart head/tail split

## Hook System

Extensible hook interfaces for customization:

- **LoopHook**: `beforeLoop()` / `afterLoop()` — intercept the entire agent loop
- **SkillHook**: `beforeSkillExecute()` / `afterSkillExecute()` — intercept individual skill calls

## Observability

### Traces (Langfuse-style)

Every agent loop execution is recorded as a structured trace with hierarchical spans:

```
AGENT_LOOP (root span — one per user message)
├── LLM_CALL (iteration 0 — model inference, per-call tokens + duration)
├── TOOL_CALL (Bash — input JSON, output, duration, toolUseId)
├── TOOL_CALL (FileRead — ...)
├── LLM_CALL (iteration 1)
├── ASK_USER (blocked waiting for user reply)
├── COMPACT (context compaction event)
└── LLM_CALL (final iteration — text response)
```

Dashboard **Traces** page shows:
- Trace list with LLM/tool call counts, duration, tokens, status
- Span detail with waterfall timeline bars, expandable I/O
- Session ID filter

### Session Replay

Dashboard **Chat** page includes a Replay toggle that restructures the flat message history into:
- Turns (each user message → agent response cycle)
- Iterations within each turn
- Tool calls with timing, input/output, success/failure

## Skill Packages

Custom skills can be uploaded as zip packages or installed from marketplaces:

```
my-skill.zip
├── SKILL.md         # Required: YAML frontmatter + prompt content
├── references/      # Optional: reference docs
├── scripts/         # Optional: executable scripts
└── docs/            # Optional: extended documentation
```

System skills are loaded from `system-skills/` directory at startup (configurable via `skillforge.system-skills-dir`).

## Roadmap

### ✅ Delivered
- **Tool & Skill system** — system tools (Java), system skills (file-based), user skills (marketplace + upload), unified loading
- **Dashboard** — chat, sessions (live), agents, skills (unified), traces, session replay, memories, model usage
- **Streaming chat** — per-token assistant text + tool_use input JSON via WebSocket
- **Agent Loop guardrails** — token budget, duration limit, tool frequency warning, no-progress detection, compact circuit breaker, tool result truncation
- **LLM observability** — TraceCollector with AGENT_LOOP / LLM_CALL / TOOL_CALL / ASK_USER / COMPACT spans, per-call token tracking
- **Session lifecycle** — runtime status, ask_user blocking, error banner, cancel (in-stream SSE + loop-level)
- **Ask mode / Auto mode** — per-agent default with per-session override
- **SubAgent orchestration** — async dispatch, child sessions, depth/concurrency limits, persistence, restart recovery
- **Auto-compact context compression** — light (rule-based) + full (LLM summary with identifier preservation), 6 trigger sources
- **Context awareness** — EnvironmentContextProvider (CWD, OS, date), Tool Usage Guidelines in system prompt
- **CLI module** (`skillforge-cli`) — YAML-first agent import/export, one-shot `chat` command
- **User message queuing** — messages queued during agent run, drained at iteration boundaries
- **Marketplace integration** — ClawHub, SkillHub, GitHub skills pre-installed as system skills

### 📋 Planned
- **Skill always-on mode** — skills that inject promptContent into system prompt permanently (needed for OpenClaw skill compatibility)
- **Memory system enhancement** — memory auto-injection into system prompt, cross-session learning
- **JWT auth** — proper user identity (today's userId scoping is a placeholder)
- **Redis session sharing** — multi-instance deployment + replace JVM stripe locks with distributed locks
- **Elasticsearch conversation search** — full-text search across session history
- **Stop hooks** — system-level hooks for custom checks at each loop iteration end
- **Preemptive compaction** — check for context overflow before sending LLM request
- **max_tokens recovery** — escalate + compact + retry on output truncation

## License

MIT
