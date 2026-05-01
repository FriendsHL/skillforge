# SkillForge ToDo

> 更新于：2026-05-02(docs governance: MSG-1/P9-2/BUG-30/BUG-31 状态回写 + 7 gap 进暂缓)
> 规则：这里只放当前执行状态；需求和方案细节放在链接的需求包中。
> 旧版：重整前长版 ToDo 已保留在 [references/legacy-todo-2026-04-28.md](references/legacy-todo-2026-04-28.md)。

## 当前队列

| 顺序  | ID        | 标题                                | 模式   | 状态        | 优先级 | 风险   | 文档                                                        | 下一步                                   |
| --- | --------- | --------------------------------- | ---- | --------- | --- | ---- | --------------------------------------------------------- | ------------------------------------- |
| 1   | P12-PRE   | Sprint 4 前置决策                     | Lite | ready     | P0  | Solo | [需求包](requirements/active/P12-preflight-decisions/index.md)       | 决定 Cost Dashboard、PG 备份、多用户权限边界       |
| 4   | P12       | 定时任务 MVP                          | Full | prd-ready | P1  | Full | [需求包](requirements/active/P12-scheduled-tasks/index.md)           | 等 P12-PRE 决策完成后进入设计评审                 |
| 5   | P9-4/P9-5 | Partial compact + post-compact 恢复 | Full | prd-draft | P2  | Full | [需求包](requirements/active/P9-4-P9-5-compaction-recovery/index.md) | 先决定“最近文件”数据来源                         |
| 6   | P10       | 聊天斜杠命令                            | Full | prd-ready | P2  | Full | [需求包](requirements/active/P10-slash-commands/index.md)            | 在更高优先级的 context / observability 工作后启动 |

## 阻塞 / 待决策

| ID | 待决策 | 负责人 | 阻塞项 |
| --- | --- | --- | --- |
| P12-PRE | Cost 可见性、embedded PG 备份、多用户 / 权限边界 | youren | P12 |
| P9-4/P9-5 | compact 后“最近文件”的数据来源 | youren + agent | P9-5 设计 |

## 最近完成

| ID | 完成日期 | Commit | 交付 |
| --- | --- | --- | --- |
| **xiaomi-mimo provider** | 2026-05-02 | `08a6d4d` + `afa8cf4` + `f6be1ab` | [交付索引](delivery-index.md) |
| SKILL-IMPORT-BATCH | 2026-05-01 | `478e135` | [交付索引](delivery-index.md) |
| SKILL-IMPORT | 2026-05-01 | `a09a17a` + `4490e14` | [交付索引](delivery-index.md) |
| MSG-1 + BUG-30 + BUG-31 | 2026-04-30 | `543a60e` | [交付索引](delivery-index.md) |
| P9-2 + BUG-32 | 2026-04-30 | `fe0404c` | [交付索引](delivery-index.md) |
| SKILL-LOAD | 2026-04-30 | `8d1fb45`(merged via `d74e01c`) | [交付索引](delivery-index.md) |
| P1-D | 2026-04-30 | `6b19439` + `223e5a8`(V33→V38 fix) | [交付索引](delivery-index.md) |
| CTX-1 | 2026-04-30 | `1de1fe1` | [交付索引](delivery-index.md) |
| OBS-1 | 2026-04-29 | 见交付索引 | [交付索引](delivery-index.md) |
| P1-C | 2026-04-28 | `555bdba` | [交付索引](delivery-index.md) |
| Memory v2 | 2026-04-27 | `9f36b59`, `8330d32`, `86703ed`, `96676b9` | [交付索引](delivery-index.md) |
| BUG-F | 2026-04-26 | `e9b48f3` | [交付索引](delivery-index.md) |
| SEC-2 | 2026-04-25 | 见交付索引 | [交付索引](delivery-index.md) |
| thinking-mode-v1 | 2026-04-25 | `55969db` | [交付索引](delivery-index.md) |

## 暂缓

| ID              | 原因                                                      | 重评触发条件                                                | 文档                                                                      |
| --------------- | ------------------------------------------------------- | ----------------------------------------------------- | ----------------------------------------------------------------------- |
| BUG-G           | 根因已修，剩余 sanitizer / 尾部不变量属于防御性补强                        | 再次出现 dangling assistant `tool_use` / 缺失 `tool_result` | [需求包](requirements/deferred/BUG-G-defensive-hardening/index.md)         |
| SEC-1           | 当前本地 / 单用户使用下重要但不紧急                                     | 多端部署、共享环境或 P12 正式上线                                   | [需求包](requirements/backlog/SEC-1-channel-config-encryption/index.md)    |
| P14             | 暂无真实多轮 benchmark 需求                                     | 出现 tau-bench 类真实业务评测需求                                | deferred package TBD                                                    |
| P3-2/P3-4       | 依赖 P14 eval 基础设施                                        | P14 基础设施就绪                                            | deferred package TBD                                                    |
| P15-3/P15-6     | Analyzer MVP 暂不需要 eval run 读取或分析 tab 落库                 | Analyzer MVP 证明有持续价值                                  | deferred package TBD                                                    |
| P12-3/P12-6     | 超出 P12 MVP 范围                                           | 需要 system job UI 或高级可靠性                               | [P12](requirements/active/P12-scheduled-tasks/index.md)                 |
| P10-4/P10-5     | 四条命令 MVP 已覆盖主要场景                                        | 出现明确自定义命令或 help menu 需求                               | [P10](requirements/active/P10-slash-commands/index.md)                  |
| OBS-1-4/OBS-1-5 | OBS-1 MVP 上线后再看 compact 验证视角 + provider quirk 自动诊断的真实使用 | 看到真实 raw payload 后用户提出明确需求                            | [OBS-1 PRD](requirements/archive/2026-04-29-OBS-1-session-trace/prd.md) |
| SKILL-UNINSTALL | dashboard delete 按钮已能用但不清 `t_agent.skill_ids`（silent dangling）；agent 没对应卸载 Tool；本期 SKILL-IMPORT 后浮现的对称缺口 | agent 主动需要卸载 skill / 用户报告删 skill 后 skill_ids 残留 stale 名字 | 主旨：UninstallSkill Tool（dry-run + confirm 双调用）+ SkillService.deleteSkill 内部加 unbind 修复（dashboard 同步受益）+ 全局扫所有 t_agent 解绑 + system 禁卸 + 不调 `npx clawhub uninstall`。Mid 档，需求包待真做时建 |
| WAITING-SUBAGENT-UX | SubAgent / TeamCreate 异步派发后，主 agent loop 结束 → `runtime_status=idle`，但 sub-agent 还在 running。UI 没有"等待 N 个 sub-agent"指示，用户误以为卡住会重发消息（session `2ad5da4d` 现场） | 多次用户反馈"主 agent 不动了"补消息后才发现 sub-agent 还在跑 / collab 页面信息不够 | 主旨：session 级 `pending_subagent_count` 字段（从活跃 collab_run 计算，per-turn refresh）+ chat header 显示"等待 N 个 sub-agent" badge + hover 展开看 handle / status / duration。语义类似 MSG-1 waiting_user 但对应 waiting_subagent。Mid 档，需求包待真做时建 |

## 阅读规则

实现任务时，先打开链接的需求包。实现前读 `prd.md` 和 `tech-design.md`；只有原始产品意图或限制不清楚时才读 `mrd.md`。
