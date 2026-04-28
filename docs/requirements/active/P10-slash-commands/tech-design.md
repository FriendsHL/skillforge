# P10 技术方案

---
id: P10
status: design-draft
prd: ./prd.md
risk: Full
created: 2026-04-28
updated: 2026-04-29
---

## TL;DR

新增一个小型 command registry，前端用于补全，后端用于执行。MVP 固定四条命令。

## 关键决策

| 决策 | 理由 | 替代方案 |
| --- | --- | --- |
| 只做四条命令 | 覆盖当前主要价值，范围可控。 | 自定义命令推迟。 |
| `/compact` 只触发 full compact | 避免依赖 P9-4。 | partial compact 等 P9-4。 |
| `/model` 只作用于 session | 避免误改持久化 Agent 配置。 | Agent 配置更新由其他能力处理。 |

## 架构

- 前端 command parser + completion menu。
- 后端 command execution endpoint 或复用现有 endpoint。
- command registry 维护 metadata 和执行映射。

## 后端改动

- 新增 command execution API 或 service。
- 将 `/new`、`/compact`、`/clear`、`/model` 路由到现有服务。

## 命令语义

| 命令 | 行为 | 约束 |
| --- | --- | --- |
| `/new` | 新建 session。 | 旧 ToDo 原文范围。 |
| `/compact` | 触发 full compact。 | 不等 P9-4 就绪。 |
| `/clear` | 清空当前对话显示。 | 旧 ToDo 原文范围。 |
| `/model` | 改 session 级临时模型。 | 不持久化到 agent 配置。 |

## 前端改动

- 增加 slash 检测。
- 增加命令补全 popup。
- 回车执行选中命令。
- 显示 loading / error 状态。

## 数据模型 / Migration

- MVP 预计不需要 migration。

## 错误处理 / 安全

- 校验命令参数。
- `/model` 只能使用已配置 model options。
- 不直接向用户暴露内部 compaction 错误。

## 实施计划

- [ ] Full Pipeline planning。
- [ ] 前端 parser / completion。
- [ ] 后端 execution。
- [ ] 浏览器验证。

## 测试计划

- [ ] Parser unit tests。
- [ ] Command endpoint tests。
- [ ] Browser command workflow checks。

## 风险

- `/compact` 触碰核心 context 行为。
- 如果不同渠道各自实现，命令语义可能漂移。
- 斜杠命令替代的是已存在的 UI 路径，不是新能力。

## 评审记录

因为 `/compact` 触碰核心 compaction 路径，实现前需要 Full Pipeline。
