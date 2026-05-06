# 常见问题排查

本文档列出创建和使用 Skill 时的常见问题及解决方法。

## YAML 解析错误

### 问题
```
Failed to parse SKILL.md frontmatter: while parsing a flow sequence...
expected ',' or ']', but got [
```

### 原因
`triggers` 或 `allowed-tools` 数组中包含未转义的方括号 `[`、`]` 或逗号 `,`。YAML 解析器将其解释为嵌套数组的开始。

### 解决
用双引号包裹包含特殊字符的元素：

**错误：**
```yaml
triggers: [find files for [feature], explore project structure]
```

**正确：**
```yaml
triggers: ["find files for [feature]", "explore project structure"]
```

### 预防
在编写 frontmatter 时，如果任何数组元素包含以下字符，始终用双引号包裹：
- `[` 或 `]`
- `,`（在数组元素内部）
- `:`（在数组元素内部）
- `{` 或 `}`

## Skill 无法被触发

### 问题
Skill 创建了，但 Agent 没有在使用时调用它。

### 可能原因和解决

| 原因 | 解决 |
|------|------|
| Description 过于笼统 | 包含具体的任务类型、工具名称、输出格式 |
| 测试用例太简单 | 使用多步骤、需要专业知识的任务 |
| Name 不直观 | 使用动词开头的短语（如 `fix-pr`） |

**好的 description 示例：**
```
用于处理 PDF 文档的技能。支持提取文本、转换格式、合并拆分页面。
当用户提到 PDF 处理、文档转换、页面操作时使用。
```

**差的 description 示例：**
```
一个处理文档的技能。
```

## 上下文消耗过多

### 问题
Skill 加载后，上下文窗口快速耗尽。

### 原因
- SKILL.md 正文过长（>500 行）
- 包含了太多解释性内容
- Agent 已有的常识也被写入

### 解决
1. **删除冗余解释**：对每段话质疑"Agent 真的需要这个吗？"
2. **拆分到 references/**：将详细信息移至参考文档
3. **使用渐进式披露**：SKILL.md 只保留核心工作流

## 重复工作

### 问题
多个测试用例中，SubAgent 都独立编写了类似的脚本。

### 解决
这是一个强烈的信号，表明 skill 应该捆绑该脚本：

1. 将脚本放入 `scripts/` 目录
2. 在 SKILL.md 中告诉 Agent 使用它
3. 这节省了每次未来调用的重复发明轮子

## 改进停滞

### 问题
多次迭代后，skill 质量没有明显提升。

### 可能原因
1. **过度拟合**：更改只适用于当前测试用例
2. **约束过强**：使用了太多 MUST/NEVER，限制了 Agent 的灵活性
3. **缺乏泛化**：没有从反馈中提取通用模式

### 解决
1. **泛化反馈**：思考"这个修改是否适用于其他类似场景？"
2. **解释原因**：用推理代替硬性约束
3. **尝试不同方法**：如果某个方向卡住，尝试分支使用不同的隐喻或工作模式

## 文件结构问题

### 问题
Skill 打包后，某些文件缺失或路径错误。

### 检查清单
- [ ] SKILL.md 在 skill 根目录
- [ ] references/ 中的文件路径与 SKILL.md 中的引用匹配
- [ ] scripts/ 中的脚本有执行权限
- [ ] evals/files/ 中的测试文件存在
- [ ] 没有包含 `.git`、`node_modules` 等无关目录

## 命名问题

### 问题
Skill 名称不符合规范，导致上传失败。

### 规则
- 仅使用小写字母、数字和连字符
- 不能以连字符开头或结尾
- 不能包含连续连字符
- 最大 64 字符

**有效名称：**
- `pdf-processor`
- `gh-issue-manager`
- `data-exporter`

**无效名称：**
- `PDF_Processor`（含大写和下划线）
- `-start-with-dash`（以连字符开头）
- `double--dash`（连续连字符）
