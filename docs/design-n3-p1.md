# N3 P1 最终实施方案

> 生成日期：2026-04-17
> 来源：Plan A（MVP 视角，`docs/n3-p1-lifecycle-hook-plan-a.md`）+ Plan B（安全视角，`docs/n3-p1-lifecycle-hook-plan-b.md`）+ Reviewer A（可行性，`docs/n3-p1-lifecycle-hook-review-a.md`）+ Reviewer B（安全/正确性，`docs/n3-p1-lifecycle-hook-review-b.md`）+ team-lead consolidation

---

## 0. P1 目标

在 P0 基础上补齐：
1. **多 entry 链式执行**（P0 只跑 `list[0]`，P1 真正支持多 handler 串联）
2. **`SKIP_CHAIN` policy**（成功/失败早退出）
3. **ScriptHandlerRunner**（bash/node 子进程执行，P1 最重）
4. **前端多 entry UI** + Script handler 启用
5. **Prompt enrichment**（UserPromptSubmit handler 输出注入 LoopContext）
6. **Forbidden Skill 黑名单**
7. **E2E 测试**

**不改 schema**，P0 的 `List<HookEntry>` + polymorphic handler 已经就绪。

---

## 1. 15 个关键决策（按影响排序）

### 1.1 【CRITICAL】工作目录路径校验：`/tmp` symlink 陷阱

macOS 上 `/tmp → /private/tmp`。两份 plan 要么没校验，要么校验错。**最终方案**：

```java
Path tmpRoot = Paths.get(System.getProperty("java.io.tmpdir")).toRealPath();
Path workdir = tmpRoot.resolve("sf-hook/" + UUID.randomUUID());
Files.createDirectories(workdir);
// 校验：
Path real = workdir.toRealPath();
if (!real.startsWith(tmpRoot.resolve("sf-hook"))) throw new SecurityException();
```

**动态拿 `tmpRoot.toRealPath()` 做前缀**，不硬编码 `/tmp/`。macOS 返回 `/private/tmp`，Linux 返回 `/tmp`，都 OK。

### 1.2 【HIGH】Stdout/stderr：双线程 drain-and-discard

Plan A 用 `StringBuilder` 累积再截断 → 死循环输出脚本触发 OOM。Plan B 双线程 drain 是对的。**最终方案**：

- `ProcessBuilder.redirectErrorStream(true)`（合并到 stdout，简化 FD 管理）
- 单独一个 reader 线程持续读 stdout，写入固定大小的 `ByteArrayOutputStream`（cap 64KB）
- 超过 64KB 后**继续读但丢弃**（drain-and-discard），**永不**让 StringBuilder 无界增长
- reader 线程生命周期：`ProcessBuilder.start()` 之后立即起，`process.waitFor()` 之后 join（最多 2s）
- submit 到 `hookExecutor`（同 P0），不新建 pool

### 1.3 【HIGH】Timeout kill：进程树递归

Plan A 的 `destroy → 2s → destroyForcibly` 杀不掉 bash `sleep 100 &` 产生的后台进程。**最终方案**（Java 17 已有 API，无依赖）：

```java
if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
    // 先收所有子孙，再收自己
    process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    process.waitFor(2, TimeUnit.SECONDS);  // 给 OS 时间 reap
}
```

### 1.4 【HIGH】环境变量：继承 5 个系统 + 白名单 SF_*

Plan B 的"全 clear + 固定 PATH"会让 macOS+Homebrew 的 `node` 找不到（`/opt/homebrew/bin` 不在 `/usr/local/bin:/usr/bin:/bin`）。Plan A 的"全继承 + 加 SF_*"会泄露 `ANTHROPIC_API_KEY`。**最终折中**：

```java
Map<String, String> env = pb.environment();
env.clear();
// 从 JVM env 继承 5 个必要的
for (String k : List.of("PATH", "LANG", "HOME", "TMPDIR", "TZ")) {
    String v = System.getenv(k);
    if (v != null) env.put(k, v);
}
// 加 SF_* 元数据
env.put("SF_HOOK_EVENT", event.name());
env.put("SF_SESSION_ID", sessionId);
env.put("SF_AGENT_ID", String.valueOf(agentId));
```

**PATH 从 JVM 继承**，macOS Homebrew 正常；`ANTHROPIC_API_KEY` / `AWS_*` / `DB_PASSWORD` 等敏感变量因 `env.clear()` 被剔除。文档明确："生产部署建议通过容器/systemd 进一步收窄 PATH"。

### 1.5 【HIGH】Prompt enrichment：独立 user message，不拼 content

