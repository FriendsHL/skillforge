# N3 P1 实现方案 — Plan A（务实 MVP 视角）

> planner-a | 2026-04-17 | v1

---

## 0. 总纲

P1 的核心任务是把 P0 "1 entry only" 的 hook 链变成真正可用的多 entry 链，同时交付 ScriptHandlerRunner（P1 最重点），并把 UserPromptSubmit 的 prompt enrichment 打通。其余子任务（Forbidden Skill、前端多 entry、Script 前端启用、E2E）全部跟着这三条主线走，不扩展边界。

---

## 1. 数据模型变更

**结论：不改 schema。** `t_agent.lifecycle_hooks` TEXT 列已经存放 `List<HookEntry>`，结构从第一天就支持 n 条 entry。P0 的"只取 list[0]"是纯代码限制，P1 只改 dispatcher 循环逻辑。

Dispatcher 变更伪码思路（`dispatch()` 方法）：

```
P0: HookEntry entry = entries.get(0); return runEntry(...)
P1: for entry in entries:
      result = runEntry(...)
      if result == ABORT  → return false (主流程中断)
      if result == SKIP_CHAIN → break loop (后续 entry 跳过，但主流程继续)
      // CONTINUE 继续下一个 entry
    return true
```

关键：`runEntry()` 已有，只需把"单次调用"的返回值语义从 `boolean` 扩展为三值枚举，或直接在 `HookRunResult` 中带 policy 决策。推荐后者：在 `HookRunResult` 增加 `ChainDecision`（`CONTINUE / ABORT / SKIP_CHAIN`）字段，dispatcher for 循环读它。

---

## 2. SKIP_CHAIN 语义 + 三条 Policy 决策表

| 场景 | CONTINUE | ABORT | SKIP_CHAIN |
|---|---|---|---|
| handler 执行成功 | 继续下一 entry | 继续下一 entry | 停止链，主流程继续 |
| handler 执行失败 | 继续下一 entry | **主流程 ABORT** | 停止链，主流程继续 |
| async=true（已 fire-forget） | N/A — async 永远 CONTINUE | 无效（async 不能 ABORT 主流程，P0 已确定） | 无效（async 不参与链控制） |
| timeout | 继续下一 entry | **主流程 ABORT** | 停止链，主流程继续 |

**设计决策**：SKIP_CHAIN 仅对 sync entry 生效；async entry 永远当 CONTINUE 处理（和 P0 语义一致）。这避免了 async entry 与链控制语义混杂的歧义。

SKIP_CHAIN 典型用场：第一个 entry 是「快速 Skill 过滤」，成功后不需要跑后续耗时的 audit entry，直接跳出。

---

## 3. ScriptHandlerRunner 方案（P1 最重）

### 3.1 ProcessBuilder vs Runtime.exec

**选 ProcessBuilder**。原因：
- `Runtime.exec(String)` 内部也用 ProcessBuilder，但有 shell 解析歧义（空格拆词问题）
- ProcessBuilder 能显式设置环境变量（`environment()` map）、工作目录（`directory()`）、合并 stderr（`redirectErrorStream(true)`），一次搞定，不需要额外包装
- `Runtime.exec` 不选的核心理由：它没有独立 API 控制子进程环境变量，绕过去需要造轮子

### 3.2 工作目录策略

- 每次执行在系统临时目录下创建 **UUID 命名子目录**：`System.getProperty("java.io.tmpdir") + "/sf-hook/" + UUID`
- 创建：runner 开始前 `Files.createDirectories(...)`
- 清理：`ProcessBuilder` 在 finally 块里 `Files.walkFileTree(…, DELETE)` —— **无论成功还是超时都清理**，避免磁盘泄漏
- 为什么不用 agent 工作目录：hook script 属于平台内部执行，不应与 agent 工作目录混用，隔离更安全

### 3.3 Timeout 切断子进程

- 超时后先调 `process.destroy()`（SIGTERM，给 script 机会清理），等待 2s，若仍存活调 `destroyForcibly()`（SIGKILL）
- 不要只用 `destroyForcibly()`：有些 bash script 会起子进程，SIGTERM 能传播到进程组，SIGKILL 不能（但实际上 ProcessBuilder 不创建新进程组，所以两步保险）
- 实现：`process.waitFor(timeoutSec, TimeUnit.SECONDS)` 返回 false → destroy sequence

### 3.4 输入：stdin + 环境变量组合

**推荐**：input map 序列化为 JSON 通过 **stdin** 传入。理由：
- 命令行参数有长度限制（约 128KB），复杂 hook input 可能超限
- 环境变量也有限制，且暴露在 `ps` 输出里（安全顾虑）
- stdin 无大小限制（实际截断 64KB 足够），不暴露给 `ps`

