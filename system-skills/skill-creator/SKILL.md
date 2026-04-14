---
name: skill-creator
description: 创建、编辑、改进或审计 Skill。当需要从零创建新 skill、改进/审查/清理已有 skill 或 SKILL.md 文件时使用。触发短语如"创建一个 skill"、"优化这个 skill"、"审查 skill"、"create a skill"、"improve this skill"。
---

# Skill Creator

本 Skill 指导你创建高质量的 SkillForge Skill 包。

## 关于 Skill

Skill 是模块化、自包含的知识包，通过向 Agent 的 system prompt 注入专业知识来扩展其能力。Skill 不是 Tool（function calling），而是"教 Agent 如何用 Tool 完成特定任务"的指南。

### Skill 提供什么

1. **专业工作流** — 特定领域的多步骤流程
2. **工具集成指引** — 教 Agent 如何使用特定 CLI、API 或文件格式
3. **领域知识** — 团队规范、Schema、业务逻辑
4. **捆绑资源** — 脚本、参考文档、模板

## 核心原则

### 精简至上

上下文窗口是公共资源。Skill 与系统提示、对话历史、其他 Skill 共享上下文窗口。

**默认假设：Agent 已经很聪明了。** 只添加 Agent 不具备的知识。对每句话提出质疑："Agent 真的需要这个说明吗？"、"这段话值得它的 token 成本吗？"

用简洁的示例代替冗长的解释。

### 设定合适的自由度

根据任务的脆弱性和可变性匹配具体程度：

- **高自由度（文字指引）**：多种方法都可行时，决策依赖上下文
- **中自由度（伪代码或带参数的脚本）**：有首选模式但允许变化
- **低自由度（具体脚本，少量参数）**：操作脆弱易错，一致性至关重要

## Skill 结构

```
skill-name/
├── SKILL.md（必需）
│   ├── YAML frontmatter（必需）
│   │   ├── name:（必需）
│   │   └── description:（必需）
│   └── Markdown 正文（必需）
└── 可选资源
    ├── scripts/      — 可执行脚本（Python/Bash 等）
    ├── references/   — 参考文档（按需加载到上下文）
    └── assets/       — 输出用文件（模板、图标等）
```

### SKILL.md（必需）

- **Frontmatter（YAML）**：包含 `name` 和 `description`。description 是触发 Skill 的关键依据，必须清晰全面地描述 Skill 用途和使用场景。
- **正文（Markdown）**：使用指引，只在 Skill 被触发后加载。

### scripts/（可选）

可执行代码，用于需要确定性可靠或反复编写的任务。

- 适用场景：相同代码反复编写、需要确定性可靠时
- 示例：`scripts/rotate_pdf.py` 用于 PDF 旋转
- 新增脚本必须实际运行测试确保无 bug

### references/（可选）

参考文档，Agent 在工作时按需读取。

- 适用场景：Agent 需要参考的文档（API 规范、数据库 Schema、领域知识）
- 保持 SKILL.md 精简，详细信息放 references/
- 大文件（>10k 字）在 SKILL.md 中提供 grep 搜索模式
- 避免重复：信息只在 SKILL.md 或 references/ 中出现一次

### assets/（可选）

不加载到上下文的文件，用于 Agent 的输出产物（模板、图片、样板代码）。

### 不要包含的内容

- README.md、CHANGELOG.md、安装指南等
- Skill 只包含 Agent 完成任务所需的信息，不包含创建过程的辅助文档

## 渐进式披露

三级加载系统高效管理上下文：

1. **元数据（name + description）** — 始终在上下文中（约 100 字）
2. **SKILL.md 正文** — Skill 触发时加载（<5k 字）
3. **捆绑资源** — Agent 按需加载

保持 SKILL.md 正文在 500 行以内。超出时拆分到 references/ 文件并在 SKILL.md 中明确引用。

### 拆分模式

**模式 1：概览 + 参考文档**

```markdown
# PDF 处理

## 快速开始
[代码示例]

## 高级功能
- **表单填写**：见 [references/forms.md](references/forms.md)
- **API 参考**：见 [references/api.md](references/api.md)
```

**模式 2：按领域组织**

```
bigquery-skill/
├── SKILL.md（概览和导航）
└── references/
    ├── finance.md
    ├── sales.md
    └── product.md
```

用户问销售指标时，Agent 只读 sales.md。

## 创建 Skill 流程

### 1. 理解 Skill 用途

用具体示例理解 Skill 的使用场景。询问：
- "这个 Skill 应该支持什么功能？"
- "能给一些使用示例吗？"
- "用户说什么话应该触发这个 Skill？"

### 2. 规划可复用内容

分析每个示例，识别需要的 scripts/、references/、assets/。

### 3. 创建目录结构

```bash
mkdir -p skill-name/{scripts,references,assets}
```

### 4. 编写 SKILL.md

**Frontmatter 编写要点：**

- `name`：Skill 名称，小写字母 + 数字 + 连字符
- `description`：主要触发机制。包含 Skill 做什么和何时使用。所有"何时使用"信息写在这里（不是正文）。

**正文编写要点：**

- 使用祈使句/动词原形
- 只包含 Agent 不具备的程序性知识
- 对每段话质疑：是否值得 token 成本

### 5. 打包上传

将 Skill 目录打包为 .zip 文件：

```bash
cd skill-name && zip -r ../skill-name.zip . -x ".*"
```

在 SkillForge Dashboard 的 Skills 页面上传 .zip 文件。

### 6. 迭代改进

使用 Skill 完成实际任务后根据反馈改进。

## 命名规范

- 仅使用小写字母、数字和连字符
- 优先用动词开头的短语（如 `fix-pr`、`create-docs`）
- 按工具命名空间提高清晰度（如 `gh-issues`、`docker-deploy`）
- 目录名与 Skill 名一致