Reviewer B 指出 Plan B 的"追加到现有 user message content 前"假设 content 是 String，但 `ContentBlock[]`（含 `tool_result` / `image`）结构会 ClassCastException 或破坏数据结构。Plan A 新增独立 user message 是正确的。

**最终方案**：
- handler output 约定 JSON：`{"injected_context": "..."}`
- 为空字符串 / null / 字段不存在 → 不注入
- 非空 → 在 `ChatService` 构造 LoopContext 时、追加原始 user message 之前，插入一条 role=user、content="[Context]\n${injected_context}" 的独立 message
- **所有 provider 均适用**（Claude / OpenAI / DeepSeek / Qwen / vLLM / Ollama 都接受连续 user role messages，会 internally concat）

**tool_use / tool_result 配对不变量不受影响**：插入发生在 LLM 调用前、已完结的 tool_result 对之后，不破坏任何 dangling 配对。

**兜底**：如果未来某个非主流 provider 严格要求 user/assistant 交替，可在 LLM 调用前加一个 normalize 步骤合并相邻同 role —— 不在 P1 scope。

### 1.6 【HIGH】`runEntry()` 返回值语义变更（两份 plan 遗漏）

P0 `runEntry()` 返回 `HookRunResult(success, output, error, duration)`。P1 dispatcher 要根据链决策做循环，`HookRunResult` 增加一个字段：

```java
public record HookRunResult(
    boolean success,
    String output,
    String errorMessage,
    long durationMs,
    ChainDecision chainDecision  // 新增：CONTINUE / ABORT / SKIP_CHAIN
) {}

public enum ChainDecision { CONTINUE, ABORT, SKIP_CHAIN }
```

**`chainDecision` 由 dispatcher 根据 `(success, entry.failurePolicy)` 计算**，不是 runner 决定（runner 只管"执行得怎样"）：

| success | failurePolicy | chainDecision |
|---|---|---|
| true | 任何 | CONTINUE（继续下一 entry） |
| false | CONTINUE | CONTINUE（忽略失败继续） |
| false | ABORT | ABORT（主流程中断） |
| false | SKIP_CHAIN | SKIP_CHAIN（跳出本 event 剩余 entry，主流程继续） |

**async entry**（`async=true`）永远返回 `ChainDecision.CONTINUE`（与 P0 一致）。`runAsync()` 不读 `ChainDecision`。

### 1.7 【HIGH】Dispatcher for 循环 + ABORT 传播

```java
for (int i = 0; i < entries.size(); i++) {
    HookEntry entry = entries.get(i);
    HookRunResult result = runEntry(entry, input, i);
    traceHookEntry(event, entry, i, result);  // 每条独立 TraceSpan
    if (result.chainDecision() == ChainDecision.ABORT) return false;  // 主流程中断
    if (result.chainDecision() == ChainDecision.SKIP_CHAIN) break;  // 跳出链，继续下一 event
    // CONTINUE fallthrough
}
return true;
```

**ABORT 语义**：不再依赖 `HookAbortException` 抛错（reviewer B 指出的遗漏点）—— `HookAbortException` 是 P0 loop 层用的，dispatcher 层只用返回值。dispatcher.dispatch() 返回 false 让 `LifecycleHookLoopAdapter` 设置 `LoopContext.abortedByHook`，再由 `AgentLoopEngine` 按 P0 既有路径处理。

### 1.8 【HIGH】Schema 降级防护：FailurePolicy 新枚举值

P1 引入 `SKIP_CHAIN` 枚举值。旧 server（回滚场景）读到 `"SKIP_CHAIN"` 时，Jackson 抛 `InvalidFormatException`，`AgentService` 的 catch 层把**整个 `lifecycle_hooks` 降级为 null**，所有 hook 静默消失。**最终方案**：在 `LifecycleHooksConfig` 的 `FailurePolicy` 反序列化处加：

```java
@JsonCreator
public static FailurePolicy fromString(String s) {
    try { return FailurePolicy.valueOf(s); }
    catch (IllegalArgumentException e) { return CONTINUE; }  // 未知枚举 → 默认 CONTINUE，保持 hook 可运行
}
```

这样 P1 → P0 回滚时，`SKIP_CHAIN` 被旧 server 读成 `CONTINUE`（保守降级，hook 照跑），不是整个配置丢。

### 1.9 【HIGH】async + SKIP_CHAIN 组合：保存时 400 拒绝

Plan B 的"静默降级"会让用户以为配置生效实际没生效。**最终方案**：`AgentService.updateAgent` 在 JSON 解析后检查：若有 `async=true && failurePolicy=SKIP_CHAIN`，抛 `IllegalArgumentException("async entry cannot use SKIP_CHAIN policy")`，Spring 转 400。前端 Zod schema 也加同样校验（前端提示更友好，但以后端为准）。

