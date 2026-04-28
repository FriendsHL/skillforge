# P10 MRD

---
id: P10
status: mrd
source: user
created: 2026-04-28
updated: 2026-04-28
---

## 用户诉求

用户需要在聊天里快速执行常见 session 操作，尤其是在飞书等没有完整 dashboard 控件的渠道里。

## 背景

BUG-F 修复后，`/reset` 不再紧急；但 `/new` 对飞书移动端仍然有价值，因为用户没有 UI 路径创建或切换 session。

## 期望结果

聊天输入框能识别少量斜杠命令，并把它们作为 UI / 平台动作执行，而不是发给 Agent。

## 约束

- MVP 只做四条命令。
- `/compact` 触碰核心 compaction 路径，需要 Full Pipeline。
- `/model` 只修改 session 级临时模型。

## 未决问题

- [ ] 确认后端 endpoint 形态。
