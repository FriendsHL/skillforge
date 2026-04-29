# Context Budget

> 来源：[ECC/skills/context-budget](https://github.com/affaan-m/everything-claude-code)。SkillForge 适配：**定期 audit** system prompt 加载量（rules + agents + commands + CLAUDE.md），防 token 膨胀拖慢任务和降低质量。

## 何时触发 audit

- 加了新 rule / agent / command 之后
- session 启动慢、output 质量降
- 计划再加东西前，先看是否有空间
- **每 2-3 周一次定期 audit**

## SkillForge 当前组件清单（持续维护）

> 加 / 删组件时同步更新这一节，且**作为 audit 基线**。

- **项目专属 rules**（`.claude/rules/*.md`）：`pipeline.md` / `pipeline-meta.md`（不自动加载）/ `docs-reading.md` / `think-before-coding.md` / `verification-before-completion.md` / `systematic-debugging.md` / `context-budget.md` / `java.md` / `frontend.md` / `design.md` —— **9 个自动加载** + 1 meta
- **vendored ECC rules**：`common/*` (7) + `web/*` (7，其中 design-quality / performance 已加 SkillForge override) + `java/*` + `typescript/*` —— 至少 **14+ 个**
- **agents**（10 个）：java-reviewer / typescript-reviewer / code-reviewer / security-reviewer / database-reviewer / architect / java-build-resolver / tdd-guide / performance-optimizer / refactor-cleaner
- **commands**（5 个）：feature-dev / code-review / tdd / refactor-clean / evolve
- **CLAUDE.md**：~95 行
- **MCP servers**：检查 `.mcp.json`（如有）

## Audit 步骤

### Phase 1：Inventory

```bash
# rules 行数 + 估算
find .claude/rules -name "*.md" -not -path "*/web/*" -not -path "*/common/*" -not -path "*/java/*" -not -path "*/typescript/*" -exec wc -l {} \; | sort -n
# vendored
find .claude/rules/common .claude/rules/web .claude/rules/java .claude/rules/typescript -name "*.md" -exec wc -l {} \; 2>/dev/null | sort -n
# agents
find .claude/agents -name "*.md" -exec wc -l {} \; 2>/dev/null | sort -n
# CLAUDE.md
wc -l CLAUDE.md
```

**Token 估算**：
- 中文 prose：`chars / 2` ≈ token
- 英文：`words × 1.3`
- 代码混合：`lines × ~13`（粗估）

### Phase 2：分桶

| 桶 | 标准 | 处理 |
|---|---|---|
| **Always needed** | 在 CLAUDE.md 表格里 / 是当前任务必读 / 项目专属规则 | 留 |
| **Sometimes needed** | 路径触发但当前任务无关（做 Java 任务时的 web rules） | 考虑改窄路径 / 加 disclaimer |
| **Rarely needed** | 没被引用 / 与另一文件重叠 / 项目类型不适用（dashboard 项目的 marketing rules） | 加 SkillForge override / 移到 meta 不自动加载 / 删 |

### Phase 3：常见 issue（按 token 节省排序）

1. **Agent description 膨胀** —— `.claude/agents/*.md` frontmatter 的 description 字段每次 Task tool 调用都加载。**>30 词 = 浪费**
2. **Heavy agent 文件** —— `>200 行` 的 agent prompt body 在 spawn 时全文加载
3. **Rule 内容重叠** —— 比如 `common/coding-style.md` 和 `web/coding-style.md` 重复说"small files / no deep nesting"
4. **CLAUDE.md 膨胀** —— `>300 行`；详细解释应该挪到 rules，CLAUDE.md 只做索引
5. **Vendored rules 不适用** —— marketing 内容 / 公网站 perf budget 在 dashboard 项目没用，加 override 注释或移走
6. **MCP 是最大杠杆** —— 每个 tool schema ≈ 500 tokens，30-tool MCP server 比所有 skills 加起来还多

### Phase 4：Report 模板

```
SkillForge Context Budget Audit (yyyy-MM-dd)
═══════════════════════════════════════
当前估算 overhead：~XX,XXX tokens
- 项目 rules（9 个自动加载）: ~Y tokens
- vendored ECC rules（14+ 个）: ~Y tokens
- agents description（10 × ~30 词）: ~Y tokens
- agents body（按需加载）: ~Y tokens
- commands（5 个）: ~Y tokens
- CLAUDE.md: ~Y tokens

Top 3 优化（按节省排序）：
1. [具体动作] → 节省 ~X tokens
2. ...
3. ...

潜在节省：~XX,XXX tokens（XX% 当前 overhead）
```

## SkillForge 已知优化历史

> Audit 后做的优化记到这里，避免重复评估。

- **2026-04-30**：
  - ✅ 拆 `pipeline-meta.md`（流水线演进 / ROI / 迁移到新项目）不自动加载，节省 ~3k token
  - ✅ `web/design-quality.md` + `web/performance.md` 加 SkillForge dashboard override disclaimer（让 LLM 知道 Scrollytelling/3D/landing-page 章节忽略），节省 LLM "思考"成本
  - ⏳ 候选未做：`common/performance.md` 模型选型表与 `pipeline.md` 矛盾 → 已在 CLAUDE.md 标注但未删；`common/development-workflow.md` 研究流程与 `pipeline.md` 重叠 → 已标注未删

## Best Practices

- 加新组件后**立刻** audit，不要积攒（积到几个月后会很难分清谁带来的膨胀）
- **MCP 配置变化**最值得 audit（最大杠杆）
- Agent description 写在 frontmatter `description:` 字段时控制在 **≤30 词**（描述要精准但不啰嗦）
- 用 `pipeline-meta.md` 模式：把"维护用 / 元工作"内容拆出去不自动加载
- Vendored 规则不适用部分加 disclaimer 而不是 fork —— 保持 ECC 一致性，但让 LLM 知道忽略

## 与现有规则关系

- 本规则是**元规则**（关于规则系统本身），不直接指导写代码
- 与 [`pipeline-meta.md`](pipeline-meta.md) 的"流水线演进 post-mortem"互补：那条管 pipeline 流程演进，本条管 system prompt size 演进
- audit 结果如果决定"动 pipeline"，按 `pipeline-meta.md` 的演进流程走