### 1.10 【HIGH】ScriptHandler.scriptBody 后端校验（reviewer B 遗漏点）

前端 Zod `maxLength=4096` 可被直接 API 调用绕过。**最终方案**：`AgentService.updateAgent` 解析 JSON 后遍历所有 `ScriptHandler` entry，校验 `scriptBody.length() <= 4096`，超限 → 400。与前端双重防线。

### 1.11 【MEDIUM】危险命令检测：抽 `DangerousCommandChecker` 复用

先 `Read` `SafetySkillHook.java` 看 `DANGEROUS_PATTERNS` 实际内容（reviewer A 警示）。如果 patterns 是针对工具参数不是 bash 命令字符串，需要补一组 bash 专用 patterns：`rm -rf /`、`sudo `、`curl|sh`、`wget|sh`、fork bomb 等。

**抽出位置**：`skillforge-core/src/main/java/com/skillforge/core/engine/DangerousCommandChecker.java`（新建工具类，`SafetySkillHook` 和 `ScriptHandlerRunner` 都依赖它）

**检测时机**：`ScriptHandlerRunner.run()` 入口，`scriptBody` 走 `DangerousCommandChecker.check()`，命中直接返回 `HookRunResult(success=false, "dangerous_command:<pattern>")`。

**文档化局限**：检测是 best-effort，eval/base64 混淆绕不过。生产部署建议 `application.yml: lifecycle.hooks.script.allowedLangs: []` 禁用整个 Script handler。

### 1.12 【MEDIUM】Forbidden Skill 校验在 Dispatcher，不在 Runner

Reviewer A 指出：runner 拿配置破坏职责分离。**最终方案**：`LifecycleHookDispatcherImpl.runEntry()` 调 runner 前先 check：

```java
if (handler instanceof SkillHandler sh && forbiddenSkills.contains(sh.getSkillName())) {
    return new HookRunResult(false, null, "forbidden_skill:" + sh.getSkillName(), 0, chainDecisionFor(false, entry.failurePolicy()));
}
```

`application.yml`:
```yaml
lifecycle:
  hooks:
    forbidden-skills: [SubAgent, TeamCreate, TeamSend, TeamKill]
    script:
      allowed-langs: [bash, node]
      max-output-bytes: 65536
```

**文档化局限**：黑名单只拦 `SkillHandler.skillName`，**不拦 Skill 内部调用其他 Skill**（reviewer B HIGH #5）。P1 不修这个，内置 Skill 都可信。

### 1.13 【MEDIUM】Lang 白名单：硬编码 [bash, node]，P2 加 python

Reviewer A/B 一致同意前端硬编码。后端走 `application.yml: allowed-langs` 动态读，前端 FormMode 固定选 `[bash, node]`。加 python 时前端改数组 + 后端配置 + 单元测试，不需要 API 端点。

### 1.14 【MEDIUM】每条 entry 独立 TraceSpan

Plan B 对的：链中哪条失败必须能独立定位。TraceSpan 属性：
- `entryIndex`（0-based）
- `handlerType`（"skill" / "script"）
- `handlerName`（SkillHandler 的 skillName；ScriptHandler 的 `<lang>:<first 40 chars of scriptBody>`）
- `chainDecision`（CONTINUE/ABORT/SKIP_CHAIN）
- `stdoutSize`（实际读取字节，未截断前）
- **不存 scriptBody**（防日志泄露；P0 已有此原则）
- stdout 输出截断到 **4KB** 存 span（再长去 stdout 文件或只看 runner log）

### 1.15 【LOW】前端 Script handler 启用 + 一次性 confirm Banner

FormMode 去 Script option 的 `disabled`；handler type = script 时渲染 lang Select (`[bash, node]`) + scriptBody `Input.TextArea`（rows=8, maxLength=4096, monospace）。

**新增 confirm banner**（Plan B §6）：首次勾选 `script` 时弹 AntD `Modal.confirm`："脚本会在服务器执行，确认内容安全。生产部署应禁用 Script handler。" localStorage 记住用户已确认，不再弹。

---

## 2. 实施文件清单

### 2.1 后端

