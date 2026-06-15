# MCP HTTP Transport + AnySearch

---
id: MCP-HTTP-ANYSEARCH
mode: full
status: done
priority: P1
risk: Full
created: 2026-06-15
updated: 2026-06-15
---

> 本需求为 **reactive built + 回溯归档**：实现先于需求包，文档为交付后补档。它是 [MCP-CLIENT-MVP](../2026-05-07-MCP-CLIENT-MVP/index.md) 当初延期到 V2 的 **HTTP transport** 续作（MVP 仅做 stdio，把 HTTP/SSE 列入 V2）。本期落地 HTTP transport 这个既定扩展点，并挂载首个远程 MCP server **AnySearch**。

## 摘要

给 MCP Client 加 **HTTP transport**（务实版 Streamable HTTP），让 SkillForge 能连接远程托管 MCP server，而不再局限于本地 stdio 子进程。同步挂载远程 server **AnySearch**（结构化垂直查询：股票 / 行情等），绑定到 Research Agent 与 Main Assistant，并修复一个 pre-existing 的 core 工具可见性分叉 bug（有 `toolIds` 白名单的 agent 此前看不到已绑定的 MCP 工具）。

**交付范围**：
1. `McpHttpTransport` —— 实现 `McpTransport` 接口的 HTTP 形态（每请求 POST + `Accept: application/json, text/event-stream`，按响应 `Content-Type` 分流 JSON / SSE 单帧，捕获并回带 `Mcp-Session-Id`，16MiB 响应体上限，错误信息脱敏，线程安全）。session/adapter/registrar/protocol 层不动（接口隔离）。
2. **Schema V152** —— `t_mcp_server` 加 `transport` / `url` / `headers` 列，`command` 放宽可空，加 `CHECK chk_mcp_transport`（stdio→command 非空 / http→url 非空）。entity/service/lifecycle/DTO 按 transport 分支，headers 复用 lifecycle `${VAR}` 替换 + Response 端 `***` 脱敏 + create/update 强制 https。
3. **Core 修复** —— `AgentLoopEngine.collectTools` 的 surface gate 让 MCP 工具豁免 `toolIds` 内置白名单，仅由 `mcpServerNames` gate 管，镜像 `executeToolCall` 的 dispatch gate。
4. **挂载 / 绑定 / 路由 V153 / V154** —— V153 seed AnySearch 行 + 绑定 Research Agent / Main Assistant；V154 给两 agent 的 `tools_prompt` 加结构化垂直查询路由引导。
5. **Dashboard UI** —— MCP 编辑抽屉加 transport 下拉 + 条件渲染（stdio→command/args；http→url+headers 键值编辑器）+ 列表页 transport tag。

## 范围

**做**：
- HTTP transport（POST + JSON/SSE 单帧响应分流 + session-id 回带 + 脱敏 + 16MiB 上限）。
- `t_mcp_server` transport / url / headers schema 扩展 + 校验。
- 远程 server AnySearch 接入 + 绑定 2 个 agent + 路由引导。
- 修 core collectTools 工具可见性分叉 bug + 回归测试。
- Dashboard transport 编辑 / 展示 UI。

**不做**（沿用 MCP-CLIENT-MVP 的 V2 边界 + 本期新边界）：
- GET 的 server→client 长连 SSE（工具调用用不上；本期 HTTP transport 不实现），仍记为后续延期项。
- OAuth flow / 用户级 MCP server / MCP resources & prompts。
- transport 列在 create 后可变（post-create immutable，避免 stdio↔http 跳变破坏 CHECK / 下游）。

## 验收点

- AC-1：远程 server AnySearch 走完整握手（initialize → notifications/initialized → tools/list → tools/call），status=connected 且工具注入成功；无 `Mcp-Session-Id` 的无状态 server（AnySearch 即无状态）也正常。
- AC-2：HTTP transport 按响应 `Content-Type` 正确分流 application/json 与 text/event-stream（按 request id 匹配 SSE `data:` 帧）。
- AC-3：headers 中的 secret（Bearer 等）绝不持久化 resolved 形态、绝不进日志 / 异常 / 错误响应；API response 用 `***` 脱敏；`***` 回传不覆盖真值（mask-preserve merge）。
- AC-4：有 `toolIds` 白名单的 agent 能看到并调用已绑定的 MCP 工具（修复前不可见）。
- AC-5：向后兼容 —— transport 默认 stdio，现有 stdio server（time）零回归。
- AC-6：Research Agent 与 Main Assistant 对结构化垂直查询（不被提示）能自动走 AnySearch 拿真数据。

## 验证方式

- BE 全量受影响模块（core + tools + server）`mvn test` BUILD SUCCESS（0 失败，2880+ 测试）；新增 `McpHttpTransportTest` / `McpServerServiceTest`（http 校验）/ `AgentLoopEngineMcpToolWhitelistTest`。
- FE `tsc` 0 + `npm run build` ✓，MCP 单测 29 passed。
- 5 reviewer 对抗（java / java-design / ts / db / security），2 blocker 修复复验 + 修复 core 工具路由 bug。
- 真机 e2e：部署后 AnySearch status=connected 4 工具、API headers 脱敏 `***`；两 agent 对结构化查询自动 `get_sub_domains` → `search` 调到 AnySearch 拿真数据，Bearer 注入全程无 401。

详见 [tech-design.md](tech-design.md) 测试计划 + 验证证据节，完整交付事实以 [delivery-index.md](../../../delivery-index.md) 为准。

## 阅读顺序

1. [技术方案](tech-design.md) — 架构 / 关键决策 / INV / 核心 bug 修复 / 测试计划

## 链接

| 文档 | 链接 |
| --- | --- |
| 技术方案 | [tech-design.md](tech-design.md) |
| 前作（stdio MVP） | [MCP-CLIENT-MVP](../2026-05-07-MCP-CLIENT-MVP/index.md) |
