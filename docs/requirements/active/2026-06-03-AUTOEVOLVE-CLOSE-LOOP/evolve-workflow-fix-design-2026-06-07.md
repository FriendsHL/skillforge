# evolve workflow 引擎修复 — 靶向恢复 + 测量守卫 + 阈值改革（2026-06-07）

> **来源**：2026-06-07 首次 workflow 引擎整体自进化 live run `6251439f`（agent 3，5 迭代 0 kept）暴露的问题。
> 主会话分析 + 独立 adversarial verifier 复核后的修复清单。用户拍板"全部修复"。
> **档位**：Full（brief >800 字 + 跨 evolve 子系统多模块批次）。

## 背景（已验证的事实）

1. **靶向断线**：BC-M2b 的错题本靶向接在 orchestrator system prompt（V144/V145）里；Phase 1 确定性重写的 `evolve-loop.workflow.js` 调 `TriggerAbEval` 不传 `evalScenarioIds`（js:255-261）→ activated 错题本场景（如 `165e0ed0` badcase-grep）从不进 A/B。06-05 orchestrator run `6b32e7ce`（50 场景含 badcase，target 80→100）vs 06-07 workflow run（36 场景无 badcase）为对照证据。两份 workflow 设计文档的"不做"清单都没列此项 = 迁移遗漏。
2. **role 退化静默丢 28% 数据集**：不传 evalScenarioIds 时 `resolveRoleSplit` 按 role 划分，50 场景只取 main_assistant(6)+general(30)=36；roles=[] 的场景结构性不可见。
3. **0-vs-null 哨兵不一致**：`computeDeltas` 有 measured==0→null 哨兵（D3），但 `computeSubsetDeltas` 只在子集为空时 null；`passRateOver` 在"子集非空但 0 个 pairwise-measured"时返 **0.0**（javadoc 自己承认）。run `6251439f` iter1 的 6 个 role-target 场景两臂全 infra-ERROR → target_delta_pp=0.0（非 null）→ `agentWouldPromote` 的 `targetOk=(0>0)=false` → iter1 整体 +14.29pp 被硬拒。iter3-5 是真测量 FAIL/FAIL，拒绝正确。
4. **无最小测量数守卫**：iter1 两臂 24-25/36 场景 infra-ERROR（VETO_EXCEPTION 风暴，疑似 run 启动预热/容量），+14.29pp 实为 ~7 个分母上 1 个场景翻转。全代码无 min-measured-N 守卫。
5. **baseline 缓存是有意推迟非遗漏**：js:249-254 注释写明 W1 guard（`startAgentAb` 把 prior winner 解析为"最近一次 COMPLETED run"而非真 winner）导致任一轮被拒后传缓存即 `IllegalStateException`。该矛盾在 orchestrator 路径同样存在（先于重写）。entity 已有 `prior_winner_ab_run_id` 列。
6. **vs-original 锚点没搬**：V145 keep 闸 ② `candidateGeneralRate ≥ originalGeneral − 5`（originalGeneral 第一轮记死不更新）在 workflow `decideKeep` 无对应。
7. **15pp 阈值陈旧且硬编码**：2026-04-16 `4ec32aeb` 引入 `PromptPromotionService:25`=15.0；skill 面 15.0+候选≥40（`SkillAbEvalService:90-91`）；`GetAbResultTool:76-78` 还有镜像副本。n=18~36 时 15pp≈5-6 个净翻转，结构性不可达。agent 面闸实际是双标准（target>0 AND regression≥−3，`GetAbResultTool.agentWouldPromote:347-355`），js:37 "15pp"注释是错的。
8. **文档漂移**：tech-design.md:259 写 `regressionDelta≥0`，实现是 −3.0 floor。

## 修复方案（7 项）

