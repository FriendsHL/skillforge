---
name: evolve
description: Analyze recent development patterns in this project and suggest or generate new commands, agents, or rules to improve the .claude/ toolkit
command: true
---

# Evolve — SkillForge Self-Improvement

Analyzes recent development patterns in the SkillForge project and proposes improvements to the `.claude/` toolkit (rules, agents, commands).

## What to Do

1. **Scan recent git history** for the last 30 commits:
   ```bash
   git log --oneline -30
   ```

2. **Read the current .claude/ structure**:
   ```bash
   ls .claude/rules/ .claude/agents/ .claude/commands/
   ```

3. **Identify recurring patterns** in the commit messages and changed files:
   - Repeated bug types → candidate for a new rule
   - Repeated multi-step workflows → candidate for a new command
   - Repeated review tasks → candidate for a new agent

4. **Check memory files** for feedback patterns:
   ```bash
   ls ~/.claude/projects/-Users-huanglin12-myspace-skillforge/memory/
   ```

5. **Produce a structured evolution report**:

```
============================================================
  EVOLVE ANALYSIS — SkillForge
============================================================

## RULE CANDIDATES
  [Rule name] — [trigger path: **/*.java etc.]
    Based on: [2-3 specific commits or patterns]
    Proposed content summary: ...

## COMMAND CANDIDATES
  /[command-name]
    Based on: [what workflow keeps repeating]
    Steps: ...

## AGENT CANDIDATES
  [agent-name]
    Based on: [what review task keeps coming up]
    Role: ...

## EXISTING ITEMS TO UPDATE
  .claude/rules/java.md — add footgun: [specific issue found in commits]
  .claude/agents/java-reviewer.md — add check for: [specific pattern]

## LOW PRIORITY / SKIP
  [items not worth the overhead]
============================================================
```

6. **If `--generate` is passed**: write the proposed files directly to `.claude/rules/`, `.claude/agents/`, or `.claude/commands/`. Always ask for confirmation before writing unless `--force` is also passed.

## Usage

```
/evolve              # Analyze and report candidates only
/evolve --generate   # Analyze and generate proposed files (asks for confirmation)
/evolve --force      # Analyze, generate, and write without asking
```

## Evolution Heuristics

### → New Rule
- Same type of mistake appears in 2+ commits
- A `java.md` / `frontend.md` footgun was triggered multiple times
- A new library/pattern was introduced that needs constraints

### → New Command
- A multi-step workflow was done manually 3+ times in the last month
- A task involves >5 sequential steps that Claude keeps repeating from scratch

### → New Agent
- A review task has consistent scope (e.g., "review schema changes") and takes >10 tool calls
- An exploration task benefits from isolation to protect parent context

### → Update Existing
- A known footgun rule was violated despite existing in `.claude/rules/`
  → The rule needs better examples or stronger wording
- An agent keeps missing the same class of issue
  → Add an explicit check to the agent's review priorities
