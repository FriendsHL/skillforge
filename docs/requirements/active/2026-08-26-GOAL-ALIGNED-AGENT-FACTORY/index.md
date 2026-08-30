# GOAL-ALIGNED-AGENT-FACTORY 目标对齐的个人助手能力工厂

---
id: GOAL-ALIGNED-AGENT-FACTORY
mode: full
status: design-proposed
priority: P1
risk: Full
created: 2026-08-26
updated: 2026-08-26
---

## 摘要

SkillForge 的主 Agent 是所有用户 Query 的统一入口。它根据当前目标，优先委派给已有的常驻专业 Agent；没有合适 Agent 时，复用现有 Agent 配置、Tool、Skill、MCP 和 Workflow 组装新的专业 Agent 候选。用户不需要先选择 Agent，主 Agent 始终负责目标理解、编排、验证和最终交付。

“专业 Agent 工厂”是个人助手背后的内部能力，不是要求用户管理多个割裂助手的产品入口。本需求不把产品决策交给模型：助手可以分析、检索、生成和推荐，但不得自行改变用户目标、扩大权限、安装未审查外部代码或把候选版本晋级到生产。

## 阅读顺序

1. [MRD](mrd.md) - 用户原始诉求、核心担忧和产品机会。
2. [PRD](prd.md) - 产品边界、用户流程、功能需求和验收标准。
3. [技术方案](tech-design.md) - 方案取舍、分期架构、安全边界和测试计划。

## 当前状态

- 完整需求与推荐技术方向已形成，关键架构决策已经用户确认；P0a Goal Brief 纵切已完成实现和 Full Pipeline 自动化验证，后续按 P0b/P1 继续推进。
- 设计批准前不实施 Agent Loop、Context、Plugin Runtime 或数据库变更。
- 本包统筹并复用现有 Skill Import/Creator、MCP、Workflow、Eval、Agent Bundle A/B、Canary、Rollback、AutoResearch 和 Context Governance；不平行重建这些系统。

## 核心原则

```text
用户定义目标与边界
        ↓
系统提出能力方案和候选 Agent
        ↓
证据证明候选是否更接近目标
        ↓
用户批准高影响决策
        ↓
小流量启用并可回滚
```

## 分期

| Phase | 名称 | 结果 |
| --- | --- | --- |
| P0a | Goal Brief 旁路验证 | 复用现有 Session Task/消息，验证长期或高风险任务的非阻塞目标确认，不改 Loop/Eval |
| P0b | Goal Contract 与证据 | P0a 验证后再决定最小版本化持久化和 Acceptance Set 关联 |
| P1 | Capability Manifest | 给现有 Prompt/Skill/Tool/MCP/Workflow/Media 建统一能力说明书 |
| P2 | Specialist Profile 与 Task Plan | 复用现有 Agent 配置管理常驻专业 Agent；任务计划只作用于当前 Session/Task |
| P3 | Capability Acquisition | 在许可范围内发现、导入、包装或创建缺失能力，并隔离验证 |
| P4 | Builder 与证据化验收 | 用户可查看、编辑、批准组装方案，并用目标导出的 Eval 验收 |
| P5 | Goal-aligned Evolution | 让 Blueprint 候选进入现有 A/B、Canary、Adopt、Rollback 闭环 |
| P6 | Experimental Runtime SPI | 最后才隔离实验 Context/Compaction/Agent Loop 等高风险策略 |

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 交付 | - |