### F1 — 靶向恢复（blocker）
- `evolve-loop.workflow.js`：Report phase 后加确定性步骤 `tool('ListActiveHarvestedScenarios', {agentId})` → 取 active harvested scenario ids；非空时**每轮** `TriggerAbEval` 额外带 `evalScenarioIds`（镜像 V144 语义：这些作 target 子集，其余照常）。空 → 不传，保持现行为。
- `WorkflowEvolveToolRegistryFactory`：白名单注册 `ListActiveHarvestedScenariosTool`（现 7 个工具里没有它，workflow `tool()` 调不到）。该工具只读、已过滤 active+session_derived+dataset-membership，安全。
- 注意：工具真名/入参 schema 以 `ListActiveHarvestedScenariosTool` 类为准，dev 先读再接。

### F2 — 子集 0-measured → null 哨兵（blocker，配 F3 一起才安全）
- `AgentEvolveAbEvalService.computeSubsetDeltas`（+`passRateOver` 或其调用侧）：子集非空但 pairwise-measured==0 → 该子集 rate/delta 记 **null**（镜像 D3 哨兵），而非 0.0。
- 影响链：target null → `agentWouldPromote` 走 fallback（regression>0）—— 语义保持。
- callers 全链 null-safe 自查（DTO/DB 列本就 nullable）。

### F3 — 最小测量数守卫（重要性 ≥ F2）
- `GetAbResultTool` agent 面响应加测量数字段：`measuredN`（overall pairwise-measured）、`totalN`、`targetMeasuredN`、`generalMeasuredN`。
- `evolve-loop.workflow.js` `decideKeep`：`measuredN < minMeasuredN`（默认 10，见 F5 配置）→ 该轮 **inconclusive 不 keep**（log 说明），防止在 n≈7 噪声上 keep。
- 守卫只看 overall/general，**不**卡 target 子集（target 合法可以只有 1 个确定性 oracle 场景）。

### F4 — baseline 缓存（win-streak 限定 + W1 guard 重设计）
- `TriggerAbEvalTool` agent 面加可选 `priorWinnerAbRunId`；`startAgentAb` 提供该参时**按显式 run id** 解析 prior winner（校验 baselineBundle == 该 run 的 candidate bundle），不再用"最近 COMPLETED"；缺省时保持现行为（向后兼容）。落 `prior_winner_ab_run_id` 列（已存在，无 migration）。
- `evolve-loop.workflow.js`：**仅当上一轮被 keep**（win-streak）时传 `cachedBaselineScore=best 分 + priorWinnerAbRunId=该轮 abRunId`；被拒后下一轮照常真测两臂。js:249-254 的推迟注释更新为新语义。
- **不做**（记 follow-up）：复用 iter1 baseline 臂结果给后续被拒轮——更深的改动，本期不碰。

### F5 — 阈值配置化 + 15→5
- 新 `@ConfigurationProperties` 类（如 `EvolveThresholdProperties`，prefix `skillforge.evolve.thresholds`）+ application.yml：
  - `prompt-delta-pp: 5`（原 15，≈ n=36 时 2 个净翻转）
  - `skill-delta-pp: 5`（原 15）、`skill-min-candidate-rate-pp: 40`（不变）
  - `agent-target-min-delta-pp: 0`、`agent-regression-floor-pp: -3`（值不变，挪配置）
  - `min-measured-n: 10`（F3）
  - `anchor-erosion-floor-pp: 5`（F6）
- 接线：`PromptPromotionService` / `SkillAbEvalService` / `AgentEvolveAbEvalService` / **`GetAbResultTool` 镜像副本一并消除**（注入同一 properties，杜绝 advisory 与真闸漂移）。behavior_rule 面（10/−3）本期**不动**（已是合理双标准，降低批次半径）。
- GetAbResult 把生效阈值随响应回显（`thresholds` 子对象），workflow 读 `minMeasuredN` 用于 F3（避免 JS 写死第二份）。

### F6 — vs-original 锚点
- `evolve-loop.workflow.js`：iter1 记 `originalGeneral = baselineGeneralRate`（GetAbResult 已暴露，js 现忽略）；keep 闸加 ②：`candidateGeneralRate ≥ originalGeneral − anchorErosionFloorPp`。originalGeneral 为 null（第一轮 general 未测出）→ 跳过锚点（不 block）。

### F7 — 卫生项
- js:37 错误注释改为真实双标准语义；js:249-254 注释随 F4 更新。
- tech-design.md:259 `regressionDelta≥0` → 与实现对齐为 −3 floor（或注明 floor 配置化）。
- 本设计文档即为"workflow 设计文档缺失靶向条目"的补录。

