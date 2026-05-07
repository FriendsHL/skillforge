# MCP Client MVP — 技术方案

---
id: MCP-CLIENT-MVP
status: done
prd: ./prd.md
risk: Full
created: 2026-05-07
updated: 2026-05-07
---

## TL;DR

新建 `skillforge-mcp` Maven 子模块，实现 MCP stdio client + JSON-RPC 协议适配。Server 进程通过 ProcessBuilder 启动，tools 以 `mcp_<server>_<tool>` 前缀注入 SkillRegistry。配置走全局 yaml + per-agent JSONB selector。dashboard 新建 `/mcp-servers` 页面 CRUD。

## 关键决策（用户 2026-05-07 ratify 9 项）

| # | 决策点 | 选择 | 理由 |
|---|---|---|---|
| Q1 | 范围 | **(a) 仅 MCP Client，不做 Server** | Server 价值低、Client 是 90% 用例 |
| Q2 | Transport | **(a) 仅 stdio，不做 HTTP/SSE** | stdio 覆盖大多数 MCP server；HTTP V2 |
| Q3 | 配置位置 | **(c) 全局 yaml + per-agent 选子集** | admin 配 server 池、agent 选启用 |
| Q4 | Dashboard UI | **(b) `/mcp-servers` CRUD + agent drawer selector** | 用户自助管理 |
| Q5 | 鉴权 | **(a) env var 通过 stdio process env 传** | OAuth V2 |
| Q6 | 工具命名 | **(a) `mcp_<server>_<tool>` 下划线前缀** | 防冲突 + trace 易识别 |
| Q7 | 错误处理 | **(b) best-effort 启动 + lazy 重连** | 单 server 故障不影响整体 |
| Q-A | 协议测试策略 | **删 Java fixture，纯 Mockito 单测 + Phase Final time server 联调** | 用户决策：fixture 维护成本不值；Phase Final 用 time 真协议联调兜底 |
| Q-D | dogfooding | **仅 time**（filesystem / github / fixture 全排除）| 与现有 tool / skill 冲突 / 维护成本 |

## 架构

```
┌─────────────────────────────┐
│  ChatService / Agent Loop   │  agent 调 tool_use("mcp_time_convert_time", {...})
└──────────┬──────────────────┘
           │ SkillContext.invoke
           ▼
┌─────────────────────────────┐
│  SkillRegistry              │  注册了 mcp_<server>_<tool> 的 McpToolAdapter
└──────────┬──────────────────┘
           │ McpToolAdapter.execute(input, ctx)
           ▼
┌─────────────────────────────┐
│  McpServerSession           │  per-server Session 对象，封装一个 stdio 连接
│  - sendRequest("tools/call")│  → JSON-RPC over stdio
│  - 处理 response / error    │
└──────────┬──────────────────┘
           │ JSON-RPC over stdin/stdout
           ▼
┌─────────────────────────────┐
│  external MCP server        │  npx -y mcp-server-time （子进程）
│  (Node.js / Java / Python)  │
└─────────────────────────────┘
```

## 模块设计

### 新模块：`skillforge-mcp`（或集成到 `skillforge-tools`）

> 决策建议：**集成到 `skillforge-tools`**，不新建模块。理由：
> - 避免循环依赖（mcp 需要引用 `core.skill.Tool` 接口）
> - tool 本质是 Tool 实现的另一种来源（A 类 = Java 内置 / B 类 = zip skill / C 类 = MCP）
> - 减少 maven 配置 + 模块边界复杂度

### 关键类（在 `skillforge-tools/.../mcp/` 下）

```
skillforge-tools/src/main/java/com/skillforge/tools/mcp/
├── transport/
│   ├── McpTransport.java              // 接口（stdio / HTTP V2 都实现）
│   └── McpStdioTransport.java         // ProcessBuilder + stdin/stdout JSON-RPC
├── protocol/
│   ├── McpRequest.java / McpResponse.java / McpError.java   // JSON-RPC 2.0 records
│   ├── McpInitializeMethod.java
│   ├── McpListToolsMethod.java
│   └── McpCallToolMethod.java
├── session/
│   ├── McpServerSession.java          // 一个 server = 一个 session（封装 transport 连接 + 协议状态机）
│   └── McpServerSessionRegistry.java  // 全局 server 池：sessionByName + lifecycle
├── adapter/
│   └── McpToolAdapter.java            // 实现 SkillForge core.skill.Tool 接口，包装一个 MCP tool
└── exception/
    └── McpClientException.java
```

