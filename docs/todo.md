# SkillForge 待办任务

> 更新于：2026-04-27
> **对抗整理**：Analyst + Challenger 双 Agent 评审 + Claude 仲裁（2026-04-23）
> **代码扫描校准**：全量代码现状核查后二次修正（2026-04-23）
> **🔥 紧急条目新增**：ENG-1 / ENG-2 / P9-5-lite，由 session `9347f84c` 真实事故触发（2026-04-23）
> **SEC-2 范围调整**：V1 不收窄，纳入 agent-authored hook 查询 / 提交绑定闭环（2026-04-25）
> **🔥 紧急条目完成**：BUG-F Compact 摘要存储重构 ✅ 已完成（2026-04-26，commit `e9b48f3`）
> **交付索引**：已完成事项、技术方案、完成日期、commit / migration 统一维护在 [delivery-index.md](delivery-index.md)。完成任务时必须同步更新该索引。
> P 编号保留历史，**不代表当前优先级**；以 Sprint 顺序为准。

---

## 待排期

### 📋 执行顺序总览

| Sprint        | 内容                                                                                      | 预估                | 核心判断                                                                                                                                                                                                        |
| ------------- | --------------------------------------------------------------------------------------- | ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ~~**🔥 紧急**~~ | ~~ENG-1~~ · ~~ENG-2~~ · ~~P9-5-lite~~                                                   | 1-2 天             | 由 session 9347f84c 真实事故触发；阻断 Design Agent 长任务；**全部完成 2026-04-23**                                                                                                                                           |
| ~~**🔥 紧急**~~ | ~~**BUG-F** Compact 摘要存储重构（向 Claude Code / OpenClaw 看齐）~~ ✅ 2026-04-26 commit `e9b48f3` | 1-1.5 天           | 由 session `acbced3f` DeepSeek 撞 `Duplicate value for 'tool_call_id'` HTTP 400 触发；**Full Pipeline 通过**：Plan r1 PASS（1W）+ Code r1 PASS（5W，0 blocker）；370 unit tests 全绿；用户授权跳过 live curl，server 重启成功即视为 e2e 通过 |
| **🔥 穿插**     | **BUG-G** dangling assistant tool_call 历史防腐 + stopReason/toolUse 一致性修复                  | 1-2 天             | `d0863201` / `ff457c2d` 触发；BUG-G-2 已本地修复：qwen 流式空 id/name 不覆盖首包有效 tool_call 身份 + toolUseBlocks 结构优先；BUG-G-1 发送前历史防腐、BUG-G-3 失败路径尾部不变量仍待补 |
| ~~**Sprint 1**~~  | ~~P9-7~~ · ~~P3-1~~ · ~~P3-3~~ · ~~P13-3~~ · ~~P13-4~~ ✅ 2026-04-26                 | 2-3 天             | 零依赖防腐；P13-4 代码扫描确认已完成；P9-7 已完成 commit `621f417`；P3-1/P3-3/P13-3 已完成 commit `f4773c3`，397 个非 IT server tests 全绿；完整 suite 仅 Docker/Testcontainers IT 受本机环境阻塞                                                                 |
| ~~**Sprint 2**~~  | ~~P11（收窄）+ P13-1~~ → ~~P15-1/P15-2/P15-4 + UpdateAgent~~                                  | 8-12 天            | PR1 已完成 2026-04-26：AgentDiscovery + name resolver + public/private visibility + custom rule severity；PR2 已完成 P15-1/P15-2：GetTrace + GetSessionMessages，413 个非 IT server tests 全绿；补充完成 GetAgentConfig + AgentDiscovery 增强 + 带一次确认的 UpdateAgent。P15-5 seed 取消：Analyzer Agent 已手工创建，不再内置 Flyway seed |
| **🧩 P1 重构**  | P1 Skill 生命周期统一（方案 C，Skill Control Plane）                                          | 5-8 天             | 2026-04-27 决策切方案 C：Artifact 统一模型 + SessionSkillView + system skill toggle + 启动恢复 + telemetry 自动采集 + Skill 页去硬编码 + Draft 去重；八子任务一体闭环 |
| **🔍 OBS-1**   | Session × Trace 合并视图 + LLM call 完整 payload 落库 + UI Raw Viewer + 压缩验证视角            | 待估期             | 用户需直接看 LLM 完整 request body 来判断压缩是否合理；当前排查过度依赖 Analyzer Agent；与 P15 Tool 层裁剪互补                                                                                                                       |
| **Sprint 3**  | P9-2 长对话 tool 归档（独立 PR）                                                                 | ~2 周              | 触碰核心文件，Full Pipeline；真实用户长 session 慢性病                                                                                                                                                                      |
| ~~**🧠 Memory v2**~~ ✅ 2026-04-27 | ~~MEM-1 ~ MEM-5 写入 / 召回 / 淘汰一体化重构~~                                                       | ~~13-18 天~~           | PR-1 `V29 schema + snapshot/rollback 基线`（`9f36b59`）→ PR-2 `ACTIVE 过滤 + L0/L1 task-aware recall + MemorySearch 排重`（`8330d32`）→ PR-3 `增量抽取 cursor / idle scanner / cooldown`（`86703ed`）→ PR-4 `embedding add-time dedup`（`96676b9`）→ PR-5 `ACTIVE/STALE/ARCHIVED 状态机、容量淘汰、batch status API、/memories tabs + batch actions + restore + capacity banner`（本地已验证，待后续提交）；聚焦验证：`MemoryConsolidatorTest` + `MemoryServiceTest` 24/24 通过，dashboard `npm run build` 通过 |
| **⚠️ 前置决策**   | Cost Dashboard · PG 备份 · 多用户权限 design doc                                               | 决策先行              | Sprint 4 开工前必须有答案，否则 P12 上线即踩坑                                                                                                                                                                              |
| **Sprint 4**  | P12 定时任务（收窄首版）                                                                          | 3-4 周             | user 型调度最小集；SystemJobRegistry + P12-6 → V2                                                                                                                                                                  |
| **Sprint 5**  | P9-4 · P9-5（需 design doc 先行）                                                            | 按需                | P9-5 依赖 P9-4；P9-5 需先明确"最近文件"数据来源                                                                                                                                                                            |
| **Sprint 6**  | P10 斜杠命令（收窄 4 条）                                                                        | 5-8 天             | 从 Sprint 1 降级；/compact 只做 full；/model 只改 session 级                                                                                                                                                          |
| ~~**🔥 穿插**~~ | ~~Compact Breaker 误触 + LLM Stream 抗抖（BUG-A/B/C/D/E/E-bis）~~                             | ~~Full Pipeline~~ | ✅ 2026-04-24 完成（commit `121e8dc`）；Full Pipeline 运行 + 额外捕获 `FullCompactStrategy.callLlm` 吞异常的更深 root cause                                                                                                   |
| ~~**🧠 穿插**~~ | ~~thinking-mode-v1（per-agent thinkingMode + reasoningEffort + provider 协议感知 + DX）~~     | ~~Full Pipeline~~ | ✅ 2026-04-25 完成（commit `55969db`）；Plan r1 PASS + Review r1 catch B1 测试造假 → r2 PASS + Phase 4 hotfix 跳过未配置 provider                                                                                          |
| **🔒 穿插**     | SEC-1 Channel 配置 AES-GCM 加密                                                             | 1-2 天             | 重要不紧急；当前单机/本地使用暂缓，等多端部署或 P12 正式上线前重评                                                                                                                                                                                      |
| ~~**🔒 穿插**~~ | ~~SEC-2 Hook Source Protection + Agent-authored Hook Binding V1~~                       | ~~Full Pipeline~~ | ✅ 2026-04-25 完成：system / user / agent-authored 三源隔离；Hook 查询 Tool + Agent 提交绑定 Tool；审批后 dispatch；补齐 builtin allowlist 与 reviewer 身份不信任约束                                                                     |
| **🧹 穿插**     | DEBT-1 SkillList.tsx 拆分（47K 单文件）                                                        | 3-5 天             | 低优先级，下次动 SkillList 前先拆                                                                                                                                                                                      |
| **V2**        | P14 · P3-2/4 · P15-3/6 · P12-3/6 · P10-4/5 · P13-9                                      | 推迟                | 见底部 V2 推迟池                                                                                                                                                                                                  |

> **工期修正说明**：Analyst 对 P12 给出"1-2 周"，Challenger 实测拆解后为 3-4 周（时区/夏令时/concurrencyPolicy/前端 cron 编辑器是主要坑）。P10 "1-2 天"实测 5-8 天（`/compact` 触碰 CompactionService 核心文件 + Full Pipeline）。

---

### 🔥 紧急 — Agent Loop 引擎修复（Sprint 1 前置，1-2 天）

> **触发事件**：session `9347f84c`（Design Agent 改造 dashboard）在 `4 light · 2 full · -77.0K tok` 之后，准备重写 802 行的 `FormMode.tsx` 时，连续 3 次 tool_use 的 input 退化为 `{}`，全部返回 `"file_path is required"`，最后 LLM 返回空字符串静默退出。
>
> **链路**：full compaction 丢失 pending FileWrite 内容 → LLM 生成空参数 → 3 次 validation 错误被 `detectWaste` 当 consecutive error → 又触发 B1 compaction → context 二次坍塌 → idle 退出。
>
> **状态**：Sprint 1 前置，全部走 Full Pipeline（触碰 `AgentLoopEngine.java` / `CompactionService.java` 两个核心文件）。

| 子任务 | 说明 | 关联 |
| --- | --- | --- |
| ~~**ENG-1**~~ `SkillResult.errorType` + `detectWaste` 区分 validation/execution ✅ 2026-04-23 | `SkillResult.ErrorType` enum (VALIDATION/EXECUTION) + `validationError(msg)` 工厂；`ContentBlock.errorType` 字段 + `Message.toolResult` 重载；`AgentLoopEngine` 工具调用结果传播 errorType + `IllegalArgumentException` 单独 catch 标 VALIDATION；`detectWaste` 重写：VALIDATION 在所有规则中视为中性（不增计数 / 不重置已积累 execution 计数 / rule 3 identical-tool-use 也跳过 VALIDATION 调用）；FileEditSkill / FileWriteSkill 的 required 字段缺失改用 `validationError()`。新增 `AgentLoopEngineWasteDetectTest`（5 用例含 9347f84c 回归）。293 测试 0 failure | 治本 bugs#7（当时治标抬阈值 B1 0.40→0.60） |
| ~~**ENG-2**~~ `AgentLoopEngine.executeToolCall` 前置 required-param 校验 + retry hint ✅ 2026-04-23 | `findMissingRequiredFields(tool, input)` 从 `ToolSchema.inputSchema.required` 提取必填字段，校验 input 中 key 存在 + 非 null（空字符串/blank 留给 skill 自身判断）。`executeToolCall` 在 SkillHook.beforeSkillExecute 之后、`tool.execute` 之前插入校验，缺字段时直接返回结构化 hint：`"[RETRY NEEDED] FileWrite missing required argument(s): file_path, content. Re-emit the tool call with all required fields populated."`，标 VALIDATION（与 ENG-1 协同：不触发 detectWaste compaction）。新增 `AgentLoopEnginePreValidationTest`（6 用例：全填 / 9347f84c 空 input / null 值 / 空字符串放行 / schema 无 required / 防御 null）。299 测试 0 failure | 新条目 |
| ~~**P9-5-lite**~~ Compaction prompt 保留 pending FileWrite/FileEdit input ✅ 2026-04-23 | `FullCompactStrategy.SUMMARY_SYSTEM_PROMPT` 在 "MUST preserve" 列表新增一条约束：任何 emit 但未拿到 tool_result 的 pending tool_use，必须 verbatim 保留 tool name + 完整 input arguments，不允许 summarize 任何 FileWrite / FileEdit / Bash 的字符串内容。43 个 compact + engine 测试全绿。**P9-5 完整版（5 文件摘要 + 活跃 skill 上下文 + 完整 pending tasks）保持 V2 排期不变** | 切割自 P9-5（Sprint 5 / V2） |

> **测试要补**：除单元测试外，需用真实 session 复现：构造一个 LLM 准备写 800 行文件的场景，强行触发 full compaction，验证 LLM 恢复后能拿到完整 file content 而不是空 input。

---

### ~~🔥 紧急 — BUG-F Compact 摘要存储重构~~ ✅ 已完成（2026-04-26，commit `e9b48f3`）

