# SkillForge

[English](README.md)

**服务端 AI Agent 平台** — 可配置 Skill 系统、多 LLM Provider 接入、多 Agent 协作，以及自进化评测流水线。基于 Spring Boot + React 构建，面向需要完全掌控 Agent 基础设施的团队。

## 为什么选择 SkillForge？

大多数 Agent 框架基于 Python、绑定单一 Provider、只适合原型开发。SkillForge 专为 **Java/Spring 生产团队** 设计：

- **多 Provider LLM** — Claude、DeepSeek、通义千问/百炼、vLLM、Ollama 自由切换，无需改代码
- **多渠道网关** — 同一个 Agent 同时接入 Web、CLI、飞书（WebSocket / Webhook）、Telegram；`ChannelAdapter` SPI 零框架改动即可扩展到微信、Discord、Slack、iMessage
- **真正的 Agent 编排** — 不止是 Chain，支持树形（SubAgent）和网状（TeamCreate/Send）两种拓扑，带持久化状态
- **自进化 Agent** — 自动评测、Prompt A/B 测试、自动晋升流水线
- **全链路可观测** — Langfuse 风格的 Trace、Session 回放、模型用量仪表盘
- **安全护栏** — 可配置生命周期 Hook、命令黑名单、路径穿越防护、防失控循环检测

## 架构

```
┌─────────────────────────────────────────────────────────┐
│   仪表盘      │    CLI    │    飞书     │    Telegram    │
├─────────────────────────────────────────────────────────┤
│              渠道网关                                     │
│   ChannelAdapter SPI │ 三阶段投递事务 │ 去重              │
├─────────────────────────────────────────────────────────┤
│              入口层                                       │
│   REST API   │   WebSocket（会话级 + 用户级）              │
├─────────────────────────────────────────────────────────┤
│              Spring Boot 服务层                           │
│   Chat / Agent / Skill / Memory / Compaction            │
│   SubAgent / 多 Agent 协作 / 评测流水线                    │
│   Lifecycle Hook / 行为规范 / Hook Method                 │
├─────────────────────────────────────────────────────────┤
│              Agent Loop 引擎                              │
│   消息 → LLM（流式）→ tool_use → Tool → 循环              │
│   取消 + 防失控 + 上下文压缩 + Trace 采集                  │
├──────────────┬──────────────┬───────────────────────────┤
│  LLM 层      │ 工具 & Skill  │ 会话 & Hook               │
│  Claude      │ 系统工具      │ 会话管理                    │
│  OpenAI*     │ 系统 Skill    │ Lifecycle Hook 分发器      │
│  DeepSeek    │ 用户 Skill    │ SafetyHook / AskUser       │
│  百炼         │ Hook Method   │ 压缩 / 记忆                │
├──────────────┴──────────────┴───────────────────────────┤
│              存储层                                       │
│   内嵌 PostgreSQL（开发）│ 外部 PostgreSQL（生产）          │
│   + pgvector（记忆） + Flyway 数据库迁移                    │
└─────────────────────────────────────────────────────────┘
* OpenAI 兼容：支持 DeepSeek、通义千问/百炼、vLLM、Ollama
  等任何 OpenAI 格式的端点。
```

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Spring Boot 3.2, Java 17, JPA / Hibernate, Flyway |
| 前端 | React 18, Ant Design, Vite, TypeScript, TanStack Query |
| 数据库 | 内嵌 PostgreSQL（开发零配置）, 外部 PostgreSQL（生产） |
| 实时通信 | WebSocket — 会话级流式推送 + 用户级通知 |
| LLM | 多 Provider：Claude, OpenAI 兼容（DeepSeek, 通义千问/百炼, vLLM, Ollama） |
| HTTP 客户端 | OkHttp 4 + SSE 流式 |
| 构建 | Maven 多模块 |

## 模块结构

