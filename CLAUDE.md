# CLAUDE.md — SkillForge

SkillForge 是一个**服务端 AI Agent 平台**，支持可配置的 Skill、多 Agent 协作、多 LLM Provider 接入。

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 3.2, Java 17, JPA / Hibernate |
| Frontend | React 18, Ant Design, Vite, TypeScript |
| Database | PostgreSQL (embedded zonky, port 15432) |
| Realtime | WebSocket (Spring) |
| LLM | Multi-provider: Claude, OpenAI-compatible (DeepSeek, DashScope, vLLM, Ollama) |
| HTTP Client | OkHttp 4 + SSE streaming |
| Build | Maven multi-module |

## Module Structure

```
skillforge/
├── skillforge-core         # Agent Loop 引擎、LLM 抽象、Skill 系统、Hooks、Context Compaction
├── skillforge-tools        # 系统 Tool 实现（Bash, FileRead, FileWrite, Glob, Grep, Memory, SubAgent）
├── skillforge-server       # Spring Boot Server：REST API、JPA entities、WebSocket、多 Agent 协作
├── skillforge-dashboard    # React 前端
└── skillforge-cli          # CLI 工具
```

## Key Concepts

- **Agent Loop**：`skillforge-core` 中的核心引擎，Message → LLM (streaming) → tool_use → Skill → loop
- **Skill**：实现 `com.skillforge.core.skill.Skill` 接口的工具，Agent 通过 tool_use 调用
- **Session**：一次对话上下文，对应 `SessionEntity`，状态机：idle → running → waiting_user / error → idle
- **SubAgent**：父 Agent 异步派发任务给子 Agent，结果自动回推，不要轮询
- **Multi-Agent Collab**：TeamCreate → 多 Agent 并行运行 → TeamKill

## Build & Run

```bash
# 构建所有模块
mvn clean package -DskipTests

# 启动 server（dev 模式，H2 内嵌数据库）
cd skillforge-server && mvn spring-boot:run

# 运行测试
mvn test
```

## Rules

### 项目专属规则（优先级最高）

| 文件 | 触发路径 | 覆盖内容 |
|------|---------|---------|
| `.claude/rules/docs-reading.md` | 任何非 trivial 任务开工前 | docs 阅读顺序（README → todo → 需求包 index → prd/tech-design），archive 不默认扫，delivery-index 为完成事实唯一来源 |
| `.claude/rules/think-before-coding.md` | 任何非 trivial 任务开工前 / 进行中 | 暴露假设、多解读端出、更简方案 push back、困惑就停；不顺手删无关 dead code（Karpathy 4 原则中 SkillForge 未覆盖的部分） |
| `.claude/rules/verification-before-completion.md` | 任何 claim "通过 / 完成 / 修好了" 之前 | Iron Law：no completion claims without fresh verification evidence；Gate Function 5 步；常见 claim → 必须证据表 |
| `.claude/rules/systematic-debugging.md` | 任何技术问题（bug / test fail / 性能 / 集成）之前 | 4 阶段（root cause → pattern → hypothesis → fix）+ 多组件取证模板（HTTP→Service→LLM→SSE→JPA 5 层）+ 3-fix 失败质疑架构 |
| `.claude/rules/pipeline.md` | 所有非 trivial 开发任务 | 4 档划分（Solo / Light / **Mid** / Full）；红灯 Full / 默认 Mid（1 轮对抗不循环）/ Solo 例外；对抗约束 A/B/C；Reviewer Sonnet + Judge Opus（2026-04-30 调整） |
| `.claude/rules/context-budget.md` | 加新 rule/agent/command 后 / 每 2-3 周定期 | system prompt 加载量 audit：inventory → 分桶 → 优化建议；SkillForge 当前组件清单基线 |
| `.claude/rules/pipeline-meta.md` | **不自动加载**，维护 pipeline 时再读 | 流水线演进观察 / ROI 判断 / 迁移到新项目 |
| `.claude/rules/java.md` | `**/*.java` | ObjectMapper footgun、时间字段、构造器注入、@Transactional、Skill/LlmProvider 扩展、测试、安全 |
| `.claude/rules/frontend.md` | `**/*.tsx` / `**/*.ts` | API 类型、WebSocket cleanup、流式渲染节流、组件规范、状态管理、Ant Design 使用 |
| `.claude/rules/design.md` | `**/*.tsx` / `**/*.css` | 设计质量标准、Anti-template、SkillForge 视觉风格方向 |

### 通用最佳实践规则（自动按路径激活）

| 目录 | 触发路径 | 内容来源 |
|------|---------|---------|
| `.claude/rules/java/` | `**/*.java` | ECC Java rules：代码风格、测试、设计模式、安全 |
| `.claude/rules/web/` | `**/*.tsx` / `**/*.ts` / `**/*.css` | ECC Web rules：编码风格、性能、设计质量、安全。**注意**：`web/design-quality.md` 和 `web/performance.md` 主要面向 marketing 公网站，SkillForge 是 dashboard 项目，文件顶部已标注 SkillForge override；冲突时以 [`design.md`](.claude/rules/design.md) / [`frontend.md`](.claude/rules/frontend.md) 为准 |
| `.claude/rules/typescript/` | `**/*.ts` / `**/*.tsx` | ECC TypeScript rules：类型安全、模式、安全 |
| `.claude/rules/common/` | `**/*` | ECC 通用规则。**被 SkillForge 项目规则覆盖**：① `common/performance.md` 模型选型表（Haiku/Sonnet/Opus）→ 走 [`pipeline.md`](.claude/rules/pipeline.md) 第四节 Reviewer Sonnet + Judge Opus；② `common/development-workflow.md` 研究流程 → 走 `pipeline.md` 整体流程 |

### Agents（按需调用）

| Agent | 何时使用 |
|-------|---------|
| `java-reviewer` | 所有 Java 改动落地后调用 |
| `typescript-reviewer` | 所有 TypeScript/React 改动落地后调用 |
| `code-reviewer` | 任何代码改动后的通用质量检查 |
| `security-reviewer` | 触碰认证、权限、SQL 查询、外部输入处理时必须调用 |
| `database-reviewer` | 新增/修改 Entity、Flyway migration、JPQL/原生 SQL 时调用 |
| `architect` | 新功能设计、跨模块重构、引入新依赖前调用 |
| `java-build-resolver` | Maven 编译/依赖问题排查 |
| `tdd-guide` | 为关键路径代码编写测试时调用 |
| `performance-optimizer` | 性能瓶颈排查 |
| `refactor-cleaner` | 定期死代码清理 |

### Commands（斜杠命令）

| Command | 用途 |
|---------|------|
| `/feature-dev` | 结构化功能开发：需求 → 探索 → 架构 → 实现 → 审查 |
| `/code-review` | 本地或 PR 代码审查 |
| `/tdd` | 测试驱动开发工作流 |
| `/refactor-clean` | 清理死代码 |
| `/evolve` | 分析近期开发模式，建议新的规则/Agent/命令 |
