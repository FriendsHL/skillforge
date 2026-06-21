# SKILLFORGE-SYSTEM-PROMPT — 平台级系统提示词（内置最上层骨架 + 可编辑实例全局）

---
id: SKILLFORGE-SYSTEM-PROMPT
mode: mid
status: delivered（2026-06-22；代码合并 + 全模块测试 3160/0;**live AC1/AC2 待部署后 curl 验**——部署人控）
priority: P2
risk: Mid
created: 2026-06-22
finalized: 2026-06-22（终定方案：全局 + 系统级内置、对用户不可见不可编辑；无表/前端，复用 claudeMd 槽。v1 中文 prompt 用户审过，可进实现）
supersedes: GLOBAL-SYSTEM-PROMPT（已合并删除）
source: 用户 2026-06-22 —— ①「要一个全局 system prompt，所有 agent 共享」②「想搞 SkillForge 最上层自己的 system prompt」，参考 research-docs `Agent-System-Prompt-规格手册.md`。两者合并为一个正式需求。
---

## 用户请求

> SkillForge 要有一个全局的 system prompt，所有 agent 都共享；而且想搞一个 SkillForge **最上层自己的** system prompt（参 leaked-system-prompts 规格手册那种 harness 层 prompt）。固定的工具使用手册等也想放进来。

## 现状（开工前必读，避免重造）

- SkillForge **已有 per-user 全局**：`UserConfigEntity.claudeMd`（按 userId 唯一），`AgentLoopEngine.claudeMdProvider`（`EngineConfig:258` 接 `userConfigService.getClaudeMd`），`SystemPromptBuilder.buildWithBoundary(claudeMd)` 作第 1 段拼进**每个原生 agent**（主/子/系统/workflow，共享同一 engine bean）。
- **但 dashboard 没有编辑 UI**：REST API（`/api/user-config/claude-md`）+ FE `api/userConfig.ts` 都在，**但无任何页面调用**（grep `.tsx` 零命中）→ 现在只能 curl 改，界面改不了。
- **SkillForge 没有产品级"最上层"prompt**：今天 agent 拿到的只有 per-user CLAUDE.md + agent 自己的 systemPrompt + soul + 几条默认 tool guidelines。**缺一个所有 agent 共享的 harness 层骨架**（身份/安全/沟通契约/自主性/工具路由）——即手册说的 Claude Code 那种 `You are Claude Code...` 块。
- **范围外（已记录）**：ACP 外部 agent cc/codex 的 `AcpAgentRunner.buildCcPrompt` 不拼平台 prompt，用户已拍**本需求不覆盖 cc/codex**。

## 目标（2026-06-22 终定：全局 + 系统级，对用户不可见、不可编辑）

给 SkillForge 一个**全局系统提示词**（全平台一份，**内置在代码里**），静默拼进**每个原生 agent** 的 system prompt 最前段：

- **全局**（非 per-user）：一份，全实例共享。
- **系统级、对终端用户不可见、不可编辑**：不挂任何用户/管理界面，纯代码内置资源；改它 = 改代码资源 + 部署（"固定"内容如工具手册由操作者改资源文件）。
- 内容 = **[`global-system-prompt-v1-zh.md`](global-system-prompt-v1-zh.md)**（中文 v1，用户 2026-06-22 审过；结合真实 Main Assistant id=3「先说再做」+ SkillForge 项目情况 + 规格手册 harness 骨架；按 [规格手册](../../../../../research-docs/research/leaked-system-prompts/Agent-System-Prompt-规格手册.md) 11 槽位）。
- **关键事实**：SkillForge 前端把 agent 输出文本当"思考过程"展示 → 用户看流式文本而非隐藏 thinking → 沟通契约"边说边做"（与 Claude Code 相反）。
- **替换**现有 per-user `UserConfigEntity.claudeMd` 机制（那个一直无 UI、空值、per-user）→ 改读这份全局内置内容。
- 范围：仅原生 agent；ACP cc/codex 不覆盖。

## 拼接顺序（最终）

```
全局系统提示词(内置) → AGENT.md(agent 自己的 systemPrompt) → SOUL.md → TOOLS.md → Behavior Rules → [cache 边界] → Context
```

全局段最前、属 stable 段（cache 友好）。原 per-user CLAUDE.md 段被这份全局内置取代。

## 11 槽位 → SkillForge 该填什么（层①草稿依据）

