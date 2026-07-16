# PRD — Task 结束后的 App 消息推送

> 状态：implemented；真实 APNs 到机验收待凭据和真机

## 目标

当用户拥有的长任务进入关键终态时，向已授权的 SkillForge iOS 设备发送可点击的系统通知。

## 默认通知范围

- `completed`：发送“任务已完成”。
- `error`：发送“任务执行失败”。
- `waiting_user`：发送“任务等待你的输入”。
- confirmation pending：发送“任务等待确认”。
- `cancelled` / `aborted_by_hook`：不推送，避免用户主动取消后的噪声。

## 功能要求

1. 用户在 Settings 中主动授权通知后，App 注册 APNs token 并上传服务端。
2. token 绑定 device 与 user，设备撤销或 token 失效后停止投递。
3. 通知点击后打开 App 并进入对应 session。
4. 同一 session 同一终态事件最多推送一次；服务重试不得造成重复通知。
5. APNs 不可用不影响任务完成、消息持久化或前台使用。
6. 默认通知正文不包含完整用户输入、工具参数、文件路径或长回复。
7. 前台收到事件时避免同时出现重复系统横幅；采用 App 内状态更新或受控 foreground presentation。
8. 默认只通知用户可见的 production 根任务；SubAgent/Team member、eval/user-sim 和内部 system
   agent 的完成事件不单独推送。它们的结果汇入根任务后，只由根任务产生一条通知。

## 建议隐私默认值

- 标题只显示 SkillForge 与状态。
- 正文只显示 Agent 名称和安全短摘要；锁屏默认不放完整 final message。
- payload 仅携带非秘密路由标识，不携带 bearer/device token。

## 验收标准

1. 真机授权后，服务端保存 token 的保护形式和不可逆 hash，日志不出现 raw token。
2. App 在后台和被系统终止两种状态下，都能收到 completed/error/waiting-user 通知。
3. 点击通知进入正确 session；未配对或已撤销设备不能读取会话。
4. 重复事件、服务重试和多 listener 不产生重复 push。
5. APNs 配置关闭或发送失败时，task 状态和最终消息仍正常落库。
6. 多设备策略默认为向用户所有 active device 投递；每个设备可独立关闭。
7. 一个根任务派发多个子 Agent 时，子任务完成不产生通知；根任务最终完成只产生一条通知。

## 已确认决策

- `cancelled` / `aborted_by_hook` 不推送。
- 锁屏只显示 Agent 名称和固定状态文案，不包含 final message。
- 向该用户所有 active device 推送。
- 同一用户从 Dashboard、渠道或 App 发起的 production 可见根任务均推送。