## 不变量 / 风险自查

- 不触碰 ChatService / SessionService / AgentLoopEngine / compact / LLM provider —— persistence-shape 与 identity-column 不变量不触发。
- 无 schema migration（`prior_winner_ab_run_id` 列已存在）。
- `t_agent_evolve_ab_run` 读路径（dashboard EvolveTrajectoryPanel / GetAbResult）对 null target rate 本就兼容（06-05 已有 null 行）。
- workflow JS 是 classpath 资源，改动随部署生效，无 DB seed 同步问题（orchestrator V144/V145 路径**不动**）。
- 工作树有未提交 FE WIP（SemanticDeltaPanel，P2a 遗留）——**本批次不碰 skillforge-dashboard 任何文件**。

## Intended file list

1. `skillforge-server/src/main/resources/workflows/evolve-loop.workflow.js`（F1/F3/F4/F6/F7）
2. `skillforge-server/.../workflow/engine/WorkflowEvolveToolRegistryFactory.java`（F1）
3. `skillforge-server/.../improve/agent/AgentEvolveAbEvalService.java`（F2/F4/F5）
4. `skillforge-server/.../tool/evolve/TriggerAbEvalTool.java`（F4）
5. `skillforge-server/.../tool/evolve/GetAbResultTool.java`（F3/F5）
6. `skillforge-server/.../improve/PromptPromotionService.java`（F5）
7. `skillforge-server/.../improve/SkillAbEvalService.java`（F5）
8. 新 `EvolveThresholdProperties.java` + `application.yml`（F5）
9. 对应测试类（新增/扩展）
10. `tech-design.md` 一行对齐（F7）

## QA 冒烟用例（部署后 qa-reviewer 执行，全 PASS 才 commit）

> 早失败检测：server 起不来 / 401 / flyway 失败 = 环境问题（BLOCKED 非 FAIL）；`tool not found: ListActiveHarvestedScenarios` = F1 白名单接线 bug（FAIL）。

- **S1 靶向进场**：触发 `POST /api/evolve/agents/3/run`（Bearer token 从 `t_access_token` 取）→ 等第一个 A/B 行出现 → `SELECT ab_scenario_results_json LIKE '%165e0ed0%' FROM t_agent_evolve_ab_run WHERE started_at > <部署时刻> ORDER BY started_at LIMIT 1` → **期望 t**（badcase 场景在场）。
- **S2 target 真测量**：该 A/B 完成后 `SELECT target_delta_pp, baseline_target_rate, candidate_target_rate ...` → **期望非 null**（badcase 作 target 子集被测量；两臂全 infra-ERROR 时允许 null —— 那是 F2 正确行为，但需贴 rationale 证据区分）。
- **S3 守卫生效**：run 结束 `summary_json.trajectory` 每个 kept=true 的迭代，其对应 A/B 的 measuredN ≥ 10（从 GetAbResult 响应或 `ab_scenario_results_json` 数 pairwise-measured）→ **期望无违例**；若全轮 inconclusive/rejected 也 PASS（守卫保守方向）。
- **S4 单测**：`mvn -pl skillforge-server -am test`（或 focused 新测试类）→ **Tests run N, Failures 0, Errors 0 → BUILD SUCCESS** 原文。
- **S5 阈值配置生效**：新单测断言 properties 默认值（prompt/skill=5 等）+ `grep -n "15.0" PromptPromotionService SkillAbEvalService GetAbResultTool` → **期望硬编码常量已消除**。
- **S6 注释卫生**：`grep -n "15pp" evolve-loop.workflow.js` → **期望 0 hit**（或仅在新注释里正确描述 prompt/skill 面）。

## 验收点

- [ ] F1-F7 全落地，intended file list 外零触碰（尤其 skillforge-dashboard）
- [ ] S1-S6 冒烟全 PASS
- [ ] mvn 全模块绿
- [ ] orchestrator 路径行为字节不变（V144/V145 不动，EvolveController orchestrator 分支不动）
