# OPT-LOOP-FRAMEWORK Delivery Receipt

> 归档：2026-05-29
> 状态：**部分 ship + 部分 rollback**，剩余路线由 [AUTOEVOLVING-MASTER](../../active/2026-05-28-AUTOEVOLVING-MASTER/) 接管

## 交付摘要

| Sprint | Commit | 内容 | 当前状态 |
|---|---|---|---|
| **Sprint 1** | `aa2cd7c` | V124 migration（rename `t_opt_report` → `t_flywheel_run` + 加 `loop_kind/trigger_source/input_json` 列 + step_kind + Backfill 13 OPT-REPORT 历史 + 兼容视图）+ FlywheelRunEntity / Step / Repository / FlywheelRunService run/step CRUD + 状态机 + WS broadcast | ✅ **保留生产**（OPT-REPORT-V1 走它） |
| **Sprint 2** | `fb037bf` + `86e0b6b` hot-fix | OrchestratorAgentExecutor + 2 Tool (DispatchOrchestrationStep / RecordOrchestrationStepResult) + WorkerCompletionListener + FlywheelStepWsBroadcaster + V125 migration (step_output_json + partial index) | 🗑️ **已删除** (commit `cf95dd7`) |
| **Sprint 3** | — | memory-curator 接管 framework | ⏭️ **从未 ship**（跳过）|
| **Sprint 4** | `9f86e39` | FlywheelOrchestratorRunController + 2 record DTO + Repository finder + Service listRuns + `/flywheel-runs` page + 5 hook + 4 component + 14 测试 case | 🗑️ **已删除** (commit `ccfd8fe`) |

## Sprint 1 在用零件（**勿删**）

```
# Database (V124 migration)
t_flywheel_run (rename from t_opt_report)
  - 列: id / agent_id / loop_kind / trigger_source / input_json / status /
        content_md / summary_json / error_reason / generator_session_id /
        window_start / window_end / created_at / updated_at
  - 13 行 OPT-REPORT 历史在用 (loop_kind='opt_report')
t_flywheel_run_step (rename from t_opt_report_batch)
  - 列: id / run_id / step_kind / sub_agent_session_id / status /
        step_input_json / step_output_count / error_reason / created_at / updated_at
  - 35 行 step 历史
兼容视图: t_opt_report / t_opt_report_batch (1 release 兜底)

# Java
skillforge-server/src/main/java/com/skillforge/server/flywheel/run/
  ├── FlywheelRunEntity.java
  ├── FlywheelRunStepEntity.java
  ├── FlywheelRunRepository.java (findByAgentIdAndLoopKindOrderByCreatedAtDesc)
  ├── FlywheelRunStepRepository.java
  └── FlywheelRunService.java (run CRUD + 状态机 + step CRUD + WS broadcast)
```

V125 migration (`step_output_json` 列 + partial index) 也保留 — V2 DSL workflow runtime 还会用。

## Sprint 2 rollback 内容（commit `cf95dd7`，2026-05-29）

**触发**：grep 验证 0 个 t_agent 配置 `DispatchOrchestrationStep` / `RecordOrchestrationStepResult` Tool，OPT-REPORT-V1 `report-generator` agent (id=13) 走老 `SubAgent` Tool 路径，**Sprint 2 framework 整套 dead code，0 production 调用**。

删除 9 main + 5 IT (~2,632 行)：
- `OrchestratorAgentExecutor.java` (~280 行 prompt-driven dispatch + barrier)
- `WorkerCompletionListener.java` (@TransactionalEventListener AFTER_COMMIT)
- `FlywheelStepWsBroadcaster.java`
- `OrchestratorWorkerSpec.java`
- `OrchestrationStepCompletedEvent.java`
- `StepStateChangedEvent.java`
- `StepResult.java`
- `DispatchOrchestrationStep.java` (Tool)
- `RecordOrchestrationStepResult.java` (Tool)
- 5 IT：OrchestratorAgentExecutorTest (7) + OrchestratorAopContractTest (13) + WorkerCompletionListenerTest (7) + DispatchOrchestrationStepTest (5) + RecordOrchestrationStepResultTest (6) = 38 case