**新增**：
- `skillforge-core/src/main/java/com/skillforge/core/engine/DangerousCommandChecker.java`（从 `SafetySkillHook` 提取 + 补 bash 专用 pattern）
- `skillforge-server/src/main/java/com/skillforge/server/hook/ScriptHandlerRunner.java`
- `skillforge-server/src/main/java/com/skillforge/server/hook/ScriptWorkdirManager.java`（路径校验 + 创建 + 清理，封装 §1.1 tmp-symlink 逻辑）
- `skillforge-server/src/main/java/com/skillforge/server/config/LifecycleHooksScriptProperties.java`（`@ConfigurationProperties("lifecycle.hooks.script")`）
- `skillforge-server/src/test/java/com/skillforge/server/hook/ScriptHandlerRunnerTest.java`

**修改**：
- `skillforge-core/src/main/java/com/skillforge/core/engine/hook/HookRunResult.java`（加 `ChainDecision chainDecision` 字段）
- `skillforge-core/src/main/java/com/skillforge/core/engine/hook/ChainDecision.java`（新 enum，放 core 层）
- `skillforge-core/src/main/java/com/skillforge/core/engine/hook/LifecycleHooksConfig.java`（FailurePolicy `@JsonCreator` 防降级炸）
- `skillforge-core/src/main/java/com/skillforge/core/engine/hook/FailurePolicy.java`（加 `SKIP_CHAIN` 枚举）
- `skillforge-server/src/main/java/com/skillforge/server/hook/LifecycleHookDispatcherImpl.java`（`dispatch()` 改 for 循环 + forbidden-skills check + SKIP_CHAIN 处理 + Script runner 路由）
- `skillforge-server/src/main/java/com/skillforge/server/hook/SkillHandlerRunner.java`（删 forbidden check，移到 dispatcher）
- `skillforge-server/src/main/java/com/skillforge/server/service/AgentService.java`（加 scriptBody 长度校验 + async+SKIP_CHAIN 组合校验）
- `skillforge-server/src/main/java/com/skillforge/server/service/ChatService.java`（UserPromptSubmit 后处理 `injected_context` 注入为独立 user message）
- `skillforge-server/src/main/resources/application.yml`（加 `lifecycle.hooks.forbidden-skills` + `script` 节）
- `skillforge-server/src/test/java/com/skillforge/server/hook/LifecycleHookDispatcherTest.java`（补多 entry / SKIP_CHAIN / forbidden 测试）

### 2.2 前端

**修改**：
- `skillforge-dashboard/src/constants/lifecycleHooks.ts`（Zod schema 加 SKIP_CHAIN + async×SKIP_CHAIN 排除校验；script lang 白名单）
- `skillforge-dashboard/src/components/lifecycle-hooks/FormMode.tsx`：
  - 每个 event Card 下渲染 entry 列表（List + 上移/下移/删除/新增，cap 10）
  - Script handler 启用：去 disabled，lang Select + scriptBody TextArea
  - 首次选 script 弹确认 Modal，localStorage 记忆
- `skillforge-dashboard/src/components/lifecycle-hooks/JsonMode.tsx`（SKIP_CHAIN 选项）

---

## 3. 测试计划

### 3.1 单元测试（必须全绿）

**`ScriptHandlerRunnerTest`**（新增 8 条）：
1. bash echo success → output 含内容，exit=0
2. bash exit 1 → success=false
3. timeout（sleep 100, timeout=1） → success=false + workdir 已清理
4. stdout 超 64KB（`head -c 1m /dev/urandom`）→ 不死锁，保留 64KB
5. 危险命令（`rm -rf /`）→ success=false + error 含 "dangerous_command"
6. 禁止 lang（`python`）→ success=false + error 含 "lang_not_allowed"
7. scriptBody > 4KB → 拒执行（其实这条在 AgentService 保存时就挡了，runner 是二次防线）
8. 进程树清理：bash `sleep 100 &` → timeout 后用 `ps` 验证孙进程已死

**`LifecycleHookDispatcherTest`**（补 6 条）：
9. 多 entry CONTINUE → 全部执行 + trace 按 index
10. 多 entry ABORT at index 1 → index 2 未执行，dispatcher 返回 false
11. 多 entry SKIP_CHAIN at index 0 → 剩余 entry 跳过，dispatcher 返回 true
12. Forbidden skill → 绕过 runner，直接 error
13. async + SKIP_CHAIN 组合实际执行时降级为 CONTINUE（即使保存时被拒，老数据可能漏）
14. 两个 async entry 并发 → hookDepth 在 executor 线程隔离正确

**`LifecycleHooksConfigSerdeTest`**（补 2 条）：
15. FailurePolicy 未知值（模拟旧 server 读新数据）→ 降级为 CONTINUE，不抛异常
16. ScriptHandler 反序列化完整 round-trip

**`ChatServiceLifecycleHookTest`**（补 2 条）：
17. UserPromptSubmit hook 返回 `{"injected_context":"X"}` → LoopContext.messages 末尾出现 `user: [Context]\nX`
18. injected_context 为空/null → messages 不变