### Server 端新增（`skillforge-server/.../mcp/`）

```
skillforge-server/src/main/java/com/skillforge/server/mcp/
├── entity/McpServerEntity.java        // t_mcp_server JPA entity
├── repository/McpServerRepository.java
├── service/McpServerService.java      // CRUD + ownership + reload
├── service/McpServerLifecycle.java    // ApplicationReadyEvent → 启动；shutdown → 停止；CRUD event → reload
├── service/McpToolRegistrar.java      // 把 server tools 以 mcp_*_* 前缀注册到 SkillRegistry
├── controller/McpServerController.java // REST `/api/mcp-servers` CRUD + dry-run test connection
└── dto/...
```

### Agent 字段

```sql
ALTER TABLE t_agent ADD COLUMN mcp_server_ids VARCHAR(512) NOT NULL DEFAULT '';
-- 格式 "time,foo" 或 ""，与现有 skill_ids 惯例一致（comma-list VARCHAR），不破坏现有惯例
```

### 前端（`skillforge-dashboard/.../mcp-servers/`）

```
src/pages/McpServers.tsx
src/components/mcp/McpServerEditDrawer.tsx
src/components/mcp/McpServerStatusTag.tsx
src/api/mcpServers.ts
src/types/mcpServer.ts
```

agent 编辑 drawer (`AgentDrawer.tsx`) 加 `mcp_server_ids` multi-select。

## 数据模型 / Migration（V61）

```sql
-- V61__create_mcp_server.sql
CREATE TABLE t_mcp_server (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL UNIQUE,             -- "time", "github"; 用作前缀 mcp_<name>_*
    command VARCHAR(256) NOT NULL,                -- "npx" or "java"
    args JSONB NOT NULL DEFAULT '[]',             -- ["-y", "mcp-server-time"]
    env JSONB NOT NULL DEFAULT '{}',              -- {"GITHUB_TOKEN": "${GITHUB_PAT}"}
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_mcp_server_enabled ON t_mcp_server(enabled);
COMMENT ON COLUMN t_mcp_server.name IS 'Used as prefix in mcp_<name>_<tool> tool registration. Must be [a-z0-9_]+';
COMMENT ON COLUMN t_mcp_server.args IS 'JSON array of process args';
COMMENT ON COLUMN t_mcp_server.env IS 'JSON object of env vars; values can use ${ENV_VAR_NAME} placeholders resolved from System.getenv';

ALTER TABLE t_agent ADD COLUMN mcp_server_ids VARCHAR(512) NOT NULL DEFAULT '';
COMMENT ON COLUMN t_agent.mcp_server_ids IS 'Comma-separated list of mcp server names (matching t_mcp_server.name) that this agent enables. Same convention as skill_ids. Empty string = no MCP servers enabled.';

-- Default seed: time server (idempotent)
INSERT INTO t_mcp_server (name, command, args, env, description, enabled)
VALUES (
    'time',
    'npx',
    '["-y", "@modelcontextprotocol/server-time"]'::jsonb,
    '{}'::jsonb,
    'Anthropic 官方 time MCP server：时区转换 / 当前时间。零配置无 auth。',
    TRUE
) ON CONFLICT (name) DO NOTHING;
```

## REST API

| Method | Path | 说明 |
|---|---|---|
| POST | `/api/mcp-servers?userId={id}` | 创建（admin 校验） |
| GET | `/api/mcp-servers?userId={id}` | 列出（含 connection status） |
| GET | `/api/mcp-servers/{id}?userId={id}` | 详情（含 tools 列表） |
| PUT | `/api/mcp-servers/{id}?userId={id}` | 修改（修改后自动 reload） |
| DELETE | `/api/mcp-servers/{id}?userId={id}` | 删除（先解绑所有 agent.mcp_server_ids 引用 + 警告） |
| POST | `/api/mcp-servers/{id}/test-connection?userId={id}` | dry-run：启 server + initialize + tools/list + close，返工具列表 |

## 关键执行语义（隐性 invariants）

