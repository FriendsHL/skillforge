# SkillForge

[English](README.md)

**服务端 AI Agent 平台** — 可配置 Skill 系统、多 LLM Provider 接入、多 Agent 协作，以及自进化评测流水线。基于 Spring Boot + React 构建，面向需要完全掌控 Agent 基础设施的团队。

## 为什么选择 SkillForge？

大多数 Agent 框架基于 Python、绑定单一 Provider、只适合原型开发。SkillForge 专为 **Java/Spring 生产团队** 设计：

- **多 Provider LLM** — Claude、DeepSeek、通义千问/百炼、vLLM、Ollama 自由切换，无需改代码
- **真正的 Agent 编排** — 不止是 Chain，支持树形和网状两种拓扑，带持久化状态
- **自进化 Agent** — 自动评测、Prompt A/B 测试、自动晋升流水线
- **全链路可观测** — Langfuse 风格的 Trace、Session 回放、模型用量仪表盘
- **安全护栏** — 命令黑名单、路径穿越防护、防失控循环检测

## 架构

```
┌─────────────────────────────────────────────────────────┐
│   React + Ant Design 仪表盘       │    CLI 模块          │
├─────────────────────────────────────────────────────────┤
│              入口层                                       │
│   REST API   │   WebSocket（会话级 + 用户级）              │
├─────────────────────────────────────────────────────────┤
│              Spring Boot 服务层                           │
│   Chat / Agent / Skill / Memory / Compaction            │
│   SubAgent / 多 Agent 协作 / 评测流水线                    │
├─────────────────────────────────────────────────────────┤
│              Agent Loop 引擎                              │
│   消息 → LLM（流式）→ tool_use → Skill → 循环             │
│   取消 + 防失控 + 上下文压缩 + Trace 采集                  │
├──────────────┬──────────────┬───────────────────────────┤
│  LLM 层      │ 工具 & Skill  │ 会话 & Hook               │
│  Claude      │ 系统工具      │ 会话管理                    │
│  OpenAI*     │ 系统 Skill    │ SafetyHook                │
│  DeepSeek    │ 用户 Skill    │ AskUser / 压缩             │
│  百炼         │ 团队协作      │ 记忆系统                    │
├──────────────┴──────────────┴───────────────────────────┤
│              存储层                                       │
│   内嵌 PostgreSQL（开发）│ 外部 PostgreSQL（生产）          │
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
├── skillforge-skills       # 系统工具：Bash, FileRead, FileWrite, FileEdit,
│                            #   Glob, Grep, Memory, SubAgent, Team*
├── skillforge-server       # Spring Boot 服务：REST API, JPA 实体, 服务层,
│                            #   WebSocket, 多 Agent 协作, 记忆系统, 评测流水线
├── skillforge-dashboard    # React 仪表盘：聊天, 会话, Agent, Skill, Trace,
│                            #   会话回放, 记忆, 模型用量, 团队, 评测
├── skillforge-cli          # CLI 客户端：picocli + OkHttp, Agent YAML 导入/导出
└── system-skills/          # 文件型系统 Skill（不可删除）
    ├── browser/            #   浏览器自动化（agent-browser CLI）
    ├── clawhub/            #   ClawHub 市场搜索 + 安装
    ├── github/             #   GitHub API + gh CLI
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
| **MEMORY.md** | 单个 Agent | 从记忆系统自动注入 |

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
- **TF-IDF 搜索排序** + 30 天衰减 + 召回频率提升
- **自动采集** — ActivityLogHook 记录每次工具调用
- **定时提取**（@Scheduled cron）— 从已完成会话提取记忆
- **整合去重** — 标题去重 + 过期标记（30 天未召回 + recallCount < 3）

### 上下文压缩（JVM-GC 风格）

- **Light** — 规则压缩：截断大输出、去重搜索、折叠失败（零成本）
- **Full** — LLM 摘要压缩，保留 tool_use ↔ tool_result 配对
- 6 种触发源：token 预算安全网、空闲间隙、LLM 工具调用、引擎自动触发、手动 API、浪费检测
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
# 否则 skillforge-server 解析不到 skillforge-core / skillforge-skills
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
    default-provider: bailian    # 或 "claude"、"openai"
    providers:
      bailian:
        api-key: ${DASHSCOPE_API_KEY:}
        base-url: https://coding.dashscope.aliyuncs.com
        model: qwen3.5-plus
      claude:
        api-key: ${ANTHROPIC_API_KEY:}
        base-url: https://api.anthropic.com
        model: claude-sonnet-4-20250514
      openai:
        api-key: ${DEEPSEEK_API_KEY:}
        base-url: https://api.deepseek.com
        model: deepseek-chat
```

