# Verification Before Completion

Read this before claiming work is complete, fixed, passing, or ready.

## Iron Law

Do not claim something is complete, fixed, or passing without fresh verification evidence from the current turn.

## Gate Function

Before any success claim:

1. Identify the command or check that proves it.
2. Run the full command or check fresh.
3. Read stdout, stderr, exit code, and failure counts.
4. Verify the output supports the claim.
5. Report the result with evidence.

## SkillForge Evidence Table

- Java tests require `mvn test` evidence with `BUILD SUCCESS`.
- Frontend build claims require `cd skillforge-dashboard && npm run build` exiting 0.
- Bug-fix claims require the original reproduction steps to show the symptom is gone.
- Regression-test claims require red-green evidence when practical: test fails without the fix and passes with the fix.
- Agent-completion claims require the main session to inspect `git diff` and spot-check changed files, not just trust an agent report.
- Data-persistence claims require API or SQL evidence that the value was actually stored.
- Whole-requirement completion requires checking every `prd.md` / `tech-design.md` acceptance point.
- Frontend usability claims require a real browser check with DOM/text assertions for critical interactions, not only screenshots or build success.
- FE/BE contract claims require checking backend outer envelope shape against frontend API types and mocks. A passing frontend mock is not enough if the mock shape was invented.
- New DTO/wire payload claims require either ObjectMapper roundtrip evidence or live curl raw JSON shape evidence when practical.
- LLM provider connectivity claims require a direct provider probe or application-level request evidence, with secrets redacted.
- Rule-migration claims require `rg --files .codex/rules`, `git diff --check`, and a coverage check against intended source files.

## Completion Gate Commands

- Backend changes: `mvn -pl skillforge-server -am test` unless a narrower command is justified.
- Core/provider/tool changes: include the relevant module in Maven, for example `mvn -pl skillforge-core,skillforge-server -am test`.
- Frontend changes: `cd skillforge-dashboard && npx tsc --noEmit && npm run build`.
- Rule/docs-only changes: no build is required, but run formatting/whitespace checks and inspect the diff.
