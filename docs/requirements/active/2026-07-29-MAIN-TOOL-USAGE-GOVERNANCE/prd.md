# 需求：Main Agent 工具使用治理

## 决策

本期不改变现有 ToolSearch / 渐进披露范围。

## 目标

1. 修复 Main Assistant 持有 Register 工具、却没有 CodeSandbox/CodeReview 的不可执行工具链。
2. 为 Web、Session 诊断、协作、Agent 配置、调度、Artifact/Media 增加精简中文选择规则。
3. 缩短罕用工具中过长或混入上层工作流的 Description。
4. 保持已有安全确认、工具 Schema、Provider 条件注册与 Agent Loop 行为不变。

## 验收

- Main Assistant 不再授权 RegisterScriptMethod / RegisterCompiledMethod。
- Code Agent 继续拥有 CodeSandbox、CodeReview 和两个 Register 工具。
- Global Prompt 能回答各组相邻工具“什么时候用哪个”。
- RunWorkflow Description 保留 Inline DSL 完整能力清单、最小可运行示例和沙箱约束，确保没有已注册工作流时模型能创建一次性工作流。
- ImportSkill Description 明确它只接收已安装的本地目录；只有名称或 Git 地址时先走受控安装流程。
- Core/Server 全量测试通过。
- 重启后 Main Assistant 实际工具清单与迁移一致。
