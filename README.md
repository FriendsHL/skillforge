# SkillForge

Server-side Agentic Assistant Platform with configurable Skills and Agents.

## Architecture

```
┌─────────────────────────────────────────────────┐
│              Entry Points                        │
│   REST API  │  WebSocket (TODO)  │  CLI (TODO)   │
├─────────────────────────────────────────────────┤
│              Spring Boot Server                  │
│   Chat / Agent / Skill / Dashboard API           │
├─────────────────────────────────────────────────┤
│              Agent Loop Engine                   │
│   Message → LLM → tool_use → Skill → loop       │
├──────────────┬──────────────┬───────────────────┤
│  LLM Layer   │ Skill System │ Session & Hooks   │
│  Claude      │ Built-in     │ Session Mgmt      │
│  OpenAI      │ Skill Zip    │ SafetyHook        │
│  DeepSeek    │ Packages     │ Loop/Skill Hooks  │
├──────────────┴──────────────┴───────────────────┤
│              Storage                             │
│   H2 (dev) / MySQL (prod)  │  Redis (TODO)      │
└─────────────────────────────────────────────────┘
```

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 3.2, Java 17 |
| Database | H2 embedded (zero-install, switchable to MySQL) |
| LLM | Multi-provider: Claude, OpenAI, DeepSeek, any OpenAI-compatible API |
| HTTP Client | OkHttp 4 + SSE streaming |
| Build | Maven multi-module |

## Modules

```
skillforge/
├── skillforge-core       # Core engine: LLM abstraction, Agent Loop, Skill system, Hooks
├── skillforge-skills     # Built-in Skills: Bash, FileRead, FileWrite, FileEdit, Glob, Grep
└── skillforge-server     # Spring Boot server: REST API, JPA entities, services
```

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

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/agents` | List all agents |
| GET | `/api/agents/{id}` | Get agent detail |
| PUT | `/api/agents/{id}` | Update agent |
| DELETE | `/api/agents/{id}` | Delete agent |
| GET | `/api/chat/sessions?userId=1` | List user sessions |
| GET | `/api/chat/sessions/{id}/messages` | Get session messages |
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

## Skill Packages (TODO)

Upload custom skills as zip packages:

```
my-skill.zip
├── skill.yaml       # Metadata: name, description, triggers, required tools
└── SKILL.md         # Prompt template loaded into agent context
```

## Roadmap

- [ ] **Phase 2**: Skill zip upload/parsing, Agent YAML import/export, more built-in skills
- [ ] **Phase 3**: React + Ant Design dashboard, WebSocket streaming chat, session replay
- [ ] **Phase 4**: JWT auth, Redis session sharing, Elasticsearch conversation search
- [ ] **Phase 5**: Sub-agent orchestration, CLI module, auto-compact context compression

## License

MIT