```
skillforge/
├── skillforge-core         # Agent Loop 引擎、LLM 抽象、Skill 系统、
│                            #   Hook、取消、上下文压缩、Trace 采集
├── skillforge-tools        # 系统工具：Bash, FileRead, FileWrite, FileEdit,
│                            #   Glob, Grep, Memory, SubAgent, Team*
├── skillforge-server       # Spring Boot 服务：REST API, JPA 实体, 服务层,
│                            #   WebSocket, 多 Agent 协作, 记忆系统, 评测流水线
├── skillforge-dashboard    # React 仪表盘：聊天, 会话, Agent, Skill, Trace,
│                            #   会话回放, 记忆, 模型用量, 团队, 评测
├── skillforge-cli          # CLI 客户端：picocli + OkHttp, Agent YAML 导入/导出
└── system-skills/          # 文件型系统 Skill（启动时自动加载，不可删除）
    ├── browser/            #   浏览器自动化（agent-browser CLI）
    ├── clawhub/            #   ClawHub 市场搜索 + 安装
    ├── github/             #   GitHub API + gh CLI
    ├── skill-creator/      #   Agent 自己创建 / 编辑 / 校验 SKILL.md 包
    └── skillhub/           #   SkillHub 市场
```

## 核心功能

### Agent Loop 引擎

- **智能循环** — 多 Provider LLM + 内置 Skill 系统，每个聊天会话在线程池异步运行，带 429 背压。
- **流式聊天** — 助手文本和 tool_use JSON 逐 token 通过 WebSocket 推送；仪表盘实时渲染增量内容，工具卡片展示 LLM 正在生成的 JSON 片段。
- **循环取消** — `POST /cancel` 设置标志位，引擎在迭代边界检查；仪表盘运行横幅显示取消按钮。
- **Ask 模式 vs Auto 模式** — 按会话配置。Ask 模式下引擎注入 `ask_user` 工具用于多选决策；Auto 模式禁用该工具。

### 多文件 Agent 配置

借鉴 Claude Code 和 OpenClaw 的设计：

| 文件 | 作用域 | 用途 |
|------|--------|------|
| **CLAUDE.md** | 全局 | 所有 Agent 的通用规则和指南 |
| **AGENT.md** | 单个 Agent | 核心指令（`systemPrompt`） |
| **SOUL.md** | 单个 Agent | 人设与语气（可选） |
| **TOOLS.md** | 单个 Agent | 自定义工具使用规则（可选） |
| **RULES.md** | 单个 Agent | 行为规范（结构化 + 自由条目） |
| **MEMORY.md** | 单个 Agent | 从记忆系统自动注入 |

### 多渠道消息网关

一个 Agent，多个前台入口 —— 来自飞书、Telegram、或网页的同一个用户消息都会路由到**同一个** Agent Loop，回复按原渠道送回：

- **`ChannelAdapter` SPI 可扩展** — Spring 自动收集实现，新平台零框架改动即可接入
- **飞书（Lark）** — 同时支持 **WebSocket 长连接**（本地开发无需公网 IP）和 Webhook 模式；SHA-256 事件签名校验；仪表盘在线切换模式，带指数退避 + 抖动重连
- **Telegram** — HTML parse mode，按 codepoint 安全切分 4096 字符上限
- **三阶段投递事务** — `claimBatch` 走 `SELECT FOR UPDATE SKIP LOCKED`、首次入库直接打 `IN_FLIGHT` 标志防 30 秒 race、`applyPrepared` → `persist` —— 崩溃可续，重复投递可控
- **Per-turn `platformMessageId` 映射** — 同一个 session 多轮回复不会命中 unique constraint
- **去重 + 重试 + 超期清扫** — 可配置重试策略、指数退避、过期消息清理
- **仪表盘 /channels 页** — 按平台卡片、会话列表、投递重试面板

### SubAgent 编排（树形拓扑）

父→子任务委派：

