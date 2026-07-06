# TDD Workflow Rules

Read this when implementing new behavior or bug fixes where a regression test is
practical.

## Cycle

1. RED: write the smallest failing test that captures the behavior or bug.
2. Run that specific test and verify it fails for the expected reason.
3. GREEN: implement the smallest code change that makes it pass.
4. Run the specific test again.
5. REFACTOR: improve structure only after the test is green.
6. Run the relevant surrounding tests.

## Test Design

- Test behavior, not implementation details.
- Include happy path, edge cases, error handling, and boundary values scaled to
  risk.
- For persistence changes, add integration or repository tests where unit tests
  cannot prove the invariant.
- For frontend behavior, add component tests or browser checks for critical user
  flows.
- For regression tests, red-green evidence is preferred: demonstrate the test
  would fail without the fix when practical.

## When TDD Is Not Practical

For operational tasks, config reloads, or external provider validation, document
the manual reproduce command and use that as verification evidence.
