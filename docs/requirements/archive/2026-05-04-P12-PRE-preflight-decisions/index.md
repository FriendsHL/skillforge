# P12-PRE Sprint 4 前置决策

---
id: P12-PRE
mode: lite
status: archived
priority: P0
risk: Solo
created: 2026-04-28
updated: 2026-05-04
---

## 请求

在 P12 定时任务开工前，先明确三个会放大成生产 footgun 的工程决策：Cost 可见性、embedded PG 备份、多用户 / 权限边界。

旧 ToDo 原始判断：P12 会放大 token 消耗和数据量，如果没有这三项决策，上线后会快速踩到账单、数据恢复和身份边界问题。这一步不要求立刻实现完整功能，但必须先确定最小边界。

## 验收

- [x] 写清 Cost Dashboard 最小范围。
- [x] 写清 embedded PG 备份策略。
- [x] 写清多用户 / 权限模型边界。
- [x] P12 PRD 和技术方案链接到这些决策。

## 实现说明

这一步保持 Lite。三项决策今天闭环，PG 备份和多用户决策结论是"暂不实现，accept current risk"——这是有效决策，跟暂缓表其他项一样的处理。如果后续要实现 Cost Dashboard、备份脚本或权限模型，再分别拆成独立需求。

## 三个前置决策（原始问题陈述）

| 决策 | 原因 | 最小版 |
| --- | --- | --- |
| Cost Dashboard | P12 / P15 / 未来 P14 都会放大 token 消耗；没有 cost 可见性会出现"跑几天账单爆炸"。 | session / agent 维度 token 用量 + LLM 估算费用，复用现有 TraceSpan 数据，单页 UI。 |
| Embedded PG 备份策略 | zonky embedded PG 无内置备份；data dir 损坏会导致 sessions / skills / memories / agent 配置 / scheduled tasks 全部丢失。 | `pg_dump` cron 脚本 + 本地压缩保留 7 天。 |
| 多用户 / 权限模型 design doc | 当前 auto-token 是单用户假设；P11 跨 Agent 调用身份、P12 定时任务触发 session 的身份都会踩到边界。 | 写 design doc，明确 agent / session / memory / scheduled_task 如何隔离；不要求立刻实现。**此外含 SkillForge 全局 auth model upgrade scope**：当前所有 controller (SkillController / AgentController / SessionController / 等) 都接受 `userId` query param 当 ownerId 来源，没有 enforce token user == query userId。多用户决策定型后，统一改成从 token 里 extract userId（AuthInterceptor 注入 request attribute）+ controller 不再接受 userId query param。SKILL-IMPORT-BATCH (2026-05-01) follow-up 触发记录此项。 |

## 决策结论（2026-05-04）

### 1. Cost Dashboard — **复用现有 `pages/ModelUsage`，不新建**

| | |
| --- | --- |
| **决策** | 把已有的 `skillforge-dashboard/src/pages/ModelUsage.tsx`（路由 `/usage`）作为 Cost Dashboard。不新建独立 dashboard，不在 P12 启动前做扩展。 |
| **范围 cover** | by-model + by-agent 维度 / Cost / Runs / Tokens in / Tokens out 四种 metric / 24h-7d-30d-90d range / Daily breakdown / 复用 TraceSpan 数据源（`/dashboard/usage/daily` + `/usage/by-model` + `/usage/by-agent`）。Cost 估算硬编码 `(tIn × 3 + tOut × 15) / 1M`（Claude Sonnet 价位）。 |
| **接受的 gap** | (a) 没有 session 级 / scheduled-run 级维度 — 单 session cost 通过 Sessions 页 / Trace 详情可看；(b) 多 provider 单价不区分（DeepSeek / Qwen / MiMo 算的都是 Sonnet 价位）。 |
| **重评触发** | by-agent 维度看不出 P12 定时任务的 cost 异常 / 用户报告"账单不符" / 多 provider 价格差距导致估算偏离实际 > 30%。 |

### 2. Embedded PG 备份策略 — **暂不实现，accept current risk**

| | |
| --- | --- |
| **决策** | 当前阶段不实现自动备份；用户在重要操作前手动 `cp -r <data-dir> <backup-dir>`。SkillForge 当前定位是单用户本地 dev 环境，data 损坏的影响是 dev 实验结果丢失，不是生产故障。 |
| **不做的原因** | 自动 `pg_dump` cron 在单机本地环境收益边际 — 写脚本 + 设 cron + 验证恢复路径的成本 > 数据丢失概率 × 损失。zonky embedded 模式下 SkillForge 进程持有 PG，cron 跑 `pg_dump` 还要确保连接 dev port 15432，复杂度比想象高。 |
| **重评触发** | (a) 多端部署 / 公网部署需求出现；(b) [SEC-1 通道加密](../../backlog/SEC-1-channel-config-encryption/index.md) 推进时一并考虑数据落盘安全；(c) 用户报告任何一次实际数据丢失事件；(d) 单 user 的数据规模超过单纯 dev 实验（如开始接生产 channel gateway）。 |

### 3. 多用户 / 权限模型 — **单用户假设继续，design doc 推迟**

| | |
| --- | --- |
| **决策** | SkillForge 继续按"单用户、本地 dev 环境"假设运行。所有 Controller 维持现状：从 `userId` query param 拿 ownerId、不强制 token-derived user 与 query userId 一致。**不写完整 design doc**，本期仅记录此决策。 |
| **现状的具体含义** | (a) [SkillController](../../../../skillforge-server/src/main/java/com/skillforge/server/controller/SkillController.java) / AgentController / SessionController 等都接受 `userId` query param；(b) `AuthInterceptor` 拦 Bearer token 但不验证 token-derived user == query userId；(c) agent / session / memory / scheduled_task 隔离仅靠 row 级 `owner_id`。 |
| **接受的 gap** | (a) 任何持 valid token 的请求理论上能改其他 user 的资源（query userId 任意填）— 当前单用户场景无对手；(b) 跨 user 资源引用边界没有正式定义；(c) SkillStorageService 三家 marketplace 路径不带 ownerId 段（disk artifact 跨用户共享）。 |
| **重评触发** | (a) 接 channel gateway 让外部用户用 SkillForge；(b) [P11 跨 Agent 调用身份](../P9-4-P9-5-compaction-recovery/) 真实需要权限边界（目前只是潜在需求）；(c) [P12 定时任务](../P12-scheduled-tasks/) 真实跨 user 触发场景出现；(d) [SKILL-IMPORT-BATCH (2026-05-01) follow-up](../../archive/2026-05-01-SKILL-IMPORT-BATCH-rescan-marketplace/) 中记录的 "auth model upgrade" 优先级提升；(e) [GAP-PRETOOL-HOOK-PERMISSION](../../../todo.md) 推进时一并考虑权限模型。 |

## 验证

- [x] 确认 P12 需求包中不存在未解决的前置 blocker（P12 index.md 状态可从 "等待 Sprint 4 前置决策完成" 推进到 "ready for design review"）。