额外通过环境变量传：`SF_SESSION_ID`、`SF_HOOK_EVENT`（轻量 metadata，方便 script 快速读取而无需 JSON parse）

命令行参数：不传 input，只传 lang-specific 标志（如 `bash -c "$scriptBody"` 或 `node -e "$scriptBody"`）

### 3.5 输出：stdout/stderr 读法 + 截断

- `redirectErrorStream(true)` 合并 stderr 到 stdout
- 后台线程读 stdout，写入 `StringBuilder`，超过 **32KB** 时截断（tail，保留最后 32KB）
- **为什么合并 stderr**：hook script 错误信息混在 stderr，不合并就看不到；32KB 足够诊断，不会撑爆 trace
- stdout 内容作为 `HookRunResult.output()`；exit code ≠ 0 → `success = false`

### 3.6 安全：复用 SafetySkillHook blocklist 够不够？

**不够，需要额外一层**。SafetySkillHook 是 `SkillHook` 接口（检查 Skill 参数），ScriptHandlerRunner 直接跑 OS 子进程，安全校验层级更底。

方案：
1. **scriptLang 白名单**：只允许 `application.yml` 里 `lifecycle.hooks.script.allowedLangs`（默认 `[bash, node]`）声明的语言；runner 开始前校验，不在白名单直接 `NOT_ALLOWED` 结果
2. **scriptBody 危险模式检测**：从 SafetySkillHook 的 `DANGEROUS_PATTERNS` 提取为独立工具类 `DangerousCommandChecker`，ScriptHandlerRunner 调用同一份 patterns —— 不重复逻辑，复用 P0 成果
3. **scriptBody 大小限制**：≤ 4KB（已在数据模型约束里，runner 再校验一次）

### 3.7 Lang 白名单：python 要不要

**P1 不加 python**。理由：
- python 环境不保证存在于所有部署机（bash/node 在 Linux/Mac 更普遍）
- python script 处理 `sys.stdin` 写法更复杂，测试量翻倍
- P2 可追加，whitelist 配置化所以加 python 不改代码

---

## 4. Prompt Enrichment（P1-5）

### 4.1 handler output 约定格式

UserPromptSubmit hook handler 如需注入上下文，output 必须是合法 JSON 且包含 `injected_context` 键：

```json
{"injected_context": "用户请求来自北京，当前时间 2026-04-17 09:30 CST"}
```

其他字段忽略。handler output 不是 JSON 或没有 `injected_context` → 视为纯 log，不注入。

### 4.2 注入 LoopContext.messages 位置

注入为 **前置 user 消息**（role=`user`），紧接在原始 user message 之前，content 前缀加 `[Context]\n`：

```
messages = [
  {role: user, content: "[Context]\n{injected_context}"},  ← 注入
  {role: user, content: 用户原始 prompt},
]
```

**为什么不注入 system**：system message 位于 messages 列表之外，修改它需要改 AgentDefinition，链路更长；插入 user 消息只需改 `LoopContext.messages`，LoopContext 本来就是 mutable 的。

**为什么不 append 到原始 user message 末尾**：合并会让 LLM 把 context 当用户意图，产生歧义；独立 user message 语义更清晰。

### 4.3 对 tool_use / tool_result 配对不变量影响

**无影响**。注入发生在 `fireUserPromptSubmit` 返回后、Agent loop 首次调 LLM 之前。此时 messages 只有初始 history（已完结的 tool_use/tool_result 对），不存在 dangling tool_use。注入的 user message 和 LLM 首次响应自然配对。

实现位置：`LifecycleHookDispatcherImpl.fireUserPromptSubmit` 运行完 dispatch 后，调用方（ChatService）拿到 `true` 后，在追加原始 user message 到 LoopContext 前，先追加 `injected_context` user message。这样时序最清晰，不需要 dispatcher 知道 LoopContext。

---

## 5. Forbidden Skill 黑名单（P1-6）

### application.yml 结构

```yaml
lifecycle:
  hooks:
    forbiddenSkills:
      - "Bash"        # 禁止 hook 触发 Bash
      - "FileWrite"
    script:
      allowedLangs:
        - "bash"
        - "node"
```

### 校验点

在 **SkillHandlerRunner** 中校验：runner 开始前检查 `handler.skillName` 是否在黑名单，在则直接返回 `HookRunResult(success=false, "forbidden_skill")`，不启动 Skill 执行。

**为什么在 Runner 不在 Dispatcher**：Dispatcher 不应知道 Skill 细节；Runner 是 Skill 执行的入口，校验在这里最自然，也方便单独测试。

---

## 6. 前端多 entry UI（P1-2）

### 列表渲染