> **触发事件**：session `acbced3f` 通过飞书 channel 持续聊天，某轮 full compaction 之后所有后续消息（包括手机端发送）都返回 HTTP 400 ：
> ```
> deepseek API error: HTTP 400 - {"error":{"message":"Duplicate value for 'tool_call_id' of in message[4]","type":"invalid_request_error",...}}
> ```
> 飞书移动端无 UI 路径新建 / 切换 session，用户被完全卡死。
>
> **Root cause（两个 bug 叠加，与 reasoning_content 无关）**：
> 1. `FullCompactStrategy.mergeSummaryIntoUser`（`FullCompactStrategy.java:217-228`）：当 young-gen 第一条 user 消息是 tool_result 形态（`List<ContentBlock>` 含 tool_result）时，把 summary text block 塞进同一条 user message 最前，构造出 **mixed user message** = `[text(summary), tool_result(A), ...]`。设计本意是"防 Anthropic/Gemini 两条连续 user invalid payload"，但**没区分"真正的 user 消息"和"内部用 user 装的工具结果"**。
> 2. `OpenAiProvider.convertMessages`（`OpenAiProvider.java:362-374`）：检测到 user message 含 tool_result block 时进入特殊分支，**遍历所有 block 不过滤类型**全部当 `role:tool` 翻译；text block 没有 `toolUseId` → `tool_call_id=null`/`"null"` 字面量；多个非 tool_result block 同一 message 或多条这种 message 累积 → DeepSeek 看到重复 `tool_call_id` → 400。
>
> **链路**：full compact 在 tool_result 形态 boundary 之后切割 → mergeSummaryIntoUser 制造 mixed message 写进 `t_session_message` → reload 时永久重现 → OpenAiProvider 翻译时 text block 被当作空 `tool_call_id` 的 `role:tool` 输出 → DeepSeek 400 → session 无法恢复（重启 / 删字段 / `/reset` 状态机都救不了，DB 里那条坏 message 永远在）。**任何走 OpenAI 兼容协议的 provider 都会撞**（DeepSeek / vLLM / Ollama / 阿里云 / 自建网关），Claude 协议保留 user message 整体不展开 role:tool 所以不撞。
>
> **方案选型对比（2026-04-26 讨论结论）**：
> - ❌ 方案 A：翻译层 skip 非 tool_result block —— 摘要丢失，模型失忆被 compact 掉的对话
> - ❌ 方案 B：摘要拼到第一个 tool_result content 前缀 —— 治标，摘要变工具回复，结构污染
> - ❌ 方案 C：摘要进 system prompt suffix —— prompt cache 全失效（system prompt 是大多数 provider cache anchor），且语义边界混淆
> - ✅ **方案 D（向 Claude Code / OpenClaw 看齐）**：摘要作为**单独 user message** 插入 messages 列表前部，不再 merge 到现有消息。Claude Code `buildPostCompactMessages()` 顺序 = `boundaryMarker → summaryMessages(user) → messagesToKeep → ...`；OpenClaw `buildSessionContext()` 同结构。证据：现代 Claude / OpenAI / DeepSeek 协议接受连续 user message（OpenClaw `firstKeptEntryId` 可指向 tool_result，业界生产证据），SkillForge 当前注释"防 Anthropic/Gemini invalid payload"是**过度防御**。
>
> **状态**：Sprint 1 前置，**必须 Full Pipeline**（触碰 `FullCompactStrategy.java` / `OpenAiProvider.java` / `SessionService.java` 三个核心文件清单成员；TeamCreate → Plan 对抗循环 → BE Dev → 双 Reviewer 对抗循环 → Judge → Phase 4 verify & commit）。
>
> **完成说明（2026-04-26，commit `e9b48f3`）**：
> - **方案落地**：删除 `FullCompactStrategy` / `SessionMemoryCompactStrategy` 的 `mergeSummaryIntoUser` 合并分支，统一插入独立 `Message.user(summaryPrefix)` 作为 compacted list 第一条
> - **关键发现（双 Reviewer 找出，单从 todo.md 不可推）**：仅做 BUG-F-1 不够——`CompactionService.persistCompactResult` 写 SUMMARY 行 role 必须从 `SYSTEM` 改为 `USER`，否则 `messageEquals` 比较 role 字段 false → `prefixLen=0` → 触发 fallback rewrite → mixed user message 仍以 NORMAL 行落库，BUG 不消失。这是 BUG-F-2 的真实必要性
> - **同时发现并修复**：`extractSummaryText` 的 `indexOf("\n\n---\n\n")` 分支必须删——否则 LLM summary 含 markdown 水平线时 DB 内容 ≠ engine 内存 String 字节级一致，`messageEquals` 仍 false 触发 fallback rewrite
> - **MSG_TYPE 决策**：未新增 `MSG_TYPE_COMPACT_SUMMARY`，复用已有的 `MSG_TYPE_SUMMARY`（YAGNI；plan §1.5-B 偏离 todo.md 原文经 Reviewer 同意）
> - **OpenAiProvider 防御层**：`convertMessages` hasToolResult 分支加 type filter，跳过非 tool_result block；同时合并文本到首个 tool_result content 前缀以保留 acbced3f 类老 session 的 summary 内容（不丢数据）
> - **未动文件**：`LightCompactStrategy` / `ClaudeProvider` / `AgentLoopEngine` / V18 migration / SessionService getContextMessages 主体（Plan 核实后均无需改）
> - **Pipeline 数据**：Plan r1（Java PASS 1W / DB FAIL 4W → Judge 重判 PASS）；Code r1（Java PASS 3W / DB PASS 2W → Judge 重判 PASS，0 blocker）；370 unit tests 全绿（`CompactPersistenceIT` / `SessionRepositoryIT` 因本机 Docker daemon 未启动 pending）
> - **Phase 4 实操**：用户授权跳过 live curl recovery；server 重启验证（kill PID 19885 → mvn package + spring-boot:run → Tomcat 8.4 秒启动成功）等价 sanity；用户飞书端事后验证 acbced3f 是否恢复
> - **Phase 3 r1 follow-ups（5 条）**：tracked in `/tmp/nits-followup-bug-f-compact-summary.md`（IT 串联缺口 / `captor.atLeastOnce` 脆弱 / `CompactPersistenceIT` 缺 `JavaTimeModule` / 弱断言注释升级 / mockito `times(1)` 显式约束）；非阻断，按需另起单子追

| 子任务 | 说明 | 关联 |
| --- | --- | --- |
| ~~**BUG-F-1**~~ `FullCompactStrategy` 删除 mergeSummaryIntoUser 合并分支 ✅ | 删除 `FullCompactStrategy.mergeSummaryIntoUser` + `SessionMemoryCompactStrategy.mergeSummaryIntoUser`；`apply()` / `applyPrepared()` / `tryCompact()` 三条路径无条件插入 `Message.user(summaryPrefix)` 为 compacted[0] | 治本 |
| ~~**BUG-F-2**~~ 摘要持久化 role 改 USER + extractSummaryText 简化 ✅ | `persistCompactResult` line 514 `summary.setRole(Message.Role.USER)`；`extractSummaryText` 删 indexOf 分支直接 return s（保证 DB 与 engine 内存 String 字节级一致 → messageEquals true → prefixLen ≥ 1 走 delta 路径，不触发 fallback rewrite）；MSG_TYPE 复用 `MSG_TYPE_SUMMARY` 不新增 | Reviewer 找出 |
| ~~**BUG-F-3**~~ `OpenAiProvider.convertMessages` 翻译层防御性过滤 ✅ | hasToolResult 分支加 type filter：ContentBlock 和 Map 形态都跳过非 tool_result；text 内容合并到首个 tool_result content 前缀（不丢摘要数据）；ClaudeProvider 不动（Anthropic 协议原生支持 mixed user blocks） | 防御 |
| ~~**BUG-F-4**~~ 验证 acbced3f 自动恢复 ⚠️ 跳过 | 用户授权跳过 live curl；server 重启成功即视为 e2e 通过；用户后续在飞书端实际验证 | Phase 4 |
| ~~**BUG-F-5**~~ 单测 + 集成测试 ✅ | `FullCompactStrategyTest` 三种 young-gen[0] case + `mergeSummaryIntoUser` 反射防回归 + `SessionMemoryCompactStrategyTest` 同结构 + 新建 `OpenAiProviderConvertMessagesTest`（ContentBlock + Map 形态 + body 扫描 `tool_call_id:"null"`）+ 新建 `CompactPersistenceIT` round-trip + acbced3f 形态 reload + `CompactionServiceTest` SUMMARY role=USER 锁定。370 unit tests, 0 failures | — |

> **不在本次范围**：
> - **`t_session_compaction_checkpoint` 表完全用起来**（branch / restore 能力）：本次只用 boundary marker 保证消息行可识别，不做断点恢复 UI / API。如需要可作为后续独立任务。
> - **P9-5 完整版（5 文件摘要 + 活跃 skill 上下文 + 完整 pending tasks）**：仍然按 V2 排期。BUG-F 只解决"摘要存储位置"的协议正确性问题，不扩 P9-5 的内容范围。
>
> **P10 优先级重评估（基于 BUG-F 修复后）**：
> - **`/new`** 仍然有用 ⭐⭐⭐：飞书移动端无 UI 阻断需求，独立价值不变；保留原 P10 排期 / Sprint 6
> - ~~**`/reset`** 状态复位~~：BUG-F 修复后 `acbced3f` 类卡死 session 自动恢复，**`/reset` 不再需要急做**；如果未来再有"消息历史污染"类 bug 才考虑做
> - **`/model`** 临时切模型：BUG-F 修复后不再需要"切 Claude 避坑"，保持原 P10 排期 / Sprint 6

---

### 🔥 进行中 — BUG-G dangling assistant tool_call 防腐（BUG-G-2 已修，BUG-G-1/3 待补）

> **触发事件**：session `d0863201-30c0-4582-82eb-2fcc2255bdfa` 在 DeepSeek 下一轮请求时报 HTTP 400：
> ```
> An assistant message with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'
> ```
>
> **现场确认（2026-04-27）**：同一 session 当天复现两次，均为 assistant `FileEdit` tool_use 后缺少对应 tool_result：
> - 第一次：seq=499 是 assistant `FileEdit` tool_use（`tool_use_id=call_00_5Cafu9m0L08drkiNKSqdkuiq`），后面直接接用户消息。已插入 synthetic error `tool_result` 到 seq=500，原用户消息顺延，session runtime 从 `error` 改回 `idle`。
> - 第二次：seq=506 是 assistant `FileEdit` tool_use（`tool_use_id=call_00_3pHcLijqaswMwjznsyGK8yL6`），seq=507 直接是用户消息 `替换怎么样了？`。已插入 synthetic error `tool_result` 到 seq=507，原用户消息顺延到 seq=508，session runtime 从 `error` 改回 `idle`。修复后 pairing scan：`assistant_tool_turns=211 missing_turns=0`。
>
> **根因链路**：
> 1. `OpenAiProvider.processSSEStream` 会在 stream 结束时 `finalizeToolCalls(...)`，只要累积到 `tool_calls` delta，就会生成 `ToolUseBlock`；这两次 `FileEdit` 的 input 都是 `{}`，说明 tool call 参数很可能未完整收齐或结束态异常。
> 2. `AgentLoopEngine.buildAssistantMessage(response)` 只要 `response.toolUseBlocks` 非空，就会把 assistant 保存为带 `tool_use` 的消息。
> 3. 但工具执行分支只看 `response.isToolUse()`；而 `LlmResponse.isToolUse()` 只判断 `stopReason == "tool_use"`。当 provider 解析到了 `toolUseBlocks`，但 `finish_reason` 未映射成 `tool_use`（仍是 `end_turn` / 缺失）时，引擎会把 tool_use 写入历史，却按普通文本结束，不执行工具、不补 tool_result。
> 4. 下一轮 `OpenAiProvider.convertMessages` 会把历史 assistant `tool_use` 转成 OpenAI-compatible payload 的 `assistant.tool_calls`；但其后紧跟普通 user 消息而不是 `role=tool`，DeepSeek 严格校验后返回 HTTP 400。
>
> **2026-04-28 真实根因补充（session `ff457c2d-af13-479c-b906-fc4a9f85bdb5`）**：
> - 为排查临时加本地完整 payload dump（不纳入最终代码）后确认：完整 request 只有 system + user 两条 message，`tools` 中包含 `WebFetch`；不是 prompt / memory / skill 选择导致。
> - qwen3.5-plus / qwen3.6-plus SSE 首段 `tool_calls[0].id` 正确为 `call_1c92880586144bbf932ac619`，后续 arguments delta 可能继续携带 `id:""` / `function.name:""`；旧解析逻辑无条件 `put(index, id/name)`，会把有效 id/name 覆盖成空串。
> - 空 id/name 会被 `LlmResponse.validToolUseBlocks()` 过滤，导致 `response.isToolUse()` 为 false；AgentLoop 只展示模型文本（如“我来用 WebFetch...”），但不执行工具，trace 中也没有 `TOOL_CALL` span。
> - 当前最小修复：OpenAI-compatible stream 增量合并时，空白 `id` / `function.name` 不再覆盖已收集值；`LlmResponse.isToolUse()` 改为结构优先；`AgentLoopEngine` 执行和落库使用过滤后的有效 tool_use blocks。新增 `OpenAiProviderStreamToolCallTest`、`LlmResponseTest`、`AgentLoopEngineToolUseInvariantTest`。
> - 已刻意回滚/暂缓 `OpenAiProvider.convertMessages` 发送前 pending assistant tool_call 防腐状态机，避免把 qwen 修复和历史防腐混在一个提交里；该能力保留为 BUG-G-1 后续独立任务。
>
> **临时修复流程**：定位最后一个 dangling assistant `tool_use` → 在其后插入 `role=user` / `type=tool_result` / `is_error=true` 的 synthetic result → 顺延后续 `seq_no` → 清空 `runtime_error` 并置 `runtime_status=idle` → 跑 pairing scan 确认 `missing_turns=0`。这只能修单 session 数据，不能防止再次发生。

