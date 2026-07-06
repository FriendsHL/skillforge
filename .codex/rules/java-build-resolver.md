# Java Build Resolver Rules

Read this when Java/Maven/Spring builds or tests fail to compile.

## Workflow

1. Run the narrowest failing command and capture the first real error.
2. Read the affected file and immediate dependencies.
3. Apply the minimal fix for the build error only.
4. Re-run the same command.
5. If it passes, run the relevant surrounding tests.

## Common Patterns

- `cannot find symbol`: missing import, typo, missing dependency, or stale API.
- incompatible types: fix the type or call site; avoid broad casts unless the
  domain type really is compatible.
- wrong method arguments: check overloads and recent signature changes.
- missing package: add the correct module dependency only after checking existing
  dependency patterns.
- annotation processor errors: inspect Lombok/MapStruct/Spring generated code
  setup before changing business logic.

## Limits

- Do not refactor while fixing the build.
- Do not suppress warnings or errors without explicit approval.
- Stop after three failed fix attempts and reassess the architecture or missing
  external dependency.
