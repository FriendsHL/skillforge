# SkillForge Agent Skills 集成方案（v2）

> 参考：tiangolo/library-skills — 库内嵌官方 Skill，教 Agent 正确使用库的功能
> 标准：agentskills.io 开放规范
> 日期：2026-05-13

---

## 一、核心理念

**Skill 不是"介绍项目"，而是"教 Agent 怎么用"。**

类比 tiangolo 的做法：
- FastAPI 的 skill → 教 Agent 怎么写依赖注入、WebSocket、BackgroundTasks
- **SkillForge 的 skill → 教 Agent 怎么通过 API 创建 Agent、配置 Skill、管理 Session、做 A/B 测试…**

每个 Skill 就是一份**库作者写的官方使用手册**，随版本同步，Agent 用的时候永远是正确的。

---

## 二、SkillForge 功能地图 → 对应 Skills

| Skill 名称 | 覆盖功能 | 触发场景 |
|-------------|---------|---------|
| **skillforge-agents** | 创建/配置/导入导出 Agent、绑定 LLM Provider、设置 system prompt | "创建一个 agent"、"配置 agent 的模型" |
| **skillforge-skills** | 上传/版本管理/Fork/A/B 测试/Evolution、SKILL.md 格式规范 | "上传一个 skill"、"给 skill 做 A/B 测试" |
| **skillforge-sessions** | Session 生命周期、Chat API、流式消息、取消、SubAgent 派发 | "发一条消息"、"开一个 session"、"派发子任务" |
| **skillforge-memory** | Memory CRUD、LLM 自动合成、提案审批、向量检索 | "存一条记忆"、"查看记忆"、"让 AI 自动总结" |
| **skillforge-eval** | 评估场景、Eval Run、从 Trace 导入、标注、分析 | "评估这个 agent"、"跑个 eval" |
| **skillforge-hooks** | Lifecycle Hooks、Behavior Rules、安全钩子 | "加一个 hook"、"设置安全规则" |
| **skillforge-traces** | Trace 查看、Session Replay、Model Usage 统计 | "看看 trace"、"这个 session 的调用链" |
| **skillforge-channels** | 多通道接入（Web/Feishu/Telegram）、Channel Adapter SPI | "接入飞书"、"配置 Telegram bot" |

---

## 三、每个 Skill 的内容结构

以 **skillforge-agents** 为例：

```
.github/skills/skillforge-agents/
├── SKILL.md
└── references/
    └── agent-api-reference.md
```

**SKILL.md**:

```yaml
---
name: skillforge-agents
description: >
  通过 SkillForge REST API 创建、配置和管理 AI Agent。
  当需要创建新 agent、修改 agent 配置、切换 LLM provider、
  设置 system prompt、导入导出 agent YAML 时使用。
  触发词：创建 agent、配置 agent、agent 模型、system prompt、导入导出 agent。
---
```

正文内容要点：
1. **API 端点速查表** — `POST /api/agents`、`GET /api/agents/{id}`、`PUT /api/agents/{id}` 等
2. **请求体结构** — AgentEntity 的关键字段（name、description、systemPrompt、llmProvider、model、skillIds）
3. **典型用法代码示例** — curl / Java / JS 调用示例
4. **LLM Provider 配置** — 支持的 provider 列表（claude、openai-compatible、deepseek、dashscope、vllm、ollama）和各自的配置项
5. **Agent YAML 格式** — `POST /api/agents/import` 和 `GET /api/agents/{id}/export` 使用的 YAML schema
6. **常见错误和注意事项** — 比如 system prompt 长度限制、model 名称格式等

---

## 四、目录结构

