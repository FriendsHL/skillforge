# SkillForge ToDo

> 更新于：2026-05-02(Round 2 gap analysis 4 个高置信 gap 进暂缓，R1 6 个误报已删)
> 规则：这里只放当前执行状态；需求和方案细节放在链接的需求包中。
> 旧版：重整前长版 ToDo 已保留在 [references/legacy-todo-2026-04-28.md](references/legacy-todo-2026-04-28.md)。

## 当前队列

| 顺序  | ID        | 标题                                | 模式   | 状态        | 优先级 | 风险   | 文档                                                        | 下一步                                   |
| --- | --------- | --------------------------------- | ---- | --------- | --- | ---- | --------------------------------------------------------- | ------------------------------------- |
| 1   | P12-PRE   | Sprint 4 前置决策                     | Lite | ready     | P0  | Solo | [需求包](requirements/active/P12-preflight-decisions/index.md)       | 决定 Cost Dashboard、PG 备份、多用户权限边界       |
| 2   | OBS-4     | 跨 agent / 跨 session trace 串联（root_trace_id） | Full | M0/M1/M2 done | P1 | Full | [需求包](requirements/active/2026-05-03-OBS-4-root-trace-id/index.md) | M3 FE 二级折叠瀑布流（3-5 天）→ M4 观察 |
| 4   | P12       | 定时任务 MVP                          | Full | prd-ready | P1  | Full | [需求包](requirements/active/P12-scheduled-tasks/index.md)           | 等 P12-PRE 决策完成后进入设计评审                 |
| 5   | P9-4/P9-5 | Partial compact + post-compact 恢复 | Full | prd-draft | P2  | Full | [需求包](requirements/active/P9-4-P9-5-compaction-recovery/index.md) | 先决定“最近文件”数据来源                         |
| 6   | P10       | 聊天斜杠命令                            | Full | prd-ready | P2  | Full | [需求包](requirements/active/P10-slash-commands/index.md)            | 在更高优先级的 context / observability 工作后启动 |

## 阻塞 / 待决策

| ID | 待决策 | 负责人 | 阻塞项 |
| --- | --- | --- | --- |
| P12-PRE | Cost 可见性、embedded PG 备份、多用户 / 权限边界 | youren | P12 |
| P9-4/P9-5 | compact 后“最近文件”的数据来源 | youren + agent | P9-5 设计 |

## 最近完成

| ID | 完成日期 | Commit | 交付 |
| --- | --- | --- | --- |
| **OBS-4 M2** | 2026-05-03 | 待 commit；GET /api/traces/{rootId}/tree + listTraces 加 rootTraceId 字段 + TraceTreeService（3 SQL + DFS depth + cycle defence）+ 12 unit test | [交付索引](delivery-index.md)；M3 FE 二级折叠瀑布流下一步 |
| **OBS-4 M1** | 2026-05-03 | `e89c593`；V46 SET NOT NULL + 写入路径全链路（ChatService 4-arg overload / AgentLoopEngine + LoopContext 透传 / PgLlmTraceStore SQL 加列 + INV-2 immutable / SessionService 4 方法 / spawn 复制 active_root / 4 新 IT 锁 INV-1~6） | [交付索引](delivery-index.md)；M2 已交付 |
| **OBS-4 M0** | 2026-05-03 | `eda937d`；V44 `t_session.active_root_trace_id` + V45 `t_llm_trace.root_trace_id` (nullable + 回填 + 索引) | [交付索引](delivery-index.md)；M1 已交付 |
| **OBS-2 M4** | 2026-05-03 | M2 ETL 实跑（33 trace + 920 tool + 4 event 回填）+ M4 关旧轨写入 `b31cfaf` | [交付索引](delivery-index.md)；M5/M6 待跟进 |
| **OBS-2 Trace 数据模型统一（M0-M3）** | 2026-05-02 | `cf9123c` + `06b2372` + `edd5a21`(M0) + `4412729`(M1) + `26023c5`(M2) + `69ee35b`(M3) | [交付索引](delivery-index.md)；M3.5/M4/M5/M6 待跟进，PRD 留 active |
| **xiaomi-mimo provider** | 2026-05-02 | `08a6d4d` + `afa8cf4` + `f6be1ab` | [交付索引](delivery-index.md) |
| SKILL-IMPORT-BATCH | 2026-05-01 | `478e135` | [交付索引](delivery-index.md) |
| SKILL-IMPORT | 2026-05-01 | `a09a17a` + `4490e14` | [交付索引](delivery-index.md) |
| MSG-1 + BUG-30 + BUG-31 | 2026-04-30 | `543a60e` | [交付索引](delivery-index.md) |
| P9-2 + BUG-32 | 2026-04-30 | `fe0404c` | [交付索引](delivery-index.md) |
| SKILL-LOAD | 2026-04-30 | `8d1fb45`(merged via `d74e01c`) | [交付索引](delivery-index.md) |
| P1-D | 2026-04-30 | `6b19439` + `223e5a8`(V33→V38 fix) | [交付索引](delivery-index.md) |
| CTX-1 | 2026-04-30 | `1de1fe1` | [交付索引](delivery-index.md) |
| OBS-1 | 2026-04-29 | 见交付索引 | [交付索引](delivery-index.md) |
| P1-C | 2026-04-28 | `555bdba` | [交付索引](delivery-index.md) |
| Memory v2 | 2026-04-27 | `9f36b59`, `8330d32`, `86703ed`, `96676b9` | [交付索引](delivery-index.md) |
| BUG-F | 2026-04-26 | `e9b48f3` | [交付索引](delivery-index.md) |
| SEC-2 | 2026-04-25 | 见交付索引 | [交付索引](delivery-index.md) |
| thinking-mode-v1 | 2026-04-25 | `55969db` | [交付索引](delivery-index.md) |

