# Install Confirmation PRD

---
id: INSTALL-CONFIRM
status: done
owner: youren
priority: P1
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-26
updated: 2026-04-29
---

## 摘要

实现 install 操作的一次性 approval token、确认卡和状态机。

## 目标

- 高风险 install 需要用户确认。
- 支持 Web / 飞书确认卡。
- 未确认直接失败。
- 支持后续 CreateAgent 等能力复用。

## 非目标

- 不让 Agent 绕过确认直接安装。

## 功能需求

- InstallTargetParser。
- SessionConfirmCache / approval token。
- SafetySkillHook 改造。
- ChatService 状态机变更。
- 前端确认组件。

## 验收标准

- [x] 未确认 install fail-closed。
- [x] 确认 token 一次性。
- [x] Web / 飞书可确认。
- [x] 后续能力可复用模式。

## 验证预期

- 后端确认流 tests。
- 前端确认卡检查。
