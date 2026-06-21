# SkillForge ToDo

> 更新于：2026-06-19（COMPACT range-model 存储重构 go-live（默认 on，V157）；CHANNEL-MIDTURN-PROGRESS 交付归档；同期 3 项 bug 修复）

> 规则：这里只放当前执行状态；需求和方案细节放在链接的需求包 / archive 中。
> 旧版：重整前长版 ToDo 已保留在 [references/legacy-todo-2026-06-16.md](references/legacy-todo-2026-06-16.md)。

## 当前队列

| 顺序 | ID | 标题 | 模式 | 状态 | 优先级 | 风险 | 文档 | 下一步 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0 | **COMPACT-IDEMPOTENCY-BOUNDARY-FIX** | 压缩失效：①负 gap idempotency 计数空间错配→曾压过的 session 自动压缩永久跳过 ②tool-heavy(SubAgent/Team)找不到 safe boundary→压不动 ③总结输入无窗口保护。+缓解:agent 窗口配置/resolveContextWindow per-model map | Full | **①+③+结构化摘要+per-model 窗口+range-model go-live 已交付（`3756ca43`/`9d226468`/`068a4a5d`）；① 负 gap 06-20 复验未再犯。剩 ② tool-heavy 最坏情况 + W3 watchdog + orphan 残骸复核（9d3eff0f 死历史 6 个 orphan tool_use，live 无害）** | P1→P2 | Full | [需求包](requirements/active/2026-06-19-COMPACT-IDEMPOTENCY-BOUNDARY-FIX/index.md) | ② tool-heavy 最坏情况（grow 完仍 no-op）根治走 storage-redesign + W3 watchdog + 决定 orphan 是否 cleanup |
| 1 | **AUTOEVOLVING-MASTER** ⭐ | autoEvolving 总包（V2-V5）。V1 已交付，V2-V5 ~5-6 个月（V2 AUTORESEARCH+K-1 / V3 K-4 outer loop+3 信号源 / V4 SkillsBench / V5 框架自进化） | Full 总包 | prd 待 ratify | P2 | Full | [需求包](requirements/active/2026-05-28-AUTOEVOLVING-MASTER/index.md) | 起 V2 子包后启动 Plan |
| 2 | **AUTOEVOLVE-CLOSE-LOOP** | 闭环采纳 + 对靶改进 + benchmark 验证。P1/P2/G5/BC-M1/BC-M2a/engine-fix/阶段A 已交付，阶段B/G3/P3 未做 | Full | 部分交付 | P2 | Full | [需求包](requirements/active/2026-06-03-AUTOEVOLVE-CLOSE-LOOP/index.md) | 阶段B（EVOLVE-BADCASE-SENSITIVITY）等用户拍是否升 active |
| 3 | **EVOLVE-JUDGE-GROUNDING** | 自进化判定优化（blog 复盘）。Phase 1 配对/comparative 判定**已交付**（复用 perScenarioFlips net-wins，治绝对打分噪声→0 赢家，无 schema 改） | Full | Phase 1 已交付 / Phase 2 见 #3b | P2 | Full | [需求包](requirements/active/2026-06-17-EVOLVE-JUDGE-GROUNDING/index.md) | — |
| 3b | **EVOLVE-CANDIDATE-GROUNDING** | Phase 2：候选 per-badcase grounding + 最小 delta 编辑（治 live net -7 候选制造回归）。决策经 architect review，A+C-seam | Full | **Phase 2 已交付**（`775fe4df`，LIVE 冒烟 PASS）| P2 | Full | [需求包](requirements/active/2026-06-18-EVOLVE-CANDIDATE-GROUNDING/index.md) | 按退出标准跨 ≥3 轮干净 run 观察净回归/赢家；不达则升级（重开 bundle 设计，用户拍） |
| 4 | **WECHAT-CHANNEL** | 加微信 channel。B-native iLink 原生 adapter。**Slice 1+2+3 已交付，全 LIVE 验证**（文本双向/扫码/文件图片发送/子Agent结果回投 + **Slice 3 FE 扫码绑定 UX：AntD QRCode 渲染 + 轮询自动绑定**）| Full | Slice 1+2+3 交付 | P2 | Full | [需求包](requirements/active/2026-06-18-WECHAT-CHANNEL/index.md) | 功能 MVP 完成；剩 ② 微信原生视频(video_item) / 卡片中性模型 / ChannelPushService 见 backlog；真手机扫码端到端 user-gated |
| 5 | **AUTORESEARCH-OPTIMIZATION** | AUTOEVOLVING V2 (a) 子需求：autoResearch 外部调研（arxiv + GitHub trending）→ LLM 2-stage 抽取 → Iron Law 人审 → 自动建 backlog | Full | prd-draft (V2 排期) | P3 | Full | [需求包](requirements/active/2026-05-28-AUTORESEARCH-OPTIMIZATION/index.md) | PRD 已草拟，等 V1 后续 V2 启动 |
| 6 | **ACP-EXTERNAL-AGENT** | SkillForge 经 ACP 编排外部 coding agent（cc/codex）+ 全程可视。Track A=ACP runner / Track B=B1 OTel 适配器。**大段已交付**：P1a~P2-3a + F2(worktree+任务框架) + L1/L3(cc/codex 自测→commit→push→PR) + **cc/codex 双接入**(adapter 按 agent 选) | Full | **大段已交付（见 delivery-index）**：cc+codex 经 ACP 跑通、worktree 隔离、渠道→PR 闭环 live 验过；剩 L2 确认门(暂缓)·codex 工具标签归一化(backlog)·AC-3 确认可达(暂缓)；**L4/L5 决定不做**(部署留人) | P3 | Full | [需求包](requirements/active/2026-06-19-ACP-EXTERNAL-AGENT/index.md) | 闭环已通；后续按需:codex 工具归一化 / agent-teams P1 成本护栏（均 backlog）|

