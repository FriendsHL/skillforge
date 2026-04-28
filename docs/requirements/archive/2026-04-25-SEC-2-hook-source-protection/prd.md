# SEC-2 PRD

---
id: SEC-2
status: done
owner: youren
priority: P0
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-25
updated: 2026-04-28
---

## 摘要

为 lifecycle hook 建立三源隔离、effective hook 查询、agent-authored proposal 和审批流程。

## 目标

- system hook 不被 user JSON 覆盖或删除。
- agent-authored hook 独立存储并需要审批。
- Dispatcher 执行 system + user + approved agent-authored hooks。
- UI 和 Tool 都能查询 effective hooks。

## 非目标

- 不实现真正多用户权限模型。
- 不允许 Agent 直接 approve hook。

## 功能需求

- 新增 system hook registry。
- 新增 agent-authored hook 表和审批状态。
- 新增 effective hook composition service。
- 新增 Hook 查询 API / Tool。
- 新增 Agent 提交 hook proposal Tool。
- UI 三源展示。

## 验收标准

- [x] user JSON 模式不能删除 system / agent-authored hooks。
- [x] Agent-authored hook 默认 `PENDING`。
- [x] Dispatcher 只执行 approved agent-authored hooks。
- [x] Trace 中记录 hook source。

## 验证预期

- 后端 composition / dispatcher / controller / tool tests。
- 前端 build。
- 安全边界 review。
