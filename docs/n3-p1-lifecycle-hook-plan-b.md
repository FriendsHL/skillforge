# N3 P1 Plan B — 防御 / 安全优先视角

> 作者：planner-b | 日期：2026-04-17 | 版本：v1

---

## 0. 前提与视角

P0 已有扎实底盘：ThreadLocal 深度守卫、独立 executor、TraceSpan、FailurePolicy。
P1 最大风险不在功能实现，而在于 **ScriptHandlerRunner 引入了用户可控代码执行**，这是一个质变——从"调用平台内部 Skill"变为"执行外部字符串作为系统命令"。

防御视角的核心原则：**能在设计时排除的攻击面，就不要依赖运行时检测。**

---

## 1. 数据模型（P1 扩展）

### 多 entry 存储
P0 schema 已是 `List<HookEntry>`，P1 只改 dispatcher 循环逻辑，无需迁数据。`version=1` 不变。

**约束收紧（P1 强化）**：
- `scriptBody` 硬上限 4KB（Jackson `@Size` 不够，需在 runner 启动前再次校验——不信任 schema 层）
- `scriptLang` 只接受 allow-list 中的值，其他值在 `ScriptHandlerRunner` 入口拒绝并返回 `HookRunResult(success=false)`，而非让 ProcessBuilder 静默接受

### 为什么不加新列
沿用 TEXT 列内嵌 JSON，与 P0 完全对称。多 entry 语义在应用层实现，避免 schema 蔓延。

---

## 2. ScriptHandlerRunner — 深挖安全边界

### 2.1 子进程 FD 继承（最高优先级）

**问题**：`ProcessBuilder` 默认继承 JVM 的所有文件描述符——包括数据库连接池的 socket FD、`hookExecutor` 的内部管道、以及任意打开的日志文件句柄。子进程的 bash 脚本可以通过 `/proc/self/fd/` 枚举并读写这些 FD。

**方案（选）**：`ProcessBuilder` 加 `inheritIO()` **不可用**，必须显式 redirect：
```
builder.redirectInput(ProcessBuilder.Redirect.PIPE)   // stdin: 我们写
builder.redirectOutput(ProcessBuilder.Redirect.PIPE)  // stdout: 我们读
builder.redirectError(ProcessBuilder.Redirect.PIPE)   // stderr: 我们读
// 不能 inheritIO() —— 那会让 JVM stdout 泄给子进程
```
JVM 端额外措施：在 `hookExecutor` 线程上调用 `ProcessBuilder.start()` 之前，**不持有**任何数据库连接（hook runner 本身无需 DB，确保 executor 线程上不持有连接）。

**MVP 能做到什么**：FD 继承问题在纯 Java 层无法完全关闭（JVM 不暴露 `close-on-exec` 批量设置的 API）。**承认局限**：MVP 只能保证我们主动 redirect 的三个 pipe，无法防止 JVM 泄漏其他 FD。需在注释和文档中说明：ScriptHandlerRunner 在 MVP 阶段不适合多租户 SaaS 场景。

### 2.2 Stdout/Stderr 读取死锁（管道 buffer 死锁）

**问题**：子进程向 stdout 写的量超过 OS pipe buffer（通常 64KB），子进程阻塞等待读者；同时主线程卡在读 stderr；双方互等 = 死锁。

**方案（选）**：两个独立线程分别读 stdout 和 stderr（提交给 hookExecutor，或用固定大小 byte[] drain 循环）。输出收集上限 64KB，超出后 **drain but discard**（继续读避免死锁，只保留前 64KB）。

**为什么不用 `ProcessBuilder.redirectErrorStream(true)`**：合并到一个流确实能简化死锁问题，但会丢失 exit-code + stderr 的关联，对错误诊断不友好。选两线程方案。

### 2.3 Zombie / Child-of-child（timeout 后进程残留）

