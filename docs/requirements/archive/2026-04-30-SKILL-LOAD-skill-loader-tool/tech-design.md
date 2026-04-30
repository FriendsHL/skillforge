# SKILL-LOAD Technical Design

## Current Behavior

`SystemPromptBuilder` renders a `## Available Skills` section from visible `SkillDefinition` instances. `AgentLoopEngine.collectTools` also converts each visible `SkillDefinition` into a tool schema with an empty input schema. `AgentLoopEngine.executeToolCall` recognizes a matching `SkillDefinition` name and returns its `promptContent`.

This creates duplicate exposure and N skill-shaped tool schemas.

## Recommended Path

Introduce one reserved loader tool named `Skill`.

Implementation shape:

- Stop rendering `## Available Skills` in `SystemPromptBuilder`.
- Replace the `SkillDefinition` loop in `AgentLoopEngine.collectTools` with a single dynamic tool schema.
- Build the dynamic schema description from the current visible skill list.
- Accept input `{ "name": "skill-name" }`.
- In `executeToolCall`, handle `Skill` before normal Java tool lookup.
- Resolve the requested name through `LoopContext.skillView` when present, otherwise fall back to `SkillRegistry`.
- Return the selected skill's `promptContent`.
- Preserve existing telemetry counters for package skill success/failure.

## Alternatives Considered

### Keep Skill List in System Prompt and Add Path

This is smaller but leaks filesystem-oriented implementation details and depends on file tools being available. It also weakens skill authorization because the model can attempt arbitrary path reads.

### Keep Per-Skill Tools and Remove Prompt List

This reduces duplicate prompt text but still leaves N pseudo tools and keeps skill packages mixed with real executable tools.

## Risks

- `Skill` becomes a reserved tool name; if a Java tool or skill already uses that exact name, the loader should win or the conflict should be rejected by tests.
- Tool-call telemetry currently keys package skill usage by the skill name; the new loader tool must still record telemetry for the loaded package skill, not only for `Skill`.
- Existing tests may assert the old `Available Skills` prompt section and need behavior-focused updates.

## Test Plan

- Unit test that system prompt omits the old `## Available Skills` section.
- Unit test that `collectTools` emits one `Skill` schema with skill names/descriptions and does not emit package skill schemas.
- Unit test that calling `Skill` with an allowed skill returns `promptContent`.
- Unit test that calling `Skill` with a denied/missing skill returns a clear error and records failure telemetry where applicable.
- Run existing core/server unit tests affected by skill exposure.
