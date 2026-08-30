# GOAL-ALIGNED-AGENT-FACTORY 技术方案

---
id: GOAL-ALIGNED-AGENT-FACTORY
status: design-proposed
prd: ./prd.md
risk: Full
created: 2026-08-26
updated: 2026-08-26
---

## TL;DR

推荐在现有 SkillForge 主 Agent 下补齐专业 Agent 选择与组装控制面，而不是重写运行时：主 Agent 是唯一用户入口；它先查询现有常驻专业 Agent，匹配则委派，不匹配才利用现有 `AgentEntity/AgentDefinition` 和能力配置创建候选。Goal Brief 约束当前任务，Capability Catalog 通过 Adapter 读取现有能力，Task Capability Plan 只在当前 Session/Task 生效。

## 三种实现路径

### 方案 A：让主 Agent 动态搜索、安装并即时组装

主 Agent 收到目标后直接 Web/GitHub 搜索，创建 Skill/Tool，修改 Agent 配置并继续执行。

优点：演示快、交互自然、初期代码少。

缺点：目标解释、权限、供应链、评测和生产变更混在一条对话中；难复现、难回滚，模型容易同时成为需求方、开发者、评委和发布者。

结论：拒绝用于生产，仅允许作为隔离原型。

### 方案 B：先实现通用 Plugin Runtime，再把现有能力迁进去

先建立可热加载的通用 Plugin 接口，并逐步重写 Tool、Skill、Context、Workflow 和 Agent Loop。

优点：架构统一，长期替换能力强。

缺点：触碰范围过大，与 Spring DI、现有 Registry、消息持久化、Compact 和 Provider 协议高度耦合；在用户价值出现前先承担运行时迁移风险。

结论：拒绝作为起点。

### 方案 C：主 Agent 路由 + 常驻专业 Agent + 现有运行时 Adapter

主 Agent 基于当前 Goal/Task 查询和选择现有常驻 Agent；缺失时以现有 Agent 配置创建候选。Tool/Skill/MCP/Workflow/Media 继续作为真相源，通过 Adapter 提供统一检索描述。任务选择绑定当前 Session/Task；只有需要 A/B/回滚的 Specialist Profile 才产生最小配置快照。

优点：最早解决目标偏离和可追溯问题；复用现有投资；可以按 Phase 提供用户价值；后续仍可逐步引入 Runtime SPI。

缺点：首期存在“统一控制模型 + 多个现有 Registry”的适配层；部分能力更新需要双向一致性检查。

结论：采用。

## 关键决策

| 决策 | 理由 | 替代方案 |
| --- | --- | --- |
| Goal Contract 先于 Capability Discovery | 防止系统在目标不清时自行决定用户需要什么 | 先搜索再让用户挑，容易被搜索结果反向定义目标 |
| Specialist Profile 按需版本化，Task Plan 只绑定任务 | 避免主 Agent全局切换和跨任务覆盖，同时复用 AgentEntity | 每个任务创建新 Agent 或全局 Blueprint |
| Manifest 首期是 Adapter/Descriptor | 降低迁移风险，保留现有 Registry 真相源 | 立即统一所有接口和存储 |
| 外部发现与安装分离 | 搜索是只读，执行代码是高风险状态变更 | 搜到即装 |
| Candidate 与 Production 分离 | 模型可以自由探索，但不能直接改变生产 | 让主 Agent边运行边自改 |
| 多维证据 + 硬约束 Gate | 单一分数容易 Goodhart，掩盖安全和用户偏好退化 | 单一总分自动晋级 |
| 用户拥有目标修订权 | 模型不能把自己的推断升级为用户意图 | 模型自动更新目标 |
| P6 才开放 Runtime SPI | Loop/Compact 影响协议与恢复不变量 | 第一阶段即 Everything is Plugin |

## 架构