| # | INV | 实现要点 |
|---|---|---|
| INV-1 | MCP server 子进程隔离：一个 server crash 不影响其他 server / 主进程 | 各 server 独立 ProcessBuilder + 独立 thread 读 stdout；Process.destroyForcibly on shutdown |
| INV-2 | 启动 best-effort：连不上 log warn 不 fail-fast | McpServerLifecycle.onApplicationReady 用 try/catch per-server，失败标 status=error 但不抛 |
| INV-3 | tool 命名 `mcp_<server>_<tool>`：server name 必须 [a-z0-9_]+；tool name 透传 server 提供 | McpToolRegistrar 校验 server name 格式；冲突时后注册的 server tool 拒绝（log error） |
| INV-4 | per-agent 启用过滤：agent 调 `mcp_*_*` tool 时若 agent.mcp_server_ids 不含该 server → 拒绝（INV-12 类比 P10） | SkillContext.resolveTool 加 mcp 前缀过滤 |
| INV-5 | env var 替换：yaml / DB 的 env value 含 `${VAR_NAME}` 时从 System.getenv 解析 | McpServerLifecycle.spawnServer 启动前 resolveEnv |
| INV-6 | CRUD 后立即 reload：DB 修改 → ApplicationEventPublisher 发 event → McpServerLifecycle 监听 → 停旧 server + 启新 server | `@TransactionalEventListener(AFTER_COMMIT)`（参考 P12 W3 教训） |
| INV-7 | shutdown 优雅 close：应用 shutdown 时 close 所有 stdio + destroyForcibly 进程 | DisposableBean.destroy 或 @PreDestroy |
| INV-8 | tools/list 缓存：连上 server 后 cache tool definitions；不每次 chat 都重新 list | McpServerSession.cachedTools，重连时 invalidate |
| INV-9 | 跨用户 ownership：MCP server 是全局资源，但 CRUD 只允许 admin / 系统 user | controller 层 owner_id 校验（按现有 admin 模式） |
| INV-10 | 协议错误处理：JSON-RPC error response → 包成 SkillResult.error；server crash → 标 unavailable + 下次 chat 时 lazy 重连 | McpToolAdapter try/catch + lifecycle 重连 |
| INV-11 | tool input schema 透传：MCP tool 定义 inputSchema (JSON Schema) → 直接作为 SkillForge ToolSchema 喂给 LLM | McpToolAdapter.getToolSchema() 返 Map/JsonNode |
| INV-12 | DELETE 引用校验：删 server 前检查 `t_agent.mcp_server_ids` 含该 server name → 返 409 Conflict + 列出引用 agent，要求先解绑 | McpServerService.delete 内部 query agents WHERE mcp_server_ids LIKE '%name%' |
| INV-13 | env mask sentinel = exactly three asterisks `"***"`. FE / BE 两端必须用同一字面值。FE 在 `McpServerEditDrawer.tsx:48` 定义 `MASKED_VALUE = '***'`，BE 在 `McpServerResponse.MASKED_VALUE` 定义 `public static final String MASKED_VALUE = "***"`。FE *** entry 透传给 BE，BE preserve-on-*** 兜底（**FE 不过滤** — 否则 BE 收不到 *** 触发 preserve）。一边改另一边必须同步，否则 secret round-trip 行为断 | FE: `McpServerEditDrawer.tsx`；BE: `McpServerResponse.java` + `McpServerService.mergeEnvPreservingMasked` |

## 协议测试策略（Q-A 决策）

**不写 Java IT fixture**（用户 2026-05-07 ratify：维护成本不值）。改走：

1. **Mockito 单测覆盖协议层**（`skillforge-tools/src/test/java/.../mcp/`）：
   - `McpStdioTransportTest`：mock `Process` / `InputStream` / `OutputStream`，验证 JSON-RPC 编码 / 解码 / 帧分隔
   - `McpServerSessionTest`：mock McpTransport 接口，验证 `initialize` 状态机 / `tools/list` 缓存 / `tools/call` 路由 / error response 处理 / disconnect 重连
   - `McpToolAdapterTest`：mock McpServerSession，验证 tool input 透传 / output 解析 / 异常 → SkillResult.error
   - `McpToolRegistrarTest`：验证 `mcp_<server>_<tool>` 命名 / 冲突拒绝 / per-agent 过滤（INV-3 / INV-4）

