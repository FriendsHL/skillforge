# N3 P1 Review B — 安全/正确性挑战

> reviewer-b | 2026-04-17 | 安全/正确性视角

---

## 给 Plan A 的挑战

### HIGH

- **危险命令检测是 best-effort 且文档化"不保证"**：Plan A §3.6 明确说"危险命令检测不保证 100%，生产应把 allowedLangs 设为空 list"。但用户通过前端 UI 启用 Script handler 时完全不会看到这条文档。UI 只有 "脚本在服务器执行，确认内容安全" 一次性 Banner（Plan B §6），既不说明白名单检测的局限，也没有引导用户去关闭 allowedLangs。设计上"后门开着，只在文档里写了危险"是 HIGH 级隐患。

- **workdir 路径：`System.getProperty("java.io.tmpdir") + "/sf-hook/" + UUID`**（Plan A §3.2）。这个路径不含 sessionId，UUID 虽然唯一，但如果应用高并发创建目录，finally 块里的 `Files.walkFileTree(…, DELETE)` 如果 IO 失败（磁盘满 / 权限拒绝），目录泄漏且没有 fallback 清理机制。Plan A §10 说"后台定时任务清理超过 1 小时的目录"，但没有包含在 P1 估时表里（P1 子任务表不含定时任务），实际不会做。

- **SKIP_CHAIN 语义在 Plan A §2 的决策表存在歧义**：表格第 4 行 "timeout" + "SKIP_CHAIN" 列写 "停止链，主流程继续"。但 timeout 实际上是 failure 的一种，Plan A 的语义要求 "timeout 视为 failure，按 failurePolicy 决策"。SKIP_CHAIN 是在 handler 成功还是失败时触发？从 Plan A 表格来看是"执行失败时"，但 Plan B §3.1 伪码是 `result == ABORT`（全局决策）而非 per-failure。两份 plan 的 SKIP_CHAIN 触发条件定义不一致，dev 会按哪个实现？

### MEDIUM

- **stdout 截断策略"tail 保留最后 32KB"**（Plan A §3.5）：对于死循环产生无限输出的 bash，后台读线程需要持续读并丢弃。但 Plan A 用 `StringBuilder` 积累再截断，不是 drain-and-discard——当脚本输出速度超过读速度时，StringBuilder 会无限增长直至 OOM，再截断。Plan B §2.2 明确用 drain-but-discard（继续读、只保留前 64KB）才是正确实现。

---

## 给 Plan B 的挑战

### CRITICAL

- **`File.getCanonicalPath()` vs macOS `/tmp` symlink**（Plan B §2.7）：macOS 上 `/tmp` 是 `/private/tmp` 的 symlink。`new File("/tmp/sf-hook-xxx").getCanonicalPath()` 解析后返回 `/private/tmp/sf-hook-xxx`，前缀变成 `/private/tmp/sf-hook-`，而不是 `/tmp/sf-hook-`。校验 `canonicalPath.startsWith("/tmp/sf-hook-")` 将**永远失败**，导致所有 macOS 开发机的 hook 执行都被拒绝。修复：校验时同时用 `canonicalPath.startsWith("/private/tmp/sf-hook-")` 或改用 `Path.startsWith(tmpDir.toPath())` 动态拿 tmpDir 的 canonical 路径再比较。

### HIGH

- **Prompt enrichment 注入位置：plan B §4.1 说"注入到现有最后一条 user message 的 content 前追加"**，而非新增 message。但设计文档 design-n3-lifecycle-hooks.md §3.5 说 "追加到 LoopContext.messages 作为 system 消息"，两份文档相互矛盾。更关键的是：如果当前最后一条 message 是 `tool_result`（multi-block content，类型为 `ContentBlock[]` 而非纯 `String`），直接往 content 前追加 String 会触发 ClassCastException 或破坏数据结构。P0 现有代码 LoopContext 中 message content 是 Jackson 反序列化的异构结构，不是统一的 String。

- **async=true + failurePolicy=SKIP_CHAIN 静默降级**（Plan B §3.2）：降级为 CONTINUE 并写 warn log。问题在于用户通过 JSON Mode 配置保存这个组合，后端存储和返回时不报错，用户以为配置生效了但实际上不是。下次用户调试时看 trace 里 SKIP_CHAIN 没有触发，却不知道是被静默改掉了。建议：保存时（PUT /api/agents/{id}）后端校验这个组合并返回 400 + 明确错误，而不是运行时静默改行为。Plan A 在决策表里直接说 "async entry 无效（async 不参与链控制）"，至少立场清晰。

### MEDIUM

- **`GET /api/lifecycle-hooks/script-langs` 新端点**（Plan B §6）需要 P1-4 去实现，但 P1 估时表里 P1-4 只有 0.5天，要同时做前端 Script 启用 + 新端点。这是 Plan B 相比 Plan A（硬编码 lang 列表，不加端点）多出的工作量，但估时没有对应增加。

---

## 6 个必挑战问题的立场

**1. PATH 白名单实用性**

Plan B 把 `PATH` 固定为 `/usr/local/bin:/usr/bin:/bin`。macOS + Homebrew 用户的 `node` 装在 `/opt/homebrew/bin/node`，不在白名单内，`node -e` 命令会 "command not found"。dev 机默认跑不起来是开发体验问题，会推着开发者在 `allowedLangs` 里把 node 移除来规避，反而比不限制 PATH 更危险（让 bash 能做一切但 node 不可用）。**MVP 建议**：PATH 保留继承，但明确文档化"生产部署应通过容器/systemd 收窄环境变量"，而不是在 Java 里硬编码一个在 macOS 上跑不通的路径。