| 槽位 | SkillForge | 决策点 |
|---|---|---|
| 1 身份/定位 | "agent running on SkillForge（服务端 AI agent 平台）"；具体 agent 身份由其自己的 systemPrompt 提供 | 一句话职业定位措辞 |
| 2 安全 | 危害面=Bash/代码执行/工具调用→恶意代码/供应链/注入；已有 `DangerousCommandChecker`（DANGEROUS_PATTERNS 硬拦 rm/sudo / CONFIRMATION_REQUIRED skill 安装）可呼应。照抄"分级+堵合理化+防注入+边界不叙述"结构 | 危害面是否还有遗漏 |
| 3 性格/语气 | 平台默认调性 | **[决策] 冷静直接 vs 温暖；anti- vs pro-engagement** |
| 4 沟通契约 | final message 承载结论、lead with outcome、readable>concise、代码注释只写约束、难逆/外向先确认 | **[决策] 用户看不看得到 thinking**（决定"final 承载全部"成不成立） |
| 5 自主性 | 够了就动手、可逆直接做/destructive 先确认、assessment vs change；SkillForge 有 PendingConfirmationRegistry 可呼应 | 哪些算 destructive 的产品线 |
| 6 工具路由 | Read/Glob/Grep 优先于 Bash cat/find（**已有此默认 guideline**，升级成 first-match 树）；不叙述路由 | — |
| 7 编排 | SubAgent（异步、结果自动回推、**不要轮询**——已有 TeamCreate "do NOT poll" 措辞）、TeamCreate/TeamKill | — |
| 8 记忆 | Memory 工具 + 用户记忆；应用分级 + 不可信输入防注入 | SkillForge 记忆敏感面 |
| 9 上下文/压缩 | SkillForge 有 CompactionService → 只给一句"会被压缩、不用提前收尾"行为提示 | — |
| 10 产物 | 文件输出路径/SHORT-LONG/环境限制 | **[决策] SkillForge 产物目录约定** |
| 元技法 | 优先级显式排序：safety > 平台红线 > helpfulness | — |

## 推荐实现（core 改动极小，无 schema、无前端）

- 把 v1 内容做成 **skillforge-core 内置资源**（resource 文件或常量，如 `global-system-prompt.md`）。
- `EngineConfig` 的 `claudeMdProvider` 改为**忽略 userId、直接返回这份内置全局**（替代 `userConfigService.getClaudeMd(userId)`）。`SystemPromptBuilder` / `AgentLoopEngine` 核心**零改动**（仍走既有 claudeMd 槽）。
- per-user `UserConfigEntity.claudeMd` 停止参与注入（列可保留不读，后续再清理）；无用户/管理 UI。
- **无新表、无 migration、无前端编辑器。**

## 验收点

- AC1：任意原生 agent 一次对话，其 LlmRequest.systemPrompt 最前段 = 全局内置内容（curl/日志验）。
- AC2：换不同 userId 都拿到**同一份**全局（确认非 per-user）。
- AC3：终端用户侧**完全看不到、改不了**这份 prompt（无任何 UI 暴露）。
- AC4：per-user claudeMd 即使有旧值也不再影响注入（已被全局取代）。
- AC5（范围外确认）：cc/codex 的 prompt **不**含这份全局。

## 风险 / footgun

- **prompt cache 边界**：①②属 stable 段，prepend 不破坏边界；但改①或②会使 stable 段变 → 全用户 cache 失效一次（admin/版本低频改，可接受）。
- **token 预算**：①②都计入每次请求 system prompt token（`RequestTokenEstimator`），尤其层②放长手册要注意（固定内容靠 prompt cache 摊薄；非缓存 provider 每次全额）。
- **多 provider**：层①别硬编码模型身份/cutoff（SkillForge 多 provider + per-agent 模型）；身份槽保持 provider 中立。
- **信任级别**：①②是产品/admin 配置，trusted，不需 custom-rule 那种 sanitization；但建议长度上限。
- 无新表/migration；触碰 `EngineConfig` 装配 → java-reviewer 确认替换 per-user→全局后注入链不破、停读 per-user claudeMd 不影响其它调用方。

## pipeline

**Mid**（无 schema、无前端、复用既有 claudeMd 槽，core 引擎零改动）。设计已定稿（v1 用户审过，HARD-GATE 已过）。reviewer：java-reviewer（EngineConfig 替换 + 停读 per-user 影响面）。影响每个原生 agent 的 prompt → Phase Final 必 live 验（curl 看某 agent systemPrompt 最前段）。

## 关联

- 规格手册：`research-docs/research/leaked-system-prompts/Agent-System-Prompt-规格手册.md`（11 槽位 + 6 元技法）
- v0 草稿：[`toplevel-prompt-v0.md`](toplevel-prompt-v0.md)
- 现有 per-user 层：`UserConfigEntity.claudeMd` / `SystemPromptBuilder`（core）/ `EngineConfig`
- 合并自：原 GLOBAL-SYSTEM-PROMPT（已删，内容并入层②）
- 范围外：`AcpAgentRunner.buildCcPrompt`（cc/codex 不拼，本需求不改）
