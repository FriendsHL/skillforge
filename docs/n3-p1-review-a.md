# N3 P1 Review A — 可行性挑战

> reviewer-a | 2026-04-17 | 角度：可行性 / 实现成本 / MVP scope

---

## 给 Plan A 的挑战（按严重度）

### HIGH

- **[§3.5] redirectErrorStream 掩盖了一个真实风险，但推论有误。** Plan A 声称"合并 stderr 解决问题"，但 pipe 死锁的触发条件是"主线程阻塞 + 子进程写满 buffer"——合并成一个流后，如果读者线程（主线程）本身在 `process.waitFor` 或 `fut.get` 里等待而不在读 stdout，死锁照样发生。Plan A §3.5 的"后台线程读 stdout"是关键，这才是真正的保护；`redirectErrorStream(true)` 只是减少了需要管理的 FD 数量，本身不解决死锁。现有描述误导实现者以为合并流就能省掉独立读线程，**这是设计陷阱**。建议：§3.5 明确要求"必须用独立线程在 `fut.get` 同时持续读取 stdout"，不能在 `fut.get` 返回后再一次性读。

- **[§3.3 + §4.2 逻辑不对称] 注入方式与 tool_use/tool_result 不变量存在潜在冲突。** Plan A §4.2 将 `injected_context` 作为独立的前置 `user` 消息插入。问题在于：如果当前 messages 末尾是 `tool_result`（多轮对话中非首轮），再插入一条 `user` 消息，消息序列变成 `[..., tool_result, user(context), user(original)]`，连续两条 `user` role 消息在 Anthropic API 里会被拒绝（必须严格交替 user/assistant）。Plan A §4.3 声称"此时 messages 只有初始 history（已完结的 tool_use/tool_result 对）"，但 UserPromptSubmit 是"每一轮都触发"（设计文档 §10），并非只在首轮——多轮对话的非首轮，末尾必然是 assistant 消息，后面跟两个连续 user 消息，API 可接受；但如果某个 LLM provider 对连续 user 消息有限制，就会炸。**建议**：在实现时验证目标 LLM provider（Claude/OpenAI/DeepSeek）对连续 user message 的容忍度，或采用 Plan B 的追加到现有最后 user message 方案，规避风险。

- **[§9 估时] P1-3 ScriptHandlerRunner 估 2 天，但遗漏了双线程 drain 的实现和测试成本。** Plan A 选了 `redirectErrorStream(true)` 的简单路，按理应该省时，但 Plan A 自己又要求"后台线程读 stdout"，这意味着 JVM 侧仍需管理 `Process.getInputStream()` 的读取线程 + `CompletableFuture` 组合。实际需要：ProcessBuilder 启动 → 独立线程 drain → 超时 kill → workdir 清理 → 测试 5 个必需用例（其中"stdout 超 64KB 不死锁"是最难写的）。对比 P0 的 `SkillHandlerRunner` 约 0.5 天，`ScriptHandlerRunner` 涉及 OS 子进程 + 跨线程 + 文件系统，2 天是合理下限，但没有缓冲——建议按 2.5 天估。

### MEDIUM

- **[§3.6] 危险命令检测从 SafetySkillHook 提取为工具类，但没说明 SafetySkillHook 的 DANGEROUS_PATTERNS 覆盖哪些。** 现有代码（`SkillHandlerRunner.java`）里没有任何 `DangerousCommandChecker` 引用，意味着这个工具类需要从零创建。如果 SafetySkillHook 的 patterns 只针对工具参数（不是 bash 命令字符串），复用率可能很低，需要重新梳理模式。建议在实现前先查阅 SafetySkillHook 的实际 patterns，再决定能复用多少。

- **[§5 Forbidden Skill] 在 SkillHandlerRunner 而不是 Dispatcher 校验，但 Dispatcher.dispatch() 里 runner 路由已经发生了。** 当前 `LifecycleHookDispatcherImpl.dispatch()` 先查 runner，再调 `runner.run()`。如果校验在 runner 内部，`SkillHandlerRunner.run()` 需要注入 `forbiddenSkills` 配置——这要求 `application.yml` 里的 `forbidden-skills` list 通过 `@ConfigurationProperties` 或类似机制注入到 runner，增加了 runner 的复杂度。另一方案是在 dispatcher 的 `runEntry()` 中、调 runner 之前校验——dispatcher 已知 `handler.getClass()` 和 `skillName`，在此校验并不破坏职责分离（dispatcher 控制"是否执行"，runner 控制"怎么执行"）。建议在 dispatcher 层校验，减少 runner 的依赖。

- **[§7 前端 lang 列表硬编码] P1-4 估时 0.5 天包含硬编码 lang 列表，但实际改动极小（只需移除 disabled + 加两个 Select 选项）。** 硬编码本身不是问题，但如果后续 P2 加 python 时，前端改动点是 `["bash", "node"]` 数组——一行代码，不存在"迁移成本"。Plan A 在此过度解释，实际不构成风险。

