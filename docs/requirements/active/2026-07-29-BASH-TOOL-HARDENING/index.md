# BASH-TOOL-HARDENING — Bash 执行可靠性与工具手册治理

> 状态：Design Proposed / Full  
> 日期：2026-07-29  
> 范围：`skillforge-tools` BashTool、Global Tool Usage Guidelines、测试与运行验收

## 目标

修复 BashTool 把非零退出误报为成功、超时可能失效、输出无界读取、超时参数未约束和默认继承
敏感环境变量的问题；同时把“什么时候使用 Bash”的稳定决策规则放入 Global Prompt，把参数和能力
说明留在 Tool Schema。

## 阅读顺序

1. [prd.md](prd.md) — 功能要求和验收标准
2. [tech-design.md](tech-design.md) — 方案、取舍、测试与残余风险

## Pipeline

1. Plan Review
2. TDD Development
3. Security / Java / Spec Review
4. Judge
5. QA + application smoke
6. Final Judge
7. Install + restart

## 范围边界

- 本期不把 Bash 改造成容器或独立系统用户沙箱。
- 本期不新增后台命令、持久 Shell、PTY 或 Monitor。
- 本期不修改其他 Tool；只输出同类风险审计清单，逐项由用户确认后另做。
- 不提交或推送，除非用户后续明确要求。
