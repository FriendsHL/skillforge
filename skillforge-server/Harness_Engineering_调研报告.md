# Harness Engineering 调研报告

> 整理时间：2026年4月
> 来源：GitHub、掘金、OpenAI、Anthropic 等技术社区

---

## 📖 目录

1. [概述](#概述)
2. [核心概念](#核心概念)
3. [OpenAI 官方实践](#openai-官方实践)
4. [Anthropic 最佳实践](#anthropic-最佳实践)
5. [六大核心方法论](#六大核心方法论)
6. [GitHub 热门资源](#github-热门资源)
7. [实战案例](#实战案例)
8. [延伸阅读](#延伸阅读)

---

## 概述

**Harness Engineering（驾驭工程）** 是 2026 年 AI 领域最火的工程范式，由 OpenAI 和 Anthropic 共同推动。

### 核心定义

> **Harness Engineering = 为 AI Agent 构建运行系统**
> 
> 它负责控制 Agent、提供工具、管理执行和校验结果。

### 演进路径

```
提示词工程 (Prompt Engineering) 2023
         ↓
上下文工程 (Context Engineering) 2025
         ↓
驾驭工程 (Harness Engineering) 2026
```

### 核心类比

| 角色 | 描述 |
|------|------|
| 🐴 马 | AI 模型（Codex/Claude），拥有强大的执行力 |
| 🎯 缰绳与马具 (Harness) | 约束、反馈回路、文档、Linter、生命周期管理 |
| 🤠 骑手 | 工程师，提供方向和判断力 |

---

## 核心概念

### Harness 的本质

```
Harness = 约束 + 反馈回路 + 文档 + Linter + 生命周期管理
```

### 核心洞察

> **在 Agent-First 时代，软件工程的核心产出不再是代码，而是让 Agent 高效产出高质量代码的系统。**
> 
> 你设计的不是功能，而是约束、反馈和环境。

### 传统开发 vs Agent-First 开发

**古法编程**：
```
设计 → 写代码 → 测试 → 部署
```

**Agent-First 开发**：
```
设计 Harness → Agent 生成代码 → Agent 测试 → Agent 修复 → Agent 提交 PR
```

---

## OpenAI 官方实践

### 实验背景

- **团队规模**：3 名工程师
- **开发周期**：5 个月
- **代码量**：100 万行代码
- **人工代码**：0 行
- **效率提升**：传统开发的 10 倍

### 核心观点

> 软件工程的核心正在从「写代码」转向「构建 AI 执行系统」（harness），工程师不再是"写代码的人"，而是设计 AI 工作环境的人。

### 四大核心实践经验

#### 1️⃣ Context Engineering

给 AI 足够的上下文，agent 的能力高度依赖上下文。

**关键原则**：
- 不要设计一个大型的 AGENTS.md 文件
- AGENTS.md 只作为目录，保留约 100 行
- 提供指向其他更深层次的链接

**推荐的文档结构**：
```
AGENTS.md
ARCHITECTURE.md
docs/
├── design-docs/
│   ├── index.md
│   ├── core-beliefs.md
│   └── ...
├── exec-plans/
│   ├── active/
│   ├── completed/
│   └── tech-debt-tracker.md
├── generated/
│   └── db-schema.md
├── product-specs/
│   ├── index.md
│   └── new-user-onboarding.md
├── references/
│   ├── design-system-reference-llms.txt
│   └── ...
├── DESIGN.md
├── FRONTEND.md
├── PLANS.md
├── PRODUCT_SENSE.md
├── QUALITY_SCORE.md
├── RELIABILITY.md
└── SECURITY.md
```

#### 2️⃣ Tooling

让 Agent 能执行真实操作，不是每次由人工来执行。

**工具清单**：
- Git
- Shell
- CI
- Test Runner
- Build System

**核心流程**：
```
写代码 → 运行 → 修复 → 循环迭代
```

**关键技巧**：
- Review 要用新的上下文窗口（subagent 或新窗口）
- 每个 Git Worktree 可以启动一个完全隔离的应用实例

#### 3️⃣ Feedback Loops

Agent 需要一个完整的自动反馈系统。

**反馈系统组成**：
- CI
- 单元测试
- Linter
- 静态分析

**自动反馈循环**：
```
Agent 写代码 → 测试失败 → Agent 修复 → 再次运行
```

**可观测性通道**：
- **日志通道**：LogQL 查询接口
- **指标通道**：PromQL 查询接口
- **UI 通道**：Chrome DevTools Protocol

#### 4️⃣ Architectural Constraints

限制 AI 的行为，让 Agent 不会写出混乱代码。

**约束类型**：
- 代码结构
- 模块边界
- API 规则
- Lint Rules

**依赖方向规则**：
```
在每个业务领域内，代码只能通过一组固定的层级进行"前向"依赖：
类型 → 配置 → 存储库 → 服务 → 运行时 → 用户界面

横切关注点（身份验证、连接器、遥测、功能标志）通过明确的接口 Providers 进入
```

---

## Anthropic 最佳实践

### 核心文章

1. **Effective harnesses for long-running agents**
   - Initializer agents
   - Feature lists
   - init.sh
   - Self-verification
   - Handoff artifacts

2. **Harness design for long-running application development**
   - Task state design
   - Evaluator design

### 关键建议

- 让 Agent 能够"看到"运行时状态
- 设计清晰的初始化流程
- 建立自我验证机制
- 创建跨上下文窗口的交接机制

---

## 六大核心方法论

### 方法论一：Context Engineering — 让 Agent "看见"架构

**核心原则**：Agent 无法在上下文中访问到的信息，对它来说等于不存在。

**渐进式披露**：
```
项目根目录/
├── AGENTS.md              ← 全局入口，精简，指向子目录
├── src/
│   ├── api/
│   │   └── AGENTS.md      ← API 层的约定、依赖规则
│   ├── service/
│   │   └── AGENTS.md      ← Service 层的约定
│   └── infra/
│       └── AGENTS.md      ← 基础设施层规则
```

**机械化保鲜**：
- Linter 和 CI 自动验证知识库的正确性和时效性
- "doc-gardening" Agent 定期扫描过时文档，自动发 PR 修复

### 方法论二：Architectural Constraints — 用机器守住架构边界

**反馈闭环设计**：
```
Agent 生成代码
    ↓
自定义 Linter 检测到违规
    ↓
构建失败 + 错误信息包含修复指令
    ↓
Agent 读取错误信息，自动修复
    ↓
再次提交 → 通过
```

**约束举例**：
- 依赖方向控制
- 结构化日志强制
- 文件大小限制
- 命名规范校验
- 循环依赖检测

### 方法论三：Garbage Collection — 把技术债务当 GC 来做

```
传统方式：技术债务累积 → 某天爆发 → 停下来还债（痛苦）
Harness 方式：GC Agent 持续运行 → 小增量清理 → 代码库自我清洁
```

**GC Agent 的工作内容**：
- 扫描并修复架构约束违规
- 清理未使用的代码、过时的接口
- 统一不一致的编码风格
- 修复文档与代码的偏差

### 方法论四：Agent Legibility — 让 Agent 能"看到"运行时

**三大可观测性通道**：
| 通道 | 技术 | 用途 |
|------|------|------|
| UI 通道 | Chrome DevTools Protocol | 截取 DOM 快照、截图、操作页面验证 |
| 日志通道 | LogQL 查询接口 | 查询错误日志、追踪请求链路 |
| 指标通道 | PromQL 查询接口 | 查询延迟、吞吐量、错误率 |

### 方法论五：Bootable per Git Worktree — 每个变更一个独立沙箱

```
Agent-1 (feature-A worktree) → 独立实例-1 → 独立数据库
Agent-2 (feature-B worktree) → 独立实例-2 → 独立数据库
Agent-3 (bugfix-C worktree) → 独立实例-3 → 独立数据库
```

**优势**：
- 独立复现 Bug，不受其他变更干扰
- 独立验证修复，不污染其他环境
- 独立推理 UI 行为，截图结果可信

### 方法论六：Autonomous Workflow — 从 Prompt 到 Merge 全自治流水线

```
一个 Prompt
    ↓
验证代码库当前状态
    ↓
复现 Bug / 理解需求
    ↓
实现修复 / 新功能
    ↓
驱动应用验证（UI + API + 指标）
    ↓
录制演示视频
    ↓
开 PR + 描述变更
    ↓
响应 Review 反馈并修改
    ↓
检测构建失败 → 自动修复
    ↓
合并（或升级给人类判断）
```

---

## GitHub 热门资源

### 🔥 热门仓库

| 仓库 | Stars | 描述 |
|------|-------|------|
| [kevinrgu/autoagent](https://github.com/kevinrgu/autoagent) | 3.6k | Autonomous harness engineering |
| [walkinglabs/awesome-harness-engineering](https://github.com/walkinglabs/awesome-harness-engineering) | 1.4k | Awesome tools & guides for harness engineering |
| [ZhangHanDong/harness-engineering-from-cc-to-ai-coding](https://github.com/ZhangHanDong/harness-engineering-from-cc-to-ai-coding) | 831 | 从 Claude Code 源码到 AI 编码最佳实践（中文书） |
| [walkinglabs/learn-harness-engineering](https://github.com/walkinglabs/learn-harness-engineering) | 532 | Harness engineering 官方风格入门教程 |
| [deusyu/harness-engineering](https://github.com/deusyu/harness-engineering) | 485 | Harness Engineering 学习指南 |

### 📚 推荐阅读列表

**官方文档**：
- [Harness engineering: leveraging Codex in an agent-first world](https://openai.com/index/harness-engineering-leveraging-codex-in-an-agent-first-world/) - OpenAI
- [Effective harnesses for long-running agents](https://www.anthropic.com/engineering/harnesses) - Anthropic
- [Harness design for long-running application development](https://www.anthropic.com/engineering/harness-design) - Anthropic

**社区文章**：
- The Anatomy of an Agent Harness - LangChain
- Harness Engineering - Thoughtworks
- Building effective agents - Anthropic
- Skill Issue: Harness Engineering for Coding Agents
- Your Agent Needs a Harness, Not a Framework - Inngest

---

## 实战案例

### 案例：多 Agent 并行开发架构

```
                Planner Agent
                      │
     ┌────────────────┼────────────────┐
     │                │                │
 Code Agent1    Code Agent2    Code Agent3
     │                │                │
 Worktree1       Worktree2       Worktree3
     │                │                │
   Test            Test            Test
     │                │                │
        └────── Merge / PR ──────────┘
```

**说明**：
- 每个 Agent 同步处理一个 Bug 或 Feature
- 基于对应的 Git worktree 去验证和实施
- 最终再合并提交结果

### 案例：OpenAI 项目中的 AGENTS.md 结构

```markdown
# AGENTS.md (约100行，作为目录)

## 架构概览
- 参见 ARCHITECTURE.md

## 设计原则
- 参见 docs/design-docs/core-beliefs.md

## 产品规范
- 参见 docs/product-specs/index.md

## 执行计划
- 活跃计划: docs/exec-plans/active/
- 已完成: docs/exec-plans/completed/
- 技术债务追踪: docs/exec-plans/tech-debt-tracker.md

## 参考资料
- 设计系统: docs/references/design-system-reference-llms.txt
```

---

## 延伸阅读

### 中文资源

1. **《驾驭工程：从 Claude Code 源码到 AI 编码最佳实践》**
   - 作者：ZhangHanDong
   - 别名：《马书》
   - 在线阅读：https://zhanghandong.github.io/harness-engineering-from-cc-to-ai-coding/

2. **掘金热门文章**：
   - Harness Engineering — AI 时代的工程最佳实践
   - OpenAI 亲自教你如何构建可靠 AI 代码
   - 2026 年 AI 领域最火范式：Harness Engineering 全解析

### 英文资源

1. **OpenAI Official**: Harness engineering: leveraging Codex in an agent-first world
2. **Anthropic Official**: Effective harnesses for long-running agents
3. **Martin Fowler Blog**: Harness Engineering 分析 by Birgitta Böckeler

---

## 总结

### 核心要点

| 维度 | 传统开发 | Harness Engineering |
|------|----------|---------------------|
| 工程师角色 | 写代码的人 | 设计 AI 工作环境的人 |
| 核心产出 | 代码 | 让 Agent 高效产出高质量代码的系统 |
| 质量保证 | 人工 Review | 自动反馈闭环 |
| 技术债务 | 定期清理 | GC Agent 持续清理 |
| 并发开发 | 人工协调 | 多 Agent + 多 Worktree |

### 关键成功因素

1. **上下文工程**：让 Agent 能"看见"架构决策
2. **架构约束**：用机器而非人来守住边界
3. **反馈闭环**：自动测试、自动修复
4. **独立环境**：每个变更一个隔离沙箱
5. **可观测性**：让 Agent 能访问运行时状态

---

*本报告基于公开资料整理，仅供技术研究和学习参考。*