- `SubAgent dispatch` 派发子会话异步运行
- 子 Agent 结果自动回传为合成用户消息
- 跨服务器重启持久化（`t_subagent_run` + 启动恢复 + 定时清扫）
- 深度限制（3 层）+ 每父并发限制（5 个子 Agent）

### 多 Agent 协作（网状拓扑）

基于团队的协作，Leader 派发多个 Agent，Agent 之间可以互相通信：

| 工具 | 说明 |
|------|------|
| **TeamCreate** | 创建团队成员（即发即忘，结果自动回传） |
| **TeamSend** | 给同伴发消息，或发给 "parent"、"broadcast" |
| **TeamList** | 列出所有团队成员及状态 |
| **TeamKill** | 取消某个成员或整个协作运行 |

核心能力：CollabRun 分组、handle→session 花名册、邻接策略、深度感知的工具过滤、级联取消、lightContext（节省 ~30-50% token）、带去重的投递重试、超时清扫。

### 行为规范层（Behavior Rules）

按 Agent 维度注入 system prompt 的可配置规范库，约束 Agent 该做什么、不该做什么：

- **15 条内置规范** — `no-commit`、`no-push-without-approval`、`ask-before-delete`、`prefer-immutable` 等
- **预设模板** — 编码 / 研究 / 运维 型 Agent 一键套用
- **自定义规则** — 按 Agent 自由撰写，XML 沙箱防 prompt 注入
- **按语言本地化** — 自动检测 locale，规则文本同步切换
- **Deprecated 链** — 老规则自动 redirect 到替代项，不破坏老 Agent 兼容

### Lifecycle Hooks（生命周期 Hook）

用户可配置的 Hook，在 Agent Loop 的关键节点触发 —— 与 Claude Code 的 Hook 心智模型一致，每个事件支持多 entry 链式执行：

| 事件 | 典型用途 |
|------|---------|
| **SessionStart** | 注入上下文、加载历史、配额不足时阻断 |
| **UserPromptSubmit** | 动态上下文增强、敏感词脱敏、终止 |
| **PreToolUse** | 工具调用前门禁、参数改写、二次确认 |
| **PostToolUse** | 审计、埋点、失败推送告警 |
| **SessionEnd** | 总结、清理资源、导出对话记录 |

**四种 Handler 类型**：

- **Skill** — 调用已有 Skill（逻辑复用）
- **Script** — 内嵌 `bash` / `node` 脚本；沙箱执行、进程树 kill、输出量截断、`/tmp` symlink 防护
- **BuiltInMethod** — 命名内置方法（`HttpPost`、`FeishuNotify`、`LogToFile`）带结构化参数；SSRF 加固的 URL 校验
- **CompiledMethod** — 运行时编译 Java 类（`javax.tools`）→ 审批流 → `child-first` 类加载器隔离（由 **Code Agent** 写代码生成）

每个事件形成一条 **Hook 链**，每个 entry 独立配 `timeoutSeconds`、`failurePolicy`（CONTINUE / ABORT）、`async`、`SKIP_CHAIN` 语义。全部执行路径记录为 `LIFECYCLE_HOOK` trace span，方便排查。

### Code Agent（自扩展的 Agent）

能**给自己写 Hook Method** 的 Agent。Phase 1-3 已交付：

- **CodeSandboxSkill + CodeReviewSkill** — 隔离沙箱执行 + 危险命令检测 + 沙箱化 `HOME`
- **ScriptMethod** — bash/node 脚本，即时生效
- **CompiledMethod** — 进程内 Java 编译 + `FORBIDDEN_PATTERNS` 安全扫描 + child-first 类加载器 + `submit → compile → approve` 审批流
- **仪表盘 HookMethods 页** — 双 Tab（script / compiled）、详情抽屉、审批操作

### 自进化流水线

自动评测与 Prompt 优化：