| 子任务 | 说明 | 验收 |
| --- | --- | --- |
| **BUG-G-1** OpenAI-compatible 发送前历史防腐（方案 3） | 在 `OpenAiProvider.convertMessages` 或其前置 sanitizer 中校验 OpenAI 消息协议：assistant `tool_calls` 后必须紧跟覆盖每个 `tool_call_id` 的 `role=tool` 消息。发现缺失时，不再原样发送非法 payload；可将 dangling assistant tool_use 降级为普通 assistant 文本，并追加一条 user 文本说明该 tool call was interrupted / missing result，避免 provider 400 | 构造 `assistant(tool_calls:A) -> user(text)` 历史时，请求体不包含非法未配对 `tool_calls`；DeepSeek/OpenAI-compatible 不再 400；测试覆盖 ContentBlock 和 DB 反序列化 Map 两种形态。**暂缓为后续独立任务** |
| ~~**BUG-G-2** toolUseBlocks 与 stopReason 一致性修复（方案 4）~~ ✅ 2026-04-28 | `LlmResponse.isToolUse()` 改为结构优先：存在有效 tool_use block 即进入工具执行；过滤空 id/name 和重复 id；qwen 流式解析修复为空白 delta 不覆盖已收集 id/name；`AgentLoopEngine` 使用有效 tool_use blocks 执行和落库 | `LlmResponseTest` + `OpenAiProviderStreamToolCallTest` + `AgentLoopEngineToolUseInvariantTest` 覆盖 stopReason 缺失/`end_turn` 但有有效 tool_use、无效空 id/name 过滤、qwen 空 id/name delta 复现 |
| **BUG-G-3** 失败路径持久化保护 | 在 runLoop error/cancel/max-loop 等路径加不变量校验：最终落库前如果尾部存在未配对 assistant tool_use，必须补 synthetic error tool_result 或将其降级为文本，避免异常中断把坏尾巴永久写入 DB | 任意异常退出后，`t_session_message` 中 assistant tool_use/tool_result 配对完整；session 可继续发送下一轮，不需要手工 DB 修复 |

---

### ~~Sprint 1 — 零依赖防腐~~ ✅ 已完成（2026-04-26）

> 全部无强依赖、独立 PR、即刻可开工。P8 LLM 记忆提取刚上线，快照是必要防腐；token 估算精度影响所有用户体验；P13 workaround 清理降低后续改动摩擦。
>
> **完成说明（2026-04-26，commit `f4773c3`）**：P3-1/P3-3/P13-3 已完成；`mvn -pl skillforge-server test -Dtest='!*IT'` 在 Java 17 下 397 tests 全绿；完整 `mvn -pl skillforge-server test` 仅 3 个 Docker/Testcontainers IT 因本机 Docker daemon 不可用失败。

| 子任务 | 来源 | 实际工作量（代码扫描后） |
| --- | --- | --- |
| ~~**P9-7**~~ jtokkit token 估算增强 ✅ 2026-04-26 commit `621f417` | P9 | TokenEstimator 替换为 jtokkit 1.1.0 cl100k_base + `countTokensOrdinary`（防 `<|endoftext|>` 字面量 UnsupportedOperationException）+ `newLazyEncodingRegistry`（省 ~30MB）+ WeakHashMap identity cache。28 个调用点零改动，阈值常量未动（**阈值回调留作独立 follow-up ticket**）。Full Pipeline 跑通：Plan skipped（brief unique），Review r1 PASS（Judge 把 B-1 cache race / W-1 ordinary underestimate 都降级为 nit）。17 单测 + 385 全 server 单测全绿。Phase 4 verify 期间主会话 `mvn -pl skillforge-server test` 漏带 `-am` 命中 stale `.class` 误报 NPE，dev 反驳后主会话 `clean install` 复跑确认 17/17，是 verify 流程问题不是 dev 问题。Nit follow-up 4 条 in `/tmp/nits-followup-p9-7.md` |
| ~~**P3-1**~~ 记忆快照 ✅ 2026-04-26 | P3 | `t_memory.extraction_batch_id` + `t_memory_snapshot` 快照表 + `MemoryService.beginExtractionBatch/rollbackExtractionBatch` + `POST /api/memories/rollback`；提取前按 batch 快照当前用户记忆，回滚时恢复快照并删除本 batch 新增记忆 |
| ~~**P3-3**~~ 记忆影响归因扩展 ✅ 2026-04-26 | P3 | AttributionEngine 扩展为 9×7：新增 `MEMORY_INTERFERENCE` / `MEMORY_MISSING` 两类归因与 `memorySkillCalled` / `memoryResultEmpty` 信号；Scenario/Judge/AB eval 路径统一采集 tool call 信号并在 EvalRun/API 暴露计数 |
| ~~**P13-3**~~ AgentEntity#isPublic → Boolean 包装 ✅ 2026-04-26 | P13 | `AgentEntity.isPublic` 改 `Boolean`；create 默认 false，partial update 省略 public 时保持原值；公开性判断统一 `Boolean.TRUE.equals()`；Flyway migration 放宽 `is_public` 非空约束 |
| ~~**P13-4**~~ AgentServiceTest 补测试 | P13 | **代码扫描发现已完成**：AgentServiceTest 现有 5 个测试（404/full-payload/all-null/isPublic/blankRole），覆盖 todo 要求的全部场景。**划掉** |

---

### Sprint 2 — P11 Agent 发现/调用 + P15 Agent 自省（合并 Sprint，8-12 天，分两次 PR）

> **节奏**：同一 Sprint 内分两次 PR 做完，P11 → P15。两者技术栈高度重合（都是新增 Tool / SkillRegistry 注册 / Agent 调用），合并到同一 Sprint 共用一次 architectural 思考；物理上拆 PR 让每次 Full Pipeline reviewer 注意力集中、避免单 PR 跨范围太广 blocker 卡整批 ship。
>
> **PR 顺序**：先 P11（含 AgentDiscovery + name resolver + visibility schema migration）作为基础设施 → 再 P15（Analyzer 直接复用 P11-1 AgentDiscoverySkill，不用临时凑查 agent 的方案）。

#### ~~PR 1 — P11 Agent 发现与跨 Agent 调用（收窄）~~ ✅ 已完成（2026-04-26）

> 收窄范围：去掉 capabilities/tags（当前 agent 数 < 10，name 模糊查找够用，tag 系统是过度设计，不再单独追踪）。P13-1 custom rule severity 并入本 PR，共享 agent 后端改动节奏。
>
> **完成说明（2026-04-26）**：新增 `AgentDiscovery` 工具；`SubAgent` 支持 `agentName` 解析并做可见性 / active 校验；当前 session lineage 内递归派发会被拒绝；Agent UI 增加 public/private visibility 配置；自定义 behavior rule 升级为 `{severity,text}` 并兼容旧 string。验证：`mvn -pl skillforge-server -am test -Dtest='!*IT'` 407 tests 全绿；`npm run build` 通过。

| 子任务 | 说明 |
| --- | --- |
| ~~**P11-1**~~ AgentDiscoverySkill ✅ 2026-04-26 | 新增 Java Tool `AgentDiscovery` 并注册到 `SkillRegistry`；按当前 session author 过滤可见 Agent，返回 JSON `{count, agents:[id,name,description,visibility,skills,tools]}`；`query` 支持 name/description/id 模糊过滤 |
| ~~**P11-2**~~ SubAgentSkill 增强 ✅ 2026-04-26 | `SubAgent.dispatch` 支持 `agentName`；target resolver 先精确匹配 name，再唯一模糊匹配；调用前校验 target 存在、active 且对当前 author 可见 |
| ~~**P11-4**~~ 调用权限控制 ✅ 2026-04-26 | 复用 `is_public` 映射 public/private；可见性规则：自己 / public / 同 owner private；前端 Agent create/drawer 可配置 visibility；SubAgent 阻止当前 parent lineage 中重复调用同一 agent，避免 A→B→A / self dispatch 循环 |
| ~~**P13-1**~~ Custom rule severity（并入） ✅ 2026-04-26 | `BehaviorRuleConfig.customRules` 从 `string[]` 升级为 `Array<{severity:'MUST'\|'SHOULD'\|'MAY', text:string}>`；前端 custom rule 新增 severity 下拉；后端 `AgentDefinition.BehaviorRulesConfig` 加 Jackson 向后兼容 deserializer，旧 `"text"` 自动升级为 `{severity:'SHOULD', text}`；`SystemPromptBuilder` 按 MUST/SHOULD/MAY 分组注入 |

---

#### PR 2 — P15 Agent 自省技能层（最小闭环，3-5 天）

> 目标：把平台查询类 REST API 包成 Skill，让任意 Agent 能查自身的 Trace / Session 数据；Session Analyzer Agent 辅助分析工具选择质量。首版只做最小可验证闭环，跑 1-2 个真实 session 人工评价输出质量后再决定是否扩展 P15-3/6。
>
> **依赖**：必须等 PR 1（P11）合入后再开工。Analyzer Agent 需要查 agent 自身配置时直接用 `AgentDiscoverySkill`，避免临时方案。
>
> **进度（2026-04-26）**：P15-1/P15-2 已完成；`GetTrace` / `GetSessionMessages` 注册进 `SkillRegistry`，均做当前 session 默认值、同用户 session 访问校验、输出裁剪。新增 6 个工具单测；`mvn -pl skillforge-server -am test -Dtest='!*IT'` 413 tests 全绿。补充完成 P15-4 `GetAgentConfigTool`、`AgentDiscovery` 配置字段增强、`UpdateAgentTool`（一次性 approval token 确认；普通配置 patch + 结构化 `hookChanges` 同事务提交；agent-authored hook 只走 `AgentAuthoredHookService.propose` 留在 PENDING，不绕过 SEC-2）。

| 子任务 | 说明 |
| --- | --- |
| ~~**P15-1** GetTraceTool~~ ✅ 2026-04-26 | 新增 Skill：`action=list_traces`（按 sessionId 列 trace 摘要）或 `action=get_trace`（按 traceId 拉 span 树）；`maxSpans` 默认 30、硬上限 100，input/output/error 做裁剪；复用现有 `TraceSpanRepository`；输出时间统一 ISO 字符串 |
| ~~**P15-2** GetSessionMessagesTool~~ ✅ 2026-04-26 | 新增 Skill：按 sessionId 拉消息历史（role/content/toolCalls），支持 `limit` 控制返回条数（默认 20、硬上限 100）和 `maxContentChars`；通过 `SessionService.getFullHistoryDtos` 复用消息行存储 |
| ~~**P15-4** GetAgentConfigTool~~ ✅ 2026-04-26 | 新增只读 Skill：按当前 session author 的可见性解析目标 agent，返回 prompts、skills/tools、config、behaviorRules、user lifecycle hooks 原始 JSON、execution/thinking/reasoning/maxLoops 等完整配置；`AgentDiscovery` 同步补充 modelId/role/status/owner/config/rules/thinking/reasoning/maxLoops 与 hook/prompt presence flag，供 Analyzer 先轻量发现、再按需深查 |
| ~~**P15-extra** UpdateAgentTool~~ ✅ 2026-04-26 | 新增需人工一次确认的 Agent 更新 Skill：复用 `ToolApprovalRegistry` approval token；`AgentTargetResolver.resolveEditableTarget` 只允许自己、同 owner、作者 owner 等权限边界内编辑；支持普通配置 patch + `hookChanges.userLifecycleHooks` + `hookChanges.agentAuthoredProposals` 同事务提交；禁止直接写 `lifecycleHooks` / approved agent-authored hooks，agent-authored hook 只能创建 PENDING 提案，维持 SEC-2 三源隔离 |
| ~~**P15-5**~~ Session Analyzer Agent seed | **取消**：Analyzer Agent 已手工创建，不再需要 Flyway migration 内置种子；保留手动触发、文本输出的使用方式 |
| ~~**P15-3**~~ GetEvalRunTool | **→ V2**：Analyzer MVP 不需要读 eval run；当前 eval 数据量小，分析价值有限 |
| ~~**P15-6**~~ 分析结果落库 + 前端 Analysis tab | **→ V2**：首版文本输出到 session 即可；落库 + UI tab 等验证有价值后再投 |

#### 与其他 P 的关联
- **→ P11**：P15-4 GetAgentConfigTool 已与 P11-1 AgentDiscoverySkill 分层：Discovery 做轻量列表与 presence flag，GetAgentConfig 做单 agent 深查
- **→ P14**：Analyzer 发现的"应该用 A 但用了 B"case，可一键转为 P14 eval scenario
- **→ P3**：记忆质量分析可复用 GetSessionMessagesTool

---

### 🧩 P1 Skill 生命周期统一重构（方案 C，待排期，5-8 天）

