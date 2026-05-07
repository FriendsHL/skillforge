# MCP Client MVP — MRD

---
id: MCP-CLIENT-MVP
status: ratified
created: 2026-05-07
---

## 用户原始痛点

> 来自 todo.md 暂缓栏 GAP-MCP（2026-05 用户反馈累积）：
>
> 当前 SkillForge 的 11 个 Tool 全是 **Java 内置**（FileRead/FileEdit/FileWrite/Bash/Browser/CodeReview/CodeSandbox/Glob/Grep/WebFetch/WebSearch），用户想接 GitHub / Slack / Notion / 内部 service catalog 等外部能力**只能写 Java 代码 + 编译进 `skillforge-tools` jar + 重启服务**。整个生态完全不能利用——Anthropic / 社区维护的几十个 MCP server 都用不上。

## 想做什么

实现 **MCP Client**：让 SkillForge 作为 MCP host 连接任意 stdio MCP server，把 server 暴露的 tools 自动注入到 SkillRegistry 给 agent 使用。

效果：
- **接 GitHub MCP** → agent 立刻能搜 PR / 建 issue（不写 Java）
- **接 Notion MCP** → agent 立刻能读 / 写 Notion 知识库
- **接 Postgres MCP（read-only）** → agent 立刻能 SQL 查 SkillForge 自己的 DB
- **接任意社区 MCP server**（Linear / Sentry / Calendar / Slack / ...）→ 通过 dashboard `/mcp-servers` CRUD 自助接入

## 用户场景

### 场景 1：研究外部 SDK 文档
用户问 agent："帮我把 Anthropic claude-api SDK 文档站爬下来，整理成 markdown 存到笔记"

- 当前：agent 用 WebFetch 抓单页，递归爬要写一堆代码
- MCP 后：用户接入 Firecrawl MCP，agent 直接调 `mcp_firecrawl_crawl` 拿整站结构化结果

### 场景 2：内部 DB analytics
用户问："统计本周新增的 agent 数量、token 总用量、最慢的 5 个 session"

- 当前：用户手动 psql 拼 SQL
- MCP 后：用户接入 Postgres MCP（read-only），agent 直接 SQL 查 SkillForge 自己的 PG

### 场景 3：长期保留的工具集
用户接入 Notion MCP / Linear MCP / Sentry MCP 之后，整个团队的工作流（文档 / 任务 / 错误监控）都接入到 agent，**无需 SkillForge 维护团队为每个外部服务写 Java 代码**。

### 场景 4（dogfooding）：时区 / 当前时间
用户问："明天 9 点上海时间是 UTC 几点？"

- 当前：agent 没有时间相关 tool，靠 LLM 自己计算（容易出错）
- MCP 后：内置 `mcp_time_convert_time` tool 精确算

## 痛点 vs 价值

| 痛点 | 当前做法 | MCP Client 后 |
|---|---|---|
| 接外部能力要写 Java | 改代码 + 重启服务 | dashboard CRUD 加一行 |
| 无法利用 MCP 生态 | 只能用 SkillForge 自带的 11 个 tool | 几十个社区 server 即插即用 |
| 团队工作流割裂 | agent 不知道 Notion / Jira 内容 | agent 直接读写 |
| 维护成本高 | 每个外部 service 写 + 维护 Java 代码 | server 升级跟着 npm / 第三方走 |

## 范围边界

**包括**：
- MCP Client（连外部 server）
- stdio transport（覆盖 90% MCP server 用例）
- per-agent 选启用哪些 server
- dashboard `/mcp-servers` CRUD
- env var auth（API key 通过 stdio process env 传给 server）
- `mcp_*` 前缀防冲突
- 接入 time MCP server 作为零配置 dogfooding

**不包括**（V2）：
- MCP Server（SkillForge 暴露给 Claude Desktop 反向用）
- HTTP / SSE transport
- OAuth flow（替代 env var）
- 接入 Firecrawl / Postgres / Notion / Calendar 等具体 server（数据层操作，用户自助加）
- 监控告警（MCP server 连不上的告警）
- 用户级别 MCP server（每个用户配自己的 server，目前只有全局）

## 不确定 / 后续评估

- **MCP server 重启 / 长连接稳定性**：实际 dogfooding 后看是否需要持续 keep-alive
- **多 agent 并发调同一 MCP server**：MVP best-effort 单进程共享；高并发时是否需要 process pool
- **MCP server 进程崩溃恢复**：lazy 重连，但反复崩溃不重试 backoff 策略待实测后定