- **评测执行器** — 在场景集（seed + held-out）上运行 Agent，3 级重试，每场景 90 秒预算
- **LLM 评委** — 2×Haiku + Sonnet 元评委进行 oracle 打分
- **归因引擎** — 7×5 矩阵分类失败原因（skill 缺失、执行失败、prompt 质量、上下文溢出、性能）
- **Prompt A/B 测试** — LLM 生成候选 prompt，在 held-out 集上对比，Δ≥15pp 自动晋升，带 4 层 Goodhart 防护
- **Session→场景提取** — LLM 分析已完成会话生成评测场景，配有 Review UI（通过/编辑/丢弃）
- **仪表盘** — 实时评测运行监控，详情抽屉展示每场景结果

### 记忆系统

跨会话持久记忆，按用户隔离：

- **5 种记忆类型**：偏好、知识、反馈、项目、参考
- **按类型槽位注入** system prompt（8000 字符上限）
- **混合召回** — pgvector（`text-embedding-3-small`，1536 维）+ PostgreSQL `tsvector` 全文检索，通过 **RRF** 融合；pgvector 不可用时优雅降级到 TF-IDF
- **两种提取模式**：
  - `rule` — 规则启发式 `SessionDigestExtractor`（默认，快）
  - `llm` — `LlmMemoryExtractor` 按 5 种类型分类 + 重要性打分
- **Session 结束 `@Async` 即时提取** — session 完成那一刻触发（`@Scheduled` 每日 cron 作为兜底扫孤儿）
- **自动采集** — `ActivityLogHook` 记录每次工具调用
- **整合去重** — 标题去重 + 过期标记（30 天未召回 + recallCount < 3）

### 会话消息存储（行存储，append-only）

**核心不变量：消息只增不删** —— UI 看到的就是全历史，压缩不会丢轮次：

- `t_session_message` 行存储（取代原先的单 CLOB），字段 `seq_no` / `role` / `content_json` / `msg_type` / `metadata_json`
- `getFullHistory`（UI）vs `getContextMessages`（LLM，跨压缩边界拼接 `young-gen + summary + 新消息`）
- 压缩时插入 `COMPACT_BOUNDARY` + `SUMMARY` 行，**从不删除旧消息**
- 支持 checkpoint / branch / restore

### 上下文压缩（JVM-GC 风格）

- **Light** — 规则压缩：截断大输出、去重搜索、折叠失败、**可压缩工具白名单**（P9-1）
- **Full** — LLM 摘要压缩，保留 `tool_use ↔ tool_result` 配对，3-phase split（guard → LLM 无锁 → persist 落库）控制并发
- **Time-based cold cleanup** — 会话空闲达到阈值后修剪陈旧工具输出（P9-3）
- **Session-memory 压缩** — 零 LLM 兜底，用 `MemoryService.previewMemoriesForPrompt` 替代 LLM 摘要（P9-6）
- **6 种触发源**：token 预算安全网、空闲间隙、LLM 工具调用、引擎自动触发、手动 API、浪费检测
- **Context Breakdown API** — `GET /api/chat/sessions/{id}/context-breakdown` 返回各段（system prompt / tool schemas / 消息区）实时占比 + Agent 实际 context window
- 所有事件记录在 `t_compaction_event` 供审计

### 可观测性

**Trace（Langfuse 风格）** — 每次 Agent Loop 记录为带层级 span 的结构化 Trace：

```
AGENT_LOOP（根节点）
├── LLM_CALL（迭代 0 — token 数 + 耗时）
├── TOOL_CALL（Bash — 输入、输出、耗时）
├── LLM_CALL（迭代 1）
├── ASK_USER（阻塞等待用户）
├── COMPACT（上下文压缩）
└── LLM_CALL（最终迭代）
```

**会话回放** — 将平铺的消息历史重组为 Turn → Iteration → Tool 调用，带时间线。

**模型用量仪表盘** — 按日/按模型/按 Agent 统计 token 消耗和成本。

### 安全 & 防失控

**SafetySkillHook**：
- 拦截危险命令（`rm -rf /`、`sudo`、`mkfs`、`shutdown`、`curl|sh`、fork 炸弹）
- 拦截未确认的市场安装
- 拦截向系统目录和敏感文件的写入
- 路径穿越防护

