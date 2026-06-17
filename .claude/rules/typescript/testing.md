---
paths:
  - "**/*.ts"
  - "**/*.tsx"
  - "**/*.js"
  - "**/*.jsx"
---
# TypeScript/JavaScript Testing

> This file extends [common/testing.md](../common/testing.md) with TypeScript/JavaScript specific content.

## E2E Testing

SkillForge dashboard 的 e2e 验证用 `agent-browser`（见 [`pipeline.md`](../pipeline.md) Phase Final 清单：`npx agent-browser goto <url>` + `eval` 断言 DOM），不引入独立 e2e-runner agent。Playwright 可作为编写 e2e 脚本的框架参考。
