---
name: skill-creator
description: 创建、编辑、改进或审计 Skill。当需要从零创建新 skill、改进/审查/清理已有 skill 或 SKILL.md 文件时使用。触发短语如"创建一个 skill"、"优化这个 skill"、"审查 skill"、"create a skill"、"improve this skill"。
---

# Skill Creator

本 Skill 指导你创建高质量的 SkillForge Skill 包。

## 快速开始

创建 skill 的核心流程：

1. **理解用途** — 询问用户 skill 的功能、示例、触发条件
2. **规划结构** — 确定需要的 scripts/、references/、assets/
3. **编写 SKILL.md** — frontmatter + 正文
4. **创建测试** — evals/evals.json 定义测试用例
5. **运行评估** — 用 SubAgent 对比 with-skill vs baseline
6. **迭代改进** — 根据反馈优化，重复步骤 3-5
7. **打包上传** — zip 后上传到 SkillForge Dashboard

## SKILL.md 结构

```
skill-name/
├── SKILL.md          # 必需：frontmatter + 正文
├── evals/            # 可选：测试评估
│   ├── evals.json
│   └── files/
├── scripts/          # 可选：可执行脚本
├── references/       # 可选：参考文档
└── assets/           # 可选：输出模板
```

### Frontmatter（YAML）

| 字段 | 必填 | 说明 |
|------|------|------|
| `name` | ✅ | 小写字母 + 数字 + 连字符 |
| `description` | ✅ | 触发机制，描述做什么和何时使用 |
| `allowed-tools` | ❌ | 允许的工具列表 |
| `triggers` | ❌ | 触发关键词数组 |

⚠️ **YAML 特殊字符**：如果 triggers 包含 `[`、`]`、`,`，必须用双引号包裹：
```yaml
triggers: ["handle [bracket] cases", "process data"]
```

### 正文编写原则

- 使用祈使句/动词原形
- 只包含 Agent 不具备的程序性知识
- 保持 <500 行，超出时拆分到 references/
- 解释**为什么**重要，而非使用强硬的 MUST

## 创建流程详解

### 1. 理解 Skill 用途

询问用户：
- "这个 Skill 应该支持什么功能？"
- "能给一些使用示例吗？"
- "用户说什么话应该触发这个 Skill？"

### 2. 规划可复用内容

分析示例，识别需要的：
- **scripts/** — 反复编写的代码
- **references/** — 需要参考的文档
- **assets/** — 输出模板

### 3. 编写 SKILL.md

创建目录：
```bash
mkdir -p skill-name/{scripts,references,assets,evals/files}
```

编写 frontmatter 和正文，遵循上述原则。

### 4. 创建 Evals

创建 `evals/evals.json`，定义 2-3 个测试用例：

```json
{
  "skill_name": "my-skill",
  "evals": [
    {
      "id": 1,
      "prompt": "用户的任务提示",
      "expected_output": "预期结果描述",
      "files": [],
      "expectations": []
    }
  ]
}
```

### 5. 运行评估

对每个测试用例，用 SubAgent 运行两次：

**With-skill：**
```
Execute this task:
- Skill path: <path-to-skill>
- Task: <eval prompt>
- Save outputs to: <workspace>/iteration-<N>/eval-<ID>/with_skill/outputs/
```

**Baseline：**
- 新 skill：无 skill，保存到 `without_skill/outputs/`
- 改进 skill：用旧版本快照，保存到 `old_skill/outputs/`

### 6. 迭代改进

改进后：
1. 在新 `iteration-<N+1>/` 目录重新运行所有测试
2. 等待用户审查反馈
3. 根据反馈再次改进
4. 重复直到满意

### 7. 打包上传

```bash
cd skill-name && zip -r ../skill-name.zip . -x ".*"
```

上传到 SkillForge Dashboard → Skills 页面。

## 命名规范

- 仅使用小写字母、数字和连字符
- 优先用动词开头（如 `fix-pr`、`create-docs`）
- 目录名与 Skill 名一致

## 常见陷阱

| 问题 | 原因 | 解决 |
|------|------|------|
| YAML 解析错误 | triggers 含未转义的特殊字符 | 用双引号包裹 |
| Skill 无法触发 | description 过于笼统 | 包含具体任务类型、工具名称 |
| 上下文消耗过多 | 正文过于冗长 | 删除冗余，移至 references/ |

## 进阶指南

详细指南见 references/：
- [references/schemas.md](references/schemas.md) — JSON schemas 完整定义
- [references/writing-guide.md](references/writing-guide.md) — 写作模式和最佳实践
- [references/eval-guide.md](references/eval-guide.md) — Eval 流程详解
- [references/troubleshooting.md](references/troubleshooting.md) — 常见问题排查