**Agent Loop 护栏**：
- Token 预算（500K）、时长限制（600 秒）、最大迭代（25 次）
- 工具执行超时（每批 120 秒）、LLM 流超时（300 秒）
- 工具频率告警（≥8 次）、无进展检测（双哈希）
- 浪费检测、压缩熔断器、工具结果截断（40K 字符）

## 快速开始

### 前置条件

- JDK 17 —— 必须是**当前生效**的 JDK（`java -version` 显示 17）。如果 `JAVA_HOME` 指向 JDK 8/11，Maven 会报 `无效的标记: --release`。
- Maven 3.8+
- Node.js 18+（前端开发）
- 一个 LLM API Key（DeepSeek、通义千问/百炼、OpenAI、Claude 等）

```bash
# macOS + Temurin 17 示例
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
```

### 构建运行（生产 jar 方式）

```bash
# 设置 API Key
export DASHSCOPE_API_KEY=sk-your-key-here

# 构建所有模块 —— 必须在仓库根目录执行，按依赖顺序打包全部子模块
mvn clean package -DskipTests

# 启动服务（内嵌 PostgreSQL 自动启动）
cd /path/to/skillforge
java -jar skillforge-server/target/skillforge-server-1.0.0-SNAPSHOT.jar
```

服务启动在 `http://localhost:8080`，内嵌 PostgreSQL 端口 15432。

### 开发模式（`spring-boot:run` 热重载）

```bash
# 在仓库根目录 —— 先 install 把上游模块发到本地 ~/.m2，
# 否则 skillforge-server 解析不到 skillforge-core / skillforge-tools
mvn install -DskipTests

# 然后在 server 子模块启动
cd skillforge-server
mvn spring-boot:run
```

> 直接进 `skillforge-server` 跑 `mvn spring-boot:run` 而**跳过根目录 `mvn install`**，
> 会报 `找不到符号: 类 BehaviorRuleRegistry` 之类编译错误 —— 因为其他子模块还没发到本地仓库。
> 一条命令等价写法：在根目录执行 `mvn -pl skillforge-server -am spring-boot:run`。

### 前端开发

```bash
cd skillforge-dashboard
npm install
npm run dev    # Vite 开发服务器 http://localhost:3000，代理到 :8080
```

### 配置

编辑 `skillforge-server/src/main/resources/application.yml`：

```yaml
skillforge:
  llm:
    default-provider: bailian            # 或 "claude"、"openai"
    providers:
      bailian:                           # 通义千问 / DashScope
        type: openai                     # OpenAI 兼容端点
        api-key: ${DASHSCOPE_API_KEY:}
        base-url: https://coding.dashscope.aliyuncs.com
        model: qwen3.5-plus              # 该 provider 默认模型
        models:                          # 前端模型下拉展示用
          - qwen3.5-plus
          - qwen3-max-2026-01-23
          - qwen3-coder-next
      claude:
        type: claude
        api-key: ${ANTHROPIC_API_KEY:}
        base-url: https://api.anthropic.com
        model: claude-sonnet-4-20250514
        models:
          - claude-sonnet-4-20250514
        context-window-tokens: 200000
      openai:                            # 也能接 DeepSeek、vLLM、Ollama 等
        type: openai
        api-key: ${DEEPSEEK_API_KEY:}
        base-url: https://api.deepseek.com
        model: deepseek-chat
        models:
          - deepseek-chat

  # 记忆提取：rule（快速启发式）| llm（语义化，5 类分类）
  memory:
    extraction-mode: rule

  # pgvector 向量检索（默认关闭，需要 embedding API key 才启用）
  embedding:
    enabled: false
    api-key: ${EMBEDDING_API_KEY:}
    base-url: https://api.openai.com
    model: text-embedding-3-small
    dimension: 1536

# Lifecycle Hook 脚本沙箱（仅允许 bash + node；输出量截断）
lifecycle:
  hooks:
    forbidden-skills: [SubAgent, TeamCreate, TeamSend, TeamKill]
    script:
      allowed-langs: [bash, node]
      max-output-bytes: 65536
      max-script-body-chars: 4096
```

