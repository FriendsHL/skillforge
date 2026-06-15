# PRD — AUTOEVOLVING V1（DSL workflow engine + autoEvolving dashboard）

## 1. 目标

让 SkillForge 团队**一眼看到 autoEvolving 整体状态 + 据此发现具体问题**（user 2026-05-28 第 4 点 "E"），并提供 **DSL workflow 编排能力**让"哪个 agent 调哪个 agent"显式可定义、可观测、可重现，为 V3 K-4 outer loop 铺底。

## 2. 非目标

- ❌ V1 **不**做 outer epoch loop（K-4，V3）
- ❌ V1 **不**做 falsification 检查（V3）
- ❌ V1 **不**拆 `optimizer_program.md`（K-1，V2）
- ❌ V1 **不**做 `complexity_delta` 维度（K-2，V2）
- ❌ V1 **不**做 `v_experiment_ledger` view（K-3，V2）
- ❌ V1 **不**接入 autoResearch 数据（dashboard 留 placeholder，V2 接入）
- ❌ V1 **不**做 DSL Phase 2 原语（workflow 嵌套 / budget 自适应 / pipeline 完整版，V2）
- ❌ V1 **不**做 3 信号源融合（V3）
- ❌ V1 **不**做框架自进化（V5）
- ❌ V1 **不**改 14-stage state machine（V3 才动）
- ❌ V1 **不**自动 cron 触发 workflow（V2+，先手动触发）

## 3. 用户工作流

### 路径 A：user 触发 OPT-REPORT 看 workflow 跑

```
[user 打开 dashboard /autoevolving]
  ↓ 顶部 KPI 卡显示当前 autoEvolving 状态全局
  ↓ user 看 workflow 库 → 点 "opt-report" 触发
  ↓ 弹 modal 填参数 (agentId / windowDays) → submit
  ↓ BE: WorkflowRunner 起 Rhino context + 加载 opt-report.workflow.js
  ↓ workflow 跑 (phase Load → Annotate parallel → Aggregate → Attribute → Approve)
  ↓ 每个 phase/agent 状态实时推 WS → dashboard workflow DAG viz panel 着色更新
  ↓ Phase=Approve 时 workflow 暂停 → dashboard 弹 review card
  ↓ user 看 attribution payload → approve / reject
  ↓ workflow resume → 写 t_flywheel_run.summary_json + status=completed
  ↓ dashboard run history 显新完成 run
```

### 路径 B：agent 写新 workflow（V1 admin only）

```
[agent 通过 LLM 写出新 *.workflow.js]
  ↓ Iron Law: workflow 写入 review 队列 (status=pending)
  ↓ admin 在 dashboard 看 workflow review card
  ↓ approve → workflow 加载到 runtime (hot-reload, 不重启服务)
  ↓ 此后可手动触发
```

### 路径 C：user 看 autoEvolving 整体状态

```
[user 打开 dashboard /autoevolving]
  ↓ 顶部 KPI 卡：当周 promoted / rolled_back / running + autoResearch pending + memory proposal pending
  ↓ 3 信号源面板:
     ① production session (OPT-REPORT 4 周历史 + 14-stage 各阶段 candidate 数)
     ② autoResearch finding (V1 显占位 "AUTORESEARCH V1 to ship")
     ③ memory proposal (DREAMING V1 pending list)
  ↓ workflow DAG viz panel: 当前在跑的 workflow + 最近 N run 历史
  ↓ 异常诊断面板: t_llm_trace + tengu_* events 最近 24h anomaly
  ↓ user 发现"哦昨晚 memory 提案 reject 率高" → 点进 memory tab 详查
```

## 4. 功能需求（FR）

### FR-1 DSL workflow engine (Rhino)

