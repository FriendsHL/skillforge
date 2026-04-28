# N3 P1 Backend Review

> Reviewer: java-reviewer | 2026-04-17
> Worktree: `worktree-agent-a958205f`
> Scope: 10 modified + 6 new Java files in diff vs main

---

## CRITICAL（阻断合并）

无。所有 §1.1–§1.4 高优先级安全要求均已落地。

---

## HIGH

### H1 — `AgentController.java:52-55` & `46-49`：`updateAgent`/`createAgent` 未捕获 `IllegalArgumentException` → 返回 500，不是 400

`AgentService.validateLifecycleHooksSemantics` 对 `async+SKIP_CHAIN` 和 `scriptBody > 4096` 均抛 `IllegalArgumentException`（设计 §1.9/§1.10 要求返回 400）。但：

- `updateAgent`（L52-55）和 `createAgent`（L46-49）都没有 `try/catch`。
- 项目中无 `@RestControllerAdvice`，无全局 `IllegalArgumentException → 400` 映射。
- 只有 `importAgent`（L76）本地捕获了它。
- Spring Boot 3.x 默认行为：未捕获的 `IllegalArgumentException` → 500 Internal Server Error。

**影响**：手动 `curl -X PUT /api/agents/{id}` 带无效 hook 配置时用户收到 500，而非预期的 400。设计 §1.9/§1.10 的合约被破坏。

**修复**：在 `AgentController.updateAgent` 和 `createAgent` 内 `catch (IllegalArgumentException e)` 并返回 `ResponseEntity.badRequest().body(Map.of("error", e.getMessage()))`，或添加一个全局 `@RestControllerAdvice`。

---

### H2 — `LifecycleHookDispatcherTest`：设计 §3.1 test #13 缺失

设计要求测试："async + SKIP_CHAIN 组合实际执行时（旧数据）运行时降级为 CONTINUE"。读取到的前 100 行测试文件中未见该用例。Dispatcher `L183-186` 已正确实现降级逻辑，但缺对应测试。若后续重构此分支时无测试兜底，静默回归风险高。

---

## MEDIUM

### M1 — `ScriptHandlerRunnerTest:138-157`：进程树 kill 断言过弱

设计 §3.1 test 8 要求"用 `ps` 验证孙进程已死"。实现仅断言 `durationMs < 10_000L`，无法证明 `sleep 30 &` 产生的孙进程在 timeout 后真正被 `destroyForcibly`。`ProcessHandle.descendants().destroyForcibly()` 的正确性无法通过此断言被验证。

**建议**：从脚本写出的 `/tmp/sf-hook-pid-test.$$` 文件读出子 PID，在 runner 返回后用 `ProcessHandle.of(pid).isPresent()` 断言已不存在。

### M2 — `DrainedOutput.drain():244-249`：UTF-8 多字节字符可能在 cap 边界被截断

`toWrite` 计算单位是 char；`bytes = new String(chunk, 0, toWrite).getBytes(UTF_8)` 转换后可能比 `remaining` 多出 1-3 字节；`Math.min(bytes.length, remaining)` 裁掉了这些字节，导致 `buf` 末尾写入不完整的 UTF-8 序列。`buf.toString(UTF_8)` 会产生 `?` 替换字符。对纯 ASCII 脚本输出无影响，但多字节语言（日文/中文脚本输出）会出现乱码。

**建议**：以 `byte[]` 为读取单位（`InputStream.read(byte[] buf)`）替代 `BufferedReader`，绕开 char/byte 二次转换。

### M3 — `LifecycleHooksConfigSerdeTest:28-29`：测试用 `ObjectMapper` 未注册 `JavaTimeModule`

测试用的 `new ObjectMapper()` 无 JavaTimeModule。当前 hook config 无 `Instant`/`ZonedDateTime` 字段，暂不影响，但违反项目规则（`java.md`：ObjectMapper 必须 `findAndRegisterModules`）。若未来 HookEntry 加时间字段，测试会静默序列化错误。

---

## LOW

### L1 — `ScriptHandlerRunner:166`：reader 线程用 `new Thread()` 而非 hookExecutor

设计 §1.2 明确"submit to `hookExecutor`（同 P0），不新建 pool"。实现直接 `new Thread(..., "sf-hook-reader")`，未通过 hookExecutor。功能正确（daemon 线程、作用域有限），但与设计偏差；每次调用在 hookExecutor 之外额外创建一个平台线程，高并发场景可能超出预期线程数。

### L2 — `AgentService.validateLifecycleHooksSemantics:105`：注释说"Spring 转 400"，实际不成立

方法 Javadoc 写"Spring maps to 400 at the controller layer"，但如 H1 所指，此路径未接通。注释会误导后续开发者。修复 H1 后同步更新注释。

---

## 亮点

| 项 | 位置 | 评价 |
|---|---|---|
| `/tmp` symlink 防护 | `ScriptWorkdirManager:40-51` | `tmpRoot.toRealPath()` 动态拿前缀，macOS/Linux 均正确，不硬编码 `/tmp/` |
| drain-and-discard 架构 | `DrainedOutput:229-265` | 独立线程 + ByteArrayOutputStream cap + overflow discard，不 OOM，不死锁 |
| 进程树 kill | `ScriptHandlerRunner:175-180` | `descendants().forEach(destroyForcibly)` + 顶层 destroyForcibly，精准匹配设计 §1.3 |
| Env 清理 + 白名单 | `ScriptHandlerRunner:147-154` | `env.clear()` + 5 个系统变量白名单，`ANTHROPIC_API_KEY` 等敏感变量无法泄漏 |
| ChainDecision 分离 | `HookRunResult:17` + `LifecycleHookDispatcherImpl:332-338` | `chainDecision` 由 dispatcher 根据 `(success, failurePolicy)` 计算，runner 不决策链行为 |
| SKIP_CHAIN for 循环 | `LifecycleHookDispatcherImpl:129-151` | ABORT return false / SKIP_CHAIN break / CONTINUE fallthrough 三分支完全符合 §1.7 |
| FailurePolicy 降级 | `FailurePolicy:22-29` | `@JsonCreator` 未知枚举值 → CONTINUE，回滚场景安全兜底 |
| async+SKIP_CHAIN 运行时降级 | `LifecycleHookDispatcherImpl:183-187` | 旧数据老 hook 运行时安全降级，不静默失败 |
| TraceSpan scriptBody 不存储 | `LifecycleHookDispatcherImpl:387-398` | `describe()` 只取前 40 chars 预览，未存全量 scriptBody |

---

## 结论

**WARNING — HIGH + MEDIUM，可改后合并。**

- **H1** 必须修复：`updateAgent`/`createAgent` 须捕获 `IllegalArgumentException` 返回 400，否则 §1.9/§1.10 合约无效。
- **H2** 必须补测：dispatcher async+SKIP_CHAIN 运行时降级路径需要测试。
- M1/M2/M3/L1/L2 建议在本轮一并处理，代价低。
- 整体实现质量高：workdir 沙箱、drain-and-discard、进程树 kill、env 清理均忠实落地了设计文档的 15 个关键决策。
