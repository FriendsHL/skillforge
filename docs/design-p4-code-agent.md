# P4 — Code Agent 设计方案

> 2026-04-19 approved → **2026-04-19 completed**（Phase 1-3 全部交付，commit `59d3d80`）

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
    compiled_class_bytes    BYTEA,          -- javac 编译产物，直接存字节码
    status                  VARCHAR(16) NOT NULL DEFAULT 'pending_review',
    compile_error           TEXT,
    args_schema             TEXT,
    generated_by_session_id VARCHAR(36),
    generated_by_agent_id   BIGINT,
    reviewed_by_user_id     BIGINT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_compiled_method_status ON t_compiled_method(status);
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
- `CodeAgentInitializer`：种子 Code Agent 模板（existsByName + DataIntegrityViolationException 防并发）
- Code Skill Pack：`["Bash","FileRead","FileWrite","Glob","Grep","CodeSandbox","CodeReview","RegisterScriptMethod","RegisterCompiledMethod"]`
- System prompt：内置选择规则（脚本 vs Java）
- 前端 `HookMethods.tsx`：`/hooks` 路由，双 Tab（Script Methods / Compiled Methods），grid/table 视图切换，detail drawer，approval/reject/compile 操作，手动创建 ScriptMethod 弹窗
- `api/index.ts`：typed API functions（ScriptMethod CRUD + CompiledMethod lifecycle）

## 已知限制（Review 记录）

### 文件系统 / 网络隔离

CodeSandboxSkill 与 BashSkill 共享同一隔离策略（ProcessBuilder + 工作目录约束）。沙箱进程**可以**：
- 读取主机文件系统（`/etc/passwd`、项目目录等）
- 发起任意网络请求（curl、wget、nc）

当前靠 `DangerousCommandChecker` 拦截高危模式，但无法防御全部变体。完整隔离需要 Docker/nsjail，计划在 Phase 2+ 评估引入。

> HOME 环境变量已设为沙箱 workdir（不继承真实 HOME），防止通过 `~/.ssh`、`~/.aws` 等路径泄露凭证。

### Registry / DB 一致性

`ScriptMethodService` 在 `@Transactional` 方法内先修改 DB 再更新 `BuiltInMethodRegistry`（内存）。
极端情况下（JVM 在事务提交后、registry 更新前崩溃），两者可能不一致。
`ScriptMethodLoader`（ApplicationRunner）在重启时从 DB 重建 registry，自动修复此类不一致。

### 认证 / 鉴权

`ScriptMethodController` 当前无认证保护，与平台其他 REST API 一致（待统一鉴权层）。
`ownerId` 由调用方传入，未校验是否与当前用户匹配。

### CompiledMethod 源码扫描局限（Phase 2）

`DynamicMethodCompiler.FORBIDDEN_PATTERNS` 是文本级正则扫描，已知绕过方式：
- **Unicode 转义**：`\u0052untime` 等在 javac 词法阶段展开，正则看不到原始 API 名。修复需在扫描前预处理 unicode escape。
- **字符串拼接**：`"Process" + "Builder"` 运行时组装。文本扫描无法捕获。
- **字节码注入**：源码扫描只覆盖源码层；理论上可通过 annotation processor 或 synthetic accessor 在字节码中引入源码中不存在的调用。

> 核心安全边界是 **人工审批**（approve），源码扫描是 defense-in-depth。

### 生成类运行时能力（Phase 2）

`GeneratedMethodClassLoader` 的 parent 是 application ClassLoader，生成类运行时可见整个 Spring classpath。
- `static { ... }` 初始化块在 `loadClass()` 时执行，先于任何后续检查
- 生成类可创建线程（Thread 不在禁止列表），线程持有 ClassLoader 引用阻止 GC
- 真正的沙箱隔离需要 OS 级进程边界（seccomp / 独立 JVM），ClassLoader 无法实现

### FQCN 未持久化（Phase 2）

`loadAndInstantiate()` 通过重新解析 `sourceCode` 推导 FQCN，未将 `CompilationResult.className()` 存入实体。
若 sourceCode 与 compiledClassBytes 不一致（如直接 DB 编辑），加载可能静默失败。低风险——API 不提供 source 编辑功能。

## 自举闭环示例

1. 用户对 Code Agent 说："写一个 hook，每次 session 结束后把 token 用量发到 Slack"
2. Code Agent 判断：外部 HTTP 调用 → ScriptMethod
3. 用 CodeSandbox 测试 curl 脚本
4. 用 CodeReview 审查安全性
5. 用 RegisterScriptMethod 注册为 `agent.slack-token-report`
6. 用户在其他 Agent 的 SessionEnd hook 引用 `agent.slack-token-report`
7. 闭环完成：Code Agent 生成的能力增强了其他 Agent
