# SKILL-CANARY-ROLLOUT MRD

---
id: SKILL-CANARY-ROLLOUT
status: ratified
owner: youren
created: 2026-05-14
updated: 2026-05-14
---

## 用户原始诉求

V1 PROD-LABEL-CLUSTER 落地后剩下"最后一公里"问题：

> "灰度上线 + 灰度期生产指标回流（你说的'重新跑一次证明效果比之前的好'）"
>
> 引自 [PROD-OPTIMIZATION-FLYWHEEL plan §一、问题陈述](../../../plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md)

当前 skill A/B 通过后 `SkillAbEvalService.promoteCandidate` 直接 enable candidate + disable parent —— 全量切。问题是：

1. A/B 在 held-out scenario 上通过 ≠ 真生产数据上更好
2. 全量切之后没有指标回流，无法证明 candidate 真的比 baseline 好
3. 万一 candidate 在真生产环境踩坑，没有 rollback 信号 / 灰度退路

## 业务痛点

V1 已经让 SkillForge 能回答：

- 哪些生产 session 失败了
- 失败聚成几类 pattern

但 V1 不回答 "**改完后是不是真好**"。具体表现：

- skill v3 → v4 A/B 通过 → enable v4 → ❓ v4 是不是真比 v3 好？
- 现在依据 = 仅 A/B 评测结果（held-out scenarios）
- 没有依据 = 实际生产 outcome / pattern 分布是不是改善
- 万一 v4 实际表现差，靠人工眼检发现 + 手动 disable

## V2 目标

**让 SkillForge 自己能回答这两个问题**：

1. 某 skill 当前是否在灰度状态 + 灰度组 vs 控制组实际生产指标对比如何
2. canary 期间 candidate 在真生产数据上表现是不是真比 baseline 好（pass rate / outcome / latency / cost 4 维 + V1 outcome 标签）

回答这两个问题 = V2 成功。**不**自动决策"哪个 skill 该灰度 + 改成什么"—— 那是 V3 attribution agent 的事。

**重要**：默认行为 **保持现行**（rolloutPercentage=100 = 一刀切）。灰度作 opt-in 模式 = 用户在 dashboard 主动起 canary 才进流程。

## 用户角色 + 使用场景

**主用户**：SkillForge 平台拥有者（dogfood 阶段 = youren 本人；多用户阶段以后）

**典型使用流（opt-in canary 模式）**：

1. 用户用 SkillForge 跑日常工作（产生 t_session）→ 触发 SkillDraftService 抽 draft → 经 SkillAbEvalService A/B 评测 → A/B 通过
2. 用户在 dashboard skill 详情页看到 Publish 按钮（默认 `auto_promote_after_ab=false`，等人按）
3. 用户选择起 canary 模式（不是一刀切）→ 起步 10% → 等 24h
4. ProdMetricsCollector hourly 跑：聚合过去 1h 内 outcome 标签（来自 V1 session-annotator agent）→ 按 sessionId 反查 canary group → 写 t_canary_metric_snapshot
5. Dashboard skill 详情页 canary panel 显示：
   - rollout gauge（10% canary / 90% control）
   - 24h control vs candidate 4 维分数对比 + outcome 分布对比
   - 累计样本数（canary + control 各多少 session）
6. 用户看着指标好 → 手动升 percentage 到 20% / 50% / 100%
7. 万一指标恶化 → auto-rollback 触发（candidate fail_rate / control fail_rate > 1.5 且样本 > 50）→ percentage 自动置 0 + dashboard 告警 + 手动 reset 才能再起

**典型使用流（默认一刀切模式）**：

1. 同上 1-2
2. 用户按 publish → SkillAbEvalService.promoteCandidate 直接 enable 100%（**等价现行行为**）
3. ProdMetricsCollector 继续 hourly 跑，跟历史 baseline 比对 → 发现退化人工 rollback

## 非目标

- 不解决"该改成什么样" —— V3 attribution agent
- 不解决"多 surface 灰度协调" —— V4 MULTI-SURFACE-FLYWHEEL
- 不解决"动态 user simulator 反向验证" —— V5
- 不做 prompt / behavior rule 的 canary（V4）
- 不做 multi-tenant 隔离（dogfood 单租户）
- 不动 SessionEntity / ChatService / SessionService / CompactionService（核心文件红灯）
- **可以**动 AgentLoopEngine（核心文件，但 V2 必须；reviewer 显式审 persistence-shape + identity-column 不变量）

## 成功指标

- V1 PROD-LABEL-CLUSTER outcome 标签持续输入到 V2 metric collector
- 一个真实 skill 起 canary 10% → 24h → dashboard 看到 control vs candidate 4 维对比
- 手动 publish → percentage 100% + active 切换 + 现行 promote 行为不退化
- 注入坏 candidate → auto-rollback 触发 + 告警
- 现行 SkillAbEvalService.promoteCandidate 全量路径 100% backward-compatible（rolloutPercentage 默认 100 = 现行行为）

## 与 backlog 关系

- V3 ATTRIBUTION-AGENT 依赖本包 canary metric 写入 + V1 pattern 输入
- V4 MULTI-SURFACE-FLYWHEEL 复用本包 CanaryAllocator + CanaryRolloutService + ProdMetricsCollector + canary panel UI 组件
- V5 EVAL-DYNAMIC-USER-SIM 把 user simulator 跑结果加进 metric 三因子合成
