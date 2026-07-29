# PRD — Bash 执行可靠性与工具手册治理

## 1. 功能要求

### FR-1 退出状态

- 退出码为 0 才能返回成功。
- 非零退出必须返回失败，保留退出码和受控输出。
- Agent 不得把 Maven、Git、Node 或任意命令的非零退出误判为成功。

### FR-2 真超时与进程清理

- 从进程启动后立即计算超时，stdout/stderr 消费不得阻塞超时判断。
- 超时后终止根进程及其可见后代进程。
- 返回稳定的 timeout 错误，不遗留无限输出线程。

### FR-3 参数约束

- 默认超时 120 秒。
- 允许范围 100 毫秒至 600 秒。
- 非数字、越界和负数必须返回 validation error，不启动进程。

### FR-4 有界输出

- stdout/stderr 继续合并，保持现有 Tool Result 兼容。
- 捕获过程最多保留 50,000 UTF-8 字节；超过后继续排空管道但不继续占用结果内存。
- 截断结果带稳定标记；不得在任意字节处产生非法 UTF-8。

### FR-5 默认敏感环境隔离

- 子进程不继承名称明显为 API key、token、secret、password、credential、private key 的环境变量。
- 必要的 PATH、HOME、语言、Java/Maven/Node 运行环境保持可用。
- 被移除环境变量的值若出现在输出中，进行二次脱敏。
- 该能力是纵深防御，不宣称形成强安全沙箱。

### FR-6 Prompt 与 Tool Description

- Global Prompt 使用中文说明 Bash 的适用场景、专用文件工具边界、命令依赖、路径引用、超时和失败判断。
- Bash Tool Description 只保留能力、合并输出、工作目录和 timeout 参数契约。
- 不写入 SkillForge 当前端口、仓库路径或 Provider 专属信息。

### FR-7 其他工具审计

- 审计 Main Agent 当前 Tool 的同类问题：错误状态、超时、无界结果、敏感信息、外部进程/网络和描述契约漂移。
- 本期只给出证据、严重度和建议顺序，不顺手修改。

## 2. 验收标准

1. `printf ok` 成功。
2. `exit 7` 返回失败并包含退出码 7。
3. 持续运行命令在配置时间内超时。
4. 超时后派生进程不继续存活。
5. 超过上限的输出被稳定截断且 JVM 不先缓存完整输出。
6. 中文与 emoji 截断后仍是合法 UTF-8。
7. 非法 timeout 不启动命令。
8. 测试注入的敏感环境变量对子进程不可见，普通 PATH 仍可用。
9. Global Prompt 包含中文 Bash Guidelines，且不重复长 Tool Description。
10. `skillforge-tools`、`skillforge-core`、`skillforge-server` 相关回归通过。
11. 真实后端冒烟覆盖成功、非零、超时、输出截断和环境泄漏探针。