```text
User Conversation / Builder
            │
            ▼
Goal Alignment Service
  Goal Contract · Examples · Anti-goals · Decision Rights
            │ approved revision
            ▼
Capability Planning Service
  required capabilities · gap analysis · confidence
            │
      ┌─────┴───────────────┐
      ▼                     ▼
Capability Catalog      Acquisition Pipeline
local adapters          local → hub → github/mcp → create
      │                     │
      │               quarantine + review + eval
      └──────────┬──────────┘
                 ▼
      Main Agent capability routing
          │                 │
          ▼                 ▼
 existing Specialist   Specialist Profile candidate
          └────────┬────────┘
                   ▼
       Task Capability Plan (session/task scoped)
                 │
          Isolated Eval Runner
                 │
          Evidence Package
                 │ approval
                 ▼
        Existing Adopt / Canary / Rollback
                 │
                 ▼
   Main Assistant + Specialist AgentDefinition / Session Runtime
```

Evolution 使用同一条后半段：

```text
production signals + user feedback + eval failures
                       ↓
                 Candidate Composer
                       ↓
              Blueprint Draft revision
                       ↓
             frozen eval + holdout + gates
                       ↓
              Evidence / approval / canary
```

## 领域状态

### Goal Contract

建议状态：

```text
DRAFT → NEEDS_USER_ALIGNMENT → APPROVED → SUPERSEDED
```

只有用户操作可以产生 `APPROVED`。系统建议修改时创建新 Draft，不原地修改已批准 revision。

### Acquisition Proposal

建议状态：

```text
DISCOVERED
  → NEEDS_REVIEW
  → APPROVED_FOR_QUARANTINE
  → VALIDATING
  → VALIDATED | REJECTED | BLOCKED
  → APPROVED_FOR_CATALOG
```

下载外部文本与批准其在生产执行是两个不同 Gate。

### Specialist Profile 与 Task Capability Plan

建议状态：

```text
DRAFT → RESOLVED → EVALUATING → REVIEW_REQUIRED
      → CANARY → ACTIVE
      → REJECTED / ROLLED_BACK / SUPERSEDED
```

首期 `ACTIVE` 继续对应现有 Agent 配置和 status。只有进入 A/B/回滚的专业 Agent 才保存最小不可变配置快照。Task Capability Plan 不使用全局 active pointer，绑定当前 Session/Task。

## 逻辑数据模型

最终表名和 migration 在每个 Phase 的详细设计中批准；本总设计只锁定概念，避免一次性建空表。

```text
GoalContract
  id, agentId?, revision, status
  intendedOutcomes, antiGoals, hardConstraints
  qualityDimensions, budget, dataPolicy, decisionRights
  createdBy, approvedBy, createdAt, approvedAt

AcceptanceSetRevision
  id, goalContractId, revision, frozen
  tasks, partitions, verifierRefs, judgePolicy

CapabilityManifest
  capabilityId, version, kind, source
  adapterRef, dependencies, permissions, compatibility
  integrity, license, risk, availability

AcquisitionProposal
  id, goalContractRevision, gapId, mode, source
  sourceRevision, contentHash, requestedPermissions
  status, reviewEvidence

SpecialistProfileSnapshot          # 确有 A/B/回滚消费者时再落表
  id, agentId, revision, resolvedConfig
  permissionSnapshot, configHash

TaskCapabilityPlan                # 优先复用 Session Task metadata/event
  sessionId, taskId, selectedAgentIds, capabilityRefs
  goalBriefRef, permissionSnapshot, configHash

EvidencePackage
  id, subjectType, subjectRevision
  acceptanceSetRevision, runRefs
  dimensionResults, hardConstraintResults
  cost, latency, safety, limitations
```

## 与现有系统的映射

