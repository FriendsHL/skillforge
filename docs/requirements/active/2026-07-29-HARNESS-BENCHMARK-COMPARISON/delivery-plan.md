# 交付计划：Harness Benchmark Comparison

## Phase 0：协议与最小样本

- 锁定模型、预算、公平性协议和 Run Manifest。
- 接入 SkillForge 与一个外部 Harness Adapter。
- Terminal-Bench 2.0 选 5 个任务 smoke。

## Phase 1：Terminal 与编码能力

- Terminal-Bench 2.0 正式子集。
- SWE-bench Verified/Live 分层子集。
- SkillForge/Claude Code/Codex CLI 同模型重复 3 次。

## Phase 2：Tool 与 Memory

- 接入 τ²-bench。
- 接入 LongMemEval/V2。
- 运行 P4/P5/P6 ablation。

## Phase 3：SkillForge 专项

- kill -9 后 task/tool/subagent 恢复。
- MCP stale/missing/denied。
- 图片/视频 artifact reference 与 compact continuity。
- 多 Agent mailbox、去重和并发。

## Phase 4：持续评测

- CI 跑 smoke，夜间跑固定子集，release candidate 跑正式集。
- Dashboard 展示 manifest、趋势、成本和失败分类。
- 只用同模型同协议的显著回归阻断发布。

## 暂不实施

在 Context Governance P2–P6 完成前不开始正式横向跑分，避免基线频繁漂移。