- **FR-1.1** 集成 Rhino（`org.mozilla:rhino:1.7.14`），作为唯一 JS engine（不用 GraalVM / Nashorn）
- **FR-1.2** 实现 6 host bindings：`agent()` / `parallel()` / `pipeline()` / `phase()` / `log()` / `humanApprove()` + 全局 `args` 和 `ctx.runId()`
- **FR-1.3** 支持 `meta` 字面量校验（pure literal，禁变量 / 函数调用 / 模板插值）；调度前静态读出 phase 信息
- **FR-1.4** Schema 强制：每个 `agent()` 传 `schema` 时，结果用 JSON Schema 校验，失败自动让 sub-agent 重试（最多 3 次），仍失败抛 SchemaViolationException
- **FR-1.5** Workflow 文件存 `skillforge-server/src/main/resources/workflows/*.workflow.js`（V1）+ runtime 监听文件改动 hot-reload（不重启服务）

### FR-2 L1 Capability Sandbox

- **FR-2.1** `ContextFactory` 配 `ClassShutter` 禁所有 Java 类访问（return false for all className）
- **FR-2.2** Workflow 内禁 `eval` / `new Function` / `Proxy` / `require` / `import` 等动态执行（Rhino setOptimizationLevel(-1) + 自定义 token filter）
- **FR-2.3** Instruction count cap：每 10,000 JS 指令回调 1 次，单 workflow 累计上限 1,000,000 指令（防 runaway loop）
- **FR-2.4** 单 workflow 总 timeout 30min（wall-clock）
- **FR-2.5** Agent call budget：单 workflow 累计 `agent()` 调用 ≤ 1000 次
- **FR-2.6** Rhino 内存上限：单 workflow 堆 ≤ 256MB
- **FR-2.7** 安全审计：Sprint 1 末做 prompt injection 测试（让 agent 写恶意 workflow 验证沙箱兜底）

### FR-3 humanApprove 原语（journal-replay resume）

- **FR-3.1** `ctx.humanApprove(payload)` 调用后 workflow **抛 `WorkflowPausedException` 退出线程**（不 park），runId 状态写 `paused_for_human_approve` + 持久化 paused step
- **FR-3.2** Payload 推 WS event `workflow_human_approve_required` → dashboard 显 review card
- **FR-3.3** Dashboard review card: 显 payload JSON + approve / reject 按钮
- **FR-3.4** User click（即使隔天 / 中间 server 重启过）→ BE 写 decision 到 `t_flywheel_run_step` → **重跑 workflow** + journal cache 跳过已完成 agent() → 跑到 `humanApprove()` 处这次拿到 decision 继续往下
- **FR-3.5** 状态在 DB，**不占线程**（vs park-thread）+ **扛 server 重启/部署** + 人等多久来点都行
- **FR-3.6** humanApprove 可选 timeout（默认无限等；可配 N 天没点自动 reject）
- **FR-3.7** V1 暂不做：UI template / multi-reviewer / multi-approve（V2 完整版）

### FR-4 Journal cache（仅用于 humanApprove resume，Q3 ratify）

- **FR-4.1** 每个完成的 `agent()` 调用 result 写 `t_flywheel_run_step.step_output_json`（V125 复用）
- **FR-4.2** **humanApprove resume 时**：重跑 workflow，HostAgent 调用前按 stepRunId 顺序查 journal cache，命中（status=completed）则跳过实际 LLM 调用直接返 cached result → 跑到 humanApprove 拿 decision 继续
- **FR-4.3** Resume 要求 JS 控制流确定性（同 args + 沙箱禁 Date.now/random 保证）
- **FR-4.4** **crash / OOM / server 重启（非 humanApprove）→ 不自动 resume**：旧 run 标 failed，user 手动重新触发 = **新 run 从头跑**（不读 journal cache）
- **FR-4.5** V2 才做：crash-recovery 自动 resume / partial JS state 持久化 / multi-phase checkpoint

### FR-5 ConsolidationLock（防 workflow 并发）

- **FR-5.1** 同一 workflow name 同时只允许 1 个 run（防误触发或 cron 重叠）
- **FR-5.2** Lock 用 PostgreSQL advisory lock（`pg_try_advisory_lock(hash(workflow_name))`）
- **FR-5.3** 第二次触发同名 workflow 返 HTTP 409 + message "workflow already running"

