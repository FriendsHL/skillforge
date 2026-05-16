# EVAL-DYNAMIC-USER-SIM PRD

---
id: EVAL-DYNAMIC-USER-SIM
status: design-draft
owner: youren
priority: P2
risk: Mid
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-05-16
updated: 2026-05-16
---

## 摘要

V5 只做 2 件事：**抽取多轮评测集**（升级 SessionScenarioExtractorService 加 6 字段）+ **agent 模拟对话**（新 UserSimulatorAgent 按 persona 跟 candidate agent 多轮对话，产 transcript 写表）。

**V5 不做**：评测 (judge) / 归因 / promote gate / 三因子合成 —— 这些是 V1/V2/V3/V5 Phase 1 已有能力的事，V5 之后看实测需要再决定接入方式（可能作为 V5.5 / 全新需求包）。

## 已 Ratify 决策 (2026-05-16)

| # | 决策 | 来源 |
|---|---|---|
| 1 | **trial × persona = 5 固定 persona × 1 trial** | 第一版 cost 可控；persona 池 dogfood 数据稳定后再加 |
| 2 | **multi-trial 聚合 = worst case (任一 trial fail → 整 candidate 拒)** | 非确定 LLM 偶尔失败 = 生产真实失败率；conservative 起步 |
| 3 | **tau-bench V5 不引入** | 进 backlog 等需对外横向对标 framework strength 时挂 adapter；user 原话 "能够评价我们框架强弱的一个事情，但成本较高，且对当前框架没什么收益，需要增加接口进行一些适配" |
| 4 | **5 persona 写 `application.yml`** | `skillforge.eval.user-simulator.personas: [...]` 5 字符串数组；dogfood 后调，第一版直接写死 |
| 5 | **UserSim agent LLM = xiaomi-mimo / mimo-v2.5-pro** | 跟 SkillDraftService line 65-66 硬编码同款；bailian (qwen-max) 已过期未续费，全切 xiaomi-mimo 是现实必须 |
| 5b | **F1 scenario 抽取也切 xiaomi-mimo / mimo-v2.5-pro (hardcoded constant)** | bailian 现状已过期；借 V5 修复 `SessionScenarioExtractorService` 现在调 `defaultProviderName` (失效) 的 bug；跟 `SkillDraftService.EXTRACT_PROVIDER_NAME/EXTRACT_MODEL` 完全同款 pattern |
| 5c | **F1 抽取不走 agent，用 LLM 直接调 (service.chat) 写死** | 抽 scenario 是纯 transform 任务（一次 prompt + session message → JSON 6 字段），不需 agent loop 多步推理 / tool 调用；后续真需要时单独升级 (V5.7)；保 V5 ~1 周 scope 控制 |
| 6 | **V5 不直接喂评测系统** | V5 终于"产出 transcript dataset"；下游消费 (judge / attribution) 是后续 follow-up |
| 7 | **触发方式 = manual button** | Dashboard "Run Dynamic Sim Trial"；cron 自动触发推后 (dogfood 后看) |
| 8 | **transcript 双写**：`t_session` origin='user_sim' + `t_simulator_trial` 元数据 | 复用 ChatWindow / SessionList viewer + dashboard query 元数据 |

## 用户流程

1. operator 改 skill / prompt / behavior_rule (V4 三 surface)
2. candidate 生成 (V3 attribution path 或 manual draft)
3. **(NEW V5) operator 在 dashboard 点 "Run Dynamic Sim Trial"** —— 选 candidate + scenario + persona (single 或 all 5)
4. UserSimulatorAgent 按 (scenario 6 字段 + persona) 跟 candidate agent 多轮对话 → transcript
5. transcript 写 `t_session` (origin='user_sim') + `t_simulator_trial` 元数据
6. **operator dashboard 看 transcript（复用 ChatWindow viewer）** —— 判断 candidate 表现
7. (V5 之外) transcript 后续被 V1 outcome 标签 / V3 attribution 怎么消费 = 后续 follow-up

## 功能需求

### F1. SessionScenarioExtractor 6 字段抽取

- `EvalScenarioEntity` 加 6 列 (V84 migration)，全部 nullable 向后兼容：
  - `business_goal TEXT` — 用户业务目标
  - `success_criteria TEXT` — 成功标准
  - `user_persona TEXT` — 用户画像（**注意**: 这是抽出来的"用户画像"，跟 V5 F2 的 5 fixed simulator persona 是不同概念，但语义类似）
  - `user_constraints TEXT` — 隐性约束
  - `failure_signals TEXT` — 失败信号
  - `expected_outcome TEXT` — 期望结果