**问题**：`process.destroyForcibly()` 只杀直接子进程。如果 bash 脚本又 fork 了子进程（`sleep 100 &`），孙进程成为 JVM 的孤儿，继续运行或占资源。

**方案（MVP 承认局限）**：Java 9+ 的 `process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly)` 可以递归杀 process tree，**应在 timeout 时使用**。但这依赖 OS 权限，在容器内通常可用。实现步骤：
1. `process.waitFor(timeoutSec, SECONDS)` 返回 false（超时）
2. `process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly)`
3. `process.destroyForcibly()`
4. 记录 warn + trace `zombie_risk: true`

**不做 seccomp / sandbox-exec**：MVP 不引入外部沙箱（macOS sandbox-exec 已弃用；Linux seccomp 需 JNI 或 jna；cgroups 需 root 或 systemd-run）。**明确承认**：P1 ScriptHandlerRunner 对脚本内容的隔离仅限于工作目录隔离 + 进程树清理，不是完整沙箱。

### 2.4 环境变量白名单（敏感变量泄露）

**问题**：默认继承 JVM 的全部环境变量，包括 `ANTHROPIC_API_KEY`、`AWS_SECRET_ACCESS_KEY`、`DB_PASSWORD`、以及用户配置的各类 `_KEY` / `_TOKEN` 变量。

**方案（选白名单，不选黑名单）**：
- 黑名单：需要穷举所有可能的敏感变量名，遗漏一个就泄露。
- **白名单**：`ProcessBuilder.environment().clear()` 后只加允许的变量集合：

```
SF_HOOK_EVENT, SF_SESSION_ID, SF_AGENT_ID,
SF_SKILL_NAME, SF_SKILL_OUTPUT, SF_SUCCESS, SF_DURATION_MS,
PATH, LANG, HOME, TMPDIR, TZ
```

input JSON 以 JSON 格式通过 **stdin** 传入，不通过环境变量——避免大字段或结构化数据通过 env 泄露。

**为什么 PATH 要保留**：bash 脚本需要找到系统命令。但 `PATH` 要固定为安全路径（`/usr/local/bin:/usr/bin:/bin`），而不是继承 JVM 的用户 PATH（可能含 `~/.local/bin` 等可控路径）。

### 2.5 Shell Injection 防御

**问题**：如果将 `scriptBody` 传给 `bash -c <scriptBody>`，那 scriptBody 本身就是可信内容（用户自己写的），不存在注入——**但**：
1. 环境变量 value 在脚本中被 unquoted `$VAR` 展开可触发注入（由脚本作者控制，是预期行为）
2. `handler.args` 的值如果以 `SF_` 前缀传入 env，其值需要过滤控制字符（NUL、换行）

**方案**：args 中的字符串 value 传 stdin JSON，不传 env。env 只传我们控制的固定 key=value。

### 2.6 Exit Code 语义

`exit code 0` = success，非 0 = fail。stderr 非空时追加到 `HookRunResult.errorMessage`（前 2KB）。不以 stdout 内容判断成败——stdout 是 output，exit code 是语义。

### 2.7 工作目录隔离

每次执行在 `/tmp/sf-hook-<sessionId>-<entryIndex>/` 独立目录，执行后删除（`finally` 块）。不允许 `..` 路径逃逸——工作目录通过 `File.getCanonicalPath()` 验证必须在 `/tmp/sf-hook-` 前缀下。

---

## 3. 多 entry + SKIP_CHAIN 语义完备

### 3.1 链式执行设计

P1 dispatcher 从 `entries.get(0)` 改为 `for (HookEntry entry : entries)` 循环：

```
for entry in entries:
  result = runEntry(entry)
  if result == ABORT → return false (主流程中断, 后续 entry 跳过)
  if entry.failurePolicy == SKIP_CHAIN && !result.success → break (后续 entry 跳过, 主流程继续)
  // CONTINUE: 忽略失败继续
```

