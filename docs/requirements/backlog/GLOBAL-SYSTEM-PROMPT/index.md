# GLOBAL-SYSTEM-PROMPT — 实例级全局 system prompt（所有原生 agent 共享）

---
id: GLOBAL-SYSTEM-PROMPT
mode: full
status: backlog
priority: P2
risk: Mid
created: 2026-06-22
source: 用户 2026-06-22「SkillForge 要有一个全局的 system prompt，所有 agent 都共享」。范围经澄清：新增**实例级**全局层（在现有 per-user CLAUDE.md 之上），**仅覆盖原生 agent**，ACP cc/codex 不强加。
---

## 用户请求

> SkillForge 要有一个全局的一个 system prompt，所有的 agent 都要会共享这个 system prompt。

## 现状（开工前必读，避免重造）

SkillForge **已有一个 per-user 全局 prompt**，机制完整：
- `UserConfigEntity.claudeMd`（按 `userId` 唯一，per-user）；dashboard 可编辑（`UserConfigController` GET/POST `/api/user-config/claude-md`）。
- `AgentLoopEngine.claudeMdProvider`（`userId → claudeMd`）在 `EngineConfig:258` 接到 `userConfigService.getClaudeMd(userId)`。
- `SystemPromptBuilder.buildWithBoundary(claudeMd)` 把它作为**第 1 段（stable 段，cache-eligible）**拼在每个 agent 前面。
- 走 `AgentLoopEngine` 的 agent（主 / **子 / 系统 / workflow**，共享同一 engine bean）**都已共享**它。

**两个缺口**（本需求要补的）：
1. 它是 **per-user**，不是 SkillForge **实例级单一全局**。用户要的「一个全局的」= 实例级一份。
2. （范围外，记录在案）ACP 外部 agent cc/codex 的 `AcpAgentRunner.buildCcPrompt` 只拼 agent 自己的 systemPrompt，**不拼全局 prompt** —— 用户已拍**本需求不覆盖 cc/codex**（它们用自己的 persona）。

## 目标

新增**实例级全局 system prompt**（全平台一份，admin 配置），层叠在 per-user CLAUDE.md **之上**，所有走 `AgentLoopEngine` 的原生 agent 共享。

## 拼接顺序（最终）

```
实例级全局 prompt → per-user CLAUDE.md → AGENT.md(agent systemPrompt) → SOUL.md → TOOLS.md → Behavior Rules → [cache boundary] → Context
```

实例级全局在**最前**、属 **stable 段**（cache 友好）。

## 范围

**In：**
- 新增实例级全局 prompt 的存储 + 读取 + dashboard 编辑入口。
- 拼进每个原生 agent 的 system prompt 最前段。
- 空/未配置时不影响现状（行为等同今天）。

**Out：**
- ACP cc/codex（用户已拍不覆盖）。
- per-user CLAUDE.md 不动（保留，实例级叠在其上，两层并存）。
- 不做按 agent 选择性退出实例全局（如需再议）。

## 推荐实现（低 core 扰动）

**关键洞察**：不必动核心文件 `AgentLoopEngine` / `SystemPromptBuilder`。在 `EngineConfig` 的 provider 装配处把两层拼好喂进既有 `claudeMd` 槽即可：

```java
// EngineConfig（server，非 core）
engine.setClaudeMdProvider(userId -> {
    String instanceGlobal = instanceConfigService.getGlobalSystemPrompt(); // 新增
    String perUser = userConfigService.getClaudeMd(userId);
    return joinNonBlank(instanceGlobal, perUser);  // 实例全局 → per-user，"\n\n" 连接
});
```

- 新增单行配置表 `t_instance_config`（无现成实例级配置表）+ `InstanceConfigEntity.globalSystemPrompt`（TEXT）+ `InstanceConfigService` get/save + REST + dashboard 编辑器（参照现有 `/claude-md` 那套 UI）。
- 这样 core 的 `SystemPromptBuilder` 第 1 段 claudeMd 槽现在装「实例全局 + per-user」，顺序天然正确，**core 零改动**。

## 验收点

- AC1：dashboard 设一段实例级全局 prompt → 任意原生 agent（主/子/系统/workflow）一次对话，其 system prompt 最前段含该全局内容（curl 验 LlmRequest.systemPrompt 或日志）。
- AC2：两层并存且顺序正确：实例全局在 per-user CLAUDE.md 之前，per-user 在 agent systemPrompt 之前。
- AC3：实例全局为空 → 行为与今天完全一致（不引入空段/多余换行扰动 cache）。
- AC4：实例全局 + per-user 都设 → 都出现，顺序正确。
- AC5（范围外确认）：cc/codex 的 prompt **不**含实例全局（符合用户决策）。

## 风险 / footgun

- **prompt cache 边界**：实例全局属 stable 段（marker 之前），prepend 不破坏 cache_control 边界；但**改实例全局会使 stable 段变化 → 全用户 cache 失效一次**（可接受，admin 低频改）。
- **token 预算**：实例全局计入每次请求 system prompt token（`RequestTokenEstimator`），别写太长。
- **信任级别**：实例全局是 admin 配置，trusted，**不需要** custom-behavior-rule 那种 XML sanitization；但仍建议长度上限。
- **空值洁净**：未配置时 join 不能引入前导 `\n\n`（AC3）。
- 新增 entity + migration → 走 database-reviewer；触碰 EngineConfig 装配 → java-reviewer 确认 provider 改动不破坏 per-user 路径。

## pipeline

Full（新增持久化 entity + migration = schema 边界红灯）。推荐实现避免触碰 core engine，但仍按 Full 走（schema + 影响每个 agent 的 prompt 装配）。reviewer：java-reviewer + database-reviewer + typescript-reviewer（dashboard 编辑器）。

## 关联

- 现有 per-user 层：`UserConfigEntity.claudeMd` / `SystemPromptBuilder`（core）/ `EngineConfig:258`。
- 范围外盲区记录：`AcpAgentRunner.buildCcPrompt`（cc/codex 不拼全局，本需求不改）。