修改 5 文件（去 dead refs）：SkillForgeConfig / FlywheelRunService / FlywheelRunServiceTest / FlywheelRunServiceStepCrudIT / OptReportServiceBackwardCompatIT

## Sprint 4 rollback 内容（commit `ccfd8fe`，2026-05-29）

**触发**：user 2026-05-28 review 后指出 mental model pivot 到 autoEvolving 父伞方向，Sprint 4 page 是 prompt-driven framework 偏差期产物：
- 跟 V1 autoEvolving master dashboard 计划冗余
- 跟现有 `/insights` Reports tab 数据重叠
- 平铺 timeline 非 user 期待的 DAG workflow viz

删除 20 文件 (~3,161 行)：
- BE: Controller + 2 record DTO + Repository.findAllWithFilters + Service.listRuns + clamp helpers + 2 IT (10 + 4 case)
- FE: api wrapper + 3 hook + 4 子组件 (FilterBar/RunList/RunDetail/StepTimeline) + statusColor + filterOptions + page + .css + 3 vitest (13 + 1 case)
- 修改：App.tsx 删 route + import；Layout.tsx 删 nav item

## 走偏的根因（教训）

OPT-LOOP-FRAMEWORK 走偏的根因是 **2026-05-27 brainstorm D3 决策时我推 push back**：

- user 2026-05-27 brainstorm 时明确说"通过 DSL 可视化" → 我评估"DSL parser 工程量大"→ push back 改成 OPT-REPORT-V1 已有 orchestrator pattern 抽象（"最小 cost / 最大复用"）
- user 当时接受 → 我**没回头确认是否还跟 mental model 一致**
- Sprint 1+2+4 全程按"prompt-driven framework"实现，没有任何 DSL 元素
- 2026-05-28 Sprint 4 ship 后 user review dashboard：mental model 不一致暴露

**教训**：重大 brainstorm 决策（D3 这种"砍掉一整种实现方向"）后必须 follow-up 跟 user 二次确认"这跟你原意还一致吗"。简单 push back 不够，**必须明确点出 trade-off 让 user 选**。

## V2-DSL-RESET.md 文档（已 archive 在本目录）

2026-05-28 user review Sprint 4 ship 后我起草的方向重启文档。其中"删 Sprint 2 整套 framework / 保留 Sprint 4 作 run history audit"决策**已部分被本日（2026-05-29）的全删 Sprint 4 + 重启 AUTOEVOLVING-MASTER 决策 superseded**。

V2-DSL-RESET.md 里有用部分（已迁移到 [AUTOEVOLVING-MASTER](../../active/2026-05-28-AUTOEVOLVING-MASTER/)）：
- Karpathy 7 抽象 5 哲学 SkillForge 应用映射
- DSL 形态对照表（GraalVM vs Rhino vs YAML vs Java DSL）
- 已 ship 资产盘点 grep 数据

V2-DSL-RESET.md 里 obsolete 部分：
- DSL YAML 形态（已改成 Rhino + JS sandbox per Claude Code Workflow）
- "Sprint 4 dashboard 保留作 run history audit"（已 rollback）
- 工程量 ~7 周估算（已重估为 ~4.5 周，复用 SubAgentRegistry + reactflow + dagre）

## 接管者

[AUTOEVOLVING-MASTER](../../active/2026-05-28-AUTOEVOLVING-MASTER/) 整体接管。具体：

- **V0（现状）** = Sprint 1 V124/V125 schema + OPT-REPORT-V1 + DREAMING V1 + memory-curator + 14-stage flywheel + canary + Iron Law
- **V1**（待启动，[AUTOEVOLVING-V1-DSL-DASHBOARD](../../archive/2026-05-29-AUTOEVOLVING-V1-DSL-DASHBOARD/)）= DSL Phase 1 + autoEvolving dashboard，~4.5 周
- **V2** = AUTORESEARCH V1 + K-1 optimizer_program.md + DSL Phase 2
- **V3** = K-4 outer loop + 3 信号源融合
- **V4** = SkillsBench 公开打榜
- **V5** = 框架自进化（PR 路径）

详见 [AUTOEVOLVING-MASTER/index.md](../../active/2026-05-28-AUTOEVOLVING-MASTER/index.md)。
