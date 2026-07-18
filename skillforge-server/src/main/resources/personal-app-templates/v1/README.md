# SkillForge Personal App templates — v1

These files are platform-owned, offline, mobile-first templates loaded from a fixed classpath allowlist. They are never copied into an Agent run workspace and are not editable tool input.

## Workflow

1. Choose `template_id=ai-daily-brief-v1` or `template_id=budget-planner-v1`.
2. Pass real business data through `initial_data` when calling `PublishInteractiveArtifact`.
3. Omit `file_path`; the server imports the allowlisted template bytes directly.
4. Omit `state_schema`, or provide the exact platform schema from the matching manifest.

The matching manifest JSON contains fallback demo `initialData` and the platform-owned state schema. Caller `initial_data` overrides the demo data, and bridge-provided data takes precedence inside the App.

The budget template keeps category labels and tones in `initial_data.categories`. Its saved and submitted state has the stable shape `{income, amounts: {[categoryKey]: number}, note}`, so caller-defined category keys survive save, restore, and submit without changing the platform schema.

For a custom Personal App, create a new UTF-8, single-file, self-contained offline HTML file in the current run workspace, then use `file_path` plus its matching `state_schema`. Continue to use `PublishChatArtifact` for ordinary supported images and documents.

## Safety contract

- Do not add remote resources, active navigation, network APIs, dynamic code execution, encoded payloads, or silent device access.
- Do not access the full transcript, credentials, filesystem, or native message handler directly.
- Use only `window.SkillForgeArtifact.initialData`, `savedState`, `saveState`, and `submitSnapshot`.
- A template may feature-detect `requestOpenURL`; the App remains responsible for validation and user confirmation when that bridge becomes available.

## Design baseline

- System fonts and semantic light/dark colors.
- 8-point spacing rhythm with 12/16/20-point radii.
- At least 44-pixel interactive targets.
- Safe-area-aware sticky actions.
- Reduced-motion support, visible focus, labels, landmarks, and live status text.
