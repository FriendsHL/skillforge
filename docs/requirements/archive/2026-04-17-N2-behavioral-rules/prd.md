# N2 PRD

---
id: N2
status: done
owner: youren
priority: P1
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-17
updated: 2026-04-29
---

## 摘要

实现 Agent 行为规则数据模型、内置规则库、REST API、prompt 注入和前端编辑器。

## 目标

- 支持内置行为规则和预设。
- 支持自定义规则。
- SystemPromptBuilder 注入行为规则。
- Dashboard 可配置规则。

## 非目标

- 不在本期实现复杂权限分层。

## 功能需求

- V8 migration。
- BehaviorRuleRegistry。
- REST API。
- BehaviorRulesEditor。
- Agent YAML round-trip。

## 验收标准

- [x] 行为规则可配置。
- [x] 规则能注入 prompt。
- [x] 自定义规则有 XML sandbox / injection 防护。
- [x] 前端可编辑。

## 验证预期

- 后端规则和 mapper tests。
- 前端构建和交互检查。