## 工具 & Skill

### 系统工具（Java 实现，始终可用）

| 工具 | 说明 |
|------|------|
| **Bash** | 带安全规则和超时的 Shell 命令执行 |
| **FileRead** | 读取文件（行号、偏移/限制） |
| **FileWrite** | 写入/创建文件 |
| **FileEdit** | 精确字符串替换 |
| **Glob** | 按模式查找文件 |
| **Grep** | 按正则搜索文件内容 |
| **Memory** | 持久记忆（5 种类型，TF-IDF 搜索） |
| **SubAgent** | 派发任务给另一个 Agent（树形） |
| **TeamCreate** | 创建团队成员（网状） |
| **TeamSend** | 给同伴/父级/广播发消息 |
| **TeamList** | 列出团队成员 |
| **TeamKill** | 取消成员或团队 |

### 系统 Skill（文件型）

| Skill | 说明 |
|-------|------|
| **Browser** | 浏览器自动化（`npx agent-browser` CLI） |
| **ClawHub** | 市场搜索 + 安装 |
| **GitHub** | GitHub API + `gh` CLI |
| **SkillHub** | SkillHub 市场 |

### 引擎内置工具

| 工具 | 说明 |
|------|------|
| **ask_user** | 多选题（仅 Ask 模式） |
| **compact_context** | 请求 light 或 full 上下文压缩 |

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
| POST | `/api/chat/{sessionId}` | 发送消息（异步，202） |
| POST | `/api/chat/{sessionId}/cancel` | 取消运行中的循环 |
| POST | `/api/chat/{sessionId}/answer` | 回答 ask_user 问题 |
| PATCH | `/api/chat/sessions/{id}/mode` | 切换执行模式 |
| POST | `/api/chat/sessions/{id}/compact` | 手动上下文压缩 |
| GET | `/api/chat/sessions/{id}/replay` | 结构化会话回放 |

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
| GET | `/api/memories/search` | TF-IDF 搜索 |
| POST | `/api/memories` | 创建记忆 |
| PUT | `/api/memories/{id}` | 更新记忆 |
| DELETE | `/api/memories/{id}` | 删除记忆 |

### WebSocket

| 端点 | 说明 |
|------|------|
| `/ws/chat/{sessionId}` | 会话流：状态、增量、ask_user、协作事件 |
| `/ws/users/{userId}` | 用户通知：会话增删改事件 |

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

- Agent Loop 引擎 + 多 Provider LLM 流式
- 工具 & Skill 系统（Java 工具 + 文件型 Skill + 市场安装）
- 仪表盘（聊天、会话、Agent、Skill、Trace、回放、记忆、用量、团队、评测）
- SubAgent 编排（树形拓扑，持久化，恢复）
- 多 Agent 协作（网状拓扑，花名册，邻接策略，级联取消）
- 上下文压缩（light + full，6 种触发源，JVM-GC 风格）
- 记忆系统（5 种类型，TF-IDF 搜索，自动提取，整合去重）
- 自进化流水线（评测执行器、LLM 评委、归因、Prompt A/B、场景提取）
- 安全护栏（命令黑名单、路径穿越防护、防失控）
- 可观测性（Trace、会话回放、模型用量仪表盘）
- Auth MVP（本地 token 自动生成）
- CLI 模块（YAML 导入/导出、一次性聊天）

### 规划中

- 记忆向量检索（pgvector + FTS 混合召回）
- Agent 行为规范层（可配置规范库）
- 生命周期 Hook（session_start / post_tool_use / session_end）
- Skill 自动生成（从会话分析中提取）
- 飞书消息网关
- JWT 认证
- Redis 多实例部署

## 许可证

MIT
