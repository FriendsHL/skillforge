# MCP Client MVP — PRD

---
id: MCP-CLIENT-MVP
status: done
owner: youren
priority: P1
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-05-07
updated: 2026-05-07
---

## 摘要

实现 MCP Client MVP：SkillForge 作为 MCP host 连外部 stdio MCP server，把 server 暴露的 tools 注入到 SkillRegistry。

## 目标

1. **基础设施**：实现 MCP stdio transport + JSON-RPC 协议（initialize / tools/list / tools/call）
2. **配置管理**：admin 可在 dashboard `/mcp-servers` CRUD MCP server 定义（command / args / env）
3. **per-agent 启用**：每个 agent 选启用哪些 MCP server（类似现有 skill_ids / tool_ids）
4. **工具注入**：MCP server 启动后 tools 自动以 `mcp_<server>_<tool>` 前缀注入到 SkillRegistry
5. **dogfooding**：默认配 `@modelcontextprotocol/server-time` + 内部 Java IT fixture 验证端到端

## 非目标（V2）

- MCP Server（反向暴露 SkillForge tools 给 Claude Desktop / Cursor）
- HTTP / SSE transport
- OAuth flow（替代 env var）
- 用户级别 MCP server（目前只有全局）
- 接入 Firecrawl / Postgres / Notion 等具体 server（dashboard CRUD 自助）
- MCP server 监控告警 / 死活检测
- MCP server tool / resource / prompt 完整生命周期（resource / prompt 留 V2，本期只做 tools）

## 功能需求

### 1. MCP Stdio Client
- 支持启动外部 stdio 子进程（command + args + env）
- 实现 MCP JSON-RPC 协议（最小三方法：`initialize` / `tools/list` / `tools/call`）
- 处理 server 异常：crash → 标记 unavailable + lazy 重连
- 进程生命周期：启动时 best-effort 全量连，应用 shutdown 时优雅 close

### 2. MCP Server 配置（全局）
- 新表 `t_mcp_server`：id / name / command / args (JSON array) / env (JSON map) / description / enabled / created_at / updated_at
- application.yml 默认配 1 行 time server（不强制写死，允许 admin 删）
- REST `/api/mcp-servers` CRUD（仅 admin / owner_id 校验）

### 3. Per-agent 启用
- `t_agent` 加 `mcp_server_ids JSONB`（or VARCHAR comma-list 与现有 skill_ids 对齐）
- agent 启动 chat session 时按 `mcp_server_ids` 把对应 MCP server 的 tools 加到 SkillContext
- agent 编辑 drawer 加 MCP server 多选 selector

### 4. 工具命名 / 注入
- `mcp_<server_name>_<tool_name>` 前缀（避免和现有 Java tool 冲突）
- 注入到 SkillRegistry，agent 通过 tool_use 调用，参数 / schema 透传 MCP server tool definition

### 5. Dashboard `/mcp-servers` 页面
- 列表：name / command / enabled / status (connected / disconnected / error) / 工具数 / actions
- 新建 / 编辑 drawer：name / command / args / env / description / enabled
- 测试连接按钮：dry-run 启动 server + initialize + tools/list + 关闭，返回工具列表
- 删除按钮（+ 引用 agent 检查警告）

### 6. dogfooding + 测试策略
- V61 migration 默认 seed time server 一行（`@modelcontextprotocol/server-time`，零配置）
- 协议测试走 Mockito 单测覆盖 McpStdioTransport / McpServerSession / McpToolAdapter / McpToolRegistrar，CI 无外部依赖
- 端到端协议正确性由 Phase Final 真启 time server 联调兜底（不写内部 Java IT fixture）

## 验收标准

### 后端
- [ ] V61 migration：`t_mcp_server` + `t_agent.mcp_server_ids`
- [ ] MCP stdio client 实现 JSON-RPC 协议（initialize + tools/list + tools/call + 异常处理）
- [ ] application 启动时连 yaml 默认配置（time），best-effort（连不上 log warn 不 fail-fast）
- [ ] CRUD `t_mcp_server` 后即时 reload server 连接（启动 / 停止 / 重连）
- [ ] tool 以 `mcp_<server>_<tool>` 前缀注册到 SkillRegistry
- [ ] agent 创建 session 时按 `mcp_server_ids` 加对应 tools 到 SkillContext
- [ ] 跨用户 ownership 校验
- [ ] Mockito 单测覆盖 4 个核心协议类（McpStdioTransport / McpServerSession / McpToolAdapter / McpToolRegistrar），含 happy path + error / disconnect / reconnect 路径
- [ ] V61 默认 seed 的 time server 在 dev 启动后自动 connected（手测：dev 跑 backend → log 显示 time connected + 2 tools 注入）
- [ ] DELETE server 时若有 agent 引用 → 返 409 + 列引用 agent 名（INV-12）
- [ ] `mvn test` 全套绿（含 IT），不能 regress 现有 1009 tests

### 前端
- [ ] `/mcp-servers` 路由可达
- [ ] 列表展示所有字段 + 连接状态 Tag
- [ ] 新建 / 编辑 drawer Form 校验
- [ ] 测试连接按钮调 dry-run endpoint
- [ ] agent 编辑 drawer 加 MCP server multi-select
- [ ] `npx tsc --noEmit` + `npm run build` 通过
- [ ] 浏览器目检（Phase Final）

### 整体
- [ ] Phase Final 浏览器 e2e：
  - dashboard 默认看到 time server connected + 列出 2 tool
  - 创建 agent 启用 time server，chat 输入 "现在上海时间几点" → agent 调 `mcp_time_get_current_time(Asia/Shanghai)` 返回正确
  - dashboard 加 / 删 server 立刻生效

## 验证预期

- 后端 service / controller / IT fixture 单元 + 集成 tests
- 前端 component / API client tests
- dashboard build + 浏览器 e2e
- 数据库 migration 校验
- 真 time MCP server 端到端联调（npx 启子进程 → tools 注入 → agent 调用 → 结果返回）