- `SessionScenarioExtractorService.extractFromSession()` LLM prompt 扩抽 6 字段 → 写 DB
- 历史 scenario 6 字段允许 NULL（不强制 backfill）
- **LLM provider 改用 `xiaomi-mimo` + `mimo-v2.5-pro` 硬编码常量**（per ratify #5b，bailian 已过期；跟 `SkillDraftService.EXTRACT_PROVIDER_NAME/EXTRACT_MODEL` 同款 pattern；加 fallback 到 defaultProvider 兜底）
- **不走 agent loop**（per ratify #5c，单次 LLM call 够用）

### F2. UserSimulatorAgent system agent

- **V85 Flyway seed** `user-simulator` system agent（pattern 同 V75/V79/V81）:
  - `owner_id=1` / `is_public=TRUE`
  - `llm_provider='xiaomi-mimo'` / `llm_model='mimo-v2.5-pro'` (ratify #5)
  - `tool_ids=["RecordSimulationResult"]` (per 2026-05-16 Phase 1.2.0 草图设计 ratify #2: `RunSimulatorTrial` 是 Java 外部启动 tool 不在 UserSim agent loop 内部调用 — spec 内部矛盾修复)
  - `status='active'`
- **System prompt** 写在 `skillforge-server/src/main/resources/system-agents/user-simulator.system.md`:
  - 描述任务: 扮演用户跟 candidate agent 多轮对话直到任务完成 / 失败信号触发 / 超 max_turns
  - 输入: scenario 6 字段 + 当前轮次扮演 persona description
  - 输出格式: 每轮一行用户输入 / [TASK_COMPLETED] / [FAILURE_SIGNAL: <signal>]
- **5 persona** 写 `application.yml`:
  ```yaml
  skillforge:
    eval:
      user-simulator:
        personas:
          - "销售经理急性子 — 短句催进度、商业语言、不耐烦细节"
          - "数据分析师细心 — 多确认、问细节、追究边界条件"
          - "CEO 高高在上 — 命令式、不解释、只要结果"
          - "实习生小白 — 问基础概念、需要铺垫、抓不准重点"
          - "DBA 老手 — 跳过初级解释、直接问深问题、对效率敏感"
  ```
- **新 tool `RunSimulatorTrial`**:
  - input: `{scenarioId, candidateAgentVersionId, persona, maxTurns=10}`
  - 内部: 构造 sandbox candidate agent + UserSim agent 多轮对话 + 持久化 t_session (origin='user_sim') + t_session_message + t_simulator_trial 元数据
  - output: `{trialId, transcriptSessionId, turnsUsed, terminationReason}`
- **新 tool `RecordSimulationResult`**: 写元数据到 `t_simulator_trial`（trial 完成时由 UserSim 自己调，供 dashboard 查询）

### F3. transcript 持久化 (双写)

- `t_session` 加一行 `origin='user_sim'` (V86 migration 加 CHECK 约束接受 'user_sim')
- `t_session_message` 沿用现有 schema 写对话
- 新 `t_simulator_trial` 表：
  ```sql
  CREATE TABLE t_simulator_trial (
      trial_id VARCHAR(36) PRIMARY KEY,
      scenario_id VARCHAR(36) NOT NULL,
      candidate_agent_version_id VARCHAR(64),
      candidate_surface_type VARCHAR(32),
      persona TEXT NOT NULL,
      session_id VARCHAR(36) NOT NULL,
      turns_used INT NOT NULL,
      termination_reason VARCHAR(64), -- 'task_completed' / 'failure_signal' / 'max_turns' / 'error'
      observed_failure_signals TEXT,
      created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
  );
  ```

### F4. 生产数据隔离 (origin filter)

`t_session.origin` 加 `'user_sim'` 枚举值 (V86 migration)。V1 / V2 / V3 各路径加过滤防污染：

| 路径 | 加过滤 |
|---|---|
| V1 `SessionAnnotationService` | `WHERE s.origin != 'user_sim'` |
| V1 `PatternClusteringService.cluster()` | `WHERE s.origin != 'user_sim'` |
| V2 `CanaryMetricsService.recompute(Duration)` | `WHERE s.origin != 'user_sim'` |
| V3 `AttributionDispatcherService` | 已通过 V1 pattern 传递隔离 (V1 已过滤) |

每路径加 regression test 锁不变量。

### F5. FE Dashboard

- **嵌入 `Insights.tsx` 第 4 tab** `'dynamic-sim'`（跟 V4 BehaviorRuleEvolution / V3 OptimizationEvents 同构）
- 新建 `components/dynamicSim/DynamicSimPanel.tsx`:
  - 顶部：candidate 选择器 (skill / prompt / behavior_rule version) + scenario 选择器 + persona checkbox (5 选 N 或 all)
  - 中部：trial 列表（per-candidate 历史 trial）
  - 底部：选中 trial 展开 → 跳 ChatWindow viewer 看完整 transcript（复用 SessionDetail page）
- "Run Dynamic Sim Trial" 按钮调 BE endpoint 触发 UserSim
- 新建 `api/dynamicSim.ts` (BE endpoint wrapper)

### F6. REST endpoint

- `POST /api/dynamic-sim/trials` body `{scenarioId, candidateAgentVersionId, candidateSurfaceType, personas: [...]}` → 异步触发 UserSim agent (类似 V3 attribution dispatcher 同款 chatService.chatAsync pattern)
- `GET /api/dynamic-sim/trials?scenarioId=&candidateAgentVersionId=&surfaceType=` → 列 trial 历史
- `GET /api/dynamic-sim/trials/{trialId}` → 单 trial detail (含 session_id 让 FE 跳 SessionDetail)

## 非目标

- **不做 judge** —— V5 Phase 1 `EvalJudgeTool.judgeMultiTurnConversation` 已能评 transcript，V5 不主动喂
- **不做 attribution** —— V3 attribution-curator 已实现，不重复
- **不做 promote gate / 三因子合成** —— V2 `auto_promote_after_ab` 现有 config 不动
- **不破 V4 OptimizableSurface / AbstractAbEvalRunner 接口** (零修改)
- **不动核心 7+1 文件 + 3 FE 文件** (Iron Law)
- **不修 prompt canary** (V2 设计如此)
- **不引入 tau-bench** (ratify #3)
- **不做 cron 自动触发** (manual button only, ratify #7)
- **不做 transcript 自动喂 V1 outcome 标签** (origin='user_sim' 隔离，下游怎么消费是后续 follow-up)

## 验收标准

### 代码

- [ ] V84 migration 加 6 字段；V85 seed user-simulator + 2 tool；V86 t_session.origin CHECK 加 'user_sim'；V85 含 t_simulator_trial table
- [ ] `SessionScenarioExtractorService.extractFromSession()` 6 字段 LLM prompt + parse + 写 DB + null backward-compat
- [ ] `UserSimulatorAgent` system agent 真启动；`RunSimulatorTrial` + `RecordSimulationResult` 2 tool 注册到 SkillForgeConfig
- [ ] 5 personas 配 application.yml + UserSim system prompt 注入
- [ ] `t_simulator_trial` 表 + 双写 (`t_session` origin='user_sim' + t_simulator_trial 元数据)
- [ ] V1 / V2 / V3 各路径加 `origin != 'user_sim'` 过滤
- [ ] FE Insights 第 4 tab + DynamicSimPanel + Run Trial 按钮 + transcript viewer 跳 SessionDetail
- [ ] 3 REST endpoint (POST trials / GET list / GET detail) + DTO + MockMvc test

### 测试

- [ ] `SessionScenarioExtractorServiceTest` 6 字段抽取 happy + null backward-compat
- [ ] `UserSimulatorAgentBootstrapTest` V85 seed idempotent
- [ ] `RunSimulatorTrialToolTest` LLM stub + 5 persona switching + max_turns / failure_signal 终止
- [ ] `RecordSimulationResultToolTest` 写 t_simulator_trial 持久化
- [ ] `SimulatorTrialPersistenceIT` (Testcontainers PG) double-write t_session + t_simulator_trial
- [ ] V1 `SessionAnnotationServiceOriginFilterTest` user_sim session 不参与聚类
- [ ] V2 `CanaryMetricsServiceOriginFilterTest` user_sim outcome 不参与 metric
- [ ] FE `DynamicSimPanel` tsc + npm build EXIT=0
- [ ] mvn 全套 BUILD SUCCESS / 0 fail / Iron Law 守住

### Dogfood

- [ ] 真启 server，dashboard 选 1 candidate skill + 1 scenario + 5 persona all → Run Trial → 5 transcript 写表
- [ ] dashboard 看 transcript（跳 SessionDetail）→ debug 完整
- [ ] V1 pattern 聚类 / V2 canary metric 都不收 user_sim session (隔离验证)
- [ ] LLM cost 实测 1 candidate × 5 trial 真实花费记录 (供 V5 后 follow-up 评估 cron 自动触发可行性)

## 后续 backlog (V5 完成后)

- **V5.5 transcript 接 judge**: 决定 V5 transcript 怎么喂 V5 Phase 1 `judgeMultiTurnConversation` (manual eval / 自动评 / 配置触发)
- **V5.6 cron 自动触发 UserSim**: candidate ready 后自动跑 dynamic sim trial (ratify #7 推后)
- **tau-bench adapter**: ratify #3 推后，等需对外横向对标
- **persona 池扩到 10+**: 5 → 10 时 trial 聚合考虑改 majority (ratify #2 推后)
- **prompt canary**: V2 设计如此，多用户阶段才上
- **lifecycle_hook surface**: V4 ratify #1 推 V5+，编辑频率信号收集后评估