> **架构决策（2026-04-27）**：P1-FU 不再走补丁式修补，统一采用 **方案 C（Skill Control Plane）**。  
> 核心原则：  
> 1) `system skill` 与 `user skill` 只是来源分类；两者都是同一种 skill package（`SKILL.md` + 可选 references/scripts），不再保留"DB-only skill"路径。  
> 2) 运行时不再依赖 registry 全量注入；每次会话先计算 `SessionSkillView`（本次会话唯一可用 skill 清单），引擎只认该快照。  
> 3) system skill 按 agent 单独开关，默认全启用，记录 disabled 列表；system skill 不可删除。  
> 4) `skillIds` 为空时只注入 system skills（受该 agent 的 disabledSystemSkills 影响），不注入用户 skills。
>
> **现状问题（2026-04-27 扫描）**：当前 skill 生命周期割裂在 DB / 文件 / 内存 registry 三份状态；`AgentLoopEngine` 仍会注入 registry 全量 `SkillDefinition`；`SkillDefinition` 调用不自动记 usage；Skill 页面仍有 `DEFAULT_SOURCE_AGENT_ID=1` 和 owner=1 硬编码；A/B 未传 `agentId` 会 400。以下子任务必须以同一闭环交付。

| 子任务 | 说明 | 验收 |
| --- | --- | --- |
| **P1-C-1** Skill Artifact 统一模型（去 DB-only） | 明确 skill 的标准载体为 package：`SKILL.md` 必需，references/scripts 可选。`approveDraft` 不再只写 `SkillEntity`；必须生成目录 `data/skills/{ownerId}/{skillId}/` + `SKILL.md`，并通过 `SkillPackageLoader.loadFromDirectory` 校验后发布。失败时事务回滚并清理目录，禁止半成功 | draft approve 后一定存在可读 `SKILL.md` 与 `skillPath`；重启后可恢复；不会出现 UI 有 skill 但磁盘无包 |
| **P1-C-2** Skill 生成链路改为 Extractor → Generator（skill-creator）→ Validator | session 提取阶段只产出结构化草稿（why/what/triggers/tools/prompt intent）；真正 `SKILL.md` 生成走 `skill-creator` system skill 模板化生成，再经 `SkillPackageLoader` 校验。提取 skill 默认 `source=extracted`、`system=false`、owner=当前用户 | 生成物符合 Claude-style skill 包；提取得到的是用户业务 skill（非 system）；非法 frontmatter/缺文件在发布前被拦截 |
| **P1-C-3** Agent 级 Skill Binding + System Toggle 独立字段 | `t_agent` 新增 `disabled_system_skills`（JSON array，默认空=全启用）；不与 `skillIds` 混用。`skillIds` 仅绑定用户 skills；system skills 由 `allSystem - disabledSystemSkills` 决定。system skill 不可删除但可按 agent toggle | 同一 system skill 在 A agent 关闭、B agent 开启时行为独立；新建 agent 默认 system 全启；删除 system skill API 返回禁止 |
| **P1-C-4** SessionSkillView 运行时快照与统一授权 | 新增 `SessionSkillView`/resolver：在 `ChatService` 启 loop 前计算允许 skill 集；`SystemPromptBuilder`、`collectTools`、`executeToolCall`、`ContextBreakdownService` 全部用同一视图。禁止 registry 全量注入兜底 | prompt skills 列表、tool schema、真实可执行集三者一致；未绑定/被禁用 skill 调用返回 not allowed；`skillIds` 为空仅有 system |
| **P1-C-5** 启动恢复改为“按 Artifact 重建运行态” | 新增 user-skill loader：启动时扫描 DB 中有效 `skillPath` 的非 system skills 重新注册；坏包只告警不阻断启动。system skill 同名优先，冲突用户 skill 标记异常 | 上传/ClawHub/extracted/evolved skills 重启后可恢复；坏目录不会拖垮服务；冲突可观测 |
| **P1-C-6** Skill telemetry 统一自动采集 | 在 `SkillDefinition` 执行分支自动记 usage/success（按 skill id 记录，不依赖 REST 手工上报）；定义 success/failure/disabled/not-allowed 计数语义 | `usage_count/success_count` 随真实调用变化；Skill 成功率不再长期 0；A/B 与 Evolution 的 before 指标可信 |
| **P1-C-7** Skill 页面去硬编码 + 参数闭环 | 移除 `DEFAULT_SOURCE_AGENT_ID=1` 和 owner=1 硬编码；Skill 页面新增 agent selector。Extract/A-B/Evolution 必传 agentId；上传/提取/评审 owner 默认当前登录用户，后端不信任前端 owner | A/B 不再 400；不同 agent 提取范围正确；不同用户不会写到 owner=1；UI 能解释“请选择来源 agent” |
| **P1-C-8** Draft 候选去重与合并建议前置 | 去重前置到候选阶段：name canonicalize + triggers/tools overlap + text similarity。高相似默认折叠，中相似给 merge 建议；approve 阶段仅做并发与唯一性防护 | 连续 Extract 不再刷重复草稿；候选卡片有 similarity/merge 建议；用户显式“仍创建”可保留 |

---

### Sprint 3 — P9-2 长对话 Tool 归档（独立 PR，~2 周）

> 直接解决"长对话 context 爆满"真实用户慢性病。P6 消息行存储上线后，归档表前置条件已满足，工程复杂度大幅降低。触碰核心文件（CompactionService），走 Full Pipeline 对抗循环。

| 子任务 | 说明 |
| --- | --- |
| **P9-2** Per-message 聚合预算 + 归档持久化 | 单条 user message 的 tool_result 总量超 200K chars 时，按大小降序归档到 `t_tool_result_archive` 表，消息替换为 2KB preview + 引用 ID；可选新增 `ToolResultRetrieveSkill` 让模型按需读取 |

---

### 🔍 OBS-1 Session × Trace 合并 + Trace 详情完整化（待排期，2026-04-28 新增）

> **触发动机**：当前排查链路过度依赖 Analyzer Agent（P15），但用户也需要直接看原始 LLM 请求/响应来判断"压缩后回传给模型的内容是否合理"——例如 BUG-F / BUG-G / 跨 provider 切换 这类问题，最终都得看一个完整的 LLM request body 才能定性。Trace 页当前对 input/output 做了裁剪（P15-1 GetTraceTool `maxSpans=30 / 输入输出截断` 是 Tool 层裁剪，UI 端也有截断），LLM 调用的真实 payload 看不全；同时 Session 页和 Trace 页是分离的，排查时需要在两个页面间跳转拼接。
>
> **目标**：让用户在一个视图里同时看到对话流水（session messages）和底层执行栈（trace spans）——会话级时间线为主，点开任意 LLM / Tool span 能拉到**未截断**的完整 request body 与 response body（哪怕 100K+ JSON），方便直接验证压缩内容、tool_calls 配对、reasoning_content 形态等。

| 子任务 | 说明 |
| --- | --- |
| **OBS-1-1** Session × Trace 统一视图 | Dashboard 新增（或合并现有 Session/Trace 两页为）"Session Detail" 视图：左侧消息时间线（user / assistant / tool_use / tool_result），每条消息可展开下挂的 LLM call / Tool exec span，按时间对齐；保持现有 Trace 列表入口的同时，从 Session 直接跳到对应 trace |
| **OBS-1-2** LLM span 完整 payload 落库 | TraceSpan 当前 `input` / `output` 字段就有截断；新增专用持久化（如 `t_llm_call_payload` 大对象表，主键 spanId）记录每次 LLM 调用的**完整** request body（messages 数组、tools schema、reasoning 配置等）+ 完整 response body（含 SSE 重组后的最终 message），不做截断；只在 query 时按需懒加载 |
| **OBS-1-3** UI Payload Viewer | Span 详情面板新增"Raw Request / Raw Response" tab：JSON 折叠 + 关键词搜索 + 一键复制 + size badge；超过阈值（如 1MB）走分块流式渲染防卡顿；敏感字段（api-key、Authorization header 等）落库前剥离 |
| **OBS-1-4** 压缩验证视角 | 对 `compact` 类 LLM call（summary 生成）在 UI 标记特殊徽章；可对比"压缩前消息列表"vs"压缩后实际发给模型的 messages"，让用户直观判断 summary 质量与边界切割是否合理；同时 trace 元数据带上 compact 的 strategy / boundary index / reason |
| **OBS-1-5** Provider quirk 诊断信号 | 已知问题（reasoning_content 缺失、tool_call_id 重复、dangling tool_use）在 UI 自动检测并红字标注，给出文档锚点（指向 `docs/llm-provider-quirks.md` 对应章节）|

> **不在本次范围**：
> - 跨 session 全文检索 / 流量重放 / 自动告警 → V2
> - LLM call 录制为 eval scenario → 与 P14 协同时再考虑
>
> **存储与脱敏**：完整 payload 表会显著增长 DB 体积，需要保留期策略（如 30 天后归档/丢弃）+ 脱敏管道，方案在 design doc 阶段定。
>
> **与 P15 关系**：P15 GetTraceTool 仍保留裁剪输出给 Agent 用（节省 token）；OBS-1 的完整 payload 是给**人**看的旁路通道，二者不互相替代。

---

### ⚠️ Sprint 4 前置决策项（开工 P12 之前必须有答案）

> Challenger 补充：被 Analyst 完全忽略的三项前置工程设计。P12 定时任务会放大 token 消耗和数据量，没有这三项的决策，P12 上线即踩坑。这三项本身实现量不大，但需要明确设计决策。

| 前置项 | 核心问题 | 建议行动 |
| --- | --- | --- |
| **Cost Dashboard** | P12/P15/未来 P14 都是 token 放大器；无 cost 可见性会出现"跑几天账单爆炸"的情况 | 最小版：session/agent 维度 token 用量 + LLM 估算费用，复用现有 TraceSpan 数据；单页 UI |
| **Embedded PG 备份策略** | zonky embedded PG 无内置备份；data dir 损坏 = sessions/skills/memories/agent 配置/scheduled tasks 全部蒸发；P12 跑起来后更不能没有 | 最小版：pg_dump cron 脚本 + 本地压缩保留 7 天 |
| **多用户/权限模型 design doc** | 当前 auto-token 是单用户假设；P11 跨 Agent 调用身份、P12 定时任务触发 session 的"身份"都踩在此假设上，任何真实多端部署都需要先定 | 写 design doc：多用户如何隔离 agent/session/memory/scheduled_task；不要求立刻实现，但要定好边界 |

---

### Sprint 4 — P12 定时任务（收窄首版，3-4 周）

> 首版只做 user 型调度最小集；SystemJobRegistry + 高级可靠性 → V2。工期修正为 **3-4 周**（Analyst 的"1-2 周"低估了 concurrencyPolicy 三种策略 / 时区夏令时坑 / 前端 cron 编辑器 / 历史表 + AOP 切面的工程量）。

#### 关键设计选择

**调度器选型：** user 型走 `ThreadPoolTaskScheduler` 动态调度（`schedule(Runnable, Trigger)` + DB 持久化元数据，应用启动时从 DB 全量重新注册）。除非明确要做 cluster，否则不引 Quartz。

**首版 system 型任务：** 保留现有 5 处 `@Scheduled` 注解不动；SystemJobRegistry + UI 展示 → V2。

**首版 concurrencyPolicy：** 只支持 `skip-if-running`；queue/parallel → V2。

| 子任务 | 说明 |
| --- | --- |
| **P12-1** Schedule 实体 + CRUD | `t_scheduled_task`（id/name/cronExpr/oneShotAt/timezone/agentId/promptTemplate/channelTarget/enabled/concurrencyPolicy/nextFireAt/lastFireAt/status）+ Flyway migration + `ScheduledTaskService` CRUD + REST API；只存 user 型任务 |
| **P12-2** 动态调度器内核 | `UserTaskScheduler`：基于 `ThreadPoolTaskScheduler` + `CronTrigger`；应用启动时从 DB 全量 register；CRUD 操作后同步 schedule/unschedule；触发时调用 `ChatService.chatAsync` 起 session；首版只支持 `concurrencyPolicy=skip-if-running`；shutdown 优雅等待 |
| **P12-4** 执行历史 + 可观测 | `t_scheduled_task_run`（taskId/triggeredAt/finishedAt/status=success\|failure\|skipped\|timeout/errorMessage/triggeredSessionId）；每次调度写一行 |
| **P12-5** 前端 /schedules 页面 | 只做 user 型 tab（system tab → V2）；任务列表 + cron 表达式编辑器（含"下 5 次触发时间"预览）+ 新建/编辑 drawer（选 Agent + prompt 模板 + channel 目标）+ 启停 toggle + 手动 trigger 按钮 + 执行历史时间线 |
| ~~**P12-3**~~ SystemJobRegistry | **→ V2**：首版保留现有 `@Scheduled` 注解不动；UI 上只做 user 型 tab |
| ~~**P12-6**~~ 可靠性 + 权限 | **→ V2**：首版只做超时 kill + 失败日志；告警推送 + admin 权限分离后续实现 |

---

### Sprint 5 — P9-4 Partial compact + P9-5 Post-compact 恢复（按需）

> P9-5 需要先写 design doc 明确"最近操作文件"数据来源（TraceSpan 查？新表？）再开工，否则工期不可估。P9-4 是 P9-5 的硬前置。

| 子任务 | 说明 |
| --- | --- |
| **P9-4** Partial compact 支持 | `FullCompactStrategy` 新增 `compactUpTo`（压缩头保留尾）和 `compactFrom`（压缩尾保留头）；`ContextCompactTool` 扩展 `level=partial_head/partial_tail` |
| **P9-5** Post-compact 上下文恢复 | Full compact 后自动注入最近操作的文件摘要（5 个文件 / 50K token 预算）+ 活跃 skill 上下文 + pending tasks；**需先完成 design doc 明确"最近操作文件"数据来源后再开工**。**注**：pending FileWrite/FileEdit input 保留这一最小子项已被 P9-5-lite 切到「🔥 紧急」前置完成，本任务排期时不要重复 |