| 新控制面 | 复用现有系统 |
| --- | --- |
| Capability Catalog | SkillRegistry、ToolCatalog、MCP config、WorkflowDefinitionRegistry、Media provider registry |
| Text Skill acquisition | Skill Import、Skill Creator、Skill Eval |
| External research | AUTORESEARCH finding/source ledger |
| Specialist Profile | AgentEntity、AgentService、AgentDefinition、CreateAgent |
| Task capability routing | Session Task、ListAgents/GetAgentConfig、SubAgent |
| Isolated run | Eval/SubAgent/Workflow sandbox 能力，补齐 child sandbox 缺口 |
| Evidence | Eval dataset/run、LLM trace/span、Artifact、Tool records |
| Evolution | Agent Bundle A/B、Attribution、Flywheel |
| Promotion | Adoption、Canary、Rollback |
| Context provenance | AGENT-CONTEXT-GOVERNANCE P7-P9 |

## Capability Manifest

首期建议字段：

```text
identity:
  id, version, kind, displayName, description

origin:
  sourceType, sourceUri, publisher, sourceRevision, contentHash, license

runtime:
  adapterType, localRef, compatibility, availability

security:
  trustLevel, sideEffect, approvalPolicy
  filesystemScopes, networkScopes, credentialScopes
  idempotency, recoverability, dataEgress

cost:
  schemaTokens, latencyClass, costClass

composition:
  dependencies, conflicts, provides, requires
```

Skill、Tool 和 Plugin 不合并成同一运行接口，只共享治理元数据。主 Agent 身份不是可被任务计划替换的模块。

## Goal Alignment 机制

### 目标澄清

系统优先询问用户能直接判断的问题：

- 给出两个你认为成功的任务例子。
- 哪些结果即使看起来更专业也不能接受？
- 质量、速度、成本、隐私发生冲突时如何取舍？
- 哪些数据可以发送给外部服务？
- 系统可以自动做到哪一步，哪一步必须先问你？

系统生成的摘要将每项标为：

```text
USER_CONFIRMED
USER_STATED
SYSTEM_INFERRED
UNKNOWN
CONFLICTING
```

只有前两类可以成为已批准 Goal Contract 的用户事实；`SYSTEM_INFERRED` 必须由用户确认或保持非约束建议，`UNKNOWN/CONFLICTING` 不得驱动高影响决策。

### 避免 Goodhart

- 硬约束不折算进平均分。
- 分维度展示，不默认压成单分。
- 冻结 acceptance set，候选不能修改考题。
- 保留 holdout，Candidate Composer 不读取答案和 judge rationale。
- 用户纠正与真实任务结果可以否决离线提升。
- Judge 与 Candidate 使用独立 Session；高风险阶段允许不同模型/provider。
- 指标提高但用户反馈下降时停止自动晋级并触发目标/指标复核。

## Capability Acquisition Pipeline

```text
1. 由 Goal Contract 生成能力需求
2. 查询本地 Capability Catalog
3. 对缺口产生外部查询计划
4. 获取候选元数据和内容到 quarantine
5. 许可、来源、完整性和恶意指令检查
6. 分类为 Skill / Tool / MCP / Workflow / unsupported
7. 选择 reuse / import / wrap / create
8. 生成 Manifest、实现和测试候选
9. 在隔离环境运行模块 Eval
10. 独立 Reviewer 检查目标适配、安全和证据
11. 用户批准进入 Catalog
12. 进入 Blueprint Draft，不直接启用生产
```

### 外部搜索边界

- URL fetch 需要 SSRF 防护、协议限制、地址解析复核、大小/超时限制。
- Git 仓库固定 commit；SkillHub/MCP 固定不可变版本或保存完整内容 Hash。
- 不执行安装脚本、postinstall 或仓库内指令来完成初步检查。
- README 中“忽略系统规则”“读取密钥”“自动发布”等均作为恶意数据处理。
- License 未知或不兼容时只能作为研究参考，不能进入 Catalog。

### 代码候选隔离

- 候选在独立临时工作区或容器中生成和构建。
- 默认只读输入、临时输出、无宿主源码写入、无凭据、无外网。
- 声明网络需求时按精确域名和操作重新批准。
- Bash/子进程能力不能只靠模型 Tool 白名单，必须由 OS/容器策略限制。
- Eval 产物经过 Artifact 导出，不直接把隔离目录挂成生产 Plugin。

