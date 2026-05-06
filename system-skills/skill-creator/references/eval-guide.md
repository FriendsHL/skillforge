# Eval 流程详解

本文档详细说明如何为 Skill 创建和运行评估。

## Evals.json Schema

完整 schema 定义见 [schemas.md](schemas.md)。

### 基本结构

```json
{
  "skill_name": "my-skill",
  "evals": [
    {
      "id": 1,
      "prompt": "用户的任务提示",
      "expected_output": "预期结果描述",
      "files": ["evals/files/input.pdf"],
      "expectations": [
        "输出包含 X",
        "使用了脚本 Y"
      ]
    }
  ]
}
```

### 字段说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `skill_name` | string | ✅ | 与 frontmatter 中的 name 匹配 |
| `evals[].id` | integer | ✅ | 唯一整数标识符 |
| `evals[].prompt` | string | ✅ | 要执行的任务 |
| `evals[].expected_output` | string | ✅ | 成功的人类可读描述 |
| `evals[].files` | string[] | ❌ | 输入文件路径（相对 skill 根目录） |
| `evals[].expectations` | string[] | ❌ | 可验证的陈述列表 |

## 运行评估流程

### Step 1: 准备测试用例

创建 2-3 个真实的测试用例 — 真实用户会说的内容。

**好的测试用例特征：**
- 具体且有明确目标
- 需要多步骤才能完成
- 有可验证的输出

**差的测试用例：**
- "读取这个文件"（太简单，不会触发 skill）
- "做一些事情"（太模糊，无法验证）

### Step 2: 运行 With-skill 和 Baseline

对每个测试用例，用 SubAgent 运行两次：

**With-skill run：**
```
Execute this task:
- Skill path: /path/to/skill-name
- Task: <eval prompt>
- Input files: <files or "none">
- Save outputs to: workspace/iteration-1/eval-1/with_skill/outputs/
- Outputs to save: <关键输出文件>
```

**Baseline run：**
- **新 skill**：无 skill，保存到 `without_skill/outputs/`
- **改进 skill**：用旧版本快照，保存到 `old_skill/outputs/`

### Step 3: 起草 Assertions

在 runs 进行时，为每个测试用例起草定量 assertions。

**好的 assertions：**
- 客观可验证（是/否问题）
- 有描述性名称
- 直接对应 expected_output

**示例：**
```json
{
  "assertions": [
    {
      "name": "output_file_exists",
      "check": "Output file was created"
    },
    {
      "name": "correct_format",
      "check": "Output is in JSON format"
    }
  ]
}
```

### Step 4: 收集 Timing 数据

当 SubAgent 任务完成时，通知中包含 `total_tokens` 和 `duration_ms`。立即保存：

```json
{
  "total_tokens": 84852,
  "duration_ms": 23332,
  "total_duration_seconds": 23.3
}
```

⚠️ 这是捕获 timing 数据的唯一机会！

## 迭代循环

改进 skill 后：

1. 应用改进到 skill
2. 在新 `iteration-<N+1>/` 目录重新运行所有测试
3. 等待用户审查反馈
4. 根据反馈再次改进
5. 重复直到满意

**停止条件：**
- 用户说满意了
- 反馈都是空的（一切看起来很好）
- 没有取得有意义的进展

## 评估指标

| 指标 | 说明 |
|------|------|
| Trigger Rate | Skill 被正确触发的比例 |
| Success Rate | 测试用例通过的比例 |
| Token Efficiency | 使用 skill 后的 token 消耗变化 |
| Duration | 完成任务的时间 |
