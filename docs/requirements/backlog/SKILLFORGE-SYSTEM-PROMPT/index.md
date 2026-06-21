# SKILLFORGE-SYSTEM-PROMPT — 平台级系统提示词（内置最上层骨架 + 可编辑实例全局）

---
id: SKILLFORGE-SYSTEM-PROMPT
mode: full
status: backlog
priority: P2
risk: Mid
created: 2026-06-22
supersedes: GLOBAL-SYSTEM-PROMPT（合并：实例级可编辑全局 = 本需求的「层②」）
source: 用户 2026-06-22 —— ①「要一个全局 system prompt，所有 agent 共享」②「想搞 SkillForge 最上层自己的 system prompt」，参考 research-docs `Agent-System-Prompt-规格手册.md`。两者合并为一个正式需求。
---

## 用户请求

> SkillForge 要有一个全局的 system prompt，所有 agent 都共享；而且想搞一个 SkillForge **最上层自己的** system prompt（参 leaked-system-prompts 规格手册那种 harness 层 prompt）。固定的工具使用手册等也想放进来。

## 现状（开工前必读，避免重造）

- SkillForge **已有 per-user 全局**：`UserConfigEntity.claudeMd`（按 userId 唯一），`AgentLoopEngine.claudeMdProvider`（`EngineConfig:258` 接 `userConfigService.getClaudeMd`），`SystemPromptBuilder.buildWithBoundary(claudeMd)` 作第 1 段拼进**每个原生 agent**（主/子/系统/workflow，共享同一 engine bean）。
- **但 dashboard 没有编辑 UI**：REST API（`/api/user-config/claude-md`）+ FE `api/userConfig.ts` 都在，**但无任何页面调用**（grep `.tsx` 零命中）→ 现在只能 curl 改，界面改不了。
- **SkillForge 没有产品级"最上层"prompt**：今天 agent 拿到的只有 per-user CLAUDE.md + agent 自己的 systemPrompt + soul + 几条默认 tool guidelines。**缺一个所有 agent 共享的 harness 层骨架**（身份/安全/沟通契约/自主性/工具路由）——即手册说的 Claude Code 那种 `You are Claude Code...` 块。
- **范围外（已记录）**：ACP 外部 agent cc/codex 的 `AcpAgentRunner.buildCcPrompt` 不拼平台 prompt，用户已拍**本需求不覆盖 cc/codex**。

## 目标

给 SkillForge 平台级系统提示词，**两层**，叠在 per-user CLAUDE.md 之上、所有**原生** agent 共享：

- **层①「最上层内置骨架」（产品自带，本需求新增）**：harness 层 prompt（身份/安全/沟通契约/自主性/工具路由/编排/记忆/上下文/产物），按 [`Agent-System-Prompt-规格手册`](../../../../../research-docs/research/leaked-system-prompts/Agent-System-Prompt-规格手册.md) 的 11 槽位 + 6 元技法设计。**代码/资源内置，设计一次随版本演进，不在 dashboard 随便改。** v0 草稿见 [`toplevel-prompt-v0.md`](toplevel-prompt-v0.md)。
- **层②「实例级可编辑全局」（= 原 GLOBAL-SYSTEM-PROMPT 合并进来）**：admin 在 dashboard 编辑的实例级内容（固定工具手册、本实例自定规则）。单行配置表 + 编辑器。

## 拼接顺序（最终）

```
① 内置最上层骨架 → ② 实例级可编辑全局 → per-user CLAUDE.md → AGENT.md(agent systemPrompt) → SOUL.md → TOOLS.md → Behavior Rules → [cache 边界] → Context
```

①② 都在 stable 段（cache 友好）。①最前、最稳。

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

## 推荐实现（低 core 扰动）

- **层①**：内置资源（如 `skillforge-core` resource 文件或常量）+ 注入。最简洁：在 `EngineConfig` 的 provider 处把 `内置骨架 + 实例全局 + per-user` 三段拼好喂进既有 `claudeMd` 槽 → **core 的 SystemPromptBuilder/AgentLoopEngine 零改动**。
- **层②**：新单行配置表 `t_instance_config` + `InstanceConfigEntity.globalSystemPrompt` + service + REST + **dashboard 编辑器**（现成的 per-user UI 不存在，要新建；可顺带把 per-user CLAUDE.md 的编辑器也补上）。
- 组合函数 `joinNonBlank(builtinTopLevel, instanceGlobal, perUserClaudeMd)`，空段不引入多余换行（保 cache）。

## 验收点

- AC1：任意原生 agent 一次对话，其 LlmRequest.systemPrompt 最前依次含【内置骨架 → 实例全局 → per-user → agent 自己】，顺序正确（curl/日志验）。
- AC2：层①内置、随版本走；层②dashboard 可编辑并即时生效。
- AC3：层②为空 / per-user 为空时，行为与今天一致（不引入空段/多余换行扰 cache）。
- AC4（范围外确认）：cc/codex 的 prompt **不**含平台 prompt（符合用户决策）。
- AC5：层①草稿 v0 经用户 review 定稿（11 槽位决策点全部拍板）。

## 风险 / footgun

- **prompt cache 边界**：①②属 stable 段，prepend 不破坏边界；但改①或②会使 stable 段变 → 全用户 cache 失效一次（admin/版本低频改，可接受）。
- **token 预算**：①②都计入每次请求 system prompt token（`RequestTokenEstimator`），尤其层②放长手册要注意（固定内容靠 prompt cache 摊薄；非缓存 provider 每次全额）。
- **多 provider**：层①别硬编码模型身份/cutoff（SkillForge 多 provider + per-agent 模型）；身份槽保持 provider 中立。
- **信任级别**：①②是产品/admin 配置，trusted，不需 custom-rule 那种 sanitization；但建议长度上限。
- 新增 entity + migration → database-reviewer；触碰 EngineConfig 装配 → java-reviewer 确认不破坏 per-user 路径。

## pipeline

Full（新增持久化 entity + migration = schema 红线；且影响每个原生 agent 的 prompt 装配）。先 **Plan/设计**：层① v0 prompt 经用户定稿（HARD-GATE，重大设计决策）→ 再实现。reviewer：java-reviewer + database-reviewer + typescript-reviewer（dashboard 编辑器）。

## 关联

- 规格手册：`research-docs/research/leaked-system-prompts/Agent-System-Prompt-规格手册.md`（11 槽位 + 6 元技法）
- v0 草稿：[`toplevel-prompt-v0.md`](toplevel-prompt-v0.md)
- 现有 per-user 层：`UserConfigEntity.claudeMd` / `SystemPromptBuilder`（core）/ `EngineConfig`
- 合并自：原 GLOBAL-SYSTEM-PROMPT（已删，内容并入层②）
- 范围外：`AcpAgentRunner.buildCcPrompt`（cc/codex 不拼，本需求不改）
