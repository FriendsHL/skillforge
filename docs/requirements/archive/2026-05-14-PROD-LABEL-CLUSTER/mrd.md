# PROD-LABEL-CLUSTER MRD

---
id: PROD-LABEL-CLUSTER
status: ratified
owner: youren
created: 2026-05-14
updated: 2026-05-14
---

## 用户原始诉求

> "现在的数据飞轮应该是 我们将 agent 的 session 信息进行打标（llm or agent），打标之后会用归因分析 agent 进行打标结果和 session 清洗之后的数据进行分析，得到需要迭代某一项的结论，然后进行这一项优化。然后重新跑一次这个流程证明效果比之前的好，然后准许上线。"

> 注：用户原话用"打标"，本文档后文统一采用"标注"作为正式术语（agent 名 `session-annotator`），两者语义等同。

V1 解决"飞轮起点": **生产 session 怎么被结构化记录失败原因 + 怎么跨 session 看到失败规律**。后续 V2 V3 才接归因 agent + 灰度验证。

## 业务痛点

SkillForge 已有 EVAL-V2（M0-M6）整套评测能力，但当前**所有 attribution 信号都困在 eval 框架里**：

- `EvalTaskEntity.FailureAttribution` enum 只在 eval 任务上标注，**真实生产 session 没有任何结构化失败标签**
- `EvalAnalysisSessionEntity` 是 per-task chat session，不存 pattern，**无法跨 session 聚合"30 个 session 都因为同一根因失败"**
- Smart Import 已有 6 类 reason 检测（agent_error / tool_failure / span_error / high_token / multi_turn / has_tool_calls），但**只在用户手动点 "Add to dataset" 时才用一次，结果没有持久化到生产 session 上**

结果：

- 用户想知道"过去一周哪些 skill 最经常翻车" → 没法回答
- 想知道"是 prompt 不行 / skill 写错 / tool 输出歧义" → 只能挨条点 session 看 trace
- 想自动化飞轮 → 没有"飞轮起点"信号

## V1 目标

**让 SkillForge 自己能回答这两个问题**：

1. 过去 N 天哪些 production session 失败了，每条失败的根因怀疑落在哪个 surface（skill / prompt / behavior_rule / 其他）
2. 这些失败能不能聚成几类典型 pattern？每类多大体量？涉及哪些 agent / 哪些 tool？

回答这两个问题 = V1 成功。**不**回答"该怎么改" —— 那是 V3 attribution agent 的事。

## 用户角色 + 使用场景

**主用户**：SkillForge 平台拥有者（dogfood 阶段 = youren 本人）

**典型使用流**：

1. 用户用 SkillForge 跑日常工作（任意 agent / 任意 skill）→ 产生 t_session 记录
2. 后台 cron hourly 跑：
   - 信号通道：从 trace/span 派生 signal label（agent_error / tool_failure 等）
   - LLM 通道：派一个 `session-annotator` agent 看每条新完成的 session，输出 outcome（success/partial/fail/cancelled）+ 怀疑 surface
3. 标签写入 `t_session_annotation`，多标签共存
4. 第二个 cron hourly 跑聚类：按 `(outcome × suspect_surface × tool × agent)` 简单 bucket，≥ 3 个 member 入 `t_session_pattern`
5. 用户进 dashboard `/insights/patterns`：
   - 看失败 pattern 列表（按 size 降序，含 first_seen / suggested_surface chips）
   - 点开某个 pattern → 看 member session 列表（链 trace 详情）
   - 抽样确认这个 pattern 真的是 cluster 不是噪音

## 非目标

- 不解决"该改成什么样" —— V3 attribution agent
- 不解决"改完后是不是真好" —— V2 灰度 + 生产指标回流
- 不做人工标签修正 UI —— V3 跟 optimization event 一起做
- 不做实时标注 —— hourly batch 即可
- 不做 ML 聚类 / embedding 相似度 —— V5+ 才评估
- 不做 multi-tenant 隔离 —— 单租户 dogfood 够用

## 成功指标

- 跑 1 周生产数据，dashboard 至少能展示 5+ pattern（≥ 3 member）
- LLM outcome label 人工 spot-check 准确率 > 70%（采样 20 条核对）
- Smart Import / EVAL-V2 现有流程零退化
- 用户能从 pattern 点回 trace 详情（数据贯通）
- V1 实际跑一周后，用户能基于 pattern 列表判断"V3 attribution agent 该聚焦哪几个 pattern 类型先做"

## 与 backlog 关系

- supersedes / refines 部分 `EVAL-DYNAMIC-USER-SIM` Phase 2/3 中"session 业务场景抽取"的前置基础设施
- 跟 `SKILL-EXTRACT-AND-AB-VIA-AGENT` backlog 共用 agent-driven pattern（session-annotator 跟未来 skill-extractor agent 同模式），V3 时统一抽象
- 跟 `EVAL-PARALLEL-SCENARIO` backlog 无关（那是 eval orchestrator 并发，本包不动 eval 路径）