## Specialist Resolver 与 Task Plan

Resolver 输入当前任务、常驻 Agent roster、现有能力和用户权限：

```text
classify task requirements
  → rank existing Specialist Profiles
  → choose existing or propose new profile
  → validate dependencies/conflicts
  → intersect user/agent/module permissions
  → validate provider/runtime availability
  → compute schema/context budget
  → produce task-scoped selections + permissionSnapshot + configHash
```

Resolver 只能缩小权限，不能因为依赖声明自动扩大权限。缺少依赖或权限时返回可解释错误，不静默降级成其他模块。

已有专业 Agent 直接使用现有 `AgentDefinition`。新候选继续通过现有 CreateAgent/AgentService 路径创建；不存在 Adapter 的能力不能进入可执行 Task Plan。

## Evolution Plane

### 候选范围

风险梯度：

1. Prompt 参数和顺序。
2. Skill 选择和版本。
3. Tool 选择与 Tool Policy。
4. Workflow topology。
5. Model/provider/config。
6. Context/Compaction strategy。
7. Agent Loop implementation。

每一层必须有独立 allowlist、最大同时改动数和专项 Gate。前一层产品闭环通过后，才启动下一层。

### 评分与 Gate

不定义全局固定综合公式。每个 Goal Contract 保存质量维度及优先级，Evidence 展示：

```text
task success
dimension scores
hard constraints
cost
latency
safety/permission delta
complexity delta
user feedback
```

系统可使用排序或 Pareto frontier 推荐候选，但最终不隐藏各维度。任何硬约束失败直接 `REJECTED`。

### 归因

- 默认每个候选只改变一个或少量模块。
- 完整 Blueprint A/B 是晋级依据。
- 高价值组合可做 leave-one-out 或有限消融。
- Trace 记录实际调用模块，而不是仅记录配置中存在的模块。
- LLM 归因只能作为 hypothesis，必须通过对照实验或用户证据验证。

## 后端改动分期

### P0a Goal Brief 旁路验证

- 不新增 Goal/Eval 表，不改 AgentLoop、ChatService、Session 或 Eval schema。
- 复用现有 Session Task/Message 表达原始目标、轻量 Goal Brief、来源分类和确认状态。
- 同一 Chat 显示非阻塞目标卡；普通聊天和一次性任务不触发。

### P0b Goal Contract

- P0a dogfood 证明价值后，再决定是否需要不可变 Goal revision 表。
- Acceptance Set 直接引用现有 EvalDatasetVersion，不新建第二套 Scenario/Task 表。

### P1 Capability Manifest

- 建 Capability Catalog interface 和只读 Adapter。
- 适配 ToolCatalog、Skill、MCP、Workflow、Media。
- 与 Context Governance Capability Descriptor 合并字段，禁止重复模型。

### P2 Specialist Profile 与 Task Capability Plan

- 复用 AgentEntity、AgentService、AgentDefinition 和现有 CreateAgent/SubAgent 路径。
- 增加 roster 查询、能力匹配说明和 task-scoped selection provenance。
- 只有进入 A/B/回滚时才增加最小 Specialist 配置快照。

### P3 Acquisition

- 本地 gap resolver、外部 source connector、quarantine store。
- Skill import/create 适配。
- Tool scaffolding、隔离构建、Review/Evidence 状态机。

### P4 Builder

- Goal、Gap、Proposal、Blueprint Diff、Evidence API。
- 复用 Workflow DAG 和现有 Agent 管理 UI 的视觉模式。

### P5 Evolution

- CandidateBundle 扩展为 Blueprint candidate reference。
- 复用现有 Eval/Adoption/Canary/Rollback。
- 增加多维结果、硬约束 Gate、permission/complexity delta。

### P6 Runtime SPI