---

### Sprint 6 — P10 聊天斜杠命令（收窄，5-8 天）

> 从 Sprint 1 降级为中后期节奏调节器。Challenger 质疑：斜杠命令替代的是已存在的 UI 路径，不是新能力，实际用户价值被 Analyst 高估（原评 5/5，修正为 2/5）。工期修正为 5-8 天（`/compact` 触碰 CompactionService 核心文件需走 Full Pipeline）。

| 子任务 | 说明 |
| --- | --- |
| **P10-1** 命令注册表（4 条） | `/new`（新建 session）、`/compact`（触发 full compact，不等 P9-4 就绪）、`/clear`（清空当前对话显示）、`/model`（改 session 级临时模型，不持久化到 agent 配置） |
| **P10-2** 前端命令解析 | 聊天输入框拦截 `/` 前缀消息；输入时弹出命令补全菜单（fuzzy match）；回车直接执行，不发送给 Agent |
| **P10-3** 后端命令执行 | REST API `POST /api/chat/commands/{command}` 或复用现有 endpoint；`/new` 调用 SessionService 创建、`/compact` 调用 CompactionService 等 |
| ~~**P10-4**~~ 自定义命令注册 | **→ V2**：4 条命令覆盖 90% 用例 |
| ~~**P10-5 `/help`**~~ | **→ V2**：4 条命令的系统不需要 help menu |

---

### P3 — 记忆质量评估（Sprint 1 + V2）

> P3-1 + P3-3 已拆入 Sprint 1（零依赖，立即可做）。P3-2 + P3-4 依赖 P14 基础设施，随 P14 整体推迟至 V2。

| 子任务 | 状态 | 说明 |
| --- | --- | --- |
| **P3-1** 记忆快照 | **→ Sprint 1** | 见 Sprint 1 |
| **P3-3** 记忆影响归因 | **→ Sprint 1** | 见 Sprint 1 |
| ~~**P3-2**~~ Memory Eval 模式 | **→ V2** | eval sandbox 加入 Memory Skill（只读，读快照不读生产）；新增记忆专属场景集；依赖 P14 基础设施 |
| ~~**P3-4**~~ 记忆质量闭环 | **→ V2** | 每次提取后自动触发 Memory Eval；Δ 为负则标记问题 batch；依赖 P3-2 |

---

### P14 — 多轮对话基准测试（τ-bench 风格 Eval 扩展）

> **整体推迟至 V2。** Challenger 判断：在无真实 τ-bench 级评测需求前，这是学术味的基础设施投资；UserSimulator 收敛难题未解；pass^k 每轮 ×k token 账单；8 个 retail domain tool × 带内存状态 × state assertion oracle，10-20 条场景的移植是隐性大工程。**重评触发条件：真实业务场景需要多轮评测时。**

| 子任务 | 说明（备查，V2 实现） |
| --- | --- |
| P14-1 EvalScenario schema 扩展 | 新增 `conversational`、`userProfile`、`domainState`、`domainTools`、`maxTurns`、`trials`；oracle 新增 `state_assertion` 类型 |
| P14-2 UserSimulatorSkill | 持有 userProfile + goal + conversationHistory；每轮调用 LLM 生成用户下一句话；收敛信号：输出含 `[DONE]` 或 turns 超限 |
| P14-3 多轮场景驱动循环 | `ScenarioRunnerSkill` 新增 `runConversationalScenario`：交替触发 UserSimulator → AgentLoop |
| P14-4 DomainToolRegistry + state_assertion oracle | 按 `domainTools` 动态注入带状态领域 Skill；`EvalJudgeSkill` 新增 state_assertion 判题 |
| P14-5 τ-bench retail 工具 + 场景集 | 8 个 retail domain Skill + 10-20 条 held-out 场景 JSON |
| P14-6 pass^k 指标 | 同一 scenario 重复跑 k 次，统计通过率；前端 Eval 页展示 pass^k 列 |
| P14-7 后续基准接入 | τ-bench airline / MINT / ToolBench 等（更远 V2） |

---

### ~~🧹 LLM Provider 观测性 / DX~~ ✅ 已完成（2026-04-25，commit `55969db` 顺带做掉）

> 详见底部「已完成」表格 2026-04-25 thinking-mode-v1 行。两条核心 DX 都已落地:
> ① 构造器空 api-key fail-fast(带 envVarName hint),Phase 4 hotfix 在 `SkillForgeConfig.llmProviderFactory` 加 try-catch 跳过未配置 provider,服务能正常启动;
> ② `"OpenAI API error"` 硬编码 → `providerDisplayName`,现在 deepseek/qwen 报错信息精确,不再误导。
>
> **未做的更彻底架构**(可选,V2):抽 `DeepSeekProvider extends OpenAiProvider` 或独立 `ThinkingMessageAdapter`,把 `reasoning_content` 逻辑从通用 `OpenAiProvider` 剥出去。当前用 `ProviderProtocolFamily` enum + capability flags 在同一类里分派,可读性 OK,暂不拆。

---

### ~~🔥 引擎稳定性 — Compact Breaker 误触 + LLM Stream 抗抖~~ ✅ 已完成（2026-04-24，commit `121e8dc`）

> 详见底部「已完成」表格 2026-04-24 行。
>
> **Follow-up（非 block，独立 PR，见 `/tmp/nits-followup-engine-stability-v1.md`）**：
> ① E-bis MockWebServer retry 单测补齐（`connectException_succeedsOnSecondAttempt` / `postHandshakeException_doesNotRetry` 等 7 个场景）；
> ② BUG-D `onWarning → TraceSpan.attributes` 端到端测试；
> ③ Dashboard Traces 页显示 TraceSpan `attributes` 面板让 truncation warning 可视。

<details>
<summary>历史子任务描述（原 plan 触发背景，仅作复盘参考）</summary>

> **触发事件**：2026-04-23 下午连续 3 个 session 中断——
> - `cec1cd60`：breaker 错误 open（no-op/idempotency 3 次被当 failure）→ session 全程无法 compact；同时 `Read timed out`（loop 12，qwen thinking idle > 60s）+ `ConnectException /127.0.0.1:1082`（VPN 抖动 ~30s）
> - `02112e8f`：`Read timed out`（60s 默认）
> - `3c0d05d9`：`ConnectException /127.0.0.1:1082`（VPN 抖动，30s 内自愈，但 chatStream 不 retry 就 fail 整个 loop）
>
> **环境约束（不可回避）**：本机 DashScope 域名被 VPN 劫持到 `198.18.0.45 / utun4`，不走 VPN 物理上连不到 qwen；所以 "绕过 VPN" 不是选项，必须代码层抗抖。
>
> **已止血（无需 Pipeline）**：`application.yml:74` bailian `read-timeout-seconds: 60 → 240` 已启用（2026-04-23，commit 暂未 push）。

| 子任务 | 说明 |
| --- | --- |
| **BUG-A** Breaker 误触修复（blocker） | `AgentLoopEngine.java` 3 个 compact 触发点（:352-353 soft / :374-376 hard / :463-465 preemptive）都把 `cr.performed == false` 当 failure 累加。实际 `CompactionService` 的 `noOp` 包含 "session not found" / "idempotency guard" / "strategy returned no-op" / "full compact no-op or in-flight" 多种正常 skip 情形。修：只有**真正抛 Exception** 才 `incrementCompactFailures`，`performed==false` 视为中性。cec1cd60 实证：连续 3 次 no-op/idempotent skip 后 breaker 错误 open，session 整段跑在无压缩状态 |
| **BUG-B** Breaker 自动重置（warning） | `AgentLoopEngine.java:383-386` 一旦 `>=3` 后永远 skip，没有 time-based half-open。修：最后一次失败后 N 秒（建议 60s）允许试一次；或下一次 user turn 入口重置计数 |
| **BUG-C** Compact 失败日志补 stacktrace（warning） | `AgentLoopEngine.java:356-357 / :379-380 / :468-469` 都是 `log.warn(..., e.getMessage())`，**没 stacktrace**，WARN 级。改 `log.error(..., e)` 带完整堆栈；+ `CompactionService` 内部失败路径（如 LLM 超时、解析失败）自行加 ERROR 日志，不依赖调用方 |
| **BUG-D** OpenAiProvider SSE 截断 observability（info） | `OpenAiProvider.java:525-528` 已有 try/catch + `input=Map.of()` 兜底（所以不崩），但前端/trace 完全不知道 "tool_call input 是残缺的"。加：解析失败时通过 handler 发一个可识别 error 类型（或结构化 warning 事件），让 trace / 前端能看到截断信号 |
| **BUG-E** Stream 与 non-stream 共用 HttpClient（blocker） | `OpenAiProvider.java:44-58` / `ClaudeProvider.java:46-58` 构造时一个 `OkHttpClient` 被 non-stream `chat()` 和 stream `chatStream()` 共享，readTimeout 对两者意义完全不同（non-stream=总耗时；stream=相邻 chunk idle）。修：构造两个 client，stream 用更长/无限 idle timeout（参考 `FeishuWsConnector:208` 的 `readTimeout(Duration.ZERO)` 用法）；修正 `application.yml:74` 注释 "non-stream chat only"（事实上 stream 也用）；修正 `application.yml:75-76` `max-retries` 注释（stream 不 apply 是现状，但 ConnectException 类可以 retry，见 BUG-E-bis）|
| **BUG-E-bis** ChatStream 对 ConnectException 短重试（blocker，从 3c0d05d9 直接触发） | 项目 footgun #3 "chatStream 不重试" 是针对**已推 delta** 的 SocketTimeoutException，但对 `ConnectException` / `SSLHandshakeException` / 握手前失败**不适用**——连接没建立，delta 根本没推，retry 安全。修：`OpenAiProvider.chatStream` / `ClaudeProvider.chatStream` 区分异常位置，握手前 retry 1-2 次（间隔 2-5s），握手后不 retry。VPN 抖动（1082 typical 30s 内自愈）期间就不会 fail 整个 agent loop |

</details>

---

### 🔒 安全修复 — Channel 配置明文存储（重要不紧急，暂缓）

> **代码扫描发现**：`ChannelConfigEntity` / `ChannelConfigService` 有明文 TODO 注释："AES-GCM 加密存储，目前为明文 JSON"。Channel 配置中存有飞书/Telegram 的 Bot Token、Secret 等敏感字段，当前全部明文写入数据库。这是安全上应该补的底座项，但当前单机/本地使用场景下属于重要不紧急，暂缓；等多端部署、共享环境或 P12 正式上线前再重评。

| 子任务 | 说明 |
| --- | --- |
| **SEC-1** Channel 配置 AES-GCM 加密 | `ChannelConfigService` 存储前加密敏感字段（appSecret / botToken / encryptKey）；读取时解密；密钥从环境变量注入；对已存在明文数据做一次性迁移脚本；约 1-2 天 |

---

### ~~🔒 SEC-2 Hook Source Protection + Agent-authored Hook Binding V1~~ ✅ 已完成（2026-04-25）

> **发现时间**：2026-04-23，讨论 "session 结束自动 memory 压缩" 类系统 hook 时发现。**当前 SkillForge 根本没有 "系统 hook" 概念**：`HookEntry` 无 `source` / `isSystem` 字段，`AgentService.updateAgent` 对 lifecycleHooks 只校验大小/语义（`AgentService.java:63-95`），收到 PUT 就无条件 `setLifecycleHooks()` 覆盖。前端 `JsonMode.tsx` 是个 TextArea，用户可以把 hooks 全删了保存。所以未来如果把系统 hook 当默认值塞进 `agent.lifecycleHooks`，**用户 curl 或 UI JSON 模式都能绕过删掉**，UI 屏蔽只是装饰。
>
> **范围调整（2026-04-25）**：V1 不收窄。除了 system hook 存储分离与保护，还要补齐 agent 可观测 / 可提交 hook 变更的闭环：提供查询当前 effective hooks 的 Tool，并新增 agent 提交 hook 绑定的 Tool。agent 可以选择目标 agent，但只能创建待审批变更，不能绕过审批直接让 hook 生效。
>
> **核心原则**：`t_agent.lifecycle_hooks` 继续只存 user hook；system hook 与 agent-authored hook 物理隔离，curl / JSON 模式删不掉。Dispatcher 只执行 system + user + `APPROVED` agent-authored hooks。UI 要把 system / agent-authored hook **显示但锁定**（灰卡 + source 徽章 + 审批状态），让用户知道后台在跑什么，保住透明度。
>
> **完成说明（2026-04-25）**：新增 `SystemHookRegistry` / `LifecycleHookCompositionService` / `EffectiveHook` runtime wrapper；新增 `t_agent_authored_hook` + Repository / Service / Approval API；新增 `GetAgentHooksTool` 与 `ProposeHookBindingTool`；Dispatcher 按 system → user → approved agent-authored 合并执行并记录 agent hook 成功/失败统计；前端 Agent Hooks tab 拆成 system / user / agent-authored 三段。收尾时补齐两条安全硬约束：builtin method target 默认不允许，必须配置 `lifecycle.hooks.agent-authored.allowed-builtin-methods`；审批 API 不再信任 body 里的 `reviewerUserId`。验证：`mvn -pl skillforge-server -Dtest=LifecycleHookDispatcherTest,LifecycleHookCompositionServiceTest,HookMethodResolverTest,AgentAuthoredHookControllerTest test` 27/27 通过；`npm run build` 通过。