2. **Phase Final 真 time server 端到端联调**（你的机器）：
   - 启动 SkillForge backend → 默认 seed 的 time server 应自动 connected
   - dashboard `/mcp-servers` 列表 status=connected / 工具数=2
   - agent enable time → chat "现在上海时间几点" → agent 调 `mcp_time_get_current_time(Asia/Shanghai)` 返回正确时间
   - dashboard CRUD 加新 server / 删除 / 测试连接 等流程

**接受 trade-off**：单测 mock 不验真 stdio race；端到端协议正确性靠 Phase Final 兜底。如果未来频繁出协议层 bug，再加 IT fixture（V2）。

## 错误处理 / 安全

- env var 含 `${VAR_NAME}` 占位，但**不**支持任意 shell expansion（防注入）
- command / args **不**走 shell，直接 ProcessBuilder array form（防 command injection）
- server name 格式校验 `[a-z0-9_]+` 长度 ≤ 32（防 SQL / path 注入）
- MCP server stdio output **不**直接 log（可能含敏感数据）；只 log 协议层 method / status
- 删除 server 前检查 agent 引用，要求用户先解绑（防 dangling reference）

## 实施计划

- [x] 完成前置 scope ratify（2026-05-07 用户 9 决策 + 2 server 决策）
- [x] Full Pipeline 实施（2 dev 并行：BE + FE）
- [x] r1 + r2 + r2.5 + r3 对抗审查（详见下方"r1-r3 对抗审查 fix 记录"）
- [x] Phase Final 真 time server e2e（uvx mcp-server-time pid=50548 connected + 2 tools 注入：`mcp_time_get_current_time` / `mcp_time_convert_time`）
- [x] commit + 归档（含修两个 startup bug：EntityScan/EnableJpaRepositories 漏 mcp package + @TransactionalEventListener 加 REQUIRES_NEW propagation）

## 测试计划

- [ ] Mockito 单测：McpStdioTransport 编码 / 解码 / 帧分隔；McpServerSession initialize / list / call / error / 重连状态机；McpToolAdapter 输入输出 / 异常路径；McpToolRegistrar 命名 / 冲突 / per-agent 过滤
- [ ] McpServerService CRUD + ownership + DELETE 引用校验 tests
- [ ] McpServerLifecycle reload event tests
- [ ] McpToolRegistrar 命名前缀 + 冲突 + per-agent 过滤 tests
- [ ] McpServerController REST + test-connection dry-run tests
- [ ] McpStdioTransport process lifecycle tests（启动 / 异常退出 / shutdown）
- [ ] 浏览器：列表 / 编辑 drawer / 测试连接 / agent drawer multi-select
- [ ] 端到端：dashboard 加 time server → agent enable → chat 调 mcp_time_get_current_time

## 风险

- **stdio MCP server 进程稳定性**：第三方 npm 包质量参差，可能频繁 crash → INV-2 best-effort + INV-10 lazy 重连
- **npx 首次启动慢**：`npx -y` 第一次会下载 npm 包（~10s），影响 `test-connection` 体验 → 接受现状，UI 显示 spinner
- **跨平台**：dev macOS 跑 npx 没问题，prod Linux 也支持 → 接受
- **MCP 协议演进**：当前实现 spec 0.x，未来 1.0 可能不兼容 → 锁版本 + brief 注明
- **触碰核心 SkillRegistry**：mcp_*_* 注册可能与现有 Java tool 名冲突 → INV-3 校验 + 注册拒绝
- **chatLoopExecutor 共享**：MCP tools 执行共享 chat loop pool（与 P10 / P12 同模式）→ 接受 V2 拆 dedicatedExecutor

## 评审记录

- 2026-05-07 design-draft：MVP scope + 9 决策 + 2 dogfooding server (time + Java IT fixture) ratified by user.
- 2026-05-07 实施完成（commit 待定），Full Pipeline r1+r2+r2.5+r3 对抗审查 PASS。

## r1-r3 对抗审查 fix 记录

**r1 reviewer（BE Sonnet / FE Sonnet）**：
- BE r1：**0 blocker / 4 warning**：W1 findAll on DELETE / W2 stderr WARN 全文 leak / W3 env field plaintext 暴露 / W4 INV-9 admin no-op
- FE r1：**1 blocker（实测证伪 main pre-existing）/ 2 warning**：B AgentDrawer.test.tsx 5 fail（实测证明 main 上 LifecycleHooksPanel jsdom 问题，非 P11 引入） / W1 edit 仍发 name 字段（rename 隐患）/ W2 listMcpServers 静默 fallback `[]`

