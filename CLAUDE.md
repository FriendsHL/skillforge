# CLAUDE.md — SkillForge

SkillForge 是一个**服务端 AI Agent 平台**，支持可配置的 Skill、多 Agent 协作、多 LLM Provider 接入。

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 3.2, Java 17, JPA / Hibernate |
| Frontend | React 19, Ant Design 6, Vite, TypeScript, React Router 7 |
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
| `.claude/rules/think-before-coding.md` | 任何非 trivial 任务开工前 / 进行中 | 暴露假设、多解读端出、更简方案 push back、困惑就停；不顺手删无关 dead code；重大设计决策 HARD-GATE + spec self-review checklist |
| `.claude/rules/verification-before-completion.md` | 任何 claim "通过 / 完成 / 修好了" 之前 | Iron Law：no completion claims without fresh verification evidence；Gate Function 5 步；常见 claim → 必须证据表 |
| `.claude/rules/systematic-debugging.md` | 任何技术问题（bug / test fail / 性能 / 集成）之前 | 4 阶段（root cause → pattern → hypothesis → fix）+ 多组件取证模板（HTTP→Service→LLM→SSE→JPA 5 层）+ 3-fix 失败质疑架构 |
| `.claude/rules/pipeline.md` | 所有非 trivial 开发任务 | 4 档划分（Solo / Light / **Mid** / Full）；红灯 Full / 默认 Mid（1 轮对抗不循环）/ Solo 例外；对抗约束 A/B/C；Reviewer 两阶段（spec → quality）；Dev 4 状态分诊（DONE/CONCERNS/NEEDS_CONTEXT/BLOCKED）；Reviewer Sonnet + Judge Opus |
| `.claude/rules/context-budget.md` | 加新 rule/agent/command 后 / 每 2-3 周定期 | system prompt 加载量 audit：inventory → 分桶 → 优化建议；SkillForge 当前组件清单基线 |
| `.claude/rules/pipeline-meta.md` | **不自动加载**，维护 pipeline 时再读 | 流水线演进观察 / ROI 判断 / 迁移到新项目 |
| `.claude/rules/java.md` | `**/*.java` | ObjectMapper footgun、时间字段、构造器注入、@Transactional、Skill/LlmProvider 扩展、测试、安全；**known footgun #4 持久化 vs Engine 内存 Message 形态字节一致** + **#5 加 t_session_message identity 列必扩 rewriteMessages preserve** |
| `.claude/rules/persistence-shape-invariant.md` | `**/CompactionService.java` / `**/SessionService.java` / `**/ChatService.java` / `**/AgentLoopEngine.java` / `**/Message.java` / `**/ContentBlock.java` | Iron Law: ChatService 持久化 Message 跟 Engine 内存 messages list 同位置 message **JSON 字节必须一致**；4 触发条件 + roundtrip 测试模板（来源 Q2 bdb0453 反例 + Q3 cc87776 修 + b2c7039 guard）|
| `.claude/rules/identity-column-on-rewrite.md` | `**/SessionService.java` / `**/SessionMessageEntity.java` / `**/V*.sql` 加列影响 t_session_message | 加 identity 列必扩 `snapshotXByseqNo + patchX` preserve 模式（来源 Q1 a4100f7 trace_id wipe regression）；列分类表 + checklist 4 步 + Light compact index-alignment limitation |
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
| `java-reviewer` | 所有 Java 改动落地后调用；触碰 SessionService rewrite 路径 / 加 t_session_message column / ChatService 持久化 + Engine 内存拼装时显式审 persistence-shape + identity-column 不变量 |
| `java-design-reviewer` | **跟 java-reviewer 并行（不替代）**：重构 / 新加 Service-Repository-Controller 类 / 跨模块抽象改动 / 引入新 interface 或 abstract class / 单类 >500 行时调用。9 维度（SRP / DIP / OCP-设计模式 / 抽象泄漏 / Java 17 现代化 / 可测试性 / 命名意图 / DDD / 跨层调用）开放性视角审查，**不抓 java-reviewer 已抓的清单式问题**。小改动 / bug fix / 单字段**不要**叫，会噪音。用 opus 模型（设计需要深推理） |
| `compact-reviewer` | **触碰 compact 子系统优先调用**（CompactionService / Light/Full/SessionMemoryCompactStrategy / FileStateCache / RecoveryPayloadBuilder / AgentLoopEngine compact 集成 / SessionService.rewriteMessages）；系统提示内嵌 8 条 compact 不变量（INV-1 tool_use↔tool_result pairing / INV-2 SUMMARY USER role / INV-3 boundary 不消失 / INV-4 持久化-engine 字节一致 / INV-5 rewrite preserve identity 列 / INV-6 不可 mutate 共享 Message / INV-7 4 路径覆盖 / INV-8 UTF-16 surrogate-safe） |
| `llm-provider-compat-reviewer` | **触碰 LLM provider 子系统优先调用**（`skillforge-core/llm/**` / ClaudeProvider / OpenAiProvider / ProviderProtocolFamily* / LlmStreamHandler / cache 子目录 / Message + ContentBlock 的 SSE 字段相关改动）；系统提示内嵌 SSE 协议差异速查表 + 9 条历史 provider regression checklist（REG-1 qwen tool call SSE identity / REG-2 enable_thinking 默认 false / REG-3 reasoning_content 双侧声明 / REG-4 reasoning_content SSE 解析 / REG-5 tool_use normalize / REG-6 cache_control 隔离 / REG-7 finish_reason 映射 / REG-8 UsageNormalizer / REG-9 ProviderProtocolFamilyResolver） |
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
| `/review-verdict` | pipeline.md 流派 review verdict 模板（PASS / blocker / warning / nit + Stage 1/2 + r2+ 前轮项复核） |
