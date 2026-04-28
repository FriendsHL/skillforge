# BrowserSkill 登录态 PRD

---
id: BROWSER-LOGIN
status: done
owner: youren
priority: P2
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-17
updated: 2026-04-29
---

## 摘要

设计 BrowserSkill 登录态持久化能力和使用流程。

## 目标

- 支持登录态复用。
- 明确新旧 action 对照。
- 明确配置项。
- 明确风险和不在范围。

## 非目标

- 不作为通用 secret manager。

## 功能需求

- profile/session 持久化。
- 用户登录流程。
- 配置项。
- 风险边界说明。

## 验收标准

- [x] 设计文档说明登录态使用流程。
- [x] 风险和不在范围明确。

## 验证预期

- 浏览器流程人工验证。
