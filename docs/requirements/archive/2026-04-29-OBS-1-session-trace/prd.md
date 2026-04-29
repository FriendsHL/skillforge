# OBS-1 PRD

---
id: OBS-1
status: prd-ready
owner: youren
priority: P1
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-28
updated: 2026-04-29
---

## 摘要

创建一个 session 排查体验，把 session messages、execution trace spans 和完整 LLM payload 访问能力合并在一起。MVP 目标是让用户不用依赖 Analyzer Agent、也不用在 Session / Trace 两页之间跳转，就能直接完成 raw payload 排查。

## 背景

当前排查链路过度依赖 Analyzer Agent，但 BUG-F、BUG-G、compact、跨 provider 切换这类问题，最终都需要直接查看完整 LLM request body 才能定性。现有 Trace 页会裁剪 input / output，Session 页和 Trace 页又是分离的，排查时需要人工来回拼接。

OBS-1 要解决的是人的可观测性，不替代 P15 面向 Agent 的裁剪 trace 工具。

## 目标

- 在一个视图中展示 session 消息和关联 trace spans。
- 点开任意 LLM / Tool span 能查看未截断的 request / response。
- 在截断摘要之外，持久化完整 LLM request / response payload。
- UI 支持默认看摘要、按需加载完整 payload。
- 保留面向 Agent 的裁剪 trace 路径，避免浪费 token。

## MVP 范围

首版只做 OBS-1-1、OBS-1-2、OBS-1-3。OBS-1-4 和 OBS-1-5 推迟到首版上线、看到真实 payload 和用户使用方式后再做。

| 子任务 | 说明 | MVP |
| --- | --- | --- |
| OBS-1-1 Session x Trace 统一视图 | 改造现有 Sessions 页：点 session 进入合并视图，左侧消息时间线展示 user / assistant / tool_use / tool_result，每条消息可展开下挂 LLM call / Tool exec span，并按时间对齐；保留现有 Traces 页作为 trace 维度入口。 | 是 |
| OBS-1-2 LLM span 完整 payload 落库 | 新建 `t_llm_trace` 和 `t_llm_span`，不在原 `t_trace_span` 上扩；DB row 永远只存 32KB 摘要 + `*_blob_ref`；流式调用同时保留 raw SSE 流和累加后 JSON；写入和查询走 `LlmTraceStore`。 | 是 |
| OBS-1-3 UI Payload Viewer | Span 详情面板新增“Raw Request / Raw Response”tab；默认显示 32KB 摘要，点击“看完整内容”才拉 blob；支持 JSON 折叠、关键词搜索、一键复制、size badge；大 payload 需要避免卡顿。 | 是 |
| OBS-1-4 压缩验证视角 | 对 compact 类 LLM call 标记特殊徽章，可对比压缩前消息列表和压缩后实际发给模型的 messages。 | V2 |
| OBS-1-5 Provider quirk 诊断信号 | 对 reasoning_content 缺失、tool_call_id 重复、dangling tool_use 等已知问题做 UI 自动检测和文档锚点。 | V2 |

## 用户流程

- 用户打开 Sessions 页，点入一个 session。
- 用户看到会话级时间线，以及每条消息关联的 LLM / Tool span。
- 用户打开一个 LLM span，看到“原始请求 / 原始响应”tab。
- 默认只看 32KB 摘要；需要时点击加载完整 payload。
- 历史 legacy span 如果没有 raw payload，UI 禁用完整加载并解释原因。
- 遇到 SubAgent 调用时，父 session 里显示跳转入口，点击进入子 session；嵌套树形展示放到 V2。

## 功能需求

- 新增或改造 Session Detail 视图。
- 保留 Traces 页作为 trace 维度入口。
- 独立持久化 LLM trace row 和 LLM span row，不直接扩旧 `t_trace_span`。
- DB row 只存有界摘要，raw payload 存文件 blob。
- 支持 live payload 和 legacy LLM_CALL 摘要行，并用 `source` 标记。
- 持久化前剥离敏感 HTTP headers。
- 配置 payload blob 和 DB row 的保留期与清理策略。
- 旧 `t_trace_span` 的 LLM_CALL 行先保留 3-6 个月作为回滚安全网。

## 决策清单

