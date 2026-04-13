---
name: clawhub
description: "Search and install Claude skill packages from the ClawHub marketplace using the `npx clawhub` CLI. Search once with a broad keyword, then inspect top results for download counts."
---

# ClawHub Marketplace

Use `npx clawhub` to interact with the ClawHub skill marketplace.

## IMPORTANT: Efficient Search Strategy

ClawHub search does NOT return download counts in results. To find popular skills:
1. Search ONCE with a broad keyword (e.g., "tool", "code", "assistant")
2. Pick the top 5-10 results from that single search
3. Use `inspect` on each to see download/star counts
4. Rank by downloads and report to the user

**Do NOT search with many different keywords** — this wastes iterations and produces redundant results. One broad search + selective inspect is the correct approach.

## Search Skills

```bash
npx clawhub search "code" --limit 15
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

- Search ONCE with a broad keyword, then inspect selectively — do NOT repeatedly search with different keywords
- Use `--json` flag for structured output when you need to parse the results
- The `inspect` command shows full details (including download counts) without installing
- Always inspect a skill before installing to verify it is safe
