# Performance Optimization

> **SkillForge override (2026-06-04)**：本文件是 ECC 通用模板。**「Model Selection Strategy」一节在 SkillForge 不生效**，子 agent 模型选型以 [`pipeline.md`](../pipeline.md) 第四节为准（Reviewer/Planner Sonnet、Dev/Judge Opus）。下方模型选型表仅作通用参考，型号名按当前可用模型理解（Opus 4.8 / Sonnet 4.6 / Haiku 4.5）。

## Model Selection Strategy（通用参考，SkillForge 走 pipeline.md §4）

**Haiku（轻量、高频调用）**：
- Lightweight agents with frequent invocation
- Pair programming and code generation
- Worker agents in multi-agent systems

**Sonnet（编码辅助 / 审查 / 信息整理）**：
- Reviewer / Planner / Explorer 等辅助角色
- Orchestrating multi-agent workflows

**Opus（主力编码 + 最深推理）**：
- 主力开发 / 写代码（main development work）
- Complex coding tasks
- Complex architectural decisions
- Maximum reasoning requirements
- Research and analysis tasks

## Context Window Management

Avoid last 20% of context window for:
- Large-scale refactoring
- Feature implementation spanning multiple files
- Debugging complex interactions

Lower context sensitivity tasks:
- Single-file edits
- Independent utility creation
- Documentation updates
- Simple bug fixes

## Extended Thinking + Plan Mode

Extended thinking is enabled by default, reserving up to 31,999 tokens for internal reasoning.

Control extended thinking via:
- **Toggle**: Option+T (macOS) / Alt+T (Windows/Linux)
- **Config**: Set `alwaysThinkingEnabled` in `~/.claude/settings.json`
- **Budget cap**: `export MAX_THINKING_TOKENS=10000`
- **Verbose mode**: Ctrl+O to see thinking output

For complex tasks requiring deep reasoning:
1. Ensure extended thinking is enabled (on by default)
2. Enable **Plan Mode** for structured approach
3. Use multiple critique rounds for thorough analysis
4. Use split role sub-agents for diverse perspectives

## Build Troubleshooting

If build fails:
1. Use **java-build-resolver** agent（SkillForge 的 Maven/Java 构建排查 agent）
2. Analyze error messages
3. Fix incrementally
4. Verify after each fix
