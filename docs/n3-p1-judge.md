# N3 P1 Judge — 最终修复清单

> Judge: claude-sonnet-4-6 | Date: 2026-04-17
> Sources: java-reviewer (backend) + typescript-reviewer (frontend) + security-reviewer
> Design authority: `docs/design-n3-p1.md`

---

## A. 冲突裁决

| 重叠 | 裁决 |
|------|------|
| java-reviewer M1 + security-reviewer M1：进程树 kill 测试无硬断言 | **合并为 BE-MEDIUM-1**。两者描述同一文件同一测试（`ScriptHandlerRunnerTest:136-157`），修复方法一致（读 PID 文件 + `ProcessHandle.of(pid).isPresent()`）。Severity 保持 MEDIUM（测试强度问题，代码逻辑本身已确认正确）。 |
| security M2（TraceSpan 40 chars 泄露）vs 设计 §1.14（明确要求存前 40 chars）| **设计文档胜出，降为 DEFER**。§1.14 的意图是"不存全量 scriptBody"，40 chars 预览是已知 trade-off，安全报告已在 M2 注明建议而非强制。监控系统 redact 是运维层面决策。 |

---

## B. Severity 调整

| 条目 | 原 Severity | 调整后 | 理由 |
|------|------------|--------|------|
| FE-MEDIUM-3（`!important`）| MEDIUM | **MEDIUM**（不降） | 项目规则明确禁用，需修复 |
| FE-MEDIUM-4（`--color-danger` 未定义）| MEDIUM | **MEDIUM**（不降） | 变量从未声明，fallback 一直生效 = 实质 bug |
| Security M2（TraceSpan 40 chars）| MEDIUM | **DEFER** | 已裁决见上 |

---

## C. 最终修复清单

### Backend（worktree: `agent-a958205f`）

| Fix ID | Severity | File:line | 问题 | 具体修复指令 |
|--------|----------|-----------|------|------------|
| BE-HIGH-1 | HIGH | `skillforge-server/src/main/java/com/skillforge/server/controller/AgentController.java:46-55` | `createAgent`/`updateAgent` 未捕获 `IllegalArgumentException` → Spring 返回 500，不是设计要求的 400 | 在 `createAgent`（L46-49）和 `updateAgent`（L52-55）方法体内用 `try/catch (IllegalArgumentException e)` 包裹 `agentService` 调用，返回 `ResponseEntity.badRequest().body(Map.of("error", e.getMessage()))`。同时删除 `AgentService.validateLifecycleHooksSemantics` Javadoc 中"Spring maps to 400"的错误注释（BE-LOW-2 合并处理）。**不要新建 `@RestControllerAdvice`**（其余 endpoint 无此需求，过度设计）。 |
| BE-HIGH-2 | HIGH | `skillforge-server/src/test/java/com/skillforge/server/hook/LifecycleHookDispatcherTest.java`（无对应行） | 设计 §3.1 test #13 缺失：async+SKIP_CHAIN 组合在旧数据运行时应降级为 CONTINUE，但没有测试覆盖该分支（`LifecycleHookDispatcherImpl:183-187`） | 新增测试：构造一个 `failurePolicy=SKIP_CHAIN, async=true` 的 entry（绕过保存时校验，直接构造对象），调用 dispatcher，断言 entry 正常执行（不抛异常，chainDecision 为 CONTINUE）。 |
| BE-HIGH-3 | HIGH | `skillforge-server/src/test/java/com/skillforge/server/hook/ScriptHandlerRunnerTest.java`（无对应行） | 缺 env 隔离回归测试：`env.clear()` 的正确性无测试保护，未来重构恢复 `inheritIO()` 时不会报红（OWASP A02） | 新增测试：脚本执行 `env`，对输出断言不含 `ANTHROPIC_API_KEY`、`AWS_`、`DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY`。即使 CI 未设置这些变量也必须覆盖代码路径（测试验证的是"runner 清理行为"，不是"环境里有没有这个 key"）。 |
| BE-HIGH-4 | HIGH | `skillforge-core/src/main/java/com/skillforge/core/engine/DangerousCommandChecker.java:53-57` | 危险命令检测未覆盖绝对路径管道目标（`curl ... \| /bin/bash`），攻击者可绕过检测执行任意远程代码（OWASP A03） | 将现有 `\| sh` / `\| bash` 模式替换为允许可选绝对路径前缀的正则：`Pattern.compile("curl\\s+.*\\|\\s*(/\\S+/)?bash")`，同理覆盖 `sh` / wget 变体（共 4 条新 pattern）。在方法注释中显式列出"绝对路径 + python3 -c 仍可绕过，best-effort"。 |
| BE-MEDIUM-1 | MEDIUM | `skillforge-server/src/test/java/com/skillforge/server/hook/ScriptHandlerRunnerTest.java:136-157` | 进程树 kill 测试仅断言执行时长 < 10s，未验证孙进程已死（java M1 + security M1 合并） | 脚本改为 `echo $BASHPID > /tmp/sf-hook-pid-test.$$ && sleep 30 &`（记录子 bash PID）；runner 返回后读取该文件，用 `ProcessHandle.of(pid).isEmpty()` 断言孙进程不存在。 |
| BE-MEDIUM-2 | MEDIUM | `skillforge-server/src/main/java/com/skillforge/server/hook/DrainedOutput.java:244-249` | UTF-8 多字节字符可能在 64KB cap 边界被截断，产生替换字符；对中文/日文脚本输出有影响 | 将读取单位从 `char`（BufferedReader）改为 `byte[]`（`InputStream.read(byte[] buf, 0, chunkSize)`），直接操作字节，避免 char→byte 二次转换截断问题。`remaining` 计算和 cap 检查均以字节为单位。 |
| BE-MEDIUM-3 | MEDIUM | `skillforge-server/src/test/java/com/skillforge/server/hook/LifecycleHooksConfigSerdeTest.java:28-29` | 测试用 `new ObjectMapper()` 未调用 `findAndRegisterModules()`，违反项目规则；当前无影响，但加时间字段后会静默错误 | 将 `new ObjectMapper()` 替换为 `new ObjectMapper().findAndRegisterModules()`。 |

