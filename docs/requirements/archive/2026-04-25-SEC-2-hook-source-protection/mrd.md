# SEC-2 MRD

---
id: SEC-2
status: done
source: historical-backfill
created: 2026-04-25
updated: 2026-04-28
---

## 用户诉求

历史补录：需要保护系统级 lifecycle hook，避免用户 JSON 编辑或 Agent-authored 变更绕过来源边界。

## 背景

原 `agent.lifecycleHooks` 只有单一 user JSON 字段。如果未来把 system hook 直接塞进去，用户可通过 curl 或 JSON 模式删除。Agent-authored hook 也需要可观测、可审批，不能直接生效。

## 期望结果

system hook、user hook、agent-authored hook 物理隔离；UI 能展示三源；Agent 只能提交待审批 hook proposal。

## 约束

- V1 不接受 inline script 或任意 method target。
- 审批 API 不注册为 Tool。
- 服务端不信任 body 中的 reviewer 身份。

## 未决问题

- 无。需求已交付。