## 工具 & Skill

> **Tool vs Skill**：**Tool** 是 Agent 可以*调用*的最小单元（带 schema + `execute`）；**Skill** 是可打包的能力（Java 类或文件型 SKILL.md 包），可以注册一个或多个 Tool。所有 Skill 都是 Tool，但用户自定义的 Skill 也可以独立加载不绑定某个 Tool schema。P13-11 正式引入了 Tool 语义层。

### 系统工具（Java 实现，始终可用）

| 工具 | 说明 |
|------|------|
| **Bash** | 带安全规则和超时的 Shell 命令执行 |
| **FileRead** | 读取文件（行号、偏移/限制） |
| **FileWrite** | 写入/创建文件 |
| **FileEdit** | 精确字符串替换 |
| **Glob** | 按模式查找文件 |
| **Grep** | 按正则搜索文件内容 |
| **WebFetch** | 抓取 URL 内容（大小截断） |
| **WebSearch** | 网页搜索 |
| **Memory** | 持久记忆 CRUD（5 种类型） |
| **MemorySearch** | 记忆混合检索（pgvector + FTS） |
| **MemoryDetail** | 按需拉取记忆全文 |
| **TodoWrite** | 按 session 维护待办列表，便于计划型 Agent |
| **SubAgent** | 派发任务给另一个 Agent（树形） |
| **TeamCreate** | 创建团队成员（网状） |
| **TeamSend** | 给同伴/父级/广播发消息 |
| **TeamList** | 列出团队成员 |
| **TeamKill** | 取消成员或团队 |
| **RegisterScriptMethod** | Agent 注册一个 bash/node 脚本方法 |
| **RegisterCompiledMethod** | Agent 提交一个 Java Hook Method 走编译 + 审批 |

### 系统 Skill（文件型，不可删除）

| Skill | 说明 |
|-------|------|
| **Browser** | 浏览器自动化（`npx agent-browser` CLI） |
| **ClawHub** | 市场搜索 + 安装 |
| **GitHub** | GitHub API + `gh` CLI |
| **SkillHub** | SkillHub 市场 |
| **skill-creator** | Agent 自己编写 Skill：脚手架 / 编辑 / 校验 SKILL.md 包 |

### 引擎内置工具

| 工具 | 说明 |
|------|------|
| **ask_user** | 多选题（仅 Ask 模式） |
| **compact_context** | 请求 light 或 full 上下文压缩 |

### Lifecycle Hook 内置方法

| 方法 | 说明 |
|------|------|
| **HttpPost** | 向 URL POST 数据，SSRF 加固的 URL 校验（拦截回环 / link-local / IPv6 陷阱） |
| **FeishuNotify** | 推送到飞书群 / 机器人 |
| **LogToFile** | 写入项目级日志文件（按路径加锁） |

## API 参考

### Agent 管理

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/agents` | 列出所有 Agent |
| GET | `/api/agents/{id}` | 获取 Agent 详情 |
| POST | `/api/agents` | 创建 Agent |
| PUT | `/api/agents/{id}` | 更新 Agent |
| DELETE | `/api/agents/{id}` | 删除 Agent |

### 聊天 & 会话

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/chat/sessions` | 创建会话 |
| GET | `/api/chat/sessions?userId=1` | 列出用户会话 |
| DELETE | `/api/chat/sessions/{id}` | 删除单个会话 |
| DELETE | `/api/chat/sessions` | 批量删除（`{ids: [...]}`，上限 100） |
| POST | `/api/chat/{sessionId}` | 发送消息（异步，202） |
| POST | `/api/chat/{sessionId}/cancel` | 取消运行中的循环 |
| POST | `/api/chat/{sessionId}/answer` | 回答 ask_user 问题 |
| PATCH | `/api/chat/sessions/{id}/mode` | 切换执行模式 |
| POST | `/api/chat/sessions/{id}/compact` | 手动上下文压缩 |
| GET | `/api/chat/sessions/{id}/replay` | 结构化会话回放 |
| GET | `/api/chat/sessions/{id}/context-breakdown` | 各段 token 占比 vs context window |

