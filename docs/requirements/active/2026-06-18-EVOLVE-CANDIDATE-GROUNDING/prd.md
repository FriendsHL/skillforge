# PRD — EVOLVE-CANDIDATE-GROUNDING（Phase 2：候选 grounding + 最小 delta 编辑）

> 状态：design-approved（决策 + architect review + 退出标准 ratify）

## 目标

让候选生成**对靶具体失败**（喂失败详情 + 接回反馈回路）**且收窄编辑爆炸半径**（最小/加性编辑），把 live 暴露的"候选净制造回归（net -7）"压成"net 接近 0 / 偶有正赢家"，为 evolve loop 产出第一个真赢家创造条件。

## 非目标

- 不做 B（per-badcase fan-out / 改候选→A/B 基数 / 改 keep 记账）。
- 不重开"整-agent bundle 一个分"设计（H2，留升级时由用户拍）。
- 不动 Phase 1 的判据逻辑、不改 `computeWeightedScore`、不改 A/B 跑法。
- 无 DB schema 迁移（V-migration 仅 UPDATE leaf prompt 内容）。

## 功能需求

- **FR1（接回反馈回路，最高性价比）**：下一轮 leaf 的 `history[]` 必须携带上一轮的 `reconciliation`（hits/misses/riskHits/surprises）+ `perScenarioFlips.regressed[].rationale`（不只 bare 场景名），让 leaf 看到"上轮为什么回归"。
- **FR2（喂 capped 失败详情）**：`ListActiveHarvestedScenarios` 返回**投影截断**的 per-scenario 失败摘要（errorSignature + 一行 task 摘要 + extractionRationale；**排除** fixtureFiles 与全文 task；带总量上限），并喂入候选生成 leaf 的 prompt；V151 leaf prompt 改为"先逐条诊断喂入的失败、再提编辑"。
- **FR3（prediction grounding + 因果链）**：向 leaf 提供**当前已知失败场景 id 集**，要求 `prediction.flipToPass/riskToFail` 填真实 id（非瞎猜/非留空）；候选记录盖 `targetScenarioId`（free-schema sidecar，无迁移）。
- **FR4（C-seam 最小 delta 编辑）**：GenerateCandidate 编辑约束为**最小/加性**——behavior_rule 优先追加一条靶向规则而非整体重写、prompt 编辑限定 diff 规模。收窄爆炸半径。

## 验收点

- AC1：下一轮 leaf prompt 真含上轮 reconciliation + 回归 rationale（不只名字）。
- AC2：候选生成 leaf prompt 真含 capped 失败详情（errorSignature 等），且总量在上限内（不撑爆 prompt）。
- AC3：`prediction.flipToPass/riskToFail` 填真实场景 id（取自已知失败集），`ReconcilePrediction` confidence 分母非空；候选记录有 targetScenarioId。
- AC4：编辑呈最小/加性形态（behavior_rule 追加为主；prompt diff 受限）——可由 GetCandidateDiff 输出观察。
- AC5（无回归）：不改候选→A/B 基数、keep 记账、Phase 1 判据；全模块测试绿。
- AC6（冒烟，部署后 qa）：真跑 evolve run，观察 leaf 收到失败详情 + reconciliation、候选带 targetScenarioId、编辑为最小 delta；贴 raw 证据。

## 退出标准（ratify 2026-06-18）

A+C 上线后用 Phase 1 判据作尺，agent 3 跨 **≥3 轮干净 evolve run**：中位 net-wins 仍 ≤0（0 赢家）**且**净回归没比 live 基线（-1/-7）明显降 → 判不够 → 升级（走 H2 重开 bundle 设计，由用户拍）。

## 冒烟用例（设计时预写，部署后执行）

1. **反馈回路**：≥2 轮 run，断言第 2 轮 leaf prompt 含第 1 轮 reconciliation + 回归 rationale。
2. **失败详情喂入 + capped**：断言 leaf prompt 含 errorSignature 等 + 总量 ≤ 上限（无 fixtures/全文）。
3. **prediction grounding**：断言候选 prediction 填真实 id + targetScenarioId 落账 + reconciliation confidence 分母非空。
4. **最小 delta**：断言 behavior_rule 候选为追加形态 / prompt diff 受限（GetCandidateDiff 观察）。
5. **早失败检测**：harvest 为空 / 无失败场景时优雅退化（不喂空详情、不崩），跟功能 bug 区分。