**Judge（team-lead 仲裁）**：
- must-fix-r2: BE W2/W3 + FE B（防御性 mock）/ W1 / W2
- accept-as-is: BE W1 brief 已 ack / BE W4 MVP ratify deferral / BE C1 t_model_provider 不存在用 LlmProperties / BE C2 SYSTEM_TOOL_NAMES cosmetic / FE C2 antd width 不顺手清

**r2 fix（BE + FE 同时跑）**：
- BE W2: stderr WARN→DEBUG + isDebugEnabled() 短路
- BE W3: env mask placeholder regex `^\$\{[A-Za-z_][A-Za-z0-9_]*\}$` 透传，否则 *** + 9 新测试
- FE B: 加防御性 vi.mock listMcpServers（FE 实测证明 5 fail 是 pre-existing）
- FE W1: edit body omit name + McpServerUpdate type 移除 name?:
- FE W2: useEffect-on-error + retry=1
- FE W3-FE（team-lead 后追加）：MASKED_VALUE 常量 + entriesToEnvMap 过滤 *** + hint 文案 + 2 新测试

**r2.5 BE 联动 fix**（基于 FE concern）：
- Fix 1 update name 兼容: service.update 现有逻辑已支持 nullable name + explicit-mismatch reject，0 code change + 3 新测试
- Fix 2 env preserve-on-***: McpServerService.update 改走 mergeEnvPreservingMasked，req value=='***' 且 key in existing → 保留 existing；其他 case 用 req value；MASKED_VALUE 改 public 单 source；5 新测试

**r2 reviewer 复审**：
- BE r2: PASS, 0 blocker / 0 warning, 1 layer boundary nit (service 引用 dto MASKED_VALUE)
- FE r2: PASS (conditional)，**抓 architectural flaw**：FE r2 W3-FE 的 entriesToEnvMap 过滤 *** 后 BE preserve-on-*** never fires（两边防御 sequential 不 compound），用户没改 secret 直接 Save → BE PUT-replaces 删 key → 真 secret 丢失

**r3 fix（FE 撤销 W3-FE filter）**：
- FE 撤销 entriesToEnvMap 中 `v === MASKED_VALUE` 过滤
- 反向 W3-FE 测试：从 "drops *** entries" → "keeps *** entries so BE preserve-on-*** can fire"
- 保留 MASKED_VALUE 常量 + Form.Item hint（用户视角语义不变："留 *** 不改"还是无操作 → 保留 secret，但底层机制从 FE drop 换成 BE preserve）

**Phase Final 启动**：发现 2 个 unit test 漏抓的 startup bug（reviewer 都没抓到）：
- Bug 1: SkillForgeApplication `@EntityScan` / `@EnableJpaRepositories` 不含 `com.skillforge.server.mcp.*` package → McpServerRepository bean not found（unit test 用 Mockito mock 跳过 Spring）
- Bug 2: `McpServerLifecycle.onUpserted` `@TransactionalEventListener(AFTER_COMMIT) + @Transactional` 在 Spring 6.1+ 必须用 `propagation = Propagation.REQUIRES_NEW` / `NOT_SUPPORTED`（默认抛 IllegalStateException）

**最终 e2e**：uvx mcp-server-time 进程 (pid 50548) 启动，session connected, 2 tools (`mcp_time_get_current_time` / `mcp_time_convert_time`) 注入 SkillRegistry, dashboard `/mcp-servers` 显示 status=connected, test-connection 返完整 inputSchema 透传。

## Ratified Decisions（2026-05-07 实施前用户 ratify）

详见上文"关键决策"表格。9 项 + 2 dogfooding 决策。

**MVP 不做**（V2）：
- MCP Server（反向）
- HTTP / SSE transport
- OAuth flow
- 用户级别 MCP server（per-user 配置）
- 接入 Firecrawl / Postgres / Notion / Calendar / Sentry 等具体 server（dashboard CRUD 自助加）
- MCP server 监控告警 / 心跳检测
- MCP resources / prompts（仅 tools）
- 专用 mcpExecutor pool（共享 chatLoopExecutor）
