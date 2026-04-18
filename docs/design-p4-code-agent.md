# P4 — Code Agent 设计方案

> 2026-04-19 approved

## 目标

创建一个能编码的 Agent，通过绑定代码类 Skill 实现自主编写 Hook 方法，提升 Agent 自身能力（自举闭环）。

## 核心架构决策

### 1. 混合 Hook Method 体系：ScriptMethod + CompiledMethod

| 维度 | ScriptMethod（脚本） | CompiledMethod（Java 类） |
|---|---|---|
| 语言 | bash / node | Java 17 |
| 存储 | `t_script_method.script_body` | `t_compiled_method.source_code` + 编译产物 |
| 注册方式 | 直接注册到 `BuiltInMethodRegistry` | `javac` 编译 + ClassLoader 加载 + admin 审批后注册 |
| 执行方式 | 子进程（复用 ScriptHandlerRunner） | JVM 进程内（与 Spring 同 ClassLoader） |
| 适用场景 | 外部调用：HTTP 通知、日志、CLI | 内部访问：DB 查询、Spring Service、Repository |
| 即时性 | 创建即生效 | 需编译 + 审批 |

### 2. 选择规则（Code Agent system prompt 内置）

- **默认走 ScriptMethod**：检测到需求只涉及外部调用（HTTP、文件、CLI）→ 生成 bash/node 脚本
- **自动升级 CompiledMethod**：检测到需要数据库访问、Spring Service 调用、SkillForge 内部 API → 生成 Java 类
- **用户可显式指定**：覆盖自动判断

### 3. 沙箱策略：ProcessBuilder + 约束

不用 Docker。复用 ScriptHandlerRunner 已有逻辑：
- 隔离工作目录：`/tmp/sf-code/<sessionId>/<UUID>/`
- 环境变量白名单（5 个）
- 输出上限 32KB
- 超时 30s（node）/ 120s（Java Maven compile）
- 进程树 kill（ProcessHandle.descendants）
- DangerousCommandChecker 预扫描

### 4. Java 编译：`javax.tools.JavaCompiler` 进程内编译

- `DynamicMethodCompiler`：进程内编译，产物写入临时目录
- `GeneratedMethodClassLoader`：URLClassLoader 子类，child-first 加载生成类，parent-first 加载 `com.skillforge.**` 接口
- 安全：源码预扫描禁止 `Runtime.exec`、`ProcessBuilder`、`System.exit`、`java.net.*`、反射 API
- ClassLoader 内存：`unregister` 时 `close()` ClassLoader，WeakReference 清理

### 5. BuiltInMethodRegistry 改为可变

- 内部 Map 从 `Map.copyOf` → `ConcurrentHashMap`
- 新增 `register(BuiltInMethod)` / `unregister(String ref)`
- 命名空间保护：`builtin.*` 只允许 @Component 注册，`agent.*` 允许动态注册

## DB Schema

### V10: Script Methods
```sql
CREATE TABLE t_script_method (
    id           BIGSERIAL PRIMARY KEY,
    ref          VARCHAR(128) NOT NULL UNIQUE,
    display_name VARCHAR(256) NOT NULL,
    description  TEXT,
    lang         VARCHAR(32) NOT NULL,        -- "bash" | "node"
    script_body  TEXT NOT NULL,
    args_schema  TEXT,                         -- JSON
    owner_id     BIGINT,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ
);
```

### V11: Compiled Methods
```sql
CREATE TABLE t_compiled_method (
    id                      BIGSERIAL PRIMARY KEY,
    ref                     VARCHAR(128) NOT NULL UNIQUE,
    display_name            VARCHAR(256),
    description             TEXT,
    source_code             TEXT NOT NULL,
    compiled_class_path     TEXT,
    status                  VARCHAR(16) NOT NULL DEFAULT 'pending_review',
    compile_error           TEXT,
    args_schema             TEXT,
    generated_by_session_id VARCHAR(36),
    generated_by_agent_id   BIGINT,
    reviewed_by_user_id     BIGINT,
    created_at              TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL
);
```

## 分 Phase 交付

### Phase 1：Code Sandbox + Code Review + 基础设施
- `CodeSandboxSkill`（skillforge-skills）：隔离执行 bash/node 代码
- `CodeReviewSkill`（skillforge-skills）：调用 LLM 审查代码
- V10 migration（t_script_method）
- `ScriptMethodEntity` + Repository
- `BuiltInMethodRegistry` 改为可变（register/unregister）
- `ScriptMethodLoader`（启动加载）+ `ScriptMethodService`（CRUD + 实时注册）
- `ScriptMethodController`（`/api/script-methods`）
- `RegisterScriptMethodSkill`：Code Agent 用来注册脚本的 Skill
- 注册所有新 Skill 到 SkillForgeConfig

### Phase 2：CompiledMethod + Java 编译
- V11 migration（t_compiled_method）
- `DynamicMethodCompiler` + `GeneratedMethodClassLoader`
- `CompiledMethodEntity` + Repository
- `CompiledMethodLoader`（启动加载 active 方法）
- `CompiledMethodService`（编译 + 审批流）
- `CompiledMethodController`（`/api/compiled-methods`）
- `RegisterCompiledMethodSkill`

### Phase 3：Code Agent Template + 前端
- `CodeAgentInitializer`：种子 Code Agent 模板
- Code Skill Pack preset：`["Bash","FileRead","FileWrite","Glob","Grep","CodeSandbox","CodeReview","RegisterScriptMethod"]`
- System prompt：内置选择规则（脚本 vs Java）
- 前端：ScriptMethod 管理页、CompiledMethod 审批页、Agent 模板创建、Hook 编辑器扩展

## 自举闭环示例

1. 用户对 Code Agent 说："写一个 hook，每次 session 结束后把 token 用量发到 Slack"
2. Code Agent 判断：外部 HTTP 调用 → ScriptMethod
3. 用 CodeSandbox 测试 curl 脚本
4. 用 CodeReview 审查安全性
5. 用 RegisterScriptMethod 注册为 `agent.slack-token-report`
6. 用户在其他 Agent 的 SessionEnd hook 引用 `agent.slack-token-report`
7. 闭环完成：Code Agent 生成的能力增强了其他 Agent
