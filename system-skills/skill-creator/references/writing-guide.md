# Skill 写作指南

本文档提供 Skill 正文写作的详细指导。

## 核心原则

### 精简至上

上下文窗口是公共资源。Skill 与系统提示、对话历史、其他 Skill 共享上下文窗口。

**默认假设：Agent 已经很聪明了。** 只添加 Agent 不具备的知识。对每句话提出质疑："Agent 真的需要这个说明吗？"、"这段话值得它的 token 成本吗？"

### 设定合适的自由度

| 自由度 | 适用场景 | 示例 |
|--------|----------|------|
| 高（文字指引） | 多种方法都可行，决策依赖上下文 | "选择最适合当前场景的方法" |
| 中（伪代码/参数化脚本） | 有首选模式但允许变化 | "使用 `process_file.py --input <file>`" |
| 低（具体脚本） | 操作脆弱易错，一致性至关重要 | 固定脚本路径和参数 |

## Writing Patterns

### 定义输出格式

```markdown
## Report structure
ALWAYS use this exact template:
# [Title]
## Executive summary
## Key findings
## Recommendations
```

### 示例模式

```markdown
## Commit message format
**Example 1:**
Input: Added user authentication with JWT tokens
Output: feat(auth): implement JWT-based authentication

**Example 2:**
Input: Fixed null pointer exception in user service
Output: fix(user-service): resolve NPE when user not found
```

### 解释原因

尝试解释为什么重要，而不是使用强硬的 MUST。

**不好：**
```
MUST always validate input before processing.
```

**好：**
```
Validate input before processing to prevent errors and security issues.
LLMs perform better when they understand the reasoning behind instructions.
```

## 渐进式披露结构

### 模式 1：概览 + 参考文档

```markdown
# PDF 处理

## 快速开始
[核心工作流，3-5 步]

## 高级功能
- **表单填写**：见 [references/forms.md](references/forms.md)
- **API 参考**：见 [references/api.md](references/api.md)
```

### 模式 2：按领域组织

```
bigquery-skill/
├── SKILL.md（概览和导航）
└── references/
    ├── finance.md
    ├── sales.md
    └── product.md
```

用户问销售指标时，Agent 只读 sales.md。

## 检查清单

创建 skill 后，检查：

- [ ] SKILL.md 正文 < 500 行
- [ ] 每个段落都有明确目的
- [ ] 复杂主题拆分到 references/
- [ ] references/ 文件有清晰的 TOC（如果 >300 行）
- [ ] 没有重复信息（只在 SKILL.md 或 references/ 中出现一次）
- [ ] 使用了祈使句/动词原形
- [ ] 解释了为什么重要，而非仅说必须做什么
