---
name: clawhub
description: "Search, browse, and install Claude skill packages from the ClawHub marketplace using the `npx clawhub` CLI. Use `explore --sort downloads` to find popular skills efficiently — do NOT search with many different keywords."
---

# ClawHub Marketplace

Use `npx clawhub` to interact with the ClawHub skill marketplace.

## Browse Popular Skills (Recommended)

To find popular/trending skills, use `explore` with sort — this is the most efficient approach:

```bash
npx clawhub explore --sort downloads --limit 10 --json
```

Sort options: `downloads`, `trending`, `newest`, `rating`, `installs`, `installsAllTime`

## Search Skills

Search by keyword (use only when looking for something specific):
```bash
npx clawhub search "code review" --limit 10
```

## Inspect Skill Details

Get metadata for a specific skill:
```bash
npx clawhub inspect <slug> --json
```

List version history:
```bash
npx clawhub inspect <slug> --versions --json
```

View a specific file in the skill:
```bash
npx clawhub inspect <slug> --file SKILL.md
```

## Security Scan

Before installing, always inspect the skill to check its safety:
```bash
npx clawhub inspect <slug> --json
```

Check the output for any security warnings or suspicious flags.

## Install / Uninstall

**IMPORTANT**: Before running install, you MUST:
1. First run `inspect` to review the skill's metadata and safety status
2. Show the inspect results to the user
3. Ask the user for explicit confirmation before proceeding

Install a skill:
```bash
npx clawhub install <slug>
```

Install a specific version:
```bash
npx clawhub install <slug> --version 1.0.0
```

Uninstall:
```bash
npx clawhub uninstall <slug>
```

## List Installed Skills

```bash
npx clawhub list
```

## Important Notes

- Use `explore --sort downloads` to find popular skills — do NOT repeatedly search with different keywords
- Use `--json` flag for structured output when you need to parse the results
- The `inspect` command shows full details without installing
- Always inspect a skill before installing to verify it is safe
