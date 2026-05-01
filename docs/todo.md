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
| GAP-REWIND | SkillForge 现有 P6 message 行存储 immutable append-only，CTX-1 / P9 / Compact 全是 forward truncation。用户卡在脏上下文时只能 `/clear` 全删或继续在脏上下文上 prompt 寄希望 LLM 自己绕过去 —— 缺 **Anthropic 1M context blog 推的"rewind / branch"** | 用户多次反馈想"回到某条消息重 prompt" / 想 fork 试两种方向 | 主旨：`POST /api/sessions/{id}/rewind?to_message_id=X` 删 X 之后的 SessionMessage 行 + 重置 last_message_id + 级联清 P9-2 tool_result_archive 配对；`POST /api/sessions/{id}/branch` fork 新 session（V40+ schema：t_session 加 parent_session_id）+ 前端 ChatWindow per-message hover "rewind / branch from here"。**P0 Mid 档**（来源：Anthropic blog 1M-context "回退而不是纠正" + Hermes `replace_messages`），见 `/tmp/skillforge-gap-analysis.md` gap #2 |
| GAP-ASKUSER-TOOL | MSG-1 已交付**渲染层**（inline ask card），但模型生成那一侧没有标准 tool —— 当前模型用普通 text + markdown bullet 提问，dashboard 需 NLP 推断渲染卡片，可靠性差 | 用户反馈"卡片识别不稳" / 想看到模型用 typed payload 直接产出 | 主旨：`AskUserQuestionSkill`（input schema `{question, options:[{label, description?, value}]}`），output 走 MSG-1 `waiting_user` + 写一条 message_type='ask_user' 行；MSG-1 卡片识别加分支 `tool_name=AskUserQuestion` 走 typed 路径。**P0 Solo/Lite 档**（来源：Claude Code 04 Skills + Anthropic blog seeing-like-an-agent），见 `/tmp/skillforge-gap-analysis.md` gap #3 |
| GAP-TOOL-PERMISSION | 危险 tool（Bash / FileWrite / 任何破坏性 Skill）目前直接执行；缺 per-tool 三态规则（allow / ask / deny + pattern 如 `Bash(git *)`）+ "this session always allow" 临时授权 + 拒绝后 stderr 反馈给 LLM。**可吸收 BUG-27**（同一 session 重复确认 = "session 级 always allow" 的具体场景）| 接 channel gateway 后陌生用户场景 / BUG-27 类反馈累积 | 主旨：`t_tool_permission_rule` entity（user + pattern + action: ALLOW/ASK/DENY + scope: SESSION/USER）+ Skill 接口加 `checkPermissions(input, context)` + AgentLoopEngine 执行前查规则 → ASK 走 MSG-1 inline confirmation card → DENY 时 stderr 拼给 LLM 重 plan。**P1 Mid 档**（来源：Claude Code 02 §7 + Hermes approval callback），见 `/tmp/skillforge-gap-analysis.md` gap #4 |
| GAP-PLAN-MODE | 复杂任务一发出 agent 边想边动手；高风险操作（重构 / 跨模块改）出错时已经损失。缺 EnterPlanMode (tool 集合自动收紧只读) + ExitPlanMode (输出结构化 plan 给用户拍板) 两个系统 Skill | 用户对 Code Agent / Self-Improve Pipeline 复杂任务"想先看方案" 反馈 | 主旨：Skill 加 metadata `plan_mode_safe: bool` + `t_session.plan_mode` 状态枚举（idle/in_plan/executing）+ EnterPlanModeSkill / ExitPlanModeSkill；plan mode 时 AgentLoopEngine 自动过滤 tool 候选只剩 plan_mode_safe=true 的；ExitPlanMode 输出结构化 plan → MSG-1 inline card 拿 user confirm 后切 executing 解锁 full toolset。**P1 Full 档**（来源：Claude Code 02 §4.6 EnterPlanMode），见 `/tmp/skillforge-gap-analysis.md` gap #5 |
| GAP-WEBHOOK-ROUTINE | P12 cron 只覆盖 schedule（cron 表达式）触发；缺 (a) 通用 HTTP webhook endpoint per-routine + auth token，(b) GitHub webhook 适配，(c) routine 执行结果 deliver 到 channel 的 declarative 配置。Anthropic 2026-04 推 Routines 概念把 Claude Code 从交互式工具变成事件驱动平台 | P12 落地 + 用户想接 CI/CD / GitHub PR / Datadog alert 触发 session | 主旨：扩 `RoutineEntity { trigger_type: SCHEDULE \| WEBHOOK \| GITHUB_WEBHOOK, prompt, agent_id, deliver_target }` + `POST /api/routines/{id}/trigger` 通用 webhook endpoint 带 token 鉴权 + GitHub webhook payload 解析 + filter + 复用 channel-gateway 的 deliver。**P1 Mid 档**（来源：Anthropic blog routines），见 `/tmp/skillforge-gap-analysis.md` gap #1 |
| GAP-TOOL-CONCURRENCY | AgentLoopEngine 当前对 LLM 一轮输出的 N 个 tool_use 串行 dispatch；read-only Read / Grep / Glob / FileRead / WebFetch 一定串行 wall time 浪费。Claude Code / Hermes 都做并行 | wall time / token 经济成为 SubAgent + Self-Improve / Eval 大量跑的瓶颈 | 主旨：Skill 接口加 `isConcurrencySafe(input)` / `isReadOnly(input)` default false + AgentLoopEngine 调度阶段按这两个 flag 分组（read-only ThreadPool + write 串行）+ 路径冲突检测（FileRead/FileWrite 同 path 不并行）+ 顺序回填 tool_result。**P2 Mid-Full 档**（来源：Claude Code 02 §5.2 + Hermes 03 §12），见 `/tmp/skillforge-gap-analysis.md` gap #6 |
| GAP-PROMPT-CACHE | SubAgent / TeamCreate 启动时每个 sub-agent 完整重建 system prompt → cache miss；ClaudeProvider / OpenAi-compatible 没用 `cache_control` 标记 system prompt（即使 Anthropic API 支持）。OBS-1 trace 也没记 cache hit rate。Self-Improve / Eval / 多轮 reviewer 跑分析时 token 浪费 ~70% | SubAgent / Collab 高频跑场景出现明显 token bill 增长 | 主旨：`LlmProvider.supportsPromptCache()` + AgentLoopEngine 给 system prompt block 标 `cache_control: ephemeral` + SubAgent 启动时 forkContextMessages 父系 system prompt + Provider 接 API response 解析 `cache_creation_input_tokens` / `cache_read_input_tokens` 写到 LLM_CALL trace + OBS-1 dashboard 加 cache hit rate 列。**P2 Full 档**（触碰 LlmProvider + AgentLoopEngine + SessionService.getContextMessages 多个核心文件），见 `/tmp/skillforge-gap-analysis.md` gap #7 |

## 阅读规则

实现任务时，先打开链接的需求包。实现前读 `prd.md` 和 `tech-design.md`；只有原始产品意图或限制不清楚时才读 `mrd.md`。
