---
name: github
description: "Interact with GitHub repositories, issues, PRs, and CI using the `gh` CLI or GitHub REST API via curl. Supports searching repos by stars, managing issues and PRs, checking CI status."
---

# GitHub Skill

Use the `gh` CLI or GitHub REST API to interact with GitHub.

## Search Repositories

Search repos sorted by stars:
```bash
curl -s "https://api.github.com/search/repositories?q=QUERY&sort=stars&order=desc&per_page=10" | python3 -c "import sys,json; [print(f'{r[\"full_name\"]} ★{r[\"stargazers_count\"]} - {r[\"description\"][:80]}') for r in json.load(sys.stdin)['items']]"
```

Search with topic filter:
```bash
curl -s "https://api.github.com/search/repositories?q=QUERY+topic:ai-agent&sort=stars&order=desc&per_page=10"
```

## Repository Info

Get repo details:
```bash
curl -s "https://api.github.com/repos/OWNER/REPO" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'{d[\"full_name\"]} ★{d[\"stargazers_count\"]} forks={d[\"forks_count\"]}\n{d[\"description\"]}')"
```

## Issues & Pull Requests

List open issues (if `gh` is available):
```bash
gh issue list --repo OWNER/REPO --limit 10
```

List PRs:
```bash
gh pr list --repo OWNER/REPO --limit 10
```

View PR with checks:
```bash
gh pr checks PR_NUMBER --repo OWNER/REPO
```

## CI / Workflow Runs

List recent runs:
```bash
gh run list --repo OWNER/REPO --limit 10
```

View failed logs:
```bash
gh run view RUN_ID --repo OWNER/REPO --log-failed
```

## Advanced API Queries

Use `gh api` or `curl` for anything not covered above:
```bash
gh api repos/OWNER/REPO/pulls/55 --jq '.title, .state, .user.login'
```

JSON output with jq filtering:
```bash
gh issue list --repo OWNER/REPO --json number,title --jq '.[] | "\(.number): \(.title)"'
```

## Important Notes

- Use `curl` + GitHub REST API when `gh` CLI is not installed
- Always use `--json` or `| python3 -c` for structured output
- GitHub API has rate limits (60/hour unauthenticated, 5000/hour with token)
- For repo search, use `sort=stars` to find popular projects efficiently
