# Claude Strategy To Codex Rules Map

Read this when auditing or extending the migration from `.claude` strategy files
into Codex rules.

## Unsupported Claude Mechanics And Codex Equivalents

| Claude concept | Codex-compatible equivalent |
| --- | --- |
| `TeamCreate` / `SendMessage` reviewer loops | Main-session orchestration with `update_plan`, optional subagents when available, and checklist-driven inline review |
| Named Claude agents | Focused `.codex/rules/*` checklists selected by trigger path |
| Slash commands | Rule files plus ordinary Codex tool execution |
| `Write /tmp/review-*.md` as required artifact | Optional local temp artifact when useful; otherwise use the same report sections in the response |
| Claude model selection inside agent frontmatter | Inherit current Codex model unless user explicitly asks otherwise |
| Claude path-trigger frontmatter | `AGENTS.md` progressive-disclosure table and explicit rule reads |

## Source Mapping

| `.claude` source | `.codex` destination |
| --- | --- |
| `.claude/rules/docs-reading.md` | `docs-reading.md` |
| `.claude/rules/think-before-coding.md` | `think-before-coding.md` |
| `.claude/rules/pipeline.md` | `pipeline.md`, `review-verdict.md` |
| `.claude/rules/systematic-debugging.md` | `systematic-debugging.md` |
| `.claude/rules/verification-before-completion.md` | `verification-before-completion.md` |
| `.claude/rules/context-budget.md` | `context-budget.md` |
| `.claude/rules/java.md` | `java.md`, `persistence-shape-invariant.md`, `identity-column-on-rewrite.md`, `llm-provider-compat.md`, `compact-review.md` |
| `.claude/rules/frontend.md` | `frontend.md`, `typescript-review.md` |
| `.claude/rules/design.md` | `design.md` |
| `.claude/agents/code-reviewer.md` | `code-review.md` |
| `.claude/agents/architect.md` | `think-before-coding.md`, `pipeline.md`, `java-design-review.md`, `performance-review.md` |
| `.claude/agents/java-reviewer.md` | `java.md` plus `code-review.md` severity behavior |
| `.claude/agents/typescript-reviewer.md` | `typescript-review.md` |
| `.claude/agents/database-reviewer.md` | `database-review.md` |
| `.claude/agents/security-reviewer.md` | `security-review.md` |
| `.claude/agents/java-design-reviewer.md` | `java-design-review.md` |
| `.claude/agents/compact-reviewer.md` | `compact-review.md` |
| `.claude/agents/llm-provider-compat-reviewer.md` | `llm-provider-compat.md` |
| `.claude/agents/performance-optimizer.md` | `performance-review.md` |
| `.claude/agents/refactor-cleaner.md` and `.claude/commands/refactor-clean.md` | `refactor-clean.md` |
| `.claude/agents/java-build-resolver.md` | `java-build-resolver.md` |
| `.claude/agents/tdd-guide.md` | `tdd-workflow.md` |
| `.claude/commands/tdd.md` | `tdd-workflow.md` |
| `.claude/commands/evolve.md` | `rules-evolution.md` |
| `.claude/commands/code-review.md` | `code-review.md` |
| `.claude/commands/review-verdict.md` | `review-verdict.md` |
| `.claude/commands/feature-dev.md` | `docs-reading.md`, `think-before-coding.md`, `pipeline.md`, `tdd-workflow.md`, `verification-before-completion.md` |
| `.claude/rules/pipeline-meta.md` | `rules-evolution.md`, `context-budget.md` |

## Vendored Rule Mapping

| `.claude/rules` source group | `.codex` destination |
| --- | --- |
| `common/coding-style.md`, `common/patterns.md` | `common-engineering.md`, plus language-specific rules |
| `common/development-workflow.md` | `pipeline.md`, `docs-reading.md`, `tdd-workflow.md`, `verification-before-completion.md` |
| `common/code-review.md` | `code-review.md`, `review-verdict.md`, `pipeline.md` |
| `common/git-workflow.md` | `common-engineering.md` and AGENTS non-negotiables |
| `common/security.md` | `security-review.md`, `common-engineering.md` |
| `common/testing.md` | `tdd-workflow.md`, `verification-before-completion.md` |
| `common/performance.md` | `performance-review.md`, `context-budget.md`, `java-build-resolver.md` |
| `common/hooks.md` | Codex has no Claude hooks; equivalent checks live in completion verification and language rules |
| `java/*` | `java.md`, `java-design-review.md`, `java-build-resolver.md`, `database-review.md`, `security-review.md` |
| `typescript/*` | `frontend.md`, `typescript-review.md`, `security-review.md`, `tdd-workflow.md` |
| `web/*` | `design.md`, `frontend.md`, `performance-review.md`, `security-review.md`, `typescript-review.md` |

## Migration Rule

When `.claude` changes, update the smallest relevant `.codex/rules` file and this
map. Do not copy Claude-specific execution mechanics unless Codex has an
equivalent capability.

## Codex-Native Extensions

- `ios.md` and `ios-pipeline.md` are SkillForge Codex-native extensions added
  after the last Claude strategy refresh. They use dynamic explorer/worker roles
  and main-session judging instead of persistent Claude iOS persona files.
