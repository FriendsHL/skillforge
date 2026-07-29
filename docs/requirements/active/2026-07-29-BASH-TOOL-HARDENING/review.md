# Bash Tool 加固审查结论

日期：2026-07-29

## Reviewer 结论

### Java / 并发正确性

结论：PASS

- 输出读取与进程等待并发进行，不再因管道填满或 `readAllBytes()` 阻塞而绕过超时。
- 正常退出、非零退出、超时、中断四条路径均有明确结果。
- 输出收尾也是有界等待，后台子进程持有管道时不会无限卡住。
- 超时会终止根进程和已发现的后代进程。
- 50,000 字节保留上限在读取阶段生效，而不是完整读取后再截断。
- UTF-8 截断只保留完整码点。

### 安全审查

结论：PASS_WITH_RESIDUAL_RISK

- 子进程环境会移除名称包含 API Key、Token、Secret、Password、Credential、Private Key 的变量。
- 普通构建环境（例如 `PATH`）仍可用。
- 被移除的敏感值若出现在返回输出中会被替换为 `[REDACTED]`。
- `SafetySkillHook` 的危险命令和安装确认机制保持不变。

剩余风险：

- Bash 仍与服务端运行在同一系统用户下；环境变量过滤不构成强沙箱。
- 用户命令中显式写入的凭据不属于“继承环境泄漏”，仍需要上游秘密治理。
- 极端情况下，进程可能在进程树快照与终止之间派生新进程。强保证需要容器、独立用户或操作系统级进程组隔离。

### Prompt / 上下文审查

结论：PASS

- Tool Description 只保留调用参数和返回契约。
- “什么时候用 Bash”、专用工具边界、Git 安全和失败处理已进入 Global Tool Usage Guidelines。
- 新增静态提示规模较小，没有加入具体项目状态或动态数据。

### 需求覆盖审查

结论：PASS

已覆盖：成功退出、非零退出、真实超时、后代进程清理、后台管道、超大输出、UTF-8、非法 timeout、敏感环境过滤、PATH 保留、Description 分层。

## Judge

最终判定：PASS

依据：

- Bash 定向测试：12 passed。
- Core + Tools 回归：Core 374 passed；Tools 79 tests，0 failure，2 skipped。
- Server 全量 Reactor 回归：3498 tests，0 failure，0 error，179 skipped。
- `git diff --check`：通过。

允许进入安装、服务重启与真实 Agent 冒烟阶段。