## 暂缓

| ID              | 原因                                                      | 重评触发条件                                                | 文档                                                                      |
| --------------- | ------------------------------------------------------- | ----------------------------------------------------- | ----------------------------------------------------------------------- |
| BUG-G           | 根因已修，剩余 sanitizer / 尾部不变量属于防御性补强                        | 再次出现 dangling assistant `tool_use` / 缺失 `tool_result` | [需求包](requirements/deferred/BUG-G-defensive-hardening/index.md)         |
| SEC-1           | 当前本地 / 单用户使用下重要但不紧急                                     | 多端部署、共享环境或 P12 正式上线                                   | [需求包](requirements/backlog/SEC-1-channel-config-encryption/index.md)    |
| P14             | 暂无真实多轮 benchmark 需求                                     | 出现 tau-bench 类真实业务评测需求                                | deferred package TBD                                                    |
| P3-2/P3-4       | 依赖 P14 eval 基础设施                                        | P14 基础设施就绪                                            | deferred package TBD                                                    |
| P15-3/P15-6     | Analyzer MVP 暂不需要 eval run 读取或分析 tab 落库                 | Analyzer MVP 证明有持续价值                                  | deferred package TBD                                                    |
| P12-3/P12-6     | 超出 P12 MVP 范围                                           | 需要 system job UI 或高级可靠性                               | [P12](requirements/active/P12-scheduled-tasks/index.md)                 |
| P10-4/P10-5     | 四条命令 MVP 已覆盖主要场景                                        | 出现明确自定义命令或 help menu 需求                               | [P10](requirements/active/P10-slash-commands/index.md)                  |
| OBS-1-4/OBS-1-5 | OBS-1 MVP 上线后再看 compact 验证视角 + provider quirk 自动诊断的真实使用 | 看到真实 raw payload 后用户提出明确需求                            | [OBS-1 PRD](requirements/archive/2026-04-29-OBS-1-session-trace/prd.md) |
| SKILL-UNINSTALL | dashboard delete 按钮已能用但不清 `t_agent.skill_ids`（silent dangling）；agent 没对应卸载 Tool；本期 SKILL-IMPORT 后浮现的对称缺口 | agent 主动需要卸载 skill / 用户报告删 skill 后 skill_ids 残留 stale 名字 | 主旨：UninstallSkill Tool（dry-run + confirm 双调用）+ SkillService.deleteSkill 内部加 unbind 修复（dashboard 同步受益）+ 全局扫所有 t_agent 解绑 + system 禁卸 + 不调 `npx clawhub uninstall`。Mid 档，需求包待真做时建 |
| WAITING-SUBAGENT-UX | SubAgent / TeamCreate 异步派发后，主 agent loop 结束 → `runtime_status=idle`，但 sub-agent 还在 running。UI 没有"等待 N 个 sub-agent"指示，用户误以为卡住会重发消息（session `2ad5da4d` 现场） | 多次用户反馈"主 agent 不动了"补消息后才发现 sub-agent 还在跑 / collab 页面信息不够 | 主旨：session 级 `pending_subagent_count` 字段（从活跃 collab_run 计算，per-turn refresh）+ chat header 显示"等待 N 个 sub-agent" badge + hover 展开看 handle / status / duration。语义类似 MSG-1 waiting_user 但对应 waiting_subagent。Mid 档，需求包待真做时建 |
| **GAP-PROMPT-CACHE** | `ClaudeProvider.buildRequestBody` (line 384-419) `root.put("system", request.getSystemPrompt())` 把 system prompt 当**裸字符串**赋值，不是 `[{type:"text", text:..., cache_control:{type:"ephemeral"}}]` 结构化数组；OBS-1 的 `cacheReadTokens` / `cacheCreationTokens` 字段是 stub（`LlmSpanDetailDto.java:25` 显示用但**无 provider 写入路径**）。每次 LLM 调用 input 全量计费，5-90% input token 浪费 | 用户 token 账单意外高 / SubAgent 高频跑成本变明显 | 主旨：改 `ClaudeProvider.buildRequestBody` 把 system + tools 改成 structured array 标 `cache_control: ephemeral` + `LlmResponse.Usage` 解析 `cache_creation_input_tokens` / `cache_read_input_tokens` 写到 LLM_CALL trace + OBS-1 dashboard 加 cache hit rate 列。**P0 Mid**（来源：Anthropic Prompt Caching API + Claude Code 02 工具系统 §6 line 110），见 `/tmp/skillforge-gap-analysis-r2.md` Gap 1（含 grep evidence 3 路 + ClaudeProvider:384 文件读 + delivery-index 检索 0 命中）|
| **GAP-PRETOOL-HOOK-PERMISSION** | `HookEvent.java:14-18` 枚举只 5 个事件（SESSION_START / USER_PROMPT_SUBMIT / **POST_TOOL_USE** / STOP / SESSION_END），**无 PRE_TOOL_USE**；`Tool.java:13-41` 接口仅有 `isReadOnly()`，**无 `checkPermissions()`**；`AgentLoopEngine.java:1053` 的 `isInstallRequiringConfirmation` 是**硬编码** `npx clawhub install / npm install` 几个特定命令不可配置；无 alwaysAllow / alwaysDeny / alwaysAsk 三级规则；危险命令（Bash `rm -rf` / FileEdit `/etc/**`）目前直接执行无 backstop。**可吸收 BUG-27** | 接 channel gateway 后陌生用户场景 / BUG-27 类反馈累积 / 真实 prod agent 误执行破坏命令 | 主旨：`HookEvent` 加 `PRE_TOOL_USE` 事件 + `Tool` 接口加 `checkPermissions(input, ctx) → allow/ask/deny` 默认 ALLOW 向后兼容 + 三级规则来源（session/agent/global）含 pattern 匹配 `Bash(git *)` / `FileEdit(/etc/**)` + DENY 时 stderr 拼给 LLM 重 plan + ASK 走 MSG-1 inline confirmation card 复用。**P1 Full**（核心 AgentLoopEngine + Tool 接口 + 规则 schema + dispatcher，触碰核心文件清单）（来源：Claude Code 02 §7 三层权限 + 07 Hooks PreToolUse + Hermes 02 approval callback），见 `/tmp/skillforge-gap-analysis-r2.md` Gap 2 |
| **GAP-MCP** | grep "MCP / mcp / model.context" 在 codebase / `application.yml` / `CLAUDE.md` / `delivery-index.md` / 暂缓表全 0 命中；`skillforge-tools/` 11 个 Tool 全 Java 内置（FileRead/FileEdit/FileWrite/Bash/Browser/CodeReview/CodeSandbox/Glob/Grep/WebFetch/WebSearch）无 MCP 桥接；用户想接 GitHub MCP / Slack MCP / Notion MCP / 内部 service catalog MCP 只能写 Java 编译进 `skillforge-tools` jar 重启服务 | 用户开始拼图把 SkillForge 接到 GitHub / Notion / Linear / 内部 service / 想让 Claude Desktop / Cursor 反向调 SkillForge 当 MCP server | 主旨：① **MCP Client** (更紧迫)：MCP stdio/SSE/HTTP transport 适配层 + `McpProperties` config + `McpToolRegistry` 注册到 SkillRegistry + `mcp_*` tool prefix 命名规则 + OAuth/auth；② **MCP Server**：把 SkillForge 暴露给 VS Code / Claude Desktop。**P1 Full**（新 module + 跨核心 SkillRegistry / Tool 接口 + 外部协议）（来源：Hermes 08 ACP-MCP + Claude Code 02 工具系统 §6 MCP 集成），见 `/tmp/skillforge-gap-analysis-r2.md` Gap 3 |
| **GAP-FILEEDIT-MULTIMODAL** | `FileEditTool.java:64-120` `execute()` 不验"本 session 在该 file_path 上 Read 过且 mtime 未变"，agent 跨 turn edit 陈旧文件不报错（external 修改被 silent 覆盖）；`FileReadTool.java:1-60` 不识别 image / PDF / Jupyter notebook（无 magic bytes 分支）；**`OpenAiProvider.java:537` 注释承认 "image / tool_use blocks ... silently dropped"**，即便 `mimo-v2-omni` / Claude Vision 等多模态模型也用不上 | 用户报 stale edit 覆盖外部修改难调 / 想上传截图 / PDF 给 agent 看图 / 用了 omni 模型但 image 不通 | **拆 2 个 sub-gap**：(4a) **Read-before-Edit precondition** (P2 Mid)：`SkillContext` 加 `readFileState` LRU + FileEdit 验 "this session 读过该 path + mtime 未变"；(4b) **Multimodal Read** (**P1 Mid**)：FileReadTool 加 image / PDF / notebook 分支 (扩展名 + magic bytes 判断 + image resize 到 token 预算 + PDF 按页 extract) + 修 `OpenAiProvider:537` 让 image blocks 真送给 vision-capable provider（含 mimo-v2-omni / Claude / GPT-4o）（来源：Claude Code 02 工具系统 §FileReadTool readFileState + 多模态分支），见 `/tmp/skillforge-gap-analysis-r2.md` Gap 4 |
| **OBS-2-ADMIN-RUN-ETL** | OBS-2 M2 / OBS-1 R__migrate_legacy_llm_call 等 ETL 当前都通过 `application.yml etl.<x>.mode: off \| flyway` placeholder + Flyway hash 切换 → **必须重启 server 才能跑**。重启是重操作（影响 chat 在线用户 + WS 连接断开 + 状态机要恢复）。运维流程"切 mode → 重启 → 切回 → 再重启"= 4 次重启 | 实际跑 OBS-2 M2 / 后续任何 ETL 类回填时（频率 = 数月一次但每次都重）/ 其他 schema 演进引入更多 Repeatable migration | 主旨：admin endpoint `POST /api/admin/observability/run-etl` body `{etl: 'legacy_llm_call' \| 'legacy_trace_span', dryRun: boolean}` → 内部直接 execute 对应 R__ SQL（绕过 Flyway hash + placeholder 切换） + ownership/role guard（只允许 admin user）+ dryRun 走 transaction-rollback 看影响行数 + 真跑返回 INSERT/UPDATE 行数。后续也可加其他维护性 SQL（dual-write 一致性核对 / orphan 清理 / blob TTL 等）。**P2 Mid**（不触碰核心文件，纯新 controller + service + auth），需求包待真做时建 |
| ~~**OBS-3 跨 session unified trace tree**~~ | **superseded by [OBS-4 root_trace_id](requirements/active/2026-05-03-OBS-4-root-trace-id/)（2026-05-03）**。OBS-3 v1（UI 层 nested rendering + 启发式 DFS 拼装）2026-05-03 上午尝试后回滚（commit `2d1a7bf`）。教训：数据模型不动 UI 层硬拼凑无法表达"一次完整调研"语义，nested 全展开把 child spans 淹没父主线。OBS-4 用 schema 一等公民字段 `root_trace_id` 把跨 agent / 跨 session trace 串联起来，UI 改成二级折叠 inline group（默认折叠），完整解决该需求 | — | — |
| **TOOL-CC-NAME-ALIAS** | session `2fb749a5-5f9f-471d-a277-f042bbd4b465` 18:47:26 现场：mimo-v2.5-pro 调 `Read` 工具被 dispatcher 拒绝（`[NOT ALLOWED] skill 'Read' is not available for this agent. Stop calling it; choose from the available skill list.`），下一轮 LLM call 改成 `FileRead` 自我纠正。我们 11 个 Tool 用 `File*` 前缀（`FileRead` / `FileWrite` / `FileEdit`，见 `skillforge-tools/src/main/java/com/skillforge/tools/*Tool.getName()`）；非 Anthropic 模型（mimo / DeepSeek / 其他 OSS）训练里见多 Claude Code 风格短名 `Read` / `Write` / `Edit`，hallucinate 高发，每次浪费 ~2-5s + 一轮 LLM token | 长 session 中此类 retry 累积明显 / Cost dashboard 看到非必要 retry tokens / 用户主动反馈 | 主旨：ToolRegistry / dispatcher 加 alias 静默重定向：`Read` → `FileRead`、`Write` → `FileWrite`、`Edit` → `FileEdit`。只 3 个高频别名，不做通用扩展点（避免膨胀 + 命名歧义）。Solo / Light 档，改 ToolRegistry 入口 + 1-2 个单测，不触碰 AgentLoopEngine 核心 |

## 阅读规则

实现任务时，先打开链接的需求包。实现前读 `prd.md` 和 `tech-design.md`；只有原始产品意图或限制不清楚时才读 `mrd.md`。
