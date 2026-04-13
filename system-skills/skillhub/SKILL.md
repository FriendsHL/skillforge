---
name: skillhub
description: "Search, browse, and install AI agent skills from the SkillHub marketplace using `npx @skill-hub/cli`. Supports trending, leaderboard, search, and install to various agents."
---

# SkillHub Marketplace

Use `npx @skill-hub/cli` to interact with the SkillHub skill marketplace.

## Browse Popular Skills

Show trending skills:
```bash
npx @skill-hub/cli trending --limit 10 --json --no-select
```

Show all-time top skills (leaderboard):
```bash
npx @skill-hub/cli top --limit 10 --json --no-select
```

Show recently added skills:
```bash
npx @skill-hub/cli latest --limit 10 --json --no-select
```

## Search Skills

Search by keyword:
```bash
npx @skill-hub/cli search "code review" --limit 10 --json --no-select
```

Search with category filter:
```bash
npx @skill-hub/cli search "testing" --category development --json --no-select
```

## Install Skills

Install a skill for Claude:
```bash
npx @skill-hub/cli install SKILL_NAME --agent claude --yes
```

Supported agents: claude, cursor, codex, gemini, copilot, windsurf, cline, roo, opencode

## Important Notes

- Always use `--json` flag for structured output
- Always use `--no-select` to skip interactive prompts
- Use `trending` or `top` to find popular skills — do NOT search with multiple keywords repeatedly
