# Harness Benchmark Comparison

> 状态：需求包完成，等待 Context Governance P2–P6 完成后实施
>
> 优先级：P2（不抢占 Context Governance）

## 目标

在固定模型、固定预算、固定环境下，量化 SkillForge Harness 相比 Claude Code、
Codex CLI、OpenHands 等系统在任务成功率、工具效率、长程记忆、恢复能力和成本上的差异。

本需求不把内部回归场景包装成“行业成绩”。公开可比结果必须来自公开 benchmark
的原始任务、官方 verifier 和可复现实验配置。

## 文档

- [PRD](prd.md)
- [技术设计](tech-design.md)
- [交付计划](delivery-plan.md)

## 准入条件

1. Context Governance P1–P6 已稳定。
2. 至少一个各 Harness 均可调用的相同模型和推理预算。
3. 容器、网络、超时、并发和重试规则已锁定。
4. 每次运行保存原始轨迹、token、耗时、费用、产物和 verifier 版本。