- 先定义实验 Runner 内的 Context/Compaction Strategy SPI。
- Agent Loop SPI 单独 Full 子需求，不与其他策略同批。
- 通过专项不变量后才能申请生产 Canary。

## 前端改动

- Goal Contract Wizard：用例、反例、取舍、数据和决策权确认。
- Capability Gap Review：已有/部分/缺失/冲突及证据。
- Acquisition Inbox：来源、安全、许可、权限和批准。
- Blueprint Builder：模块树/DAG、依赖、权限和预算。
- Blueprint Diff：版本变化、permission delta、预期影响。
- Evidence View：逐维结果、硬约束、运行证据和限制。
- Promotion View：Review、Canary、Rollback 状态。

Builder 是面向高级用户和诊断场景的辅助入口；普通用户可以完全通过主助手对话完成目标确认和能力准备。不在首版实现自由布局无限画布。Builder 数据稳定后，无限画布只能作为同一 Blueprint/Workflow 模型的投影。

## 数据模型 / Migration

这是多期 Full 需求，每期只创建当期实际消费的表和索引，不在 P0 一次性创建所有表。

共通要求：

- 新时间字段使用 `Instant/TIMESTAMPTZ`。
- Revision 行不可原地覆盖；可变 active pointer 与不可变 revision 分离。
- 所有 ownership 从服务端认证上下文解析，不信任客户端 userId。
- 状态转换使用乐观锁或条件更新，避免重复批准/晋级。
- Migration 运行态验证与 JPA roundtrip 必须纳入每期 Full 门禁。
- 新增到 `t_session_message` 的关联字段必须通过所有 rewrite/compact 路径保持；优先使用 Session/Trace sidecar，避免无必要修改消息表。

## 错误处理 / 安全

关键失败必须有稳定 reason code：

```text
GOAL_ALIGNMENT_REQUIRED
GOAL_REVISION_STALE
ACCEPTANCE_SET_NOT_FROZEN
CAPABILITY_NOT_FOUND
CAPABILITY_SOURCE_UNTRUSTED
CAPABILITY_LICENSE_BLOCKED
CAPABILITY_INTEGRITY_MISMATCH
CAPABILITY_PERMISSION_REQUIRED
CAPABILITY_SANDBOX_UNAVAILABLE
CAPABILITY_VALIDATION_FAILED
BLUEPRINT_DEPENDENCY_CONFLICT
BLUEPRINT_PERMISSION_CONFLICT
BLUEPRINT_RUNTIME_UNAVAILABLE
EVIDENCE_INSUFFICIENT
HARD_CONSTRAINT_FAILED
HUMAN_JUDGMENT_REQUIRED
PROMOTION_APPROVAL_REQUIRED
```

- 客户端不显示本地绝对路径、源码、秘密或内部 Prompt。
- 日志记录 ID、Hash、状态和 reason code，不默认记录外部正文和用户私有内容。
- 下载、验证、Catalog admission、Blueprint admission、Canary、Promotion 使用不同权限动作。
- 外部 Tool 的 provider credential 只在生产运行时按现有 secret resolver 注入，候选构建/Eval 不获得真实 credential。
- Prompt Injection 不能调用服务端批准 API；批准必须来自认证用户的明确操作。

## 实施计划

### P0a：Goal Brief 旁路验证

- [ ] 普通一次性任务完全不显示 Goal UI。
- [ ] 长期/复杂任务最多追问 1–2 个问题并生成紧凑 Goal Brief。
- [ ] Goal Brief 记录原始消息引用与来源分类。
- [ ] Chat 卡支持“确认并继续 / 需要修改 / 先只做一次”，且不禁用输入框。
- [ ] 复用现有 Session Task/Message，不新增表、不改 Eval/AgentEntity/Loop。
- [ ] 用一个 Coding 长任务和一个非 Coding 长任务 dogfood。

### P0b：Versioned Goal Contract