---

### Frontend（worktree: `agent-a88559ca`）

| Fix ID | Severity | File:line | 问题 | 具体修复指令 |
|--------|----------|-----------|------|------------|
| FE-HIGH-1 | HIGH | `skillforge-dashboard/src/components/lifecycle-hooks/FormMode.tsx:292-294` | `{ ...entry.handler, ...patch } as HookHandler` cast 绕过 discriminated-union 安全检查；当前调用方不传 `type` 字段导致运行时 OK，但类型系统无法防止跨分支 spread | 按 handler 类型拆分 patch 函数：将 `handleUpdateHandler` 拆成 `patchSkillHandler(entry, patch: Partial<SkillHandler>)` 和 `patchScriptHandler(entry, patch: Partial<ScriptHandler>)`，各自在 narrowed 类型上操作，再重新附上 `type` 字段。彻底消除 `as HookHandler`。 |
| FE-MEDIUM-1 | MEDIUM | `skillforge-dashboard/src/components/lifecycle-hooks/FormMode.tsx:219` | `key={idx}` 用于可排序列表；键盘操作触发 `handleMoveEntry` 时无 blur，防抖 timer 可能以旧 args 触发提交 | 在 `buildDefaultEntry()` 里加 `id: crypto.randomUUID()`（或 `Date.now().toString(36)+Math.random().toString(36).slice(2)`）；`HookEntry` 类型增加 `id: string`；list `key={entry.id}`。 |
| FE-MEDIUM-2 | MEDIUM | `skillforge-dashboard/src/components/lifecycle-hooks/FormMode.tsx:285-312` | `handleHandlerTypeChange`、`handleUpdateHandler`、`handleUpdateField` 每次渲染重建，违反 `frontend.md` useCallback 规则 | 用 `useCallback` 包裹三个函数，deps 数组：`[entries, updateEntries]`（或更细粒度的 `entryIndex`）。配合后续对子组件加 `React.memo` 时可发挥最大收益。 |
| FE-MEDIUM-3 | MEDIUM | `skillforge-dashboard/src/styles/index.css:886-891` | `.sf-hooks-script-body` 使用 `!important` 覆盖 AntD TextArea，违反 `frontend.md`："不要用 !important" | 改用更高特异性的选择器（如 `.sf-lifecycle-hooks .ant-input`）或通过 AntD ConfigProvider `theme.components.Input` token 注入 monospace 字体。删除所有 `!important`。 |
| FE-MEDIUM-4 | MEDIUM | `skillforge-dashboard/src/styles/index.css:882` | `var(--color-danger, #d4646c)` 中 `--color-danger` 从未在 `:root` 声明，fallback hex 永远生效，违反 CSS variable 规范 | 方案 A（推荐）：将 `var(--color-danger, #d4646c)` 替换为已定义的 `var(--color-error)`（L67 已声明）。方案 B：在 `:root` 补 `--color-danger: oklch(55% 0.18 15)`。选 A 更简洁，无需新增 token。 |

---

## D. 修复后必须验证

### Backend
```bash
cd skillforge-server && mvn install -DskipTests   # 编译通过
mvn test                                           # 所有测试绿
```
- `ScriptHandlerRunnerTest` 8 条全部通过（含新增 env 隔离 + 进程树硬断言）
- `LifecycleHookDispatcherTest` 补充后 ≥13 条全通过
- `LifecycleHooksConfigSerdeTest` ObjectMapper 修复后反序列化 round-trip 仍通过

**Regression（P0 既有测试）：**
- `LifecycleHookDispatcherTest` 原有的 ABORT / SKIP_CHAIN / forbidden-skill 测试仍绿
- `AgentControllerTest`（若存在）`updateAgent` happy-path 仍返回 200

### Frontend
```bash
cd skillforge-dashboard && npm run build   # 0 errors, 0 warnings
npx tsc --noEmit                           # 类型检查通过
```
- `FormMode` 重排后 key 稳定（无 React key 警告）
- `as HookHandler` cast 已消除，tsc 不报 `any` 相关警告

### E2E（agent-browser）
- 多 entry 列表上移/下移后保存 + 重载，顺序一致
- Script handler 首次选择弹确认 Modal；再次选择不弹
- `curl -X PUT /api/agents/{id}` 带 `async+SKIP_CHAIN` 返回 **400**（验证 BE-HIGH-1 修复）

---

## E. 延后事项（defer to follow-up）

| 条目 | 理由 |
|------|------|
| BE-LOW-1：reader 线程用 `new Thread()` 而非 hookExecutor | 功能正确；daemon 线程作用域受限；高并发场景再优化 |
| Security M2：TraceSpan 前 40 chars 可能含敏感内容 | 设计文档已知 trade-off；运维层 redact 决策，不在 P1 scope |
| FE-LOW-1：内部组件缺 `React.memo` | 10 entry 上限下无性能问题；待 useCallback 落地后再配套 |
| FE-LOW-2：`(next ?? DEFAULT) as number` 冗余 cast | 纯噪音，不影响运行 |
| FE-LOW-3：Zod schema 允许 ABORT on non-abortable events（JSON mode） | 后端静默忽略，无数据损坏；用户体验问题，P2 加 per-event validation |

---

**总计：5 HIGH + 7 MEDIUM 需本轮修复。**