### FR-6 autoEvolving Dashboard `/autoevolving`

- **FR-6.1** 新 page route `/autoevolving`，加 Layout.tsx nav 进 primaryNav
- **FR-6.2** **顶部 KPI 卡**：当周 promoted / rolled_back / running 数 + autoResearch pending（V1 显 N/A）+ memory proposal pending
- **FR-6.3** **3 信号源面板**（并列）：
  - ① production session（OPT-REPORT 最近 4 周 list + 14-stage 各阶段 candidate 数 + click 跳 `/insights/patterns?tab=reports`）
  - ② autoResearch finding（V1 placeholder 卡片 "AUTORESEARCH V1 to ship in V2"，含 link 到 [子需求包](../2026-05-28-AUTORESEARCH-OPTIMIZATION/)）
  - ③ memory proposal（接 DREAMING V1 t_memory_proposal pending list + click 跳 `/memories`）
- **FR-6.4** **workflow DAG viz panel**（复用现有 FlywheelObservability + reactflow + dagre）：列在跑 workflow（DAG 实时着色）+ 最近 N=20 run 历史 list（点 row 切到那个 run 看 DAG）
- **FR-6.5** **异常诊断面板**：聚合 `t_llm_trace` + `tengu_*` events 最近 24h anomaly（如 agent loop exceeded / schema validation failed / human approve timeout 等）
- **FR-6.6** **Trigger workflow button**：列已注册 workflow + 点击弹 modal 填 args + submit → BE POST `/api/workflows/{name}/run`
- **FR-6.7** 路由 / nav 加 placeholder 在 `Optimization` 之后

### FR-7 OPT-REPORT 改造为 Demo Workflow（保留 fallback）

- **FR-7.1** 写 `opt-report.workflow.js` 实现 OPT-REPORT 4 节点 fanout 流程（load → annotate parallel → aggregate → attribute → approve）
- **FR-7.2** OPT-REPORT-V1 `report-generator` agent (DB id=13) **保留 SubAgent Tool 路径不动**（fallback 安全网）
- **FR-7.3** 触发 OPT-REPORT 时优先走 DSL workflow path（feature flag `flywheel.opt-report.use-workflow=true` 控制）
- **FR-7.4** DSL workflow path 跑通 ≥ 5 个 OPT-REPORT 实际生产 case 后才考虑去掉 fallback（**不在 V1 范围**）

### FR-8 REST + WebSocket API

- **FR-8.1** `GET /api/workflows` — 列已注册 workflow（name / description / phases）
- **FR-8.2** `POST /api/workflows/{name}/run` body `{args: {...}}` → 启动 workflow + return runId
- **FR-8.3** `GET /api/workflows/runs?status=&limit=` → 列最近 run
- **FR-8.4** `GET /api/workflows/runs/{runId}` → run detail + step list
- **FR-8.5** `POST /api/workflows/runs/{runId}/approve` body `{decision: 'approved'|'rejected', reason?: string}` → 触发 humanApprove resume
- **FR-8.6** WS events:
  - `workflow_run_status_changed` (status: pending → running → paused_for_human_approve → completed / failed / cancelled)
  - `workflow_step_state_changed` (stepRunId / phase / agentSlug / status)
  - `workflow_human_approve_required` (runId / payload / 触发显示 review card)

## 5. 验收标准（AC）