每个 event Card 内部新增 `HookEntryList` 子组件：
- 渲染 `List<HookEntry>` 为竖向卡片列表（index 0 在上）
- 每个 entry 卡片展示：handler 摘要（skill 名 / script lang）+ timeout + policy + 操作按钮

### 操作按钮

| 操作 | 实现 |
|---|---|
| 上移 | swap `entries[i]` 和 `entries[i-1]`，新数组触发 `onConfigChange` |
| 下移 | swap `entries[i]` 和 `entries[i+1]` |
| 删除 | `entries.filter((_, idx) => idx !== i)` |
| 新增 | append 一个默认 entry（`{handler: {type: "skill", skillName: ""}, timeoutSeconds: 30, failurePolicy: "CONTINUE", async: false}`） |

上移/下移用不可变 spread，不 mutate 原数组。每个事件 entry 数量 cap 在 10，`新增` 按钮在达到 10 时 disabled。

### 默认值

新增 entry 时：handler type 默认 `skill`（因为 P1 script 才解锁，默认选已可用的），skillName 为空（用户必须填），timeout 30s，policy CONTINUE，async false。

---

## 7. 前端 Script handler 启用（P1-4）

### 最小 UI 变更

现有 FormMode 中 handler type Radio 里 `script` 选项已经存在但 disabled。P1 解锁步骤：

1. 移除 script option 的 `disabled` 标记
2. handler type = `script` 时，展示两个字段：
   - `lang` 下拉（Select）：选项来自后端 `GET /api/lifecycle-hooks/config`（或前端硬编码 `["bash", "node"]`，MVP 优先硬编码）
   - `scriptBody` TextArea：maxLength=4096，展示字符计数
3. Zod schema 的 script 分支已定义（P0 前端 schema 有 scriptHandler 类型）；只需移除 `.disabled()` 限制

**为什么硬编码 lang 列表而不请求后端**：少一个 API 调用；lang 列表极少变更；MVP 快。

---

## 8. 测试

### ScriptHandlerRunnerTest 必须用例

| 用例 | 断言 |
|---|---|
| bash echo → success | output 含 stdout 内容，exit 0 |
| bash exit 1 → failure | `success=false` |
| script 超时（sleep 100s，timeout=1s）| `success=false`，workdir 已清理 |
| 危险命令（`rm -rf /`）| `success=false`，`errorMessage` 含 "dangerous" |
| 禁止 lang（python）| `success=false`，`errorMessage` 含 "not allowed" |
| scriptBody > 4KB | 拒绝执行 |
| workdir 每次隔离 | 两次执行的 workdir 不同 |

### E2E 覆盖路径

1. 配置多 entry（2 个 SkillHandler）→ 触发 SessionStart → 两个 handler 都执行（trace 中各有一条 span）
2. 第一个 entry ABORT → 第二个 entry 不执行 + 主流程中断
3. SKIP_CHAIN → 第二个 entry 不执行，主流程继续
4. Script handler（bash echo） → 触发 UserPromptSubmit → output 含 injected_context → prompt enrichment 生效
5. Forbidden Skill → hook 配置 Bash → 触发时返回 forbidden，主流程按 failurePolicy 决定

---

## 9. P1 估时重算

P0 实际 5.5 天（含前后端），P1 子任务分解：

| 子任务 | 估时 |
|---|---|
| P1-1 dispatcher 链式 for 循环 + ChainDecision | 0.5天 |
| P1-3 ScriptHandlerRunner（含测试）| 2天 |
| P1-5 Prompt enrichment | 0.5天 |
| P1-6 Forbidden Skill | 0.5天 |
| P1-2 前端多 entry UI | 1天 |
| P1-4 前端 Script 启用 | 0.5天 |
| P1-7 E2E 测试 | 1天 |
| **合计** | **6天** |

---

## 10. 风险 + 缓解

| 风险 | 缓解 |
|---|---|
| 子进程僵尸进程：timeout 后 destroy 失败，子进程残留 | destroy → 2s wait → destroyForcibly；记录 PID 到 trace 便于手动排查 |
| workdir 清理失败（IO 异常）导致磁盘积累 | finally 块加 try-catch；后台定时任务清理 `sf-hook/` 下超过 1 小时的目录 |
| prompt enrichment 插入位置错误：注入 user message 被 LLM 当工具调用结果 | 单元测试验证 messages 顺序；注入消息加 `[Context]` 前缀帮助 LLM 识别 |
| SKIP_CHAIN 和 async entry 混用产生歧义 | async entry 强制 CONTINUE，在 dispatcher 内部判断，不依赖用户配置 |
| ScriptHandlerRunner 危险命令漏检（复杂 eval/base64 混淆）| 文档明确：危险命令检测是 best-effort，不保证 100%；生产部署应把 allowedLangs 配置为空 list 禁用 script handler |
