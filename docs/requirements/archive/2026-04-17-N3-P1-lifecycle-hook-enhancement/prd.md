# N3 P1 PRD

---
id: N3-P1
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

增强 lifecycle hook 为多 entry 链式执行体系，支持 script handler、prompt enrichment、forbidden skill 黑名单和前端多 entry 编辑。

## 目标

- 多 entry 链式执行。
- ScriptHandlerRunner 安全执行。
- Prompt enrichment 注入。
- Forbidden Skill 黑名单。
- 前端多 entry UI。

## 非目标

- 不允许无约束脚本执行。
- 不允许破坏 provider 消息结构。

## 功能需求

- ChainDecision / SKIP_CHAIN policy。
- ScriptHandlerRunner。
- Prompt enrichment。
- Forbidden Skill blacklist。
- 前端 entry reorder / delete / add。
- scriptBody 长度和 failurePolicy 校验。

## 验收标准

- [x] 多 entry hook 按链式语义执行。
- [x] script handler 安全边界生效。
- [x] prompt enrichment 支持全 provider。
- [x] 前端多 entry UI 可用。

## 验证预期

- 145 后端测试。
- browser e2e。
