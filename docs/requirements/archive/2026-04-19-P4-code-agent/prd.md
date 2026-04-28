# P4 PRD

---
id: P4
status: done
owner: youren
priority: P1
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-19
updated: 2026-04-28
---

## 摘要

实现 Code Agent、CodeSandboxSkill、CodeReviewSkill、ScriptMethod 和 CompiledMethod 审批流。

## 目标

- Agent 可执行受限代码沙箱。
- 支持 script method CRUD。
- 支持 Java compiled method submit/compile/approve。
- 前端提供 HookMethods 管理页面。

## 非目标

- 不允许未审批 compiled method 直接运行。
- 不放开危险命令或任意环境访问。

## 功能需求

- CodeSandboxSkill。
- CodeReviewSkill。
- ScriptMethod 服务和 UI。
- DynamicMethodCompiler。
- CompiledMethodService 审批流。
- Code Agent seed。

## 验收标准

- [x] 沙箱限制生效。
- [x] 编译方法需审批。
- [x] 前端可管理 script/compiled methods。
- [x] 安全 review 修复完成。

## 验证预期

- 后端 service/compiler/sandbox tests。
- 前端 build 和关键 UI 检查。