### SubAgent & 协作

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/chat/sessions/{id}/children` | 列出子会话 |
| GET | `/api/chat/sessions/{id}/subagent-runs` | 列出 SubAgent 运行 |
| GET | `/api/collab-runs/{id}/members` | 列出协作成员 |

### 评测流水线

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/eval/runs` | 触发评测运行 |
| GET | `/api/eval/runs` | 列出评测运行 |
| GET | `/api/eval/runs/{id}` | 评测运行详情（含会话） |
| GET | `/api/eval/scenarios` | 列出场景 |

### Skill 管理

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/skills` | 列出所有 Skill |
| POST | `/api/skills/upload` | 上传 Skill zip 包 |
| DELETE | `/api/skills/{id}` | 删除用户 Skill |
| PUT | `/api/skills/{id}/toggle` | 启用/禁用 Skill |

### 可观测性

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/traces` | 列出 Trace |
| GET | `/api/traces/{id}/spans` | Span 树 |
| GET | `/api/dashboard/overview` | 仪表盘概览 |
| GET | `/api/dashboard/usage/daily` | 每日用量 |
| GET | `/api/dashboard/usage/by-model` | 按模型用量 |
| GET | `/api/dashboard/usage/by-agent` | 按 Agent 用量 |

### 记忆

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/memories` | 列出记忆 |
| GET | `/api/memories/search` | 混合检索（pgvector + FTS 通过 RRF 融合） |
| POST | `/api/memories` | 创建记忆 |
| PUT | `/api/memories/{id}` | 更新记忆 |
| DELETE | `/api/memories/{id}` | 删除记忆 |

### 渠道网关

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/channels` | 列出渠道配置（飞书 / Telegram / …） |
| POST | `/api/channels` | 创建渠道配置 |
| PATCH | `/api/channels/{id}` | 更新配置（如飞书 ws ↔ webhook 模式切换） |
| GET | `/api/channels/{id}/conversations` | 列出路由到该渠道的对话 |
| POST | `/api/channels/deliveries/{id}/retry` | 重试失败的投递 |

### Lifecycle Hook & Hook Method

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/lifecycle-hooks/events` | 可用 Hook 事件 + 预设 |
| GET | `/api/lifecycle-hooks/methods` | 列出已注册的 built-in / script / compiled 方法 |
| POST | `/api/hook-methods/script` | 注册 bash/node 脚本方法 |
| POST | `/api/hook-methods/compiled` | 提交 Java 类走编译 + 审批 |
| POST | `/api/hook-methods/compiled/{id}/approve` | 审批通过 compiled 方法 |
| GET | `/api/behavior-rules` | 列出内置行为规范 |
| GET | `/api/behavior-rules/presets` | 列出预设规范模板 |

### LLM

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/llm/models` | 列出各 provider 可用模型（后端驱动） |

### WebSocket

| 端点 | 说明 |
|------|------|
| `/ws/chat/{sessionId}` | 会话流：状态、增量、ask_user、协作事件 |
| `/ws/users/{userId}` | 用户通知：会话增删改 + CollabRun 事件 |

## CLI

```bash
# 安装
mvn -pl skillforge-cli -am install -DskipTests
alias skillforge='java -jar skillforge-cli/target/skillforge-cli-1.0.0-SNAPSHOT-shaded.jar'

# 使用
skillforge agents list
skillforge agents create -f examples/agents/general-assistant.yaml
skillforge agents export 1 > my-agent.yaml
skillforge chat 1 "列出 /tmp 中 3 个有趣的文件"
skillforge sessions list
skillforge compact <session-id> --level full --reason "任务结束"
```

