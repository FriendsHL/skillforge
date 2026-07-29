# Main Agent 其他工具同类风险审查

日期：2026-07-29

本轮只读审查，不修改 Bash 之外的工具。

## 独立 P0：启动日志泄露访问令牌

`AuthService.initToken()` 在每次启动时以 INFO 级别记录完整 SkillForge Access Token。该日志可能进入终端、文件采集器或远程日志平台。

建议单独立即修复：

- 日志只记录 token 已初始化及其不可逆指纹或末四位。
- 检查历史日志的保留与访问范围，必要时轮换当前 token。
- 增加测试，禁止完整 token 出现在日志消息中。

## P0：建议下一批优先处理

### Read / Write / Edit / Grep / Glob：统一工作区边界

现状：

- 工具实现直接接受任意绝对路径。
- `SafetySkillHook` 只拦截部分系统目录和用户敏感路径，不等于“仅项目工作区”。
- Read、Grep、Glob 可遍历工作区外文件；Write、Edit 也没有基于 `SkillContext.workingDirectory` 的统一 containment 校验。
- 符号链接和路径规范化策略没有在五个工具间统一。

建议：

- 引入共用的 `WorkspacePathPolicy`，按工具区分 read/write 权限。
- 使用规范化路径和 `toRealPath()` 处理已存在目标及符号链接。
- 明确 artifact workspace、项目 workspace、临时目录的允许关系。
- 先锁定兼容需求；当前部分 Agent 可能被有意授权读取项目目录外文件，不能直接收紧。

### WebFetch：SSRF 与响应读取上限

现状：

- `ResponseBody.string()` 会在 `maxLength` 截断前把完整响应载入内存。
- robots.txt 是站点礼仪机制，不是 SSRF 防护。
- 未看到对 loopback、私网、link-local、云元数据地址及重定向后目标的统一拒绝策略。

建议：

- DNS 解析后阻止私网、loopback、link-local、multicast 和元数据地址。
- 每次重定向重新校验目标。
- 在字节流读取阶段设置硬上限，并分别约束压缩前后大小。
- 将 timeout、maxLength 的非法输入归类为 VALIDATION，而不是静默 clamp 或 EXECUTION。

## P1：可靠性和资源治理

### Read / Edit / Grep：完整文件载入内存

- Read 即使只请求少量行，也先 `Files.readAllLines()` 整个文件，并把完整内容写入恢复缓存。
- Edit 使用 `Files.readString()` 完整读取。
- Grep 对每个最多 5 MiB 的文件使用 `readAllLines()`，缺少总扫描字节、目录深度和总耗时预算。

建议：流式分页、单文件/总任务字节预算、扫描 deadline、恢复缓存独立上限。

### Glob / Grep：遍历耗时不可控

- 结果数量有限，但目录遍历本身没有 timeout、最大访问文件数或最大深度。
- 大目录、网络卷或特殊文件系统可能占用工具执行线程很久。

建议：统一 `ScanBudget`（deadline、visited files、bytes、depth），超限返回明确的部分结果状态。

### CodeSandbox：与 Bash 相近的进程边界

优点：

- 已并发排空 stdout/stderr、限制输出、过滤环境，并在超时后处理后代进程。

仍需验证：

- 正常父进程退出但后台子进程持有输出管道时，reader join 超时后的返回是否完整、后台进程是否遗留。
- 进程树快照竞态和真正的系统沙箱边界。
- `sandbox` 名称是否会让用户误以为已有容器级隔离。

## P2：状态与协议一致性

### 参数错误分类不一致

- Read、Grep、Glob 等部分必填参数错误仍返回默认 EXECUTION。
- Write、Edit、TodoWrite 等已经使用 VALIDATION。

影响：AgentLoop 的重试、浪费检测和 compact 决策可能错误地把可修正参数问题当作执行故障。

建议：建立所有 Tool 的输入验证规范和契约测试。

### 外部副作用工具

范围：SendChannelFile、TeamSend、TeamKill、调度创建/更新/删除、Artifact 发布、Agent 创建/更新。

建议统一审查：

- 幂等键与重复调用语义。
- “已请求”与“已完成”的状态区别。
- 超时后的最终状态查询。
- 对外发送、删除、覆盖操作的确认和审计字段。

## 推荐实施顺序

1. WebFetch SSRF + 有界响应读取（安全红灯，Full Pipeline）。
2. WorkspacePathPolicy 设计与兼容性盘点（先设计，Full Pipeline）。
3. Read/Grep/Glob 资源预算（Mid；若共用协议跨模块则 Full）。
4. 全工具 VALIDATION/EXECUTION 契约统一（分批 Mid）。
5. 外部副作用工具的幂等和状态机审查（逐工具 Full/Mid）。