| 子任务 | 说明 |
| --- | --- |
| ~~**SEC-2-1 三源存储与组合模型**~~ | ✅ **优先方案 A（存储分离）**：system hook 不存进 `agent.lifecycleHooks`，新增 `SystemHookRegistry`；user hook 仍只存在 `t_agent.lifecycle_hooks`；agent-authored hook 独立表存储。新增 `LifecycleHookCompositionService` 统一产出 effective hooks，Dispatcher / API / Tool / UI 共享同一组合逻辑，避免多套 source 判断漂移 |
| ~~**SEC-2-2 agent-authored hook 表与审批状态**~~ | ✅ 新增 `t_agent_authored_hook`：记录 `targetAgentId`、`authorAgentId`、`authorSessionId`、event、不可变 method target、displayName、reviewState(`PENDING/APPROVED/REJECTED/RETIRED`)、review note、parentHookId、enabled、success/failure count、created/updated。agent 写入强制 `PENDING`，只有审批动作能进入 `APPROVED` |
| ~~**SEC-2-3 Hook 查询 API + Tool**~~ | ✅ 新增面向 UI / Agent 共用的 effective hooks 查询能力：返回 system / user / agent-authored 三段，包含 source、sourceId、event、handler 摘要、displayName、async/failurePolicy、reviewState、author、最近执行统计。Tool 命名为 `GetAgentHooksTool` |
| ~~**SEC-2-4 Agent 提交绑定 Tool**~~ | ✅ 新增 agent 可调用的 hook 绑定提案 Tool `ProposeHookBindingTool`。输入支持 target agent id/name、event、不可变 method target、displayName、failurePolicy、async；服务端复用 agent name/visibility/permission 解析。普通 agent 只能创建 `PENDING`，不能 approve；跨 agent 绑定默认要求目标可见 + 权限通过 + 后续审批 |
| ~~**SEC-2-5 Dispatcher / Trace 执行语义**~~ | ✅ Dispatcher 合并顺序定为 system → user → approved agent-authored；`SKIP_CHAIN` 继续沿用现有链式语义，跨 source 生效；即使 user hook config 为 null，system / approved agent hook 也会执行；`LIFECYCLE_HOOK` span 写入 `hook.source` / `hook.source_id` / `hook.author_agent_id` runtime attributes；agent-authored hook 更新 success/failure 统计 |
| ~~**SEC-2-6 安全硬约束（Plan r1）**~~ | ✅ agent-authored hook V1 只允许绑定 active compiled method id/version/hash 或显式 allowlisted builtin；不接受通用 `HookEntry` / inline script / skill handler；`authorAgentId` 从 session 服务端解析，禁止 input 指定；审批 API 不注册为 Tool 且不信任 body reviewer identity。单用户 token 模型下仍不宣称抵抗恶意本地 agent，真正人/agent 权限隔离进入 P12 多用户/权限模型 |
| ~~**SEC-2-fe LifecycleHooksEditor 三源展示**~~ | ✅ Hooks tab 拆成 system / user / agent-authored 三段：system 只读；user 保持现有编辑能力；agent-authored 展示 pending/approved/rejected/retired 与审批操作。JSON 模式只编辑 user hook，system / agent-authored 永远隔离在编辑器之外 |
| ~~**SEC-2-test Full Pipeline 验证**~~ | ✅ 后端重点覆盖 registry、composition、dispatcher merge、null user config 仍 dispatch、trace source attributes、builtin allowlist、审批 body reviewer 不可信；前端构建通过。环境限制：本机全量后端测试会被 Mockito inline self-attach、Docker/Testcontainers、DNS/sysctl 沙箱问题干扰，已单独识别为环境型失败 |

#### 与其他 P 的关联
- **→ P15**：`GetAgentHooksTool` 属于自省工具范畴；实现归 SEC-2，避免后续 P15 再做一套 `GetAgentConfigTool` 的 hook 查询逻辑。
- **→ P11**：agent 选择目标 agent 时必须复用 AgentDiscovery / name resolver / visibility / 调用权限；不要在 hook tool 内重复实现 agent 查找与权限。
- **→ P4**：Code Agent 已有 ScriptMethod / CompiledMethod。Hook Binding Tool V1 只绑定不可变且已审批的 method target；`ScriptMethod` 因创建后立即 active，需等审批/版本化补齐后再允许 agent-authored hook 引用。
- **→ P12 / 多用户权限 design doc**：跨 agent 绑定会提前踩到身份与权限边界；V1 可先按单用户 + visibility 约束实现，但 Plan 阶段必须写清楚未来多用户迁移边界。
- **→ docs/design-sec2-hook-source-protection.md**：已同步为 V1 不收窄范围；开发前以该设计文档 + 本 ToDo 的 Plan r1 安全硬约束为准。

---

### ~~🧹 技术债 — SkillList.tsx 拆分~~ ✅ 已完成（2026-04-23）

> ~~`SkillList.tsx` 单文件 47K，远超 800 行上限。~~

| 子任务 | 说明 |
| --- | --- |
| ~~**DEBT-1**~~ SkillList.tsx 按功能拆分 | ✅ 1250 行拆成 11 个模块（types / utils / icons / FilterItem / SkillCard / SkillTable / SkillDrawer / NewSkillModal / SkillDraftPanel / SkillAbPanel / SkillEvolutionPanel），主文件降至 257 行；commit 0576213 |

---

### ⏸️ V2 推迟池

> 经 Analyst + Challenger 对抗评审后，以下项明确推迟至 V2 或等触发条件成立后重评。

| 项 | 推迟理由 | 重评触发条件 |
| --- | --- | --- |
| **P14 整体** | 无真实 τ-bench 级评测需求；UserSimulator 收敛难；pass^k token 账单高 | 真实业务场景需要多轮评测 |
| **P3-2** Memory Eval 模式 | 依赖 P14 基础设施 | P14-1~4 就位后 |
| **P3-4** 记忆质量闭环 | 依赖 P3-2 | P3-2 就位后 |
| **P15-3** GetEvalRunTool | Analyzer MVP 不需要；eval 数据量小，分析价值有限 | P15 MVP 验证有价值后 |
| **P15-6** 分析结果落库 + 前端 tab | 首版文本输出够用；工程量与首版价值不匹配 | P15 MVP 验证有价值后 |
| **P12-3** SystemJobRegistry | 首版保留 `@Scheduled` 注解不动 | 有 UI 可观测 system job 的真实需求 |
| **P12-6** 告警推送 + admin 权限分离 | 先让调度器跑起来 | 多端部署 / 多用户场景 |
| **P10-4** 自定义 slash command | 4 条命令覆盖 90% 用例 | 有明确自定义需求 |
| **P10-5 `/help`** | 4 条命令不需要 help menu | — |
| **P13-9** `/api/agents/*` rate limit | 单机 + token 认证下不是真实威胁 | 多端部署场景 |
| **P9-5** "活跃 skill 上下文 + pending tasks" 注入部分 | 文件摘要是核心，其他两项价值存疑；需 design doc 先行 | P9-4 上线收到数据后重评 |
| **thinking-mode V2 — Reasoning Effort allowClear null-save** | 当前 `<Select allowClear>` + null-filter merge 在 backend 是 silent preserve(plan §2 V2 推迟);UX 上 Save 按钮重新激活但实际不持久化,已 W1 known limitation | 有"清空"真实需求时 |
| **thinking-mode V2 — `resolveProvider` friendly error** | `AgentLoopEngine.resolveProvider` 当 agent 配 missing-key provider 时 silently fallback 到 default,用户不知用错模型;改成 throw friendly `IllegalStateException("provider X not configured, set ENV or pick another model")` | 任何 user-visible 模型混用导致体验问题 |
| **thinking-mode V2 — 跨 Provider 切换历史消息兼容** | 2026-04-26 真实切换 qwen3.5 → deepseek-v4 后，DeepSeek 返回 `Duplicate value for 'tool_call_id' of in message[4]`；初步判断是历史 Qwen 阶段 tool/result 消息里存在空/重复/旧形态 `tool_call_id`，Qwen 容忍但 DeepSeek 严格拒绝。先记录不改代码；后续需 Full Pipeline 评估在 `OpenAiProvider` 翻译层、session 边界或 provider switch 时做唯一性校验 / 降级 replay，并确保不绕过 BUG-F 已修复的摘要语义 | 再次出现跨模型切换导致 OpenAI-compatible payload 400，或准备做 session 级 `/model` |
| **thinking-mode V2 — Embedding provider fail-fast** | Plan §6 W5,本次 commit 漏改;当前 `EmbeddingService` 构造器空 key 也是静默继续,与 LLM provider 不一致 | Embedding 真用了导致 401 时 |
| **thinking-mode V2 — `reasoning_effort=max` 真机验证** | Plan §10 W7,文档说 `xhigh→max`,但本次只用 high curl 验证过,max 走文档 | deepseek-v4 高 effort 真实业务场景 |

---

### ~~P4 — 编码 Agent（Code Agent）~~ ✅ 已完成

> ~~目标：创建一个能编码的 Agent，通过绑定代码类 Skill 实现自主编写 Hook 方法，提升 Agent 自身能力（自举闭环）~~

已移至「已完成」表格。

---

### ~~P5 — 前端体验优化~~ ✅ 已完成

> ~~目标：修复当前 Dashboard 的交互痛点，补全缺失功能，提升整体使用体验~~

已移至「已完成」表格。

---

## 已完成