**ABORT 语义**：命中即停，后续 entry 不执行，trace 记录"aborted_at_entry_N"。

**SKIP_CHAIN 语义**：跳出当前事件的 entry 链，主流程继续。适用于"前置校验不通过就不用执行后续审计"。

### 3.2 Async entry 的 SKIP_CHAIN 无意义

async=true 的 entry 是 fire-and-forget，runner 已 submit 到 executor 后立即返回 true，result 无法影响链执行。**设计决策**：async entry 的 `failurePolicy=SKIP_CHAIN` 在运行时静默降级为 `CONTINUE`，并在 dispatcher 入口记 warn。不报错——用户可能通过 JSON 模式配置了这组合。

### 3.3 Entry timeout 的链语义

某条 entry timeout，按该 entry 的 failurePolicy 决定：timeout 视为 failure。不额外引入"剩余 budget"分配——P1 不做级联 timeout 控制，每个 entry 独立计时。这是 MVP 合理边界。

---

## 4. Prompt Enrichment 不变量（P1-5）

### 4.1 消息对不变量

`LoopContext.messages` 必须维持 tool_use / tool_result 的配对不变量（compaction 教训）。注入的 `injected_context` 消息**只能是 `user` role 或 `system` role，不能插入 `assistant` role**——LLM 对 assistant role 的消息有特殊语义（它代表模型自己的输出）。

**选 `user` role，不选 `system` role**：
- `system` role 的 message 通常在 `system` 参数中，不在 messages 列表——各 LLM provider 实现不一致
- `user` role 插入到现有最后一条 user message **之前**（不是之后），以"[注入上下文]"前缀标记

**插入位置**：在现有最后一条 user message 的 content 前追加，而不是新增 message 对象——避免破坏 message 对的 turn 结构（如果当前最后是 tool_result，新增 user 会违反轮次约定）。

### 4.2 output 字段约定

UserPromptSubmit hook 的 Skill 输出中，`injected_context` 字段：
- 不存在 / null / 空字符串 → 不注入，主流程不改变
- 非空字符串 → 注入。不做 HTML/JSON 解析，原样拼接

**为什么不注入到 system**：避免与 agent 配置的 systemPrompt 发生隐式优先级竞争，行为难以预测。

### 4.3 并发 UserPromptSubmit

P1 不处理并发 UserPromptSubmit（hook 是同步执行，loop 本身串行）。无需额外防护。

---

## 5. Forbidden Skill 黑名单（P1-6）

配置：`application.yml: lifecycle.hooks.forbidden-skills: [SubAgent, TeamCreate, TeamSend, TeamKill, TeamDelete]`

**校验时机**：`SkillHandlerRunner.run()` 入口，在 SkillRegistry 查表之前，先检查 skillName 是否在黑名单。命中则返回 `HookRunResult(success=false, error="forbidden_skill")`，dispatch 按 failurePolicy 决策。

**不在前端校验**：前端 Select 可以过滤显示，但后端必须强制——前端可被绕过（直接 API 调用）。

---

## 6. 前端（P1-4：Script handler 启用）

去掉 P0 的 "coming in P1" 置灰，启用 Script 选项：
- lang 下拉（bash / node，从后端 `GET /api/lifecycle-hooks/script-langs` 获取白名单，不硬编码前端）
- `<Input.TextArea>` 12 行，等宽字体，Zod 校验 scriptBody ≤ 4096 chars
- 保存时前端提示"脚本在服务器执行，确认内容安全"警告 Banner（一次性，localStorage dismiss）

多 entry（P1-2）：每个事件 Card 底部 "+ 添加 handler"，列表支持上移/下移/删除，最多 10 条。

---

## 7. Observability

**每条 entry 独立 TraceSpan**（不共享）：entryIndex 作为 span 属性，方便排查链中哪条失败。

