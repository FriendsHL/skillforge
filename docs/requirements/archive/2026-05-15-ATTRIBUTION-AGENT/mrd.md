# ATTRIBUTION-AGENT MRD

---
id: ATTRIBUTION-AGENT
status: ratified
owner: youren
created: 2026-05-15
updated: 2026-05-15
---

## 用户原始诉求

V1 + V2 落地后剩下"归因"这一公里：

> "归因分析 agent：基于打标聚类输出'该优化哪一项 / 改成什么'的结论"
>
> 引自 [PROD-OPTIMIZATION-FLYWHEEL plan §一、问题陈述](../../../plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md)

V1 给出 pattern（"30 个 session 都因为 X 失败"），但**没说该改 skill 还是 prompt 还是 behavior_rule**。V2 给出灰度通道，但**也没说该灰度哪个 candidate**。中间这一步是 attribution agent。

## 业务痛点

当前飞轮"起点"（V1 标注 + 聚类）和"终点"（V2 灰度 + 指标回流）都有了，但**中间断了**：

- V1 dashboard `/insights/patterns` 显示一堆失败 pattern → 用户人工看每个 pattern 决定该改哪个 surface
- V2 可起 canary 但需要先有 candidate skill → 用户手动调 SkillDraftService 创建 draft → 等 A/B → 手动 publish/canary

**完全人工驱动，飞轮飞不起来**。

## V3 目标

**让 attribution agent 闭环驱动飞轮中间这一公里**：

1. 自动扫 V1 pattern 列表
2. 对每个有效 pattern（足够 member 量 + suspect_surface 明确）派 `attribution-curator` agent
3. agent 读 pattern + 抽样 member session + trace 详情 → 输出 proposal：
   - `surface`: skill / prompt
   - `change_type`: "rewrite_skill_md" / "tune_prompt" / 等
   - `description`: 改成什么样
   - `expected_impact`: 期望指标变化（如 "outcome 失败率从 60% 降到 20%"）
   - `confidence`: 0..1
   - `risk`: low / medium / high
4. 写 `t_optimization_event` 表（stage=proposal_pending）
5. 推送到 dashboard "Pending Approvals" 队列
6. 用户点 approve → 自动触发：
   - surface=skill → 调现有 `SkillDraftService` 生成 candidate skill draft
   - surface=prompt → 调现有 `PromptImproverService` 生成 candidate prompt version
7. 候选生成后自动触发现有 A/B run
8. A/B 通过后写 event stage=ab_passed → 用户 publish 按钮 → 自动起 V2 canary
9. canary 期间 V2 metrics + auto-rollback 继续工作 → 每 stage 转换写 event
10. 全链路终态：promoted（canary 通过升档 100%）或 rolled_back（auto-rollback 或人工）

## 用户角色 + 使用场景

**主用户**：SkillForge 平台拥有者（dogfood 阶段 = youren 本人）

**典型使用流**：

1. 用户用 SkillForge 跑日常工作，V1 hourly 标注 + 聚类 → dashboard `/insights/patterns` 有数个 pattern
2. **V3 attribution-dispatcher hourly cron** 扫 `t_session_pattern`：
   - 找未 attribute 过的 pattern（且未在 24h 冷却内）
   - 找 member_count ≥ 3（per V1 准入）+ suspect_surface in (skill, prompt)（V3 不接 behavior_rule / other / unclear）
   - 对每个 candidate pattern 派一次 `attribution-curator` agent run
3. agent 内部 orchestrate 5 tool：
   - PatternRead: 读 pattern 详情 + member sessions
   - SessionAnnotationRead: 拿 pattern member 的 V1 标注详情
   - GetTrace（复用 V1 V76）: 读 member session trace + span
   - ProposeOptimization: 让 agent LLM 输出结构化 proposal
   - WriteOptimizationEvent: 写 stage=proposal_pending 入 `t_optimization_event`
4. dashboard 新页 `/insights/optimization-events`（或 `/insights/proposals`）展示 Pending Approvals
5. 用户点 approve：
   - 后端调对应 surface 的 candidate generator
   - 写 event stage=candidate_created
   - candidate 生成完触发现有 A/B
   - 写 event stage=ab_running → ab_passed / ab_failed
6. dashboard 显示 timeline view 全链路 stage 转换

## 非目标

- 不做 behavior_rule / hook / tool candidate generator（V4）
- 不做 user simulator 多轮 prove-better（V5）
- 不做 cross-surface proposal（同一 pattern 不会同时建议改 skill + prompt）
- 不做自动 publish / 自动 canary 全自动路径（V1 ratify 半自动 + V2 ratify publish 按钮，V3 继承）
- 不做生产数据 grounding 反向训练 attribution prompt（V5 user sim 才做）
- 不动 SessionEntity / ChatService / SessionService / CompactionService / AgentLoopEngine（核心红灯）

## 成功指标

- V1 真生产数据出 ≥ 3 个 pattern 后，attribution-curator 至少对 1 个产出 "可读 + 可执行" proposal（人工评估 reasonable）
- proposal approve 后真自动起 candidate → 真自动起 A/B → A/B 通过自动 enable publish 按钮
- 全链路有 dashboard timeline 还原（pattern → attribution → candidate → ab → canary → verified）
- 1 周 dogfood 至少跑通 1 次端到端飞轮（pattern → attribution → candidate → A/B 通过 → canary → promote）

## 与 backlog 关系

- supersedes plan.md §V3 草稿；本包 ratify 化
- V4 MULTI-SURFACE-FLYWHEEL 依赖本包：扩 `ProposeOptimization` tool 接 behavior_rule 分支
- V5 EVAL-DYNAMIC-USER-SIM 复用本包 `t_optimization_event` 表加 user_sim_verified stage
- 跟 `SKILL-EXTRACT-AND-AB-VIA-AGENT` backlog 共用 agent-driven pattern（attribution-curator 跟 session-annotator + memory-curator 同模板）
