# prd — AUTOEVOLVE-AGENT-LEVEL-BUNDLE

> 状态：draft，待 Plan review + 用户 ratify Phase 1。

## 1. 背景 / 痛点

现 evolve loop 只爬 prompt 单面（BUG-1 已交付）。把 skill/behavior_rule 纳入时发现：与其"按面各自爬"（各面单独赢的改动拼起来从没整体验证过、可能打架），不如让候选 = 跨面改动包、量整个 agent 改完变好没。底座 `AbEvalPipeline` 本来就整-agent 跑分，路 B 反而更自洽、更省、天然支持以后 tools/hook。

## 2. 目标

- evolve 一轮可同时改多个面（V1：prompt + behavior_rule），评测产**一个整体分**，跟当前 best 比、winner-carry-forward。
- best 永远是一个**完整评测过的自洽 agent 状态**（指针元组）。
- 复用现成评测/生成/promote/归因，一行评测算力不重写。

## 3. 范围

**V1 in**：prompt + behavior_rule 两面进包；整-agent A/B（一个分 + target/general 不退化保护）；orchestrator 种子 v2 包状态机爬坡 + 反思。
**V1 out**：skill（Phase 4）/ tools / hook（远期，连 surface 都没建）；按面归因消融；自动 promote。

## 4. 验收点

- AC-1（Phase 1）：新整-agent A/B 通道用「只含 prompt 的包」跑一轮，整体分对齐现 prompt A/B（回归不退化）。
- AC-2（Phase 2）：一轮候选同时改 prompt + behavior_rule，整-agent A/B 出一个整体分 + target_delta/regression_delta；gate 要求 target↑ 且 general 不退化。
- AC-3（Phase 2）：behavior_rule 顺延生成（候选 rules 建在 best 包的 rule 版本上，非现役）。
- AC-4（Phase 3）：orchestrator 跑通多轮整-agent 爬坡，best bundle 顺延，反思反哺，收尾汇总赢家包；全程不 auto-promote。
- AC-5：ownership/安全 —— ab_run agent_id guard + bundle 里版本归属校验。
- AC-6：mvn 全绿 + 活体 e2e（Ark）多轮整-agent 爬坡产真实可信轨迹。

## 5. 已 ratify 决策（2026-06-02 用户）

| # | 决策 | 选定 | 理由 |
|---|---|---|---|
| D1 | 架构方向 | 路 B：agent 整体爬坡 | 爬的是 agent；底座本就整-agent；避免"补丁集没整体验证" |
| D2 | 打几个分 | 一个（整-agent pass-rate） | 按面归因要消融 2^N 臂，爬坡不值 |
| D3 | V1 范围 | prompt + behavior_rule | 都有现成生成路径；skill/tools/hook 后续 |
| D4 | 包持久化 | 复用各面版本表 + 指针元组 | 复用现成 promote；改动小 |
| D5 | 不退化保护 | target/general 双标准降级成整体分上的场景分组 gate | 对任何改动适用，不再 behavior_rule 专属 |

## 6. 待定（Plan / 后续 phase 决策）

- target 场景子集判定（tech-design R2，Phase 2）
- promote 包的原子性（R3，Phase 3）
- orchestrator 种子 v2 多面选择策略（R4，Phase 3 先按 issue.fixSurface）
