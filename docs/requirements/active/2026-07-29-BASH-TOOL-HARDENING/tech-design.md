# Technical Design — BashTool Hardening

## 1. 现状与根因

当前 BashTool 在调用 `waitFor(timeout)` 前同步执行 `readAllBytes()`，长运行或持续输出进程可能永远
到不了超时判断。进程结束后未检查 exit code，所有正常退出都被包装为 success。输出在完整读入内存后
才截断，timeout 只存在描述层且未校验。`ProcessBuilder` 默认继承 Server 全部环境变量。

`SafetySkillHook` 与 `DangerousCommandChecker` 是防误操作的正则 guard，源码已声明不能抵抗编码、
拆分或间接执行，不是安全沙箱。

## 2. 方案比较

### 方案 A：最小修补现有同步实现

- 先 `waitFor`，再读输出。
- 优点：改动少。
- 缺点：子进程输出填满 pipe 后会阻塞，`waitFor` 也可能永远无法完成；不可采用。

### 方案 B：单独输出消费者 + 有界缓冲 + 进程树清理（推荐）

- 启动进程后立即由一个 daemon executor 排空合并后的 stdout/stderr。
- 主线程独立执行 timed `waitFor`。
- collector 只保留前 50,000 字节，后续继续排空并记录 truncated。
- timeout 时先终止 descendants，再终止 root；短暂等待后强杀仍存活进程。
- 优点：不改变 Tool 接口，修复死锁、超时和内存上界，容易单测。
- 缺点：仍是本机同用户进程，不构成强隔离。

### 方案 C：独立 Worker/容器/系统 Sandbox

- Bash 请求进入受限子服务或容器。
- 优点：真正隔离文件、进程和密钥。
- 缺点：跨模块、部署和 macOS 兼容范围显著扩大。
- 结论：作为后续 Runtime Sandbox 项目，不纳入本期。

## 3. 推荐实现

### 3.1 执行状态机

```text
VALIDATE
  -> START_PROCESS
  -> START_OUTPUT_DRAIN
  -> WAIT_WITH_TIMEOUT
     -> EXIT_0       -> SUCCESS
     -> EXIT_NONZERO -> ERROR(exitCode, boundedOutput)
     -> TIMEOUT      -> KILL_TREE -> ERROR(timeout, boundedOutput)
     -> INTERRUPTED  -> KILL_TREE -> restore interrupt -> ERROR
  -> CLOSE_COLLECTOR
```

### 3.2 输出收集

- 保持 `redirectErrorStream(true)`，不改变调用方看到的输出顺序语义。
- 新增包内私有 `BoundedOutputCollector` 或 BashTool 私有静态类，不建立跨项目通用框架。
- 按字节读取；最多保存 50,000 字节，继续排空剩余数据。
- 最终使用带 replacement policy 的 UTF-8 decoder，丢弃不完整尾字节并追加截断标记。
- collector Future 必须在进程终止后有界等待并关闭 executor。

### 3.3 timeout

- Tool 输入允许 `Number`，转换前检查整数范围。
- 常量：
  - default `120_000 ms`
  - min `100 ms`
  - max `600_000 ms`
- validation failure 不创建进程。

### 3.4 进程树

- 使用 `ProcessHandle.descendants()` 获取快照。
- 先对 descendants 调用 `destroy()`，再对 root 调用 `destroy()`。
- grace period 后对仍 alive 的句柄调用 `destroyForcibly()`。
- 进程树清理是 best effort；测试锁定直接派生子进程场景。

### 3.5 环境变量

推荐本期采用 deny-by-name + output redaction，而不是清空后 allowlist：

- 删除 key 命中以下大小写不敏感片段的变量：
  `API_KEY`、`APIKEY`、`TOKEN`、`SECRET`、`PASSWORD`、`PASSWD`、
  `CREDENTIAL`、`PRIVATE_KEY`。
- 保存长度不少于 6 的被移除值，仅用于结果字符串精确替换为 `[REDACTED]`。
- 不记录变量名对应的值。
- 保留 PATH、HOME、JAVA_HOME、M2_HOME、LANG、TMPDIR 等开发工具所需环境。

纯 allowlist 会破坏用户项目自定义构建变量；denylist 不是强安全边界，但在不引入 Sandbox 的前提下
更符合本地开发工具兼容性。

### 3.6 Prompt 分层

Global Platform：

- 何时使用 Bash。
- 与 Read/Grep/Glob/Edit/Write 的边界。
- 独立命令与依赖命令。
- 路径引用、目标范围、合理 timeout、根据退出状态判断结果。

Bash Tool Description：

- 执行 shell 命令。
- cwd 来自 SkillContext。
- stdout/stderr 合并。
- timeout 默认值和合法范围。

Git force push、安装确认和破坏性操作继续由统一安全规则与 Engine confirmation 管理，不在 Bash
Description 重复。

## 4. 测试设计

### 单元测试

- success/nonzero/validation。
- real timeout。
- bounded large output。
- multibyte truncation。
- sensitive env removed and output redacted。
- PATH preserved。
- child process cleanup（仅 Unix，使用 JUnit assumption）。
- Tool Description 不重复 Read/Grep/Edit/Git 长规则。

### Prompt 测试

- 中文 Bash 标题和关键决策语句存在。
- Global 长度预算仍小于既有上限。

### QA / Smoke

从运行中的 SkillForge 执行成功、失败、超时、输出截断、cwd、PATH 和敏感环境探针；核对 Tool Result
及 Agent 最终判断。

## 5. 其他工具审计方法

对 Main Agent Tool 逐项检查：

1. `SkillResult.success/error` 是否忠实反映底层状态。
2. IO 是否有 timeout、大小和资源释放上界。
3. 是否继承 Server secret 或把外部响应原样写入消息。
4. 路径、URL、用户输入是否在边界校验。
5. Tool Description 是否与实际执行契约一致。
6. 是否需要静态 Guidelines、动态 Provider 指南，或仅 Schema 即可。

只记录至少 80% 置信度的问题，不在 Bash diff 中修改其他工具。

## 6. Plan Reviewer 自审

### Spec Compliance

- 退出码、超时、输出、环境、Prompt、QA 和其他 Tool 审计均有明确实现及验证入口。
- 未增加后台命令、PTY、持久 Shell或新公共 API。

### 主要反例

1. **只删环境变量仍可通过同用户系统能力旁路读取。** 已明确为纵深防御，并保留 Sandbox 后续项。
2. **清空环境会破坏 Maven/Node。** 采用 deny-by-name 并加 PATH/构建工具冒烟。
3. **停止读取会让子进程阻塞。** 超限后继续 drain，只停止保存。
4. **强杀根进程可能遗留子进程。** 先 descendants 后 root，并以真实子进程测试覆盖。
5. **新线程泄漏。** 使用每次调用独立 daemon executor，在 finally 中 shutdownNow，并对 Future 有界等待。

### Verdict

`PASS_WITH_RESIDUAL_RISK`：方案可进入开发；残余风险是 Bash 仍运行在 Server 同一系统用户下，
不构成文件与进程级强隔离。
