# SKILL-LOAD PRD

## Goal

Expose skills to the model through one dedicated loader tool instead of one pseudo tool per skill.

## Functional Requirements

1. The LLM request should include one Skill loader tool when the current session has visible skills.
2. The Skill loader tool description should summarize the visible skills with `name` and `description`.
3. Calling the Skill loader tool with `{ "name": "<skill-name>" }` should return that skill's `promptContent`, equivalent to the current `SKILL.md` content.
4. Calling the Skill loader tool with an unknown or unauthorized skill name should return a clear not-found/not-allowed result.
5. The system prompt should no longer include the detailed `## Available Skills` list.
6. Java tools should remain exposed normally.
7. The loader must use the current session's `SessionSkillView` where available, with legacy fallback only for tests or contexts without a session view.

## Acceptance Criteria

- Tool schema collection no longer emits one tool schema per `SkillDefinition`.
- Tool schema collection emits at most one Skill loader schema for package skills.
- Existing built-in tools remain present.
- Skill loading still records skill telemetry for success and failure paths.
- Existing session skill authorization behavior remains intact.
- Targeted unit tests cover prompt rendering, schema collection, allowed load, and denied load.

## Verification

- Run targeted core tests for prompt/tool exposure.
- Run server telemetry tests that cover the SkillDefinition loading path.
- Run relevant backend unit tests from the isolated worktree.
