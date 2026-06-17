# SkillForge ToDo

> 更新于：2026-06-16（docs reconcile：归档已交付 AUTOEVOLVING V1 / AGENT-FLYWHEEL / AGENT-LEVEL-BUNDLE；修 OPT-LOOP-FRAMEWORK 死链；todo 瘦身，旧版存 references/legacy-todo-2026-06-16.md）

> 规则：这里只放当前执行状态；需求和方案细节放在链接的需求包 / archive 中。
> 旧版：重整前长版 ToDo 已保留在 [references/legacy-todo-2026-06-16.md](references/legacy-todo-2026-06-16.md)。

## 当前队列

| 顺序 | ID | 标题 | 模式 | 状态 | 优先级 | 风险 | 文档 | 下一步 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | **AUTOEVOLVING-MASTER** ⭐ | autoEvolving 总包（V2-V5）。V1 已交付，V2-V5 ~5-6 个月（V2 AUTORESEARCH+K-1 / V3 K-4 outer loop+3 信号源 / V4 SkillsBench / V5 框架自进化） | Full 总包 | prd 待 ratify | P2 | Full | [需求包](requirements/active/2026-05-28-AUTOEVOLVING-MASTER/index.md) | 起 V2 子包后启动 Plan |
| 2 | **AUTOEVOLVE-CLOSE-LOOP** | 闭环采纳 + 对靶改进 + benchmark 验证。P1/P2/G5/BC-M1/BC-M2a/engine-fix/阶段A 已交付，阶段B/G3/P3 未做 | Full | 部分交付 | P2 | Full | [需求包](requirements/active/2026-06-03-AUTOEVOLVE-CLOSE-LOOP/index.md) | 阶段B（EVOLVE-BADCASE-SENSITIVITY）等用户拍是否升 active |
| 3 | **AUTORESEARCH-OPTIMIZATION** | AUTOEVOLVING V2 (a) 子需求：autoResearch 外部调研（arxiv + GitHub trending）→ LLM 2-stage 抽取 → Iron Law 人审 → 自动建 backlog | Full | prd-draft (V2 排期) | P3 | Full | [需求包](requirements/active/2026-05-28-AUTORESEARCH-OPTIMIZATION/index.md) | PRD 已草拟，等 V1 后续 V2 启动 |

## 阻塞 / 待决策

| ID | 待决策 | 负责人 | 阻塞项 |
| --- | --- | --- | --- |
| **EVOLVE-BADCASE-SENSITIVITY**（CLOSE-LOOP 阶段 B） | 是否升 active：补 weightedScore 0.4 bad-case 权重的尺子敏感度（扩工具确定性误用 + 行为模式失败新 oracle）。基础设施失败明确排除 | 用户 | 阶段 A 已 ship（2026-06-08），用户拍是否升 active |
| **EVAL-429 场景级重试** | infra 失败摘出分母已交付（2026-06-03），剩场景级自动重试是否单独排期 | 用户 | 见 [archive/2026-06-03-EVAL-429-ROBUSTNESS](requirements/archive/2026-06-03-EVAL-429-ROBUSTNESS/index.md) |

## 最近完成

| ID | 完成日期 | Commit | 交付索引 |
| --- | --- | --- | --- |
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
| **OUTCOMES-RUBRIC-FOUNDATION** | `t_rubric` entity + grader 隔离 audit（V1）/ AgentLoopEngine 第 5 轴 exit（V2）。DREAMING 姊妹包独立 ship | Full 候选 | 用户拍是否升 active |
| **WEBSEARCH-SEARXNG-BACKEND** | WebSearch SearXNG 自部署 backend。重要不紧急 | Mid 候选 | 每周搜索量/费用升高或隐私/内网诉求 |
| **SANDBOX-EPHEMERAL-WORKDIR-DRY** | 抽 `EphemeralWorkdir` 小工具 DRY 掉 eval/sandbox 与 CodeSandboxTool 的临时 workdir 重复（~15 行）。ROI 低纯清理 | Solo/Light | 顺手或专门 refactor 时 |
| **EVAL-DYNAMIC-USER-SIM** | 动态用户模拟多轮评测（Phase 2/3） | — | 见需求包 |

## 暂缓需求包

见 `docs/requirements/deferred/`（SEC-1 / BUG-G / P9-4 / TEAM-COORDINATOR-FOUNDATION）。