- [ ] P0a 通过后决定跨 Session Goal 的作用域和最小持久化。
- [ ] 引用现有 EvalDatasetVersion 作为 Acceptance Set。
- [ ] Goal 与 Acceptance 分权批准。

### P1：Capability Inventory

- [ ] 与 Context Governance Capability Descriptor 收敛字段。
- [ ] 建立只读 Capability Catalog 和现有 Registry adapters。
- [ ] 增加来源、权限、成本、兼容性和 availability 观测。
- [ ] 验证 Catalog 不改变当前 Tool/Skill 暴露结果。

### P2：常驻专业 Agent 选择与组装

- [ ] 主 Agent 查询 roster 并解释为何选择某个专业 Agent。
- [ ] 缺失时复用现有配置生成 Specialist Profile 候选。
- [ ] Task Plan 绑定 Session/Task，不修改主 Agent active pointer。
- [ ] 子 Agent 权限保持当前任务授权子集。
- [ ] 仅在 A/B/回滚需要时实现配置快照。

### P3：Capability Acquisition

- [ ] 实现本地 gap analysis 与用户确认。
- [ ] 接入 AutoResearch/SkillHub/GitHub/MCP source adapters。
- [ ] 实现 quarantine、integrity、license 和安全检查。
- [ ] 打通文本 Skill import/create/eval/admission。
- [ ] 打通 Tool candidate scaffold/build/test/review，但保持生产批准门。

### P4：Builder 与完整 Agent 验收

- [ ] 实现 Builder、Gap Review、Acquisition Inbox、Evidence View。
- [ ] 完成一个非 Coding Agent 和一个 Coding Agent E2E。
- [ ] 验证用户可理解模块来源、权限和版本差异。

### P5：Goal-aligned Evolution

- [ ] 扩展 CandidateBundle 为 Blueprint candidate。
- [ ] 引入 hard constraint、permission delta、complexity delta 和多维证据。
- [ ] 复用 A/B、Canary、Adopt、Rollback。
- [ ] 实现目标/指标冲突时 `HUMAN_JUDGMENT_REQUIRED`。

### P6：Experimental Runtime SPI

- [ ] 先实现实验 Context/Compaction Strategy Runner。
- [ ] 设计 Agent Loop SPI 和 provider compatibility matrix。
- [ ] 运行 pairing、persistence、compact、recovery、stream/cancel 专项测试。
- [ ] 未经独立批准不进入生产。

## 测试计划

### 目标对齐

- [ ] 模糊目标进入 `NEEDS_USER_ALIGNMENT`。
- [ ] 模型推断不能成为用户确认字段。
- [ ] Goal revision 变化不修改历史 Blueprint/Eval 解释。
- [ ] 恶意外部内容不能修改 Goal 或 decision rights。

### Capability 与 Blueprint

- [ ] Adapter inventory 与现有 Registry 一致。
- [ ] 依赖冲突、缺失版本、权限扩大和不可用 provider fail loud。
- [ ] 同一 Draft 确定性解析为同一 configHash。
- [ ] Session 固定 revision；非空 Session 切换被拒绝。
- [ ] 子 Agent 权限不超过父 Agent。

### Acquisition 与安全

- [ ] GitHub floating ref 被解析并锁定 commit。
- [ ] Hash 变化阻止复用旧 Review。
- [ ] SSRF、路径穿越、命令注入、秘密读取和恶意安装脚本被阻止。
- [ ] Sandbox 不可用时不回退宿主执行。
- [ ] 未批准代码无法进入生产 Catalog。
- [ ] SkillHub/GitHub/MCP 连接器超时、限流和内容超限返回明确错误。

### Evidence 与 Evolution

- [ ] Candidate 无法修改冻结 acceptance set。
- [ ] 硬约束失败阻止高平均分候选。
- [ ] Judge 与 Candidate session 隔离。
- [ ] Evidence 能回查真实 Session/Tool/Test/Artifact。
- [ ] Canary 回滚恢复准确 Blueprint revision。
- [ ] 指标与用户反馈冲突时不自动晋级。