- **AC-1** Rhino 集成成功：mvn test 全过 + Sprint 1 spike 跑通 hello-world workflow（`phase('Hello') → log('world')`）
- **AC-2** L1 sandbox 工作：单测覆盖 workflow 写 `require('fs')` / `eval(...)` / `new java.io.File(...)` / `while(true){}` 全部被沙箱拦
- **AC-3** 6 原语 host binding 工作：单测每个原语 happy path + 边缘条件覆盖
- **AC-4** Schema 强制工作：sub-agent 输出违反 schema 时自动重试 3 次 + 最终失败抛 SchemaViolationException + 写 t_llm_span
- **AC-5** humanApprove journal-replay：触发 workflow → 跑到 humanApprove → 抛 WorkflowPausedException 退出线程 + WS push → **重启 server** → user click approve → 重跑 workflow + journal cache 跳过已完成 agent() → 拿 decision 继续 + status=completed（证明扛 server 重启）；另测 crash 非-humanApprove 时不自动 resume（旧 run failed + 新触发从头）
- **AC-6** ConsolidationLock：同 workflow 双触发 → 第二次 HTTP 409
- **AC-7** humanApprove：触发 workflow → 跑到 humanApprove → WS push 到 dashboard → click approve → workflow resume + status=completed
- **AC-8** OPT-REPORT DSL workflow：feature flag 开启时 → 跑出跟 agent-driven 等价的 summary_json（content_md 可有差异，但 topIssues 数量 + ID 一致）
- **AC-9** Dashboard `/autoevolving` 4 区域（KPI + 3 信号源 + DAG viz + 异常诊断）全部能渲染真数据
- **AC-10** 手动触发按钮：列 workflow + 触发 + DAG viz 实时着色显进度
- **AC-11** Hot-reload：手动改 `opt-report.workflow.js` 文件 → workflow runtime 检测到 + 下次触发用新版本（不需重启服务）

## 6. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| Rhino 集成意外（沙箱漏洞 / 性能 / Spring 冲突） | 高 | Sprint 1 头 3 天 spike 验证 + 安全审计 Sprint 1 末 |
| L1 sandbox 不够安全（agent prompt injection 注入危险 JS） | 高 | ClassShutter + token filter + instruction cap + budget + 单测覆盖恶意 workflow |
| Hot-reload race（user 改 workflow 文件时正好有 run 在跑） | 中 | 检测到文件改动后，正在跑的 run 用旧版继续，新触发用新版 |
| `t_flywheel_run_step` schema 不够 V1 用 | 低 | 复用 V125 step_output_json + 必要时 V126 加列（不影响 OPT-REPORT-V1） |
| Dashboard `/autoevolving` 跟现有 `/insights` 数据源重叠 user 不知该看哪个 | 中 | `/autoevolving` 作"总览"页 + 每个面板 click 跳现有 deep-dive page |
| OPT-REPORT DSL workflow 跟 agent-driven 不等价（输出漂） | 高 | 双跑对比 + feature flag 渐进切换；问题出来时直接走 fallback |
| Workflow runtime OOM（Rhino 内存吃完） | 中 | Rhino 堆上限 256MB + workflow 总 timeout 30min |
| Journal/resume 边缘条件（resume 时 args 跟原 run 不一致） | 低 | resume 必须传同 args；不一致拒绝 + 提示 user 重新触发 |

## 7. 实施 order

详见 [`tech-design.md`](tech-design.md) Sprint 1-4 任务拆分。

## 8. Out-of-scope（V2+ 才做）

- **DSL Phase 2 原语**：humanApprove 完整版（UI template / multi-reviewer / timeout / multi-approve）/ workflow 嵌套（`workflow()` 原语）/ budget 自适应（`budget.total` / `budget.remaining()`）/ pipeline 完整 schema → V2 子包
- **K-1 拆 `optimizer_program.md`**（SkillDraftService 1430 行）→ V2 子包
- **K-2 complexity_delta + K-3 v_experiment_ledger view** → V2 子包
- **K-4 outer epoch loop + falsification + predicted_impact + 早停** → V3 子包
- **3 信号源融合**（finding → candidate）→ V3 子包
- **AUTORESEARCH 数据接入**（dashboard panel 真数据）→ V2 AUTORESEARCH 子包 ship 时一起做
- **DB 存 workflow + dashboard 编辑器 + cron 自动触发** → V3+
- **框架自进化**（CompactionService / Engine / hook framework / `.claude/rules/*` 改→ PR 路径）→ V5
- **SkillsBench 公开打榜** → V4
- **Bilevel Autoresearch**（workflow 自我演化 / `optimizer_program.md` 自己改自己）→ V99