```
skillforge/
├── .github/
│   └── skills/
│       ├── skillforge-agents/
│       │   ├── SKILL.md
│       │   └── references/
│       │       └── agent-api-reference.md
│       ├── skillforge-skills/
│       │   ├── SKILL.md
│       │   └── references/
│       │       └── skill-lifecycle.md
│       ├── skillforge-sessions/
│       │   ├── SKILL.md
│       │   └── references/
│       │       └── session-api-reference.md
│       ├── skillforge-memory/
│       │   ├── SKILL.md
│       │   └── references/
│       │       └── memory-api-reference.md
│       ├── skillforge-eval/
│       │   ├── SKILL.md
│       │   └── references/
│       │       └── eval-workflow.md
│       ├── skillforge-hooks/
│       │   ├── SKILL.md
│       │   └── references/
│       │       └── hook-types.md
│       ├── skillforge-traces/
│       │   └── SKILL.md
│       └── skillforge-channels/
│           ├── SKILL.md
│           └── references/
│               └── channel-spi.md
├── .claude/                    # 保留：Claude Code 专属 rules/agents/commands
├── AGENTS.md                   # 保留：Codex 兼容
├── CLAUDE.md                   # 保留：精简为项目索引 + 指向 .github/skills/
└── system-skills/              # 保留：SkillForge 运行时加载的系统 skill
```

---

## 五、与现有文件的关系

| 文件 | 策略 | 原因 |
|------|------|------|
| `.github/skills/` | **新建** | Agent Skills 标准入口，跨所有 Agent 平台 |
| `CLAUDE.md` | **精简** | 改为项目索引 + 指向 skills，不再重复写功能说明 |
| `.claude/rules/` | **保留不变** | 细粒度 path-triggered 开发规范（踩坑点、代码风格），skills 管不到这个粒度 |
| `.claude/agents/` | **保留不变** | Claude Code sub-agent 定义 |
| `.claude/commands/` | **保留不变** | Claude Code slash commands |
| `AGENTS.md` | **保留不变** | Codex 兼容入口 |
| `system-skills/` | **保留不变** | 运行时 skill，是 SkillForge 平台加载的，不是给 IDE Agent 用的 |

**总结**：
- `.github/skills/` = **教 Agent 怎么调 SkillForge 的 API 使用功能**（跨平台）
- `.claude/rules/` = **教 Agent 怎么在 SkillForge 项目里写代码**（Claude Code 专属）
- `system-skills/` = **SkillForge 平台运行时加载的 Skill**（服务端）

三者各管一层，不冲突。

---

## 六、实施计划

### Phase 1：核心 Skills（本周，~1 天）

先做最常用的 3 个，覆盖 80% 使用场景：

| # | Skill | 内容来源 | 预估 |
|---|-------|---------|------|
| 1 | `skillforge-agents` | AgentController + AgentEntity + AgentYamlMapper | 2h |
| 2 | `skillforge-skills` | SkillController + SkillService + skill-creator skill | 2h |
| 3 | `skillforge-sessions` | ChatController + SessionService + ChatService | 1.5h |

### Phase 2：扩展 Skills（下周，~1 天）

| # | Skill | 内容来源 |
|---|-------|---------|
| 4 | `skillforge-memory` | MemoryController + MemoryService + AdminMemoryLlmSynthesis |
| 5 | `skillforge-eval` | EvalController + EvalScenario + EvalTask |
| 6 | `skillforge-hooks` | LifecycleHookController + BehaviorRuleController |
| 7 | `skillforge-traces` | TracesController + TraceTreeService |
| 8 | `skillforge-channels` | ChannelConfigEntity + ChannelAdapter SPI |

### Phase 3：平台能力（后续迭代）

- SkillForge 平台支持加载 agentskills.io 标准格式 skill
- Dashboard 支持从 GitHub 导入 skill
- SkillForge 发布 SDK 时内嵌 skill（参考 tiangolo 的 `uvx library-skills`）

---

## 七、质量标准

每个 SKILL.md 必须满足：

1. **description 包含完整触发词** — Agent 靠这个匹配，必须覆盖中英文常用说法
2. **API 示例可直接复制执行** — curl 命令带上正确的端口、认证 header
3. **标注版本** — 在 references 里注明对应的 SkillForge 版本，方便后续更新
4. **覆盖错误场景** — 不只写 happy path，常见 4xx/5xx 怎么处理也要写
5. **中文优先** — 因为 SkillForge 目前主要面向国内团队