## 阻塞 / 待决策

| ID | 待决策 | 负责人 | 阻塞项 |
| --- | --- | --- | --- |
| **EVOLVE-BADCASE-SENSITIVITY**（CLOSE-LOOP 阶段 B） | 是否升 active：补 weightedScore 0.4 bad-case 权重的尺子敏感度（扩工具确定性误用 + 行为模式失败新 oracle）。基础设施失败明确排除 | 用户 | 阶段 A 已 ship（2026-06-08），用户拍是否升 active |
| **EVAL-429 场景级重试** | infra 失败摘出分母已交付（2026-06-03），剩场景级自动重试是否单独排期 | 用户 | 见 [archive/2026-06-03-EVAL-429-ROBUSTNESS](requirements/archive/2026-06-03-EVAL-429-ROBUSTNESS/index.md) |

## 最近完成

| ID | 完成日期 | Commit | 交付索引 |
| --- | --- | --- | --- |
| COMPACT range-model 存储重构 go-live（默认 on，V157，零膨胀验证；需求包仍 active 有开放项） | 2026-06-19 | P1 `2dac6f8b` … GO-LIVE `068a4a5d` | [delivery-index](delivery-index.md) |
| CHANNEL-MIDTURN-PROGRESS（渠道中途进度推送，飞书默认开/微信默认关） | 2026-06-19 | `43869ded` | [delivery-index](delivery-index.md) |
| 压缩/渠道 go-live 同期 bug 修复（agent 窗口 override / channel 路由 running / reminder 占用%分母） | 2026-06-19 | `99b50868` / `defae3e8` / `40c726f3` | [delivery-index](delivery-index.md) |
| CHANNEL-ASYNC-DELIVERY（异步续跑结果投递回渠道 bug） | 2026-06-19 | `96d4de6e` | [delivery-index](delivery-index.md) |
| WECHAT-CHANNEL Slice 2 文件发送 + client_id 去重修复 + 渠道标签 | 2026-06-19 | `850384de` / `9a6a03db` | [delivery-index](delivery-index.md) |
| WECHAT-CHANNEL B-native Slice 1（iLink adapter 文本双向+扫码） | 2026-06-18 | `f19cb70d` | [delivery-index](delivery-index.md) |
| EVOLVE-CANDIDATE-GROUNDING Phase 2（候选 grounding + 最小编辑） | 2026-06-18 | `775fe4df` | [delivery-index](delivery-index.md) |
| EVOLVE-JUDGE-GROUNDING Phase 1（配对 net-wins 判据） | 2026-06-18 | `5be19db9` | [delivery-index](delivery-index.md) |
| MCP-HTTP-ANYSEARCH | 2026-06-15 | `9c57a9fc` | [delivery-index](delivery-index.md) |
| EVOLVE-LOOP-HILLCLIMB 阶段 A | 2026-06-08 | 本次 | [delivery-index](delivery-index.md) |
| AUTOEVOLVE-CLOSE-LOOP evolve workflow 引擎修复 | 2026-06-07 | 本次 | [delivery-index](delivery-index.md) |
| AUTOEVOLVE-CLOSE-LOOP BC-M2a + BC-M1 | 2026-06-04 | 本次 | [delivery-index](delivery-index.md) |
| EVAL-429-ROBUSTNESS（infra 失败摘出分母） | 2026-06-03 | 本次 | [delivery-index](delivery-index.md) |
| AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 1-4 | 2026-06-02 | 本次 | [delivery-index](delivery-index.md) |
| 火山方舟 Ark LLM provider + chat-path 可配 | 2026-06-02 | 本次 | [delivery-index](delivery-index.md) |
| AUTOEVOLVE-AGENT-FLYWHEEL judge per-case rationale（方案 B） | 2026-06-02 | 本次 | [delivery-index](delivery-index.md) |
| AUTOEVOLVE-AGENT-FLYWHEEL 进化 loop 反思 + BUG-1 winner-carry-forward | 2026-06-01 | 本次 | [delivery-index](delivery-index.md) |
| AUTOEVOLVING V1 Sprint 1-4（DSL workflow engine + /autoevolving） | 2026-05-29 | `9000bd5` / `b675ee7` / `85ff279` | [delivery-index](delivery-index.md) |

