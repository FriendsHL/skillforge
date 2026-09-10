# MRD — Compact 后的历史事实恢复与 Session 连续性

## 1. 背景

长 Session 经过 Full Compact 后，模型主要看到 summary 与未覆盖尾部。完整原始消息仍在数据库，但当前模型能力
不能稳定地按关键词、范围、Tool 名、`toolUseId` 或归档 occurrence 定位并精确读取。结果是：

- summary 遗漏编号、路径、用户修正或 Tool 输出后，Agent 知道“以前谈过”，却无法可靠找回；
- 用户指出“任务偏了”时，Agent 容易猜测、重放大量历史，或误把事实恢复等同于破坏性 checkpoint restore；
- 大型 `tool_result` 已归档全文，但旧归档仅靠 `toolUseId` 关联，无法保证 restore/replay 后不会命中同 ID 的另一份内容；
- 当前轮 Tool intent/result 主要在循环末尾整体对账，进程硬崩溃可能让已经发生的外部副作用没有可恢复的持久化边界；
- summary、History、Task、runtime snapshot、checkpoint 与 recovery payload 都被泛称为“恢复”，责任边界不清。

论文 *Context as an Environment* 与长程 Harness 的共同方向是“检索定位 → 精确展开 → 提炼当前事实”：完整事件保持
可访问，模型工作区只装入当前需要的证据。SkillForge 已有主要存储基础，本需求补齐稳定协议、崩溃边界与端到端验收。

## 2. 用户问题

1. 如何让 Compact 尽量少丢关键数据，而不把整段历史重新塞回模型？
2. 当任务衔接不上、Agent 丢失事实或用户感觉方向偏移时，怎样由 Agent 自行检索和恢复事实？
3. 当前 Session、History、checkpoint、runtime snapshot 和 Recovery 各自解决什么问题？
4. 当前轮事实如何在硬崩溃、并行 Tool、等待用户输入和恢复执行时仍形成完整、可审计的历史？
5. 如何证明新能力提高恢复率，同时不导致普通短会话机械调用 History 或显著增加 token？

## 3. 产品目标

### G1：非破坏性事实恢复

每个 Agent 都能在当前 Session 中按关键词、范围、消息类型、Tool 名和 `toolUseId` 定位旧证据，再用稳定 ref 精确读取；
History 读取不修改时间线，也不能选择另一个 Session。

### G2：Compact 可导航且可重复

模型看到确定性、非自闭合的 checkpoint envelope、逐字不变的 active summary 与未覆盖的已持久化尾部。Envelope 包含
数据库确定的 summary/range 与固定恢复 cue；连续 Compact 不重复总结旧 envelope，也不重复 cue。

### G3：持久事实边界先于外部执行

assistant TOOL_USE intent 必须先持久化并 ACK，执行 claim 必须取得当前 generation/fence 后才允许 Tool 产生副作用；全部
TOOL_RESULT 按原始 provider 顺序一次闭合。当前轮超过 20 条也不会因为尚未落库而被 Compact 永久丢失。

### G4：崩溃恢复不假装知道外部结果

服务端仅自动重放确认只读或真正支持稳定幂等键的 Tool。未知/有副作用 Tool 在崩溃接管时进入不确定屏障，阻止下一次
LLM、inbox drain 与 Compact，直到 owner/显式授权 admin 通过可幂等、只追加审计的命令确认“结果未知”。

### G5：恢复语义分层

- summary + tail：当前工作视图；
- History Search/Read：遗漏事实的按需恢复；
- TaskList：当前任务状态权威；
- FileRead/工作区：当前文件权威；
- `ContextRuntimeSnapshot`：Tool/Skill 控制面引用；
- checkpoint：用户显式 branch/restore 时间线；
- RecoveryManifest/Attachment：仅评测，不进入首版生产协议。

### G6：可量化收益

使用相同 fixture 比较 A（summary-only）、B（当前 summary+tail+legacy recent）、C（summary+tail+History、无 cue）、
D（envelope+原始 summary+tail+History），并把 R0/R1/R2 作为正交的 evaluator-only attachment 变量。S25 单独防止
“有工具就滥用工具”。

## 4. 为什么 summary + tail + History 仍不等于全部运行状态

对于“用户说过什么、Tool 返回过什么、之前做过什么”，summary + tail + History 是生产事实恢复基线。

但动态发现的 Tool schema 与 Skill 正文属于控制面能力：重启或 branch/restore 后必须按 ID/hash 从当前授权 Registry 重建，
不能把 summary 或旧正文当成当前定义。因此 checkpoint 继续保存 content-free `ContextRuntimeSnapshot`：resume 使用当前状态，
branch 解析 checkpoint snapshot 到子 Session，restore 用 checkpoint snapshot 覆盖当前 Session；老数据为 null/corrupt 时清空。

旧 `recovery_payload` 文件头也不是事实权威。当前文件通过 FileRead 重读，Task 通过 TaskList/数据库重建，历史文件内容通过
旧 Tool 证据读取。首版只在离线评测中比较 R0/R1/R2，不加入生产 Recovery marker、Attachment 或 provider one-shot 状态。

## 5. 产品约束

- History Search/Read 是总开关控制的系统工具对，不使用 per-Agent grant；关闭时两者都不可见且 direct dispatch 被拒绝。
- Harness 从可信上下文固定当前 `userId/sessionId`，模型输入没有身份字段；SessionList/Session 负责跨 Session 发现。
- Cursor 首版不做 HMAC，只保证服务端原样 token 的冻结快照分页；它不能授权，所有 continuation 都重新鉴权并校验 epoch/selector/projection。
- 任意外部系统副作用与 SkillForge 结果事务无法普遍实现严格原子提交。产品选择 fail-stop + generation fencing + 人工审计，而不是自动猜测或重试。
- Archive identity 必须绑定持久化 message/block occurrence、精确 `toolUseId` 和内部 exact-payload hash；hash 不向模型暴露。
- History 自身的 Search/Read tool blocks 永久不是证据；同一 occurrence 的 raw/archive 只出现一次，原始证据默认先于 summary。

## 6. 成功指标

- D 相对 A 的精确事实恢复率提升不少于 20 个百分点，同时报告 C-A 与 D-C。
- locator/ref 正确率不低于 98%，跨用户/跨 Session 泄漏为 0。
- 普通无需恢复轮次中的不必要 History 调用率低于 10%；S25 质量下降不超过 2 个百分点。
- History schema 开销 p95 不超过 `min(1500 tokens, 有效上下文 5%)`。
- 真实低信任 wrapper 后的 History wire 不超过 32K 字符，且低于现有 40K truncator。
- Archive 授权投影分页重组 hash 一致率 100%；内部原始 hash 对模型暴露数为 0。
- 100K archive/search fixture 的 Search p95 不高于 500ms。
- intent-before-effect、完整原序结果向量、hard-crash takeover、WAITING_USER 恢复、unknown resolution、queued-user exactly-once 与 restore audit 全部通过。

## 7. 非目标

- 自动判断并回滚用户 Session，或自动回放整段历史。
- 将 summary 变成严格 JSON 后才允许上线 History。
- 用 Memory、summary 或提前抽取的事实替代 Session 原始记录。
- 首期跨用户、跨 Session 或全局 History 搜索。
- 给 cursor 增加授权含义，或支持调用方修改 cursor 内部 cutoff/watermark。
- 生产 RecoveryManifest/Attachment、旧文件头恢复或动态 landmark 注入。
- 宣称可以自动恢复未知外部副作用，或在 `UNCERTAIN_PENDING_RESOLUTION` 上继续调用 provider。