### 运行时不变量

- [ ] `tool_use/tool_result` 配对。
- [ ] ChatService 持久化与 Engine 内存 Message JSON 一致。
- [ ] Compact 保持边界、身份和 Artifact refs。
- [ ] 外部副作用结果未知不盲目重试。
- [ ] Provider stream/cancel/recovery 回归。

### 最终验证

- [ ] 各 Phase 聚焦单测和 JPA/API 集成测试。
- [ ] Maven Core+Server 相关 reactor 回归。
- [ ] Dashboard typecheck、Vitest、生产 build。
- [ ] 真实浏览器完成 Goal→Gap→Proposal→Blueprint→Evidence→Canary 流程。
- [ ] 恶意外部能力红队测试。
- [ ] 真进程 kill/restart 验证评测、Acquisition 和 Canary 状态收口。

## 风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 用户仍不知道如何表达目标 | 验收指标漂移 | 用真实例子和取舍问题澄清，支持 `NEEDS_USER_ALIGNMENT` 停止 |
| 模型既生成又评测 | 自证循环 | 冻结数据、独立 Session/Judge、确定性 verifier、用户 Gate |
| 搜索结果反向定义需求 | 目标被供应侧绑架 | Goal Contract 先冻结，Gap 必须引用目标字段 |
| 外部代码供应链攻击 | 凭据/文件/网络泄露 | 固定版本、Hash、隔离、无凭据、权限差异审批、fail closed |
| Capability Manifest 变成新大一统接口 | 迁移爆炸 | 首期 Descriptor+Adapter，不替换现有运行接口 |
| Specialist Snapshot 与 AgentEntity 双真相 | 配置漂移 | AgentEntity 继续运行真相；Snapshot 只作评测/回滚证据，由单一 Promotion 服务应用 |
| Benchmark 过拟合 | 离线提升、真实退化 | holdout、复杂度惩罚、真实反馈、Canary、指标冲突停机 |
| 自动化批准过多 | 失去用户控制 | 默认只自动发现/生成/评测；生产晋级显式批准 |
| 需求范围过大 | 长期无法交付 | 每 Phase 独立需求包、独立价值和迁移；P0-P2 先闭最小可复现链路 |
| Runtime SPI 破坏核心不变量 | 数据和恢复错误 | P6 单独 Full、实验隔离、专项协议门禁 |

## 评审记录

### 自审结论

- 方案没有把“用户确认”简化为每一步弹窗；低风险只读发现和隔离候选生成保持自动，只有目标、权限、代码执行和生产晋级设置高影响 Gate。
- Goal Contract、Capability Manifest 和 Blueprint 分别解决“为什么做”“有哪些零件”“实际装了什么”，职责不重叠。
- 自进化复用现有 Eval/Flywheel/Canary，不新建平行闭环。
- 外部能力获取区分检索、下载、隔离执行、Catalog admission 和生产启用，避免一次批准覆盖全链路。
- P6 明确后置，当前设计不授权修改 Agent Loop、消息协议或 Compact。

### 已确认的架构决策

1. P0 Goal Contract 是自动能力发现和自进化的前置条件，但普通闲聊与一次性低风险任务不强制进入 Goal 对齐流程。
2. 系统可自动执行只读搜索、生成候选和隔离 Eval；执行外部代码、扩大权限、Catalog admission 和生产 Promotion 仍需单独批准。
3. 首个可交付纵切为 P0a Goal Brief 旁路验证，复用 Session Task metadata，不新增表、不先做 GitHub 自动 Tool 生成。
4. 主 Agent 是唯一用户入口和最终责任人；优先路由到常驻专业 Agent，缺失时才组装候选专业 Agent。
5. 第一版 Builder 使用表单/树/DAG，不把无限画布作为前置；Runtime SPI 保留为 P6，前五期不替换生产 Agent Loop/Context/Compaction。
