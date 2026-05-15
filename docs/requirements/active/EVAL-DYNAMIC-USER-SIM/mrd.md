# EVAL-DYNAMIC-USER-SIM MRD

---
id: EVAL-DYNAMIC-USER-SIM
status: design-draft
created: 2026-05-16
updated: 2026-05-16
---

## 痛点

V4 完成后飞轮全闭环（标注 → 聚类 → 归因 → candidate 生成 → A/B → canary → 回流 → 决策 → 回 ①），但 A/B 评测一环存在结构性盲区：

### 1. Static held-out scenario 不模拟真实多轮 push back

现有 `SkillAbEvalService` (V5 Phase 1 已修 multi-turn fallback) 跑的 scenario 是**历史 session 抽出的固定对话片段**：

- 用户消息固定（"我要导出 Q3 销售报告"）
- agent 该回什么也固定
- judge 比对文本相似度

但生产里 agent 答错时**真用户会追问 / 救场 / 转方向**。Static scenario 测不出"candidate 在用户追问下能不能扳回来"。

### 2. 没业务完成度概念

现有 scenario 只有 `task` (一句任务描述) + `oracleExpected` (期望输出)。但用户实际目的可能是：

- **业务目标**："导出 Q3 销售报告" (业务层)
- **成功标准**："下载链接生成且文件含 Q3 字段 + sample size > 100" (验收层)
- **约束**："文件 < 5MB" / "不能离开当前页面" (隐性约束)
- **失败信号**："用户重复问同一个问题 / 主动放弃 / 转人工" (失败检测)

A/B judge 不评这些 → candidate 看起来过但上线后 30% 用户绕路 / 重新问。

### 3. 单视角假用户 vs 多 persona 真实分布

历史 session 抽 scenario 是**某一个真实用户的某一次对话**。candidate 可能对销售经理类用户改进，但对 DBA 类用户退化 — 静态 scenario 测不出。

### 4. 单轮 pass 不等于多轮 pass

agent 在第 1 轮答对，第 5 轮答错。Multi-turn judge (V5 Phase 1) 只看 transcript 文本相似度，不看"任务真完成度"。

## 用户场景

### 场景 A: skill 改进上线后 5% 用户绕路

```
operator: "改了 SQL 助手 skill 加更精确的 schema 推断"
A/B 评测: candidate 92% > baseline 87%，过
canary 10%灰度回流: success_rate 88% (baseline 86%), promote ✓
但 7 天后 dashboard 显示：5% 用户在 candidate 上 "改 SQL 6+ 轮才完成" (baseline 平均 3 轮)

→ A/B 测的是单轮答对，但用户实际场景需要多轮调整。candidate 在
   单轮看起来更精准，但多轮 push back 时反而退化。
```

V5 解：UserSimulatorAgent 按 persona 跑完整多轮对话 + ProcessLevelJudge 看轮数 / 重复 / 完成度 → A/B 阶段就拦下这类 candidate。

### 场景 B: candidate 通过 A/B 但用户实际不满意

```
operator: "改 prompt 加 '总结时列 5 个关键点'"
A/B 评测: candidate 输出确实 5 个关键点，judge 评分高 → promote
真用户反馈: "总结都列 5 个但前 3 个不是核心"
operator 重新调 prompt 反复 4 次才稳定
```

V5 解：UserSimulatorAgent persona 含"重视关键点优先级"约束，ProcessLevelJudge 把"约束满足"作为独立维度评 → A/B 阶段就发现 candidate 在该约束下不达标。

### 场景 C: 不同用户类型反向退化

```
operator: "改 behavior_rule 增加'回答前确认用户意图'"
A/B held-out (主要 sales 类 session): pass
真上线后:
- 销售经理: 满意 (确认意图减少误导)
- DBA: 不满意 (DBA 想要直接答案, 多一轮确认 = 浪费)
```

V5 解：5 固定 persona × 1 trial 必须全 pass (worst case ratify)，DBA persona trial 必拒 → A/B 拦下。

## 我们想要的能力

1. **scenario 抽业务语义 6 字段**：businessGoal / successCriteria / userPersona / userConstraints / failureSignals / expectedOutcome
2. **UserSimulatorAgent 真模拟多轮对话**：按 persona+goal+约束动态生成下一轮 user msg，跟 candidate agent multi-turn
3. **ProcessLevelJudge 看 transcript 全局**：任务完成度 / 约束满足度 / 重复解释计数 / 工具调用合理性 / 轮数 / latency
4. **三因子 promote gate**：A/B (V4) + canary (V2) + dynamic sim (V5) 三者全过 = 真有效
5. **生产数据隔离**：UserSim 跑的 session 不污染真实 `t_session_annotation` / `t_session_pattern` (用 origin 字段区分)

## V5 不在 MRD 范围内

- **不替换** V4 现有 A/B 评测 (V5 是**第三因子**，不是替换)
- **不引入 tau-bench** (4 ratify decision #4，等需对外横向对标时再做)
- **不动 V5 Phase 1 已交付 multi-turn runner** (`runMultiTurnScenario` 私有 method)
- **不破 V2 auto_promote_after_ab** (V5 加独立 `auto_promote_after_dynamic_sim` config)
- **不修 prompt canary** (现状 prompt 不接 canary 是 V2 设计如此)

## 用户 quote / 决策来源

- 2026-05-16 与 user 4 ratify 决策对话: "其实我还挺想做V5的" / tau-bench "后续可以引入。能够评价我们框架强弱的一个事情，只不过 这个成本较高，且对我们当前框架没什么收益 还需要增加接口 进行一些适配"
- plan.md §V5: 整体方案锁定 V1-V4 完成后做 V5
- backlog `EVAL-DYNAMIC-USER-SIM` (todo.md L144): 字段抽 / UserSim / process judge / 三因子 gate 范围已锁