**Script stdout 截断**：TraceSpan 中 scriptStdout 最多 4KB（`TRACE_OUTPUT_MAX`），超出截断并标注 `[truncated]`。**不在 trace 中存储 scriptBody**——scriptBody 是用户可控代码，可能含敏感逻辑，存 trace 有泄露风险。

**log 规范**：只打 `handler type + entryIndex + event + durationMs`，不打 scriptBody / skillInput 原始内容（与 P0 已有原则对称）。

---

## 8. 测试策略

**ScriptHandlerRunnerTest（关键）**：
- stdout 超 64KB 不死锁（用 `yes` 命令产生无限输出）
- exit code 非 0 → `success=false`
- timeout 后进程树清理（mock ProcessHandle.descendants）
- 环境变量白名单（断言 `ANTHROPIC_API_KEY` 不出现在子进程 env）
- forbidden lang（`python` 不在白名单时返回 fail）

**MultiEntryChainTest**：
- ABORT 在第 N 条命中，N+1 条不执行
- SKIP_CHAIN 跳出链，主流程继续
- async entry SKIP_CHAIN 降级为 CONTINUE

**PromptEnrichmentTest**：
- injected_context 正确插入最后 user message 前
- null/empty output 不修改 messages
- messages 长度约束后 tool_use/tool_result 对完整

---

## 9. 估时与风险

| 子任务 | 估时 | 主要风险 |
|---|---|---|
| P1-1 多 entry + SKIP_CHAIN | 0.5天 | ABORT/SKIP_CHAIN 语义混淆（需要测试驱动） |
| P1-2 前端多 entry | 1天 | 拖拽排序 UX 复杂，MVP 用上移/下移替代 |
| P1-3 ScriptHandlerRunner | **1.5天（提高估时）** | 管道死锁 + 进程树清理 + env 白名单，比预期复杂 |
| P1-4 前端 Script 启用 | 0.5天 | 白名单动态从后端拉取，需加端点 |
| P1-5 Prompt enrichment | 0.5天 | message 对不变量，需仔细测试 |
| P1-6 Forbidden skill 黑名单 | 0.5天 | 配置加载 + 后端校验，低风险 |
| P1-7 E2E 测试 | 0.5天 | script handler 场景需要 server 真实运行 |

**P1-3 原计划 1 天，B 计划建议 1.5 天**：管道死锁问题在 Code Review 前是隐形炸弹，必须留出充分测试时间。

---

## FINAL

**P1 最核心的安全决策是 ScriptHandlerRunner 的 FD 继承与管道死锁**。FD 继承问题在 JVM 层无完整解法——MVP 只能做到"我们主动 redirect 的三个 pipe + 禁止 inheritIO"，其余 FD 泄露风险在文档中明确标注，不适合多租户场景。管道死锁通过双线程 drain + 上限截断解决，这是 ProcessBuilder 的经典陷阱，必须在实现前测试覆盖。

**环境变量用白名单而非黑名单**，这是防御编程的第一原则——无法枚举所有敏感变量，但可以枚举所有必要变量。Input 数据通过 stdin JSON 传入，不通过 env，避免大字段和结构化数据污染 env 空间。

**Prompt enrichment 注入到最后 user message 前而非新增 message 对象**，这是 tool_use/tool_result 对完整性的约束决定的，不是 API 设计偏好。compaction 教训证明 messages 结构的隐式约束一旦被破坏，排查极难。

**SKIP_CHAIN on async entry 静默降级为 CONTINUE**，不抛异常不报错，因为这个组合是用户配置错误而非恶意行为，强错误处理会使用户体验变差；warn log 足够。Zombie 进程通过 Java 9+ `ProcessHandle.descendants().destroyForcibly()` 递归清理，比单纯 `destroyForcibly()` 可靠，但 **MVP 不引入 seccomp / cgroups 等系统级沙箱**——这需要基础设施配合，应在 P2 或容器化部署策略中统一解决。