| # | 决策点 | 选择 | 状态 |
| --- | --- | --- | --- |
| Q1 | 流式 SSE 落库形态 | 累加后 JSON 落 DB 摘要路径，raw SSE 落 blob，方便排查 chunk 边界问题。 | 已定 |
| Q2 | blob 触发阈值 | 都落 blob：DB 永远只存 32KB 摘要 + blob_ref，UI 体验一致，DB row 体积可预测。 | 已定 |
| Q3 | 旧 `t_trace_span` 处理 | 一次性 ETL + `source` 字段标记，消除 fallback 路径。 | 已定 |
| Q4 | trace_id 体系 | 复用现有 trace_id，保持全链路约定。 | 已定 |
| Q5 | UI 落位 | 改造现有 Sessions 页；Traces 页保留。 | 已定 |
| Q6 | 保留期 + 清理 | 30 天 + cron 每日清理过期 blob 和 DB row，清理失败 warn 不抛。 | 已定 |
| Q7 | 脱敏 | 仅 HTTP headers 层剥离；request body 不主动扫。 | 已定 |
| Q8 | SubAgent 展示 | 父 session span 显示“跳转到子 session”，嵌套树 V2。 | 已定 |
| 其他 | 物理隔离 | 独立 maven module `skillforge-observability`。 | 已定 |
| 其他 | LlmProvider 周边 | Observer 模式，不用 AOP。 | 已定 |
| 其他 | PG backup 是否带 payload | 不带；payload 文件丢失只影响 raw 查看，DB 摘要仍可用。 | 已定 |
| 其他 | payload 目录结构 | `data/llm-payloads/{yyyy-MM-dd}/{traceId}/{spanId}-{request|response}.json`。 | 已定 |

## 非目标

- MVP 不做跨 session 全文搜索。
- MVP 不做流量重放。
- MVP 不做 provider quirk 自动诊断。
- MVP 不引入 ClickHouse。
- MVP 不把 LLM call 录制成 eval scenario。
- MVP 不物理删除旧 `t_trace_span` LLM_CALL 行。

## 验收标准

- [x] Session detail 能展示消息时间线和关联 LLM / Tool span。 _(SessionDetail.tsx + SessionTimelinePanel, 用户实测 2026-04-29)_
- [x] LLM span 不加载完整 blob 时也能显示 request / response 摘要。 _(LlmSpanDetail.inputSummary / outputSummary 32KB 摘要直渲)_
- [x] live span 可以按需加载完整 request / response payload。 _(PayloadViewer 按钮触发 GET /api/observability/spans/{id}/blob)_
- [x] legacy span 禁用完整 payload 加载，并清楚说明原因。 _(blob_status='legacy' 时 PayloadViewer 按钮 disabled + 文案"历史 session 无 raw payload")_
- [x] P15 GetTraceTool 仍保持裁剪、token-safe。 _(未触碰 GetTraceTool 实现路径，用 OBS-1 旁路通道)_
- [x] trace 持久化失败不会影响主 LLM 调用。 _(observability/server 双层测试覆盖：LlmTraceStoreFailDoesNotBlockChatTest store throw → handler.onComplete 收完整响应 + inFlightCount=0)_
- [x] SubAgent span 能跳转到子 session。 _(SubagentJumpLink 在 ToolSpanDetailView 渲染，SubagentSessionResolver 双源解析 output text + parent_session_id 反查)_
- [x] HTTP header 中的敏感字段不会落库。 _(HeaderSanitizer DROP_HEADERS 覆盖 authorization / x-api-key / Bearer 等 9 个 header；header redact)_

## 依赖

- 现有 trace/span 持久化。
- 现有 session message row 存储。
- 现有 LLM provider 调用链路。
- 涉及持久化、UI workflow、provider observer 行为，必须走 Full Pipeline。

## 验证预期

- 后端：live / legacy span 存取测试、blob 持久化测试、observer 失败隔离测试。
- 前端：session detail 渲染、payload viewer、legacy disabled state。
- 浏览器：在真实运行的 dashboard 中检查一个 session 的消息时间线和 payload tabs。
- 数据库：migration 校验、旧 `t_trace_span` LLM_CALL 行数统计、ETL 抽样 spot check。

## 开工前置

- [x] 确认 Q1 / Q2 / Q4 / Q5 / Q6 / Q7 / Q8 草拟决策。 _(2026-04-29 全部接受 default)_
- [x] 执行 `SELECT count(*) FROM t_trace_span WHERE span_type='LLM_CALL'` 验证数据量。 _(实施时实际数据量 ~104 sessions，远低于 50 万阈值，直接走 mode=flyway 一次性迁移；之后 mode 切回 off)_
- [ ] Full Pipeline 进入 plan 对抗循环；OBS-1 是新基础设施，schema 和接口设计必须 reviewer 把关。
