# SKILL-LOAD MRD

## User Request

The current skill exposure is too noisy: the system prompt lists available skills, and each skill is also exposed as an individual pseudo tool. The desired direction is either:

- system prompt lists `name + description + path`, and skills are loaded through file access, or
- a single Skill tool advertises the available skills in its description and returns the selected `SKILL.md` content when called with a skill name.

After reviewing the current SkillForge structure, the preferred direction is the second option.

## Problem

Current behavior duplicates skill metadata across prompt and tool schemas. It also mixes skill packages into the same tool namespace as executable Java tools, which increases tool schema token cost and blurs the boundary between tools and skills.

## Constraints

- Keep the session-level skill authorization boundary from `SessionSkillView`.
- Do not expose local absolute paths as the primary loading mechanism.
- Do not require the model to use file tools to load skill instructions.
- Keep this separate from broader skill catalog/root convergence work.
- Touches `AgentLoopEngine` and tool call handling, so this is a Full-risk backend change.

## Non-Goals

- No change to skill package parsing, installation, storage roots, or DB catalog.
- No frontend changes.
- No schema migrations.
- No change to Java tool registration.