### LOW / 观察

- **[§2 ChainDecision 扩展] `HookRunResult` 增加 `ChainDecision` 字段需要注意现有代码兼容。** `LifecycleHookDispatcherImpl` 多处用 `result.success()` 判断，增加 `ChainDecision` 后需同步修改所有调用点（`runSync` 方法）。改动量不大，但容易遗漏 async 路径（async 永远不读 ChainDecision）。

- **[§8 E2E 测试] P1-7 估 1 天，包含 5 个路径。** 单靠 agent-browser 验证 script handler 场景需要 server 真实运行，而且 bash 子进程在 CI 环境的行为和本地开发机可能不同（PATH 不同，命令可用性不同）。建议 E2E 测试只覆盖前 3 个路径（多 entry + ABORT/SKIP_CHAIN），script 相关放单元测试验。

---

## 给 Plan B 的挑战（按严重度）

### HIGH

- **[§2.2 双线程 drain + §9 估时] Plan B 把 `stdout 超 64KB 不死锁` 列为测试用例，但低估了测试难度。** `yes` 命令产生无限输出，在 JUnit 5 里运行一个真实子进程 + drain 线程 + timeout，需要确保 drain 线程不被 JVM GC 或 executor 提前回收，以及 `Process.getInputStream()` 关闭时机。这个测试本身就需要 0.5 天调试时间，但 Plan B 把整个 `ScriptHandlerRunner` 估成 1.5 天——双线程实现 + 5 个测试 + 死锁用例，1.5 天偏紧。

- **[§4.1 Prompt Enrichment 注入方式与设计基线矛盾] Plan B §4.1 说"注入到现有最后 user message 的 content 前"（追加到现有消息），但 design-n3-lifecycle-hooks.md §3.5 说"追加到 LoopContext.messages 作为 system 消息"。** 这是两份文件的直接冲突。Plan B 实现时采用哪个？追加到现有 user message content 要求知道 content 的数据结构（可能是 `String` 或 `List<ContentBlock>`），如果是流式场景的 ContentBlock list，拼接逻辑更复杂。需要先确认 `LoopContext.messages` 中 user message content 的实际类型，再决定注入方式。

### MEDIUM

- **[§2.4 环境变量白名单 PATH 固定] Plan B 建议 PATH 固定为 `/usr/local/bin:/usr/bin:/bin`。** 在 macOS 开发环境，`bash` 本身就在 `/bin/bash`，没问题；但 `node` 在 macOS + nvm 环境下通常在 `~/.nvm/versions/node/xxx/bin/`，固定 PATH 会导致 `node -e ...` 直接失败。**Plan B 白名单方案的 PATH 策略在非 Linux 标准环境下会让 node 命令不可用**，测试环境就炸。建议：PATH 从 JVM 继承（`System.getenv("PATH")`），但只继承 PATH 一个变量，其余敏感变量全部剔除——折中于"完全继承"和"固定白名单"之间。

- **[§2.1 FD 继承问题] Plan B 承认"JVM 层无完整解法"，但在 MVP 文档中把它描述得很重，花了整个 §2.1 篇幅。** 对于本项目的实际场景（单租户 / 开发者自用），这个风险等级被高估了。bash script 通过 `/proc/self/fd/` 读取 JVM socket FD 需要攻击者有意构造恶意脚本——这已经超出了"用户配置 hook script"的威胁模型（用户自己写的脚本，攻击自己的数据库连接？）。**建议降级为 LOW 风险，一句注释说明，不需要在方案里占半页。**

- **[§6 前端 lang 下拉从后端 GET /api/lifecycle-hooks/script-langs 获取] 需要新增一个 API 端点。** Plan B 在 P1-4 的 0.5 天里要同时实现前端 + 后端这个新端点。现有 `LifecycleHookController` 已有 `/events` 和 `/presets`，再加 `/script-langs` 只是 3 行代码，可行，但得确认 0.5 天包含了这个后端改动。

### LOW / 观察

- **[§3.2 async entry SKIP_CHAIN 静默降级] Plan B 和 Plan A 在此一致**，都选择静默降级 + warn log。不需要挑战，记录为两份方案对齐点。

- **[§7 E2E 估时] Plan B 把 E2E 从 Plan A 的 1 天缩到 0.5 天**，但场景覆盖实际上增加了（多了 env 白名单断言）。0.5 天对 5 个路径偏乐观。

---

## 6 个必挑战问题的立场

**1. 环境变量策略：折中（继承 PATH + 剔除敏感变量）**