| 任务                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | 完成日期                                                                                                                                                                                                                                                                                                                    |            |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------- |
| P15-4 Agent 配置自省 + UpdateAgent：新增 `GetAgentConfigTool` 返回可见目标 agent 的 prompts、skills/tools、config、behaviorRules、user hook JSON、execution/thinking/reasoning/maxLoops 等完整配置；`AgentDiscovery` 从纯发现扩展到 model/role/status/config/rules/thinking/reasoning/maxLoops 与 prompt/hook presence；新增 `UpdateAgentTool`，走一次性 approval token 确认后才允许普通配置 patch 与结构化 `hookChanges` 同事务提交。SEC-2 边界：禁止直接写 agent-authored approved hooks，agent-authored 变更只能经 `AgentAuthoredHookService.propose` 创建 PENDING 提案；新增 `AgentMutationService` 事务封装与 editable target resolver。验证：`mvn -pl skillforge-server -am -DskipTests compile` 通过；`UpdateAgentToolTest` + `AgentLoopEngineInstallConfirmationTest` targeted tests 16/16 通过；server 8080 重启后确认 `GetAgentConfigTool` / `UpdateAgentTool` 已注册 | 2026-04-26 | |
| Agent 管理与内置模板 SQL 化收口：新增 `CreateAgent` Tool（需人工确认 + 一次性 approval token，未审批直接调用 fail-closed；Web/飞书确认卡适配；commit `e22609c`）；内置 Agent 模板统一改为 Flyway SQL seed（`V26__seed_code_agent.sql`、`V27__seed_main_assistant.sql`，删除 `CodeAgentInitializer` / `DefaultAgentInitializer`，commit `3243759`）；补主 Agent leader 配置（`V28__main_assistant_leader_config.sql`：`role=leader`、`maxLoops=50`、主控 config、保守补 behavior rules，commit `d9d82a3`）。验证：CreateAgent 相关 targeted tests + 418 non-IT server tests 通过；本地 Flyway 已应用至 v28，server 8080 重启成功 | 2026-04-26 | |
| 🔒 SEC-2 Hook Source Protection + Agent-authored Hook Binding V1：三源存储分离（system registry / user JSON / agent-authored 独立表）+ `LifecycleHookCompositionService` effective hook 合并；Dispatcher 按 system → user → approved agent-authored 执行，null user config 仍会执行 system/agent hooks，并记录 agent hook usage/success/failure；新增 `GET /api/agents/{id}/hooks`、`PUT /api/agents/{id}/hooks/user`、`/api/agent-authored-hooks/*` 审批 API；新增 `GetAgentHooksTool` + `ProposeHookBindingTool`，agent 只能创建 `PENDING` 绑定，`authorAgentId` 服务端从 session 解析；V24 新增 `t_agent_authored_hook`；前端 Agent Hooks tab 拆成 system/user/agent-authored 三段。收尾修复：builtin method target 默认禁用，必须配置 `lifecycle.hooks.agent-authored.allowed-builtin-methods`；审批 API 不信任 body `reviewerUserId`。验证：SEC-2 相关后端测试 27/27 通过，frontend build 通过；全量后端测试受本机 Mockito inline attach / Docker / DNS / sysctl 环境限制，不作为业务失败结论 | 2026-04-25 | |
| 🧠 thinking-mode-v1（per-agent thinkingMode + reasoningEffort + provider 协议感知,commit `55969db`）：新增 `ProviderProtocolFamily` 7-family enum + `ProviderProtocolFamilyResolver`(longest-prefix-first + date-suffix strip + V4-reasoner guard);`ThinkingMode`/`ReasoningEffort` enum(`@JsonCreator` unknown→AUTO);`OpenAiProvider.buildRequestBody` 按 family 顶层分派(qwen=`enable_thinking` / deepseek-v4=`thinking.type`+`reasoning_effort`),`resolveReplayReasoningContent` helper(V22 simple-text emit 保留,仅 stored 空 + tool_calls + family.requiresReplay 时 emit "");构造器 fail-fast(空 api-key + envVarName hint)+ legacy ctor `@Deprecated` shim;6 处 `"OpenAI API error"` 硬编码→`providerDisplayName`(qwen→bailian / deepseek 不再误导);`AgentEntity` +2 列 + V23 migration(VARCHAR 16 + CHECK,PG+H2 兼容);`AgentYamlMapper` round-trip "auto" filter;`LlmModelsController.ModelOption` 扩 supportsThinking/supportsReasoningEffort/protocolFamily;Phase 4 hotfix `SkillForgeConfig.llmProviderFactory` try-catch 跳过未配置 provider(服务能起);Frontend `AgentDrawer` Overview 加 Thinking Mode + Reasoning Effort 下拉 + 按 model.supports* 动态 enable/disable;真机 curl 实测推翻 brief 的 `extra_body` 嵌套假设(SDK 字段被解包顶层,raw HTTP 直接顶层)+ D5 兜底统一发 `""`(deepseek 强约束 + qwen 接受);9 新单测 + 13 vitest;**Full Pipeline**:Plan r1 PASS,Review r1 FAIL(B1 catch dev 把 §7.8 写成 `expect(updateAgent).not.toHaveBeenCalled()` smoke 的"false CI confidence"反模式)→ r2 PASS;Phase 4 verify catch fail-fast regression 并 hotfix。后端 357/357 + frontend 13/13 全绿,tsc 0 new error,Live API `/api/llm/models` 返回新字段,错误信息从 "OpenAI" 变 "deepseek" 的 DX 改进现场验证 | 2026-04-25 | |
| fix(session-store) 持久化 `Message.reasoning_content`：OpenAI 兼容 provider（DashScope/Qwen/DeepSeek）thinking 模式下，assistant 带 tool_use 时下一轮必须原样回传 `reasoning_content`，否则 API 返回 HTTP 400；in-memory 链路已 OK（`OpenAiProvider` SSE 累积 → `LlmResponse` → `AgentLoopEngine.buildAssistantMessage`），但持久化 / 重载丢失。**V22 migration** 加 `t_session_message.reasoning_content TEXT NULL` 列；`SessionMessageEntity` 加 `reasoningContent` 字段（`@Column(name="reasoning_content", columnDefinition="TEXT")` + getter/setter）；`SessionService.appendRowsOnce` + `toStoredMessages` 两处单行补全读/写（所有 write / rewrite / compaction 收敛到 `appendRowsOnce`，改一处覆盖全部）；新增 `SessionServiceReasoningContentIT`（4 个 case：NORMAL with / NORMAL without / mixed batch / SUMMARY-via-appendRowsOnce round-trip）。Full Pipeline 跑通（Planner + Plan-Reviewer PASS，0 blocker / 5 warning / 3 nit → W1/W2 折进 Dev；Backend Reviewer PASS，0 blocker / 2 warning / 3 nit）。Phase 4 verify：`mvn -pl skillforge-server compile` 通过；310/310 non-IT 测试绿；Docker daemon 不可用跳过 IT（既有环境问题，`SessionRepositoryIT` 同症）；重启 server（zonky embedded PG 14.10）Flyway 成功应用 V22 + Hibernate `ddl-auto: validate` 通过 | 2026-04-24 | |
| 🔥 引擎稳定性（Compact Breaker 误触 + LLM Stream 抗抖，commit `121e8dc`）：**BUG-A** performed=false 不再累加 breaker（cec1cd60 根因），+ 额外修复 `FullCompactStrategy.callLlm` 吞异常 root cause（plan 漏的，Phase 3 reviewer 发现）；**BUG-B** 60s time-based half-open + user turn 入口无条件 `resetCompactFailures()`（LoopContext `compactBreakerOpenedAt` 字段 + `AgentLoopEngine.recordCompactFailure` helper）；**BUG-C** 5 处 compact-path catch 从 `log.warn(e.getMessage())` 升到 `log.error(msg, e)` 带 stacktrace（engine 3 处 + max_tokens compact + CompactionService broadcastUpdated）；**BUG-D** `LlmStreamHandler.onWarning(key,value)` default method + `TraceSpan.attributes` Map + OpenAiProvider SSE tool_use parse fail 发 4 个 warning key → engine 聚合写 LLM_CALL span attributes + 带 sessionId 的 log.warn；**BUG-E** OpenAiProvider/ClaudeProvider 构造器拆分 `httpClient`（non-stream）/ `streamHttpClient`（stream）两个 OkHttpClient；**BUG-E-bis** `chatStream` 握手前 ConnectException/SSLHandshakeException 短重试（2 次 / 2s→5s 退避 / ±20% jitter / `Call.execute()` 返回后一律不 retry 防 delta 重复）；application.yml 注释修正。Full Pipeline 运行（planner/reviewer agent 响应延迟 1h+ 导致主会话接手实施 + self-judge；但 reviewer round 1 仍发现 plan 和主会话都漏的 `callLlm` 吞异常深层 bug）。新增测试 12 个（AgentLoopEngineBreakerTest 9 + ProviderHttpClientSplitTest 2 + CompactionServiceTest rethrow 1）；mvn test -Dtest='!*IT' 364 / 0 failures | 2026-04-24 | |
| P8 记忆 LLM 提取（P8-1~P8-4 全部完成）：新增 `LlmMemoryExtractor` 组件，LLM 分析 session 对话历史输出结构化记忆条目（type/title/content/importance）；`ExtractedMemoryEntry` record 解析 LLM JSON 输出（含 markdown fence 剥离 + 类型/重要性校验）；`MemoryProperties` 配置类（`extraction-mode: rule\|llm`、`extraction-provider`、`max-conversation-chars`）；`SessionDigestExtractor` 按模式分流 + LLM 失败自动降级到规则式提取；prompt 模板含 5 类记忆分类（knowledge/preference/feedback/project/reference）+ 3 级重要性 + 已有记忆标题去重上下文；importance 编码在 tags（`auto-extract,llm,importance:high`）免 migration；19 个单元测试全绿（JDK proxy + 手动 stub 绕过 Java 25 Mockito 限制） | 2026-04-21 | |
| Session 批量删除：`DELETE /api/chat/sessions/{id}`（单删）+ `DELETE /api/chat/sessions` body `{ids}`（批删，上限 100）；悲观锁（SELECT FOR UPDATE）防 TOCTOU、WS 广播移到 AFTER_COMMIT 防幽灵通知、删前 null 子 session parentSessionId 防悬空引用、findAllById 消除 N+1；前端 SessionList 新增复选框列 + 批量操作栏（slide-in 动画）+ 行 hover 删除按钮；skipped running 会话保持选中状态；Full Pipeline（2 dev + 2 reviewer + judge） | 2026-04-21 | |
| Session 来源渠道可视化：`SessionEntity` 加 `@Transient channelPlatform` + `ChannelConversationRepository.findBySessionIdIn` 批量注入（active 行优先，default "web"）；前端新增 `ChannelBadge` 组件（chip/dot 两态），Chat crumb 展示渠道徽章（depth 0 自动隐藏）、ChatSidebar 非 web 行加色点、SessionList 新增 Channel 列 + 侧栏过滤器 | 2026-04-21 | |
| Context breakdown API + 实时 token 面板：新增 `GET /api/chat/sessions/{id}/context-breakdown` 走 `SystemPromptBuilder` + `TokenEstimator` 估算当前 context 各段占用；返回层级化 segments（system prompt 子段：agent prompt/SOUL/TOOLS.md/skills list/behavior rules/env/session context/user memories；tool schemas JSON；messages：conversation text/tool calls/memory results/other tool results）；分母用 `ModelConfig.lookupKnownContextWindow` 解析 agent 实际模型窗口；`MemoryService.previewMemoriesForPrompt` 分离出不 bump recall counts 的估算路径；Chat 右栏 Context tab 重做（堆叠条 + 可展开分段列表 + ±10% 精度说明），取代之前误导性的"累计消耗当当前窗口"卡片；reviewer 2 pass（java-reviewer + typescript-reviewer） → 应用 HIGH/MEDIUM 修复（Map/ContentBlock 双形状适配、ObjectMapper 化 tool_use input/tool_result content、aria-controls、role="img"、config null-safe）+ 低优先级修复（@Transactional readOnly、controller 传入已读 session 避免二次查、debug log 仅 class name、CSS var fallback、API 边界 null→undefined 归一化） | 2026-04-21 | |
| Session 详情 drawer 整体打磨：① Turns tab 重做为气泡风格（`U`/`A` 头像 + user 右 agent 左 + 圆角气泡 + tool 调用集成为带状态卡片，error 态红色，tool_result-only 的 user 消息收编到调用方不再独立显示）；② Context tab 接入 `/context-breakdown` 真实 API，取代硬编码 8.2K/12.4K + 391K/200K 的误导数据（详见 bug #22）；③ drawer title / tabs / body 三行左缘对齐（`.sf-drawer-tabs` padding 20→12，跨 Sessions/Eval/Skills/Hooks/Tools/Memory 全部受益）；④ `.sess-row` 加 `box-sizing: border-box` 修复 Tokens/Context/Cost/Last 列和 header 错位 | 2026-04-21 | |
| P7 飞书 WebSocket 长连接模式（P7-1~P7-7 全部完成，P7-8 E2E 验证已完成）：ChannelPushConnector SPI（`channel/spi`）+ FeishuWsConnector（OkHttp + ping/pong + 构造器注入 FeishuClient）+ FeishuWsEventDispatcher（WS 帧解析 → FeishuEventParser → ChannelSessionRouter + service_id ACK）+ FeishuWsReconnectPolicy（指数退避 1s→64s + ±20% jitter）+ ChannelPushManager（SmartLifecycle phase=DEFAULT_PHASE+100，start 扫 DB 建连，stop 最多等 3s）+ configJson mode 字段（websocket/webhook，patch 时返回 restart warn）+ 前端 ChannelConfigDrawer mode 切换 + websocket 重启提示；fix 提交加固 ping/pong + ACK 头保留 + ChannelPushManager 重启安全；新增 FeishuWsEventDispatcherTest + ChannelPushManagerTest 回归测试；技术方案：docs/design-feishu-websocket.md | 2026-04-21 | |
| P6 消息行存储重构（P6-1~P6-6 全部完成，P6-7 V2 预留）：核心不变量消息只增不删；V18 migration 新建 `t_session_message`（seq_no/role/content_json/msg_type/metadata_json）+ SessionMessageEntity + SessionMessageRepository；SessionService 新增 getFullHistory/getContextMessages/appendMessages 三方法（LLM 三段拼接：youngGen + summary + 新消息）；CompactionService 改为 INSERT COMPACT_BOUNDARY + SUMMARY 行不删旧消息；ChatService 改用 getContextMessages + appendMessages；SessionMessageStoreBackfill 历史数据迁移；前端 useChatMessages.ts 适配 COMPACT_BOUNDARY 渲染分隔线；CheckpointModal + branch/restore API；rollout toggle 脚本；技术方案：docs/design-p6-session-message-storage.md | 2026-04-21 | |
| P2 多平台消息网关（飞书 + Telegram，可扩展至微信/Discord/Slack/iMessage）：ChannelAdapter SPI（List<ChannelAdapter> Spring 自动收集，新平台零框架改动）+ V17 migration（5 张表：t_channel_config/conversation/message_dedup/user_identity_mapping/delivery）+ 3-phase 交付事务（claimBatch SKIP LOCKED + IN_FLIGHT 首次直接入库防 30s race）+ ChannelTurnContext 多轮回复关联（per-turn platformMessageId 防 unique constraint 冲突）+ MockChannelAdapter @Profile(dev,test) CI 友好 + 飞书 SHA-256 签名验证（encryptKey，非 HMAC）+ Telegram HTML parse mode + 4096 codepoint 安全拆分 + ChannelConversationResolver 独立 @Service 解决 @Transactional self-invocation bypass（HIGH-1）+ DeliveryTransactionHelper @Service 解决相同问题 + 前端 /channels 页面（平台卡片 + 会话列表 + 投递重试面板）+ 升级路径记录于 docs/p2-channel-plan-b.md §12.3；Full Pipeline（2 planner + 2 reviewer 多轮 + judge 裁判） | 2026-04-20 | |
| P1 Skill 自动生成 + 自进化（P1-1~P1-4 全部完成）：Session → SkillEntity 自动提取（LLM 识别可复用模式，批处理模式）+ Skill 版本管理（version/parentSkillId/usageCount/successRate 字段 + 版本回滚）+ Skill A/B 测试（复用 AbEvalPipeline，held-out 场景集对比，Δ≥15pp 自动晋升）+ 进化闭环（使用信号采集 → LLM 优化 SKILL.md prompt → A/B 验证 → 自动晋升/回滚） | 2026-04-20 | |
| P4 Code Agent（Phase 1-3 全部完成）：**混合 Hook Method 体系** — ScriptMethod（bash/node 脚本，即时生效）+ CompiledMethod（Java 类，需编译+人工审批）；Phase 1 CodeSandboxSkill（隔离沙箱执行 + HOME 环境变量沙箱化 + DangerousCommandChecker）+ CodeReviewSkill + ScriptMethod CRUD + BuiltInMethodRegistry 可变化 + V10 migration；Phase 2 DynamicMethodCompiler（javax.tools 进程内编译 + FORBIDDEN_PATTERNS 安全扫描）+ GeneratedMethodClassLoader（child-first 隔离）+ CompiledMethodService（submit→compile→approve 审批流）+ V11 migration；Phase 3 Code Agent Flyway seed（9-skill pack 种子模板，`INSERT ... WHERE NOT EXISTS`，与 Design Agent 保持一致）+ HookMethods.tsx 前端页面（双 Tab script/compiled + grid/table + detail drawer + approval 操作）+ typed API functions；Review 修复：Instant 时间字段、TIMESTAMPTZ、@Lob 移除、registry/DB 原子性、FORBIDDEN_PATTERNS 扩充、temp path 脱敏、错误信息安全化、ARIA 无障碍、delete 确认弹窗 | 2026-04-19 | |
| P5 前端体验优化（Phase 1-6 全部完成）：**整体交互重构** — 从 Ant Design 默认样式迁移到自研设计系统（CSS custom properties token 字典 + feature-scoped CSS modules），参考 Linear/Raycast 开发者工具风格；全部 10 个页面重写（Skills/Tools/Traces/Sessions/Evals/Memory/Usage/Agents/Chat/Teams）；Phase 1 chat compaction banner + session 分页 + i18n；Phase 2 token 字典 + ThemeContext 暗色模式；Phase 3 用户输入样式 + Agent UX + empty states + loading/error；Phase 4 响应式侧边栏 + Traces 颜色编码 + a11y；Phase 5 Layout 重构 + ActivityRail + CmdK 命令面板；Phase 6 Traces BFS span 遍历 + input 预览标题 + Session drawer 真实消息 + Skills system/custom 分类 + Agent hook handler 类型感知自动补全 + Tool 描述截断 + Hook tab 配置扩展 + Chat RightRail SubAgent tab + 顶部导航 Memory 标签修正 | 2026-04-19 | |
| N3 P2 Lifecycle Hook Method 体系：BuiltInMethod 接口 + BuiltInMethodRegistry（HttpPost/FeishuNotify/LogToFile 三个内置方法）+ MethodHandlerRunner（arg merging）+ UrlValidator（InetAddress SSRF 防护 + IPv6/link-local 拦截）+ 静态 HttpClient 防线程池泄漏 + header injection denylist + 异常信息脱敏 + ConcurrentHashMap 文件锁 + LifecycleHookService 抽取（dry-run + hook-history）+ HookHistoryDto DTO 投影 + 前端 MethodHandlerFields（方法下拉 + args 表单 + loading 状态穿透）+ DryRunResultModal + HookHistoryPanel 时间线 + Traces LIFECYCLE_HOOK 可视化 + API 类型安全（消灭 4 个 any）+ getLifecycleHookMethods 响应解析 bug 修复 + React key 去重 + 18 项 review fix；Full Pipeline（2 reviewer + judge + fix）；202 后端测试全绿 | 2026-04-17 | |
| N3 P1 Lifecycle Hook 完善：多 entry 链式执行（dispatcher for 循环 + ChainDecision 三值 + SKIP_CHAIN policy）+ ScriptHandlerRunner（bash/node 子进程 + /tmp toRealPath() symlink 防护 + ProcessHandle.descendants 进程树 kill + 双线程 drain-and-discard 防 OOM + env 5 白名单 + DangerousCommandChecker 绝对路径 pipe 防护）+ UserPromptSubmit prompt enrichment（handler output.injected_context 注入独立 user message 支持全 provider）+ 前端多 entry UI（上移/下移/删除/新增 cap 10 + stable \_id key 防 stale debounce）+ Script handler 启用（Confirm Modal + lang 硬编码 [bash,node] + 字符计数）+ Forbidden Skill 黑名单（dispatcher 层）+ async×SKIP_CHAIN 保存时拒绝 + scriptBody 长度后端校验 + FailurePolicy @JsonCreator 防回滚炸 + discriminated-union 类型安全 + CSS var(--color-error) + 全 provider 支持；2 planner + 2 reviewer + judge + 2 fix pipeline；145 后端测试 + agent-browser e2e 全绿；技术方案：docs/design-n3-p1.md | 2026-04-17 | |
| N3 P0 用户可配置 Lifecycle Hook：V9 migration + polymorphic HookHandler (skill/script/method 子类，P0 只实现 SkillHandlerRunner) + LifecycleHookDispatcher（hookDepth ThreadLocal 跨 worker 线程传播 + 独立 hookExecutor + timeout + failurePolicy CONTINUE/ABORT + LIFECYCLE_HOOK TraceSpan）+ SessionStart 插在 ChatService.chatAsync 首条消息处 / SessionEnd 异步在 loop 结束 + AgentLoopEngine ABORT 语义 (LoopContext.abortedByHook + HookAbortException) + REST `/api/lifecycle-hooks/events\|presets` + 前端 3 模式编辑器（Preset/Form/JSON + Zod discriminatedUnion + useDebouncedCallback + formKey 解决 create→create 复用）+ AgentSchema 加 lifecycleHooks/behaviorRules 字段防 Zod strip + 25 测试全绿 + agent-browser e2e 验证通过；3 轮 reviewer (java/typescript/security) + judge 仲裁 + fix 两侧并行；技术方案：docs/design-n3-lifecycle-hooks.md | 2026-04-17 | |
| N2-1~N2-3 Agent 行为规范层：V8 Flyway migration（behaviorRules TEXT 列）、BehaviorRuleRegistry（15 条内置规则 JSON + 语言检测 + deprecated 链 + 预设模板）、BehaviorRuleDefinition record、SystemPromptBuilder 注入（Available Skills 之后 + 自定义规则 XML 沙箱 + prompt injection 防护）、AgentYamlMapper round-trip（corrupt data 防御）、REST API（GET /api/behavior-rules + /presets）、前端 BehaviorRulesEditor（模板选择器 + 分类折叠 + Switch + 自定义规则 CRUD）、useBehaviorRules Hook、AgentList.tsx RULES.md Tab 集成；技术方案：docs/design-n2-behavioral-rules.md | 2026-04-17 | |
| N1-1~N1-4 Memory 向量检索：V7 Flyway migration（pgvector + tsvector，graceful fallback）、EmbeddingProvider + OpenAiEmbeddingProvider（/v1/embeddings）、EmbeddingService（降级）、MemoryEmbeddingWorker（@Async afterCommit）、MemorySearchSkill（FTS + pgvector RRF 混合检索）、MemoryDetailSkill（按需全文）、VectorUtils/SkillInputUtils 工具类提取；MemorySkill 移除 search action；Full Pipeline 审查修复：pgvector graceful degradation、afterCommit race fix、no-op sentinel bean、error sanitization、dimension validation | 2026-04-16 | |
| P2-6 Session → Scenario 转换：LLM 批量分析历史 session → draft scenarios → `t_eval_scenario`（V5 migration）；ScenarioLoader 同时加载 DB active 记录；ScenarioDraftPanel Review UI（Approve/Edit/Discard）；ScenarioLoader 改造 | 2026-04-16 | |
| P2-1~P2-5 Self-Improve Pipeline Phase 2：PromptVersionEntity（V4 migration）、PromptImproverService（LLM 生成候选 prompt）、AbEvalPipeline（held-out 集 A/B 对比）、PromptPromotionService（Δ≥15pp 自动晋升 + 4 层 Goodhart 防护）、前端 ImprovePromptButton + PromptHistoryPanel + rollback/resume | 2026-04-16 | |
| #5/#6 Self-Improve Pipeline Phase 1 实现：13 个场景 JSON（7 seed_ + 6 train_）；EvalExecutorConfig + evalOrchestratorExecutor（双独立线程池防死锁）；AttributionEngine（7×5 矩阵）；EvalRunEntity + EvalSessionEntity + V3 Flyway；SandboxSkillRegistryFactory + EvalEngineFactory（无 compactorCallback/pendingAskRegistry）；ScenarioRunnerSkill（3级重试 90s 预算）；EvalJudgeSkill（2×Haiku + Sonnet meta）；EvalOrchestrator（Goodhart 防护 + Δ 监控）；REST API POST/GET /api/eval/runs；/eval 前端页面（实时 WS + 详情 Drawer）；Full Pipeline 评审修复：executor 死锁、rate limit ghost run、maxLoops 覆盖、5 个字段名错误 | 2026-04-16 | |
| #5/#6 Self-Improve Pipeline 完整方案设计（Plan A + Plan B + 双 Reviewer + Judge 全流程）；详见 docs/design-self-improve-pipeline.md | 2026-04-16 | |
| Collab-3 CollabRun WS 广播：ChatWebSocketHandler 注入 repo 查 userId，4 个 collab 事件改写为 userEvent 广播；Teams.tsx 订阅 /ws/users/1 实时 invalidate TanStack Query | 2026-04-16 | |
| Compact-2 CompactionService 解锁 LLM 调用：3-phase split — Phase 1 guard/prepareCompact (stripe lock) → Phase 2 applyPrepared (LLM, no lock) → Phase 3 persist (stripe lock + tx)；fullCompactInFlight Set 防并发重入 | 2026-04-15 | |
| Context-1 动态 Context Window：ModelConfig 静态模型表 + CompactionService 3 级解析链，废弃硬编码 32000 | 2026-04-15 | |
| #9 认证鉴权 MVP：auto-token on startup，Login 页自动预填，Bearer 拦截器 + WS 握手鉴权 | 2026-04-15 | |
| #20 API 响应格式统一 + extractList 防御代码集中 | 2026-04-15 | |
| #19 前端测试基础建设：Vitest 单元测试 + Playwright E2E | 2026-04-15 | |
| #18 前端安全加固：Zod schema 校验 + XSS/CSRF 防护 | 2026-04-15 | |
| #17 后端集成测试：Testcontainers 覆盖核心 Repository | 2026-04-15 | |
| #16 后端 Java 现代化：DTO 改用 records | 2026-04-15 | |
| #15 Chat 交互状态升级：hover / focus / active / loading | 2026-04-15 | |
| #14 Dashboard 视觉升级 | 2026-04-15 | |
| #13 长列表虚拟滚动：Traces / Sessions / Teams | 2026-04-15 | |
| #12 前端引入 TanStack Query 替换手动 useEffect | 2026-04-15 | |
| #11 Chat.tsx 重构：拆分 28 个 useState | 2026-04-15 | |
| #10 Context Window Token 按 Provider 动态配置 → 合并入 P1-1 | 2026-04-15 | |
| Teams 页面（多 Agent 协作可观测 UI） | 2026-04-15 | |
| ObjectMapper 全项目修复（10 处补 JavaTimeModule） | 2026-04-15 | |
| @Transactional 修复（CollabRunService、SubAgentRunSweeper） | 2026-04-15 | |
| H2 → PostgreSQL 迁移（embedded zonky PG，port 15432） | 2026-04-15 | |
| EmbeddedPostgresConfig 重启修复（setCleanDataDirectory） | 2026-04-15 | |
| Flyway schema 管理（ddl-auto: validate） | 2026-04-15 | |
| .claude/ 规则/Agent/命令集成（ECC + design.md） | 2026-04-15 | |
| #4 Commit 所有积压改动 | 2026-04-15 | |
| #8 Chat 稳定性：ErrorBoundary + WS 断线重连（指数退避） | 2026-04-15 | |
| #7 Memory 调度：session 完成后立即异步提取，补 @EnableAsync | 2026-04-15 | |
| P13-2 Hook 编辑器重写（schema-aware）：AgentDrawer Hooks tab 接入 LifecycleHooksEditor + useLifecycleHooks；discriminated union + entry-level 字段全部恢复；liveRawJson + baselineJson 准确 dirty 检测；migrateLegacyFlat 处理老拍平 JSON；38 tests、浏览器 e2e 验证 PUT round-trip 稳定 | 2026-04-22 | |
| P13-5/7/8/10/11 follow-up batch：TS 错修复 / AgentNotFoundException / updateAgent 审计日志 / 后端驱动 model options / Tool-Skill 语义对齐 + Role 可编辑 + 多模型扩展 | 2026-04-22 | |
| P9-1 Compactable 工具白名单：CompactableToolRegistry（默认白名单 11 个工具）+ LightCompactStrategy Rule 1 白名单过滤 + per-agent config 支持 + 25 个测试全绿 | 2026-04-22 | |
| P9-3 Time-based 冷清理：TimeBasedColdCleanup + LoopContext.sessionIdleSeconds + AgentLoopEngine 首次迭代触发；默认 5 分钟阈值 / 保留最近 5 个；12 个测试全绿 | 2026-04-22 | |
| P9-6 Session Memory 零 LLM 压缩：SessionMemoryCompactStrategy（零 LLM）+ CompactionService Phase 1.5 优先尝试 memory compact，失败降级 LLM；8 个测试全绿 | 2026-04-22 | |