**2. macOS /tmp symlink**

如上 CRITICAL 所述：`getCanonicalPath().startsWith("/tmp/sf-hook-")` 在 macOS 上永远判错。两份 plan 都未提及这个问题，Plan A 没有做路径校验，Plan B 做了但校验逻辑本身有 bug。这是真实会发生的 correctness 问题，不是边界猜测。

**3. user message 多 block 结构**

Plan B §4.1 的注入方式（"在现有最后 user message content 前追加"）假设 content 是单一 String。但 Anthropic API 的 user message content 可以是 `ContentBlock[]`（含 `tool_result` block、`image` block 等）。LoopContext 存储的历史消息如果包含多 block content，往 content 前面拼 String 的操作要么 ClassCastException，要么只追加到第一个 text block，语义错误。Plan A §4.2 的方案（插入独立的 user message 对象）完全规避这个问题，是正确的。

**4. async + SKIP_CHAIN 静默降级**

行为上的静默改变比报错更危险。用户配置 `async=true, failurePolicy=SKIP_CHAIN` 是错误配置，Plan B 选择静默降级 + warn log，但 warn log 用户不看。建议在保存时 API 层校验拒绝，或至少在 TraceSpan 里打上 `policy_overridden: SKIP_CHAIN→CONTINUE` 让用户能在 Traces 页面发现。

**5. Forbidden Skill 嵌套拦截**

两份 plan 的禁止 Skill 黑名单都在 `SkillHandlerRunner` 入口校验（Plan A §5，Plan B §5）。但 `hookDepth` ThreadLocal 只防止 "hook 里的 Skill 调 hook"，不能防止 SkillA（非禁止）内部通过 SkillRegistry 直接调用 SkillB（被禁止）。实际上 P0 的 `SkillHandlerRunner` 直接调 `skill.execute()`，如果 SkillA 的实现内部 new 出 SubAgent 或直接调用 TeamCreate，黑名单在这层是绕过的。`hookDepth` 只管 dispatcher 层。这个攻击向量在 MVP 能被恶意 Skill 利用，但现有 Skill 都是内置的，风险暂时可控——但设计文档应明确说明这个局限。

**6. Schema version 降级**

当前 `version: 1` 没有语义。P1 引入 `SKIP_CHAIN` failurePolicy 是一个新枚举值，旧 server（没有 P1 代码）读到 `failurePolicy: "SKIP_CHAIN"` 时，`@JsonIgnoreProperties(ignoreUnknown = true)` 只忽略**未知字段**，不忽略**已知字段的未知枚举值**——Jackson 反序列化 `FailurePolicy.valueOf("SKIP_CHAIN")` 会抛异常，导致整个 `lifecycle_hooks` 降级为 null（AgentService 的防腐层 catch），所有 hook 静默消失。降级回滚时的表现不是"hook 按旧逻辑跑"而是"hook 全部不跑"。两份 plan 都未提及这个 rollback 风险。建议：把 `SKIP_CHAIN` 的引入标记为需要 `version: 2` 的 breaking change，或在 FailurePolicy 反序列化时显式配置 `DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL` + null 时降级为 CONTINUE。

---

## 两份 plan 都遗漏的 correctness 点

- **`HookAbortException` 在 `runEntry()` 抛出路径未覆盖**：P0 的 `LifecycleHookDispatcherImpl` 把 runner 放在 `CompletableFuture.supplyAsync` 里执行，`runSync` catch 的是 `ExecutionException`。但 `HookAbortException` 是 P0 设计（`LoopContext.abortedByHook` + 异常）用于 loop 层，不是 dispatcher 层。如果 runner 内部抛 `HookAbortException`，会被 `ExecutionException` 包裹后在 dispatcher 的 catch 块里当普通异常处理，ABORT 语义丢失。P1 多 entry 循环里需要明确这个异常的传播路径。

- **`ScriptHandler.scriptBody` 的 4KB 限制在 P0 data model 中并无后端校验**：设计文档说"backend 校验 scriptBody ≤ 4KB"，但现有 `HookHandler.ScriptHandler` 只有字段无 `@Size`，`AgentService` 的 updateAgent 路径也没有校验。前端 Zod maxLength=4096 是唯一防线，可被直接 API 调用绕过。

- **多 entry 循环与 `hookDepth` 的交互**：P1 dispatch 改为 for 循环后，整个链的 depth 递增逻辑需要重新确认。当前 P0 `runEntry` 在进入时 `hookDepth.set(previousDepth + 1)`，在 finally 恢复。多 entry 循环调多次 `runEntry` 时，每次进出都递增再恢复，如果 entry N 的 runner 内部触发另一个 dispatch（depth=1），depth check 是 `>= MAX_HOOK_DEPTH(1)`，会正确拦截。逻辑自洽。但 async entry 的 `propagatedDepth` 是拍摄的快照，在 executor 线程上设置，if 第二个 async entry 也在并发执行，两个 executor 线程各自带 depth=1 去运行，互不干扰——这是正确的。但需要单元测试验证：两个 async entry 并发时各自的 depth 隔离。

---

## 最终建议

Plan B 的安全防御思路正确，FD 隔离和 env 白名单是真实需要。但 `/tmp` symlink 的 path 校验 bug（CRITICAL）和 Prompt enrichment 的 multi-block content 假设（HIGH）是会在首次上线即暴露的 correctness 问题。建议 dev agent 采用 Plan A 的 prompt enrichment 方案（插入独立 user message），同时采用 Plan B 的 env 白名单原则但改 PATH 策略为继承+文档约束，并在 FailurePolicy 反序列化处加 unknown-enum-as-null 防护以支持平滑降级。