## 暂缓 / Backlog

| ID | 标题 | 模式 | 触发 |
| --- | --- | --- | --- |
| **SKILL-CURATOR** 🆕 | 技能低使用自动归档 curator（复用 `MemoryConsolidator` 模式套 `SkillEntity.usageCount`，治"无 SkillConsolidator"不对称）。来源 [wiki-takeaway-triage 缺口③](../requirements/backlog/SKILL-CURATOR/index.md)（审计自荐）。用户 2026-06-20 拍板有道理、入表 | Mid 候选 | 易摘 P1,优先于其它 triage 新需求;随时可起 |
| **OUTCOMES-RUBRIC-FOUNDATION** | `t_rubric` entity + grader 隔离 audit（V1）/ AgentLoopEngine 第 5 轴 exit（V2）。DREAMING 姊妹包独立 ship。**注**:= triage 的 OUTCOME-DRIVEN-LOOP（缺口 D）,已在此立项 | Full 候选 | 用户拍是否升 active |
| **WF-CONCURRENT-PIPELINE** | 学 CC 并发 pipeline（stage 重叠/无 barrier）补 workflow 引擎（现 pipeline() 串行）。AUTOEVOLVING V2(d) | Full 候选 | V2 启动 / 多阶段 fan-out 链路成瓶颈时 |
| **CHANNEL-RICH-MESSAGE** | 微信原生视频(iLink video_item，当前视频走 file type4) + 卡片中性模型(飞书原生交互卡片 + 微信降级 text/image，iLink 无 card/button) | Mid/Full 候选 | 有富消息/卡片诉求时 |
| **OTEL-NATIVE-TRACING** | 观测层「仿 OTel」→ 真标准 OTLP（**长期/可选**，非 ACP 必需）。逐文件实测 **~70–100 dev-day** 且付全额成本仍非纯 OTel（kind/event/聚合/cache·cost/blob/origin 无 OTel 语义约定）→ **big-bang 否决**；要做只能渐进（双写→按簇迁读 + 自进化簇并行验证→ETL→下线 LlmSpan）。ACP 可观测已由 B1 适配器满足，此包仅为"标准互通/通用 OTLP 平台"战略目标 | Full 候选 | 仅当明确要标准 OTel 平台时 |
| **CHANNEL-PUSH-SERVICE** | 通用「按 sessionId 主动推送」服务(外部事件/异步工具/agent out-of-band)。CHANNEL-ASYNC-DELIVERY 的 listener 是第一个客户，复用 ReplyDeliveryService；出现第二个客户再抽 | Mid 候选 | 第二个主动推送需求出现时 |
| **WEBSEARCH-SEARXNG-BACKEND** | WebSearch SearXNG 自部署 backend。重要不紧急 | Mid 候选 | 每周搜索量/费用升高或隐私/内网诉求 |
| **SANDBOX-EPHEMERAL-WORKDIR-DRY** | 抽 `EphemeralWorkdir` 小工具 DRY 掉 eval/sandbox 与 CodeSandboxTool 的临时 workdir 重复（~15 行）。ROI 低纯清理 | Solo/Light | 顺手或专门 refactor 时 |
| **EVAL-DYNAMIC-USER-SIM** | 动态用户模拟多轮评测（Phase 2/3） | — | 见需求包 |

## 暂缓需求包

见 `docs/requirements/deferred/`（SEC-1 / BUG-G / P9-4 / TEAM-COORDINATOR-FOUNDATION）。
