# MULTI-SURFACE-FLYWHEEL

> 飞轮 V4 — 把 behavior_rule 接入飞轮，抽 `OptimizableSurface<V>` + `AbstractAbEvalRunner<V>` Template Method。lifecycle_hook 推 V5（hook 改动频率低，ROI 不够）。

## 文档

- [tech-design.md](tech-design.md) — 10 章节技术方案，含 §8 5 ratify decisions（全 2026-05-15 锁定）
- Investigation: `/tmp/v4-phase1.0-investigation.md` (Phase 1.0 调研报告，18KB)

## 状态

- **Phase 1.0** ✅ 调研 + tech-design 草稿（2026-05-15）
- **Phase 1.0 → 1.1 gate**：5 ratify 全 2026-05-15 锁定
- **Phase 1.1** ⏳ behavior_rule surface 实现 + OptimizableSurface 接口填实 + JPA IT
- **Phase 1.2** ⏳ AbstractAbEvalRunner Template Method 抽取 + 重构现有 2 service
- **Phase 1.3** ⏳ CanaryAllocator 泛型化 + behavior_rule canary 接入
- **Phase 1.4** ⏳ Dashboard behavior rule panel UI（复用 V2 canary panel 模板）
- **Phase Final** ⏳ e2e + 归档

## ratify 决策（已锁）

1. lifecycle hook → V5（不在 V4 范围）
2. OptimizableSurface<V> 6-method 接口（surfaceType / loadActive / loadVersion / createCandidate / injectForSandbox / promote / rollback）
3. AbstractAbEvalRunner<V> 4-hook Template Method
4. Canary 互斥：跨 surface 同 agent 1 active canary（防 confounding）
5. behavior rule candidate LLM = defaultProvider（与 PromptImprover 一致）

## 前置依赖

- V2 SKILL-CANARY-ROLLOUT ✅ 已交付
- V3 ATTRIBUTION-AGENT ✅ 已交付（含 V3.1 + V3.2 闭环）