Plan B 的纯白名单在 macOS/nvm 开发环境会让 node 找不到，Plan A 的默认继承 + 加 SF_* 会泄露 ANTHROPIC_API_KEY 等变量。**建议折中**：从 JVM env 继承 PATH、LANG、HOME、TMPDIR、TZ 五个变量，再加 SF_* 元数据，其余一律 clear()。这比 Plan B 的固定 PATH 更通用，比 Plan A 的全继承更安全。

**2. stdout/stderr 读取：Plan B 双线程优于 Plan A，但理由需要修正**

真正需要双线程的原因是：`fut.get(timeout)` 阻塞期间如果没有独立线程持续消费 stdout，子进程写满 pipe buffer（Linux 默认 64KB）就会阻塞写入，而 scriptBody ≤ 4KB 的脚本本身不会产生 64KB 输出——但脚本可以 `cat /dev/urandom | head -c 1MB` 或循环 echo，实际风险真实存在。双线程方案正确；`redirectErrorStream(true)` 不解决死锁，只减少 FD 数量。**选 Plan B 双线程**，但可接受 `redirectErrorStream(true)` 同时使用以简化代码（单流 + 单线程 drain）。

**3. timeout kill：Plan B 递归杀进程树更可靠，MVP 应选**

`bash sleep 100 &` 产生的后台进程用 `process.destroyForcibly()` 杀不到，`ProcessHandle.descendants().destroyForcibly()` 可以。Java 17 项目已有此 API，无额外依赖，实现成本低（3-4 行代码）。**选 Plan B**。Plan A 的"destroy → 2s → destroyForcibly"两步并没有解决孤儿子进程问题。

**4. Prompt 注入方式：追加到现有最后 user message content（Plan B）更安全，但实现更复杂**

Plan A 的独立前置 user 消息在多轮对话非首轮可能引入连续 user 消息问题（取决于 provider 严格度）。Plan B 追加到现有消息避免了 message 数量增加，但需要了解 content 字段的实际类型——如果是纯 String 则简单，如果是 ContentBlock[] 则复杂。**建议先确认 LoopContext message content 类型，再定实现方式**；如果是 String，选 Plan B；如果是 ContentBlock，选 Plan A 更安全（增加一个简单的 user 消息）。

**5. 前端 lang 列表：P1 硬编码，Plan A 更合理**

`GET /api/lifecycle-hooks/script-langs` 是一个返回 `["bash", "node"]` 的 API，ROI 极低——lang 列表变化的唯一场景是加 python（P2），到时候加一行前端数组就搞定，不需要提前增加一个 API 端点。**选 Plan A 硬编码**。

**6. 估时：A 估 6 天更诚实，但 P1-3 应按 2.5 天估**

B 估 5 天是因为降低了 E2E（0.5 天）、提高了 ScriptHandlerRunner（1.5 天）。实际上：
- ScriptHandlerRunner 双线程 + 进程树清理 + env 白名单 + 5 个测试用例：2 天是底线
- E2E 5 个路径 + script handler 场景：0.75 天更实际
- **总估时：6-6.5 天**，Plan A 的 6 天是合理下限。

---

## 两份 plan 都遗漏的点

1. **`LifecycleHookDispatcherImpl.dispatch()` 的 P1 改造涉及 `runEntry()` 返回值语义变更。** 现有 `runEntry()` 返回 `boolean`，P1 需要返回三值 `ChainDecision`。两份 plan 都只描述了 dispatcher for 循环逻辑，没有提到 `runSync()` 和 `runAsync()` 方法也需要同步修改——特别是 `runAsync()` 永远不应返回 `ABORT` 或 `SKIP_CHAIN`，需要明确说明。

2. **`hookDepth` ThreadLocal 在 `hookExecutor` 线程上的传播问题 P0 已经解决（`runRunnerWithDepth`），P1 的 for 循环需要保证 hookDepth 在整个链执行期间保持正确。** 如果某个 entry 触发了嵌套 dispatch（在 hookExecutor 线程上），hookDepth 要正确计算。两份 plan 都没讨论 P1 链式执行对深度计数的影响。

3. **ScriptHandlerRunner 的工作目录和 `hookExecutor` 线程池的关系没人提及。** 如果 hookExecutor 有 8 个线程，同时跑 8 个 script 执行，每个都创建了 UUID workdir，finally 清理是否会有竞争？实际上不会（每次 UUID 不同），但需要确认 finally 块的 `Files.walkFileTree` 是否对并发安全。

---

## 最终建议（100 字以内）

**采用 Plan A 主体 + 植入 Plan B 的三个安全决策**：(1) 进程树递归 kill（`ProcessHandle.descendants()`）；(2) 环境变量折中策略（继承 PATH/LANG/HOME 等 5 个，clear 其余）；(3) stdout 双线程 drain（即使同时用 `redirectErrorStream(true)`）。lang 列表前端硬编码，prompt 注入方式待确认 content 类型后决定。估时按 6 天。
