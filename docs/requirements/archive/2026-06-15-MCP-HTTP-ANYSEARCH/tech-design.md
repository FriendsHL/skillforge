# MCP HTTP Transport + AnySearch — 技术方案

---
id: MCP-HTTP-ANYSEARCH
status: done
prd: ./index.md
risk: Full
created: 2026-06-15
updated: 2026-06-15
---

## TL;DR

在 [MCP-CLIENT-MVP](../2026-05-07-MCP-CLIENT-MVP/index.md) 的 `McpTransport` 接口下新增 `McpHttpTransport`（务实版 Streamable HTTP），让 MCP Client 连接远程托管 MCP server。`t_mcp_server`（V152）加 `transport` / `url` / `headers` 列并按 transport 分支校验。挂载远程 server **AnySearch**（V153/V154 seed + 绑定 + 路由引导）。顺带修复 core 一个 pre-existing 工具可见性分叉 bug。session / adapter / registrar / protocol 层不动（接口隔离）。

## 架构

```
┌─────────────────────────────┐
│  ChatService / Agent Loop   │  agent 调 tool_use("mcp_anysearch_search", {...})
└──────────┬──────────────────┘
           │ SkillContext.invoke
           ▼
┌─────────────────────────────┐
│  SkillRegistry              │  注册了 mcp_<server>_<tool> 的 McpToolAdapter
└──────────┬──────────────────┘
           │ McpServerSession.sendRequest（不变）
           ▼
┌─────────────────────────────┐
│  McpTransport（接口）        │  stdio / http 两实现，session 层不感知
│  ├── McpStdioTransport      │  ← MVP（ProcessBuilder + NDJSON）
│  └── McpHttpTransport ★新增 │  ← 本期（OkHttp + POST + JSON/SSE 分流）
└──────────┬──────────────────┘
           │ HTTPS POST（Accept: application/json, text/event-stream）
           ▼
┌─────────────────────────────┐
│  远程 MCP server（AnySearch）│  https://api.anysearch.com/mcp（无状态，无 Mcp-Session-Id）
└─────────────────────────────┘
```

接口隔离是本方案的关键：transport 是 MVP 已留的扩展点，新增 HTTP 形态只动 transport 层 + DB/DTO/lifecycle 的 transport 分支，**session / adapter / registrar / protocol（JSON-RPC records）层不动**。

## 关键决策

| # | 决策点 | 选择 | 理由 |
|---|---|---|---|
| D1 | 远程接入形态 | **HTTP transport（原方案 B）** | 不走 stdio 桥（mcp-remote）也不写独立 Java Tool；一次实现，所有远程 MCP server 复用 |
| D2 | HTTP 协议范围 | **务实版 Streamable HTTP**：只做 POST + JSON/SSE 单帧响应 | 不做 GET 的 server→client 长连 SSE（工具调用用不上）；覆盖 AnySearch 及多数托管 MCP server |
| D3 | transport 列可变性 | **post-create immutable** | 避免 stdio↔http 跳变破坏 CHECK 约束 / 下游 lifecycle 假设 |
| D4 | 路由引导落点 | **`t_agent.tools_prompt`（而非 system_prompt）** | 运行时读 `tools_prompt`；且不被 `auto_improve` 的 system_prompt 版本晋升冲掉 |
| D5 | headers seed 占位符 | **`chr(36)` 拼接 `${VAR}`** | 规避 Flyway 占位符展开（沿用 V152 注释里同款 trick） |

## McpHttpTransport 设计要点

- **请求形态**：每请求 `POST` + `Accept: application/json, text/event-stream`。
- **响应分流**：按响应 `Content-Type` 分流 —— `application/json` 直接解析；`text/event-stream` 解析 SSE `data:` 帧，按 request id 匹配对应响应。
- **session 捕获**：捕获 `initialize` 响应的 `Mcp-Session-Id`，在后续请求回带；无 `Mcp-Session-Id` 的无状态 server（AnySearch 即无状态）也正常工作。
- **OOM 防护**：响应体 16MiB 上限（r1 blocker 修复，见下方）。
- **脱敏**：错误信息只带 `url.redact()` + status + method，绝不带 header / body / token。
- **线程安全**：OkHttp 客户端 + `volatile sessionId`。

## Schema / Migration

### V152 —— `t_mcp_server` transport 扩展

- 加 `transport`（默认 `stdio`）/ `url` / `headers` 列。
- `command` 放宽为可空。
- 加 `CHECK chk_mcp_transport`：`stdio` → `command` 非空 / `http` → `url` 非空。
- entity / service / lifecycle / DTO 按 transport 分支。
- headers 复用 lifecycle 的 `${VAR}` 替换（与 env 一致）+ Response 端 `***` 脱敏 + `mergeEnvPreservingMasked` 防 `***` 回传覆盖真值。
- create / update 强制 https（`localhost` / `127.0.0.1` / `::1` 例外）。

### V153 —— seed AnySearch + 绑定 agent

- seed AnySearch 行：`transport=http`，`url=https://api.anysearch.com/mcp`，`headers={"Authorization":"Bearer ${ANY_SEARCH_API_KEY}"}`（用 `chr(36)` 拼接躲 Flyway `${}` 展开）。
- 绑定 Research Agent（id=5）/ Main Assistant（id=3）的 `mcp_server_ids`。

### V154 —— 路由引导

- 给两 agent 的 `tools_prompt` 加结构化垂直查询路由引导：优先 AnySearch + 先 `get_sub_domains` 摸参数。