### 3.2 E2E（agent-browser）

1. 多 entry 配置（2 个 SkillHandler）+ 保存 + 重载 → JSON 回填正确
2. Script handler 启用 → bash `echo hello` → 触发 session 观察 trace
3. 首次选 script 弹确认 Modal
4. 多 entry 顺序（上移/下移）+ 保存 + 重载 → 顺序保持

### 3.3 手动验证（合并前跑）

- `curl -X PUT /api/agents/{id}` 带 `async=true && failurePolicy=SKIP_CHAIN` → 返回 400 ✅
- `curl -X PUT /api/agents/{id}` 带 `scriptBody` 超 4KB → 返回 400 ✅
- macOS 上跑一个 Script handler → workdir 校验不误拒 ✅

---

## 4. 风险 + 缓解

| 风险 | 缓解 |
|---|---|
| ScriptHandlerRunner pipe 死锁 | 双线程 drain-and-discard，64KB cap；单元测试 4（stdout 无限输出）|
| Timeout 后子进程残留 | `ProcessHandle.descendants().destroyForcibly()`；单元测试 8 |
| `/tmp` symlink 路径校验假负 | 动态 `tmpRoot.toRealPath()` 做前缀比较；单元测试覆盖 macOS |
| scriptBody 绕过前端校验塞大 payload | 后端 AgentService 二次校验 |
| SKIP_CHAIN 回滚不兼容 | FailurePolicy `@JsonCreator` 未知值 → CONTINUE |
| async + SKIP_CHAIN 用户配置错误 | 保存时 400 拒绝（前后端双重） |
| Forbidden Skill 嵌套绕过 | 文档化局限，P1 不修（内置 Skill 可信） |
| Env var `ANTHROPIC_API_KEY` 泄给 script | env.clear() + 白名单 5 个系统变量 |
| 危险命令 eval/base64 混淆绕过 | 文档化 best-effort；生产用 `allowed-langs: []` 禁用 |
| 前端连续 user message 在某些严格 provider 被拒 | 主流 provider（Claude/OpenAI/DeepSeek/Qwen/Ollama）都接受连续 user；真遇到严格 provider 在 LLM 调用前 normalize 合并相邻同 role（P2） |

---

## 5. 估时

| 子任务 | 估时 |
|---|---|
| P1-1 多 entry for 循环 + SKIP_CHAIN + ChainDecision + Forbidden check 移到 dispatcher | 1天 |
| P1-2 前端多 entry UI（列表 + 操作按钮 + cap） | 1天 |
| P1-3 ScriptHandlerRunner（含 ScriptWorkdirManager + DangerousCommandChecker + 8 个单元测试）| **2.5天** |
| P1-4 前端 Script 启用（lang + body + confirm Modal）| 0.5天 |
| P1-5 Prompt enrichment（ChatService 注入 + 单元测试）| 0.5天 |
| P1-6 Forbidden Skill 配置化 + FailurePolicy 防降级 + scriptBody 校验 + async×SKIP_CHAIN 校验 | 0.5天 |
| P1-7 E2E 测试（agent-browser 4 路径）| 0.5天 |
| **合计** | **6.5天** |

---

## 6. Pipeline 配置

按 SkillForge Full Pipeline 走：
1. ~~Plan（本文档）~~ ✅
2. **Dev**：backend + frontend 并行 worktree Agent（Opus）
3. **Review**：java-reviewer / typescript-reviewer / security-reviewer 并行（Sonnet，可能 via Team 或 Agent spawn）
4. **Judge + Fix**：via TeamCreate（用户明确要求）
5. **Verify**：mvn test + npm build + agent-browser e2e
6. **Commit**：single feature commit

---

## 7. 用户确认纪要（2026-04-17）

- ✅ §1.4 环境变量白名单：初始 5 个系统变量（PATH/LANG/HOME/TMPDIR/TZ）+ SF_* 元数据，其余 clear；后续需要可扩
- ✅ §1.5 Prompt enrichment：支持 Claude / OpenAI / DeepSeek / Qwen / Ollama 所有主流 provider（全部接受连续 user role）
- ✅ §1.12 Forbidden Skill 嵌套：只防 Hook→dispatch→Skill 路径，不防恶意 Skill 主动调 SkillRegistry 绕行；文档化局限（内置 Skill 可信，风险仅在用户上传 zip Skill 场景）
- ✅ §1.15 Script 首次选择时弹一次性 Confirm Modal
- ✅ 估时 6.5 天

确认完毕，启动 Dev pipeline。
