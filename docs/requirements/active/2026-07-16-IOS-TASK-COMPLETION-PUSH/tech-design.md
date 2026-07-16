# Tech Design — Task 结束后的 App 消息推送

> 状态：implemented；2026-07-16 完成本地构建与定向测试，真实 APNs 到机验收待凭据和真机。

## 实现前代码取证

- `SettingsView.enableNotifications` 与 `PushRegistration` 只请求本地通知授权。
- App 没有远程通知注册、device-token delegate、token 上传和通知点击处理。
- `skillforge-ios/project.yml` 没有 Push Notifications capability/entitlement。
- 服务端 `t_mobile_device.token_hash` 只认证 SkillForge device bearer token；没有 APNs token 模型。
- `SessionLoopFinishedEvent` 可作为终态信号来源，但缺少稳定的本次用户 task/turn identity。
- `SessionEntity.activeRootTraceId` 每次真实 user message 会重置，接近 task identity，但当前 finished
  event 未携带它；实现阶段应显式固化 notification task id，不能在 listener 里事后猜。

## 可选方案

### A. 原生 APNs token-based provider API（推荐）

服务端持有 Apple `.p8` key 配置，按 device token 直接发送。依赖少、链路可控，适合自托管 SkillForge。

### B. 第三方 Push 服务

接 Firebase/OneSignal 等。控制台和运营能力更强，但增加第三方数据处理、依赖和部署复杂度；当前规模不必要。

### C. 仅本地通知

只能覆盖 App 自己仍在运行的场景，无法可靠覆盖进程被系统终止，因此不满足需求。

## 推荐架构

1. 新建移动端 push token 持久化模型，保存 deviceId、token hash、保护后的 token、environment、status、时间戳。
2. 新增 device-token 鉴权的注册/注销 endpoint。
3. iOS 增加 Push Notifications entitlement；用户主动授权后调用
   `registerForRemoteNotifications`，收到 token 后上传，token 变化时更新。
4. 终态 listener 只接收 eligible 根任务：`origin=production`、`parentSessionId=null`、非内部
   system agent；SubAgent/Team 完成由根会话汇总后再通知。
5. listener 不直接访问 APNs，而是在任务状态落库的同一可靠边界写入 notification outbox。
6. 后台 worker claim outbox，按 active devices fan-out delivery rows，调用 APNs；短暂错误重试，
   永久 token 错误停用 token。
7. 通过稳定 task/turn id + notification kind + deviceId 做唯一约束；构造最小 APNs payload，发送
   失败不回滚任务消息。
8. 通知 payload 携带 session route；App 接收点击后先验证本地配对，再由现有 API 获取会话。

## 为什么需要 Outbox

单纯 `@Async @EventListener` 有两个无法补救的窗口：任务已经提交但进程在 listener 执行前退出会丢
通知；APNs 超时后无法可靠判断是否该重试。Outbox 将“需要通知”先持久化，再异步投递，可在服务重启
后继续，并用数据库唯一约束保证 listener 重放不会重复建事件。

建议最少两张表：

- `t_mobile_notification`：task identity、session、user、kind、safe title/body、状态、时间戳，唯一
  `(task_id, kind)`。
- `t_mobile_notification_delivery`：notification × device、APNs token、attempt/status/error/provider id，
  唯一 `(notification_id, device_id)`。

如果 V1 只做单实例，也仍保留 outbox；worker 可用 `FOR UPDATE SKIP LOCKED` claim，避免未来多实例重复。

## Task 资格与身份

- 资格：production 根 session、用户可见 agent、终态属于启用通知类型。
- `waiting_user` 与 confirmation 都可能来自同一暂停，应归一成一个 `action_required` kind，避免双推。
- task identity 必须代表“一次真实用户消息触发的 run”，不能只用 sessionId。
- 推荐在 ChatService 接收真实 user message 时生成/固定 `taskId`，随 finished event 传播；可评估复用
  `activeRootTraceId`，但需证明重试/恢复时稳定且不会在同一 task 内改变。
- scheduled task 若通知 App，应使用 scheduled run id 作为 task identity，并避免同时由 session listener
  与 scheduler listener 双写。

## 安全要求

- `.p8` key、key id、team id 只来自外部配置/secret，不进仓库。
- raw device token 不写日志；数据库需保护存储并保留 hash 用于查重。
- payload 不含 bearer token、完整 transcript、工具参数和敏感文件路径。
- APNs 返回 token 无效时标记 inactive，避免持续失败重试。

## 可靠性与幂等

- 终态采集必须快速；APNs 网络调用只在 outbox worker 中执行，不能阻塞 ChatService teardown。
- task 状态与 notification outbox 先落库，再发送通知。
- event key 使用稳定 task identity；不能只按 sessionId，避免后续新一轮任务永远不推。
- 短暂错误有限重试，永久 token 错误不重试。
- APNs 成功响应后标 delivered；已 delivered delivery 永不重发。
- `waiting_user` 恢复后最终 `completed` 可以再次推，因为 notification kind 不同；同一种暂停不重复推。

## 验证计划

- Server 单测：状态映射、payload 隐私、幂等、失效 token、发送失败隔离。
- API/数据库：token owner、撤销、加密/保护字段、outbox 唯一约束、claim 并发、raw token 日志扫描。
- iOS XCTest：token 编码、注册状态、notification route reducer。
- XCUITest：通知点击后导航可用 deterministic fixture 验证。
- 真机：development APNs 的 foreground/background/terminated、点击跳转、token rotation、撤销设备和
  多设备验证；模拟器结果不能代替该门。

## 2026-07-16 实现与验证记录

- 数据库：新增 `t_mobile_push_token`、`t_mobile_notification`、`t_mobile_notification_delivery`；
  task/kind 与 notification/device 均有唯一约束。
- 服务端：device 鉴权 token 注册/注销、AES-256-GCM 保护存储、根任务终态采集、outbox worker、
  APNs HTTP/2 token provider、有限重试和永久失效 token 停用已实现。未配置加密或 APNs 凭据时
  worker fail closed，且不会消耗投递重试。
- iOS：Push entitlement、授权与 APNs 注册、token 上传、上传失败反馈、通知点击 session 路由已实现。
- 签名兼容：Debug 默认不附带 Push entitlement，可由免费 Personal Team 安装到真机但不能接收 APNs；
  Release 保留 production `aps-environment`，供加入 Apple Developer Program 的团队构建正式推送版本。
- 已通过：服务端完整测试 3324 个（0 失败，175 跳过，其中本需求定向测试 6/6）；iOS
  `MobileApiClientTests` 16/16，iPhone 17 Pro 模拟器；iOS Release 模拟器构建成功。
- 未运行：真实 development/production APNs、后台/terminated 到机、多真机、token rotation；原因是
  当前环境未提供 Apple Team ID、Key ID、`.p8` 私钥和已签名真机。