## Core 修复：工具可见性分叉 bug

**根因**：`AgentLoopEngine.collectTools` 的 surface gate（决定 LLM 看得到哪些工具）此前把 MCP 工具（`mcp_` 前缀）也纳入 `toolIds`（`allowedToolNames`）内置白名单过滤，但 `executeToolCall` 的 dispatch gate（line ~2751）只按 `mcpServerNames` gate（`isMcpToolAllowed`）。二者分叉导致：**有 `toolIds` 白名单的 agent 此前看不到已绑定的 MCP 工具**（dispatch 放行但 surface 挡掉 → 工具可调用却不可见）。

**修复**：`collectTools` 让 `mcp_` 前缀工具豁免 `toolIds` 白名单，仅由 `mcpServerNames` gate（`isMcpToolAllowed`，line ~1694）管，**镜像** `executeToolCall` 的 dispatch gate（line ~2751）。

**回归**：加 `AgentLoopEngineMcpToolWhitelistTest`（3 case），revert fix 后变红。

## 关键执行语义（不变量 INV）

| # | INV | 实现要点 |
|---|---|---|
| INV-A | `collectTools` 的 surface gate 对 mcp 工具的处理**必须镜像** `executeToolCall` 的 dispatch gate（都按 `mcpServerNames` 而非 `toolIds`） | 二者分叉会导致工具可调用却不可见（本次 bug 根因）；`AgentLoopEngineMcpToolWhitelistTest` enforce |
| INV-B | secret（Bearer / header 值）绝不持久化 resolved 形态、绝不进日志 / 异常 / 错误响应 | API response 用 `***` 脱敏；mask-preserve merge 防 `***` 回传覆盖真值；transport 错误只带 `url.redact()` + status + method |
| INV-C | headers 的 `${VAR}` 替换复用 `McpServerLifecycle.substitute`（与 env 一致） | 不另写一套替换逻辑 |
| INV-D | 向后兼容 —— transport 默认 stdio，现有 stdio server（time）零回归 | V152 `transport` 列 DEFAULT `stdio`；CHECK 不影响既有 stdio 行 |
| INV-E | 远程 MCP server 必须能走完整握手（initialize → notifications/initialized → tools/list → tools/call）；无 `Mcp-Session-Id` 的无状态 server 也正常 | HTTP transport session-id 捕获/回带为可选；AnySearch 即无状态 |

## 测试计划

- `McpHttpTransportTest`（11，MockWebServer）：JSON 响应 / SSE 帧 / session-id 捕获回带 / 非 2xx / headers 注入 / 并发 / close / 超大体（16MiB 上限）。
- `McpServerServiceTest`（http 校验）：url 必填 / command 可空 / transport immutable / https 强制 / headers mask-preserve。
- `AgentLoopEngineMcpToolWhitelistTest`（3）：surface gate 对 mcp 工具豁免 toolIds 白名单，revert 后变红。
- FE：MCP 单测 29 passed；`tsc` 0 + `npm run build` ✓。

## 验证证据

- BE 全量受影响模块（core + tools + server）`mvn test` BUILD SUCCESS，0 失败（2880+ 测试）。
- FE `tsc` 0 + `npm run build` ✓，MCP 单测 29 passed。
- 5 reviewer 对抗（java / java-design / ts / db / security）：2 个 blocker 已修复复验 ——
  - **blocker 1**：`McpHttpTransport` 响应体无上限 → OOM 风险（加 16MiB 上限修复）。
  - **blocker 2**：`McpServerEditDrawer` setState-in-effect ESLint error。
  - 另发现并修复 core `collectTools` 工具路由 bug（surface/dispatch gate 分叉）。
- 真机 e2e：部署后 AnySearch status=connected 4 工具、API headers 脱敏为 `***`；Research Agent + Main Assistant 对结构化查询（不被提示）自动 `get_sub_domains` → `search` 调到 AnySearch 拿真数据（贵州茅台 600519.SH 收盘 1291.91 / AAPL $291.13），Bearer 注入全程无 401。

## 评审记录

- 2026-06-15 Full pipeline 5 reviewer 对抗（java / java-design / ts / db / security）：2 blocker（OOM 响应体上限 / FE setState-in-effect）已修复复验，另修 core 工具路由分叉 bug。

## 后续 follow-up / backlog

详见 [bugs.md](../../../bugs.md) 对应 follow-up 项：

- MCP 写接口（`/api/mcp-servers` POST/PUT/DELETE）无 admin-role gate（pre-existing；HTTP transport 放大 SSRF 风险面，建议补 admin gate）。
- FE `TestConnectionResponse.success` 与 BE `{"status":"ok",...}` 字段不匹配（pre-existing，"测试连接" 按钮 `if(body.success)` 永远 falsy）。
- `McpServerLifecycle` 里 `new OkHttpClient()` 宜抽成 `@Bean`（共享连接池 / dispatcher）。
- `McpServerRequest` 可变 getter/setter 类宜改 record（pre-existing 项目惯例）。
- V152 CHECK 未用 NOT VALID 分阶段 / url 无索引 / transport 隐式枚举（db nit，数据量小可忽略）。

## 未做 / 延期（沿用 MVP V2 边界 + 本期边界）

- GET 的 server→client 长连 SSE（工具调用用不上，本期 HTTP transport 不实现）—— 仍延期。
- OAuth flow / 用户级 MCP server / MCP resources & prompts —— 沿用 MVP V2 边界。
- transport 列 post-create immutable（不支持 stdio↔http 跳变）。
