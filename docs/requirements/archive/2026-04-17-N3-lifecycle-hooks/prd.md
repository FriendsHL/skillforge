# N3 PRD

---
id: N3
status: done
owner: youren
priority: P0
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-17
updated: 2026-04-28
---

## 摘要

实现 lifecycle hook 配置、dispatcher、handler 多态、trace 可观测和前端编辑能力。

## 目标

- 支持用户配置 lifecycle hooks。
- 支持 skill/script/method handler 扩展。
- 支持 hook history 和 dry-run。
- 前端提供 schema-aware 编辑器。

## 非目标

- 不在基础 P0 中开放全部高风险脚本能力。
- 不跳过安全校验和失败策略约束。

## 功能需求

- V9 migration。
- Polymorphic HookHandler。
- LifecycleHookDispatcher。
- REST API。
- 前端 hooks editor。
- TraceSpan 记录。

## 验收标准

- [x] hooks 可配置、可执行、可 trace。
- [x] failurePolicy 语义生效。
- [x] 前端编辑器可保存并 round-trip。

## 验证预期

- 后端 hook dispatcher tests。
- 前端 e2e。