## Skill 包格式

自定义 Skill 以 zip 包上传：

```
my-skill.zip
├── SKILL.md         # 必需：YAML frontmatter + prompt 内容
├── references/      # 可选：参考文档
├── scripts/         # 可选：可执行脚本
└── docs/            # 可选：扩展文档
```

## 路线图

### 已交付

- **Agent Loop 引擎** + 多 Provider LLM 流式（Claude、OpenAI 兼容、DeepSeek、百炼、vLLM、Ollama）
- **工具 & Skill 系统** — Java 工具 + 文件型 SKILL.md 包 + 市场安装（ClawHub / SkillHub）
- **仪表盘** — 聊天、会话、Agent、Skill、Trace、回放、记忆、用量、团队、评测、渠道、Hook Method、计划任务
- **SubAgent 编排** — 树形拓扑，跨重启持久化，启动恢复 + 超时清扫
- **多 Agent 协作** — 网状拓扑，花名册，邻接策略，级联取消，lightContext（节省 ~30-50% token）
- **上下文压缩** — light + full + time-based cold cleanup + session-memory 零 LLM 压缩，6 种触发源（JVM-GC 风格）
- **记忆系统** — 5 种类型，**pgvector + FTS 混合召回（RRF 融合）**，session 结束 `@Async` 即时提取，**LLM 语义提取模式**，整合去重
- **自进化流水线** — 评测执行器、LLM 评委（2×Haiku + Sonnet 元评委）、7×5 归因矩阵、Prompt A/B 自动晋升（Δ≥15pp + 4 层 Goodhart 防护）、Session→场景提取
- **会话消息行存储** — `t_session_message` append-only，checkpoint / branch / restore
- **Skill 自进化** — Session → Skill 提取、版本管理、A/B 验证、自动晋升/回滚
- **多渠道网关** — `ChannelAdapter` SPI、飞书（WebSocket + Webhook）、Telegram、三阶段投递事务、重试/去重、`/channels` 仪表盘
- **Lifecycle Hooks** — SessionStart / UserPromptSubmit / PreToolUse / PostToolUse / SessionEnd，支持 Skill / Script / BuiltInMethod / CompiledMethod 四种 Handler，多 entry 链式执行，discriminated-union 编辑器
- **行为规范层** — 15 条内置规范 + 预设模板 + 按 Agent 自定义规则（XML 沙箱）
- **Code Agent** — 能给自己写 Hook Method 的 Agent，含编译 + 审批流
- **安全护栏** — SafetySkillHook（命令黑名单、路径穿越防护）+ Agent Loop 防失控（token / 时长 / 迭代预算，无进展检测，浪费检测）
- **可观测性** — Langfuse 风格 Trace、会话回放、模型用量仪表盘、Context Breakdown API、渠道可视化
- **Auth MVP** — 本地 token 自动生成
- **CLI 模块** — picocli + OkHttp、YAML 导入/导出、一次性聊天

### 规划中

- **记忆质量评估（P3）** — 提取快照、记忆归因信号、Δ 为负自动回滚
- **Tool 输出精细裁剪（P9-2/4/5/7）** — 单条消息聚合预算 + 归档持久化、partial 压缩（保头/保尾）、压缩后上下文恢复、`jtokkit` 本地 token 精确计数
- **斜杠命令（P10）** — 聊天框 `/new`、`/compact`、`/clear`、`/model`、`/help`
- **Agent 发现 + 跨 Agent 调用（P11）** — `AgentDiscoverySkill`、按 name 调用、可见性、循环调用检测
- **定时任务（P12）** — 用户自定义 cron / 一次性触发、系统作业注册表、统一的 `/schedules` UI、执行历史
- **JWT 认证** + Redis 多实例部署

## 许可证

MIT
