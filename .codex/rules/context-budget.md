# Context Budget Rules

Read this when changing agent rules, prompts, commands, plugins, MCP/tool exposure, or when the session feels slower or lower quality.

## When To Audit

- After adding a new rule, agent, command, or prompt-heavy file.
- When sessions become slow or output quality drops.
- Before adding more prompt material.
- Every two to three weeks.

## Rules

- Keep `CLAUDE.md` and `AGENTS.md` as indexes and compact operating rules.
- Move long maintenance notes into non-auto-loaded meta files.
- Keep agent descriptions short, ideally 30 words or fewer, because descriptions are loaded frequently.
- Prefer narrowing path triggers, moving rare guidance to meta files, or adding SkillForge override notes over duplicating large vendored rules.
- Treat MCP/tool schema growth as the largest context-budget lever.

## Suggested Audit Commands

```bash
find .claude/rules -name "*.md" -exec wc -l {} \; | sort -n
find .codex/rules -name "*.md" -exec wc -l {} \; | sort -n
find .claude/agents -name "*.md" -exec wc -l {} \; 2>/dev/null | sort -n
wc -l CLAUDE.md AGENTS.md
```
