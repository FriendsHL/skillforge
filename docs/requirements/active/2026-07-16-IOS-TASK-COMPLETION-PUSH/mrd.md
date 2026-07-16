# MRD — Task 结束后的 App 消息推送

## 用户请求

> 如果 task 结束了，会有消息推送吗？

结合上下文，用户需要的不是仅在 Chat 页面更新状态，而是 App 退到后台或关闭后仍能收到系统通知。

## 实现前事实

- `SessionLoopFinishedEvent` 已提供 sessionId、finalStatus、finalMessage、userId。
- iOS Settings 已能读取并请求通知权限，但 `PushRegistration` 目前也只调用
  `requestAuthorization`，没有 `registerForRemoteNotifications`、APNs token delegate 或上传动作。
- `project.yml` 没有 Push Notifications capability/entitlement，生成工程中也没有
  `aps-environment`；当前构建不具备远程推送签名能力。
- 尚无 APNs device token 表、注册 endpoint、服务端 APNs client 或通知点击路由。
- 现有 `t_mobile_device.token_hash` 是 SkillForge 配对登录 token 的 hash，不是 APNs device token，
  两者不能混用。
- 因此当前答案是：前台状态会更新，后台/关闭时不会收到 APNs 推送。

## 现有事件边界

`SessionLoopFinishedEvent` 会为主会话、SubAgent、Team member、定时任务和系统 Agent loop 发出，且
同一个 session 可以处理多次用户任务。实现不能把每个 finished event 都直接变成用户通知，也不能
只用 `sessionId + finalStatus` 去重。

## 用户价值

- 长任务运行时无需一直停留在 App。
- 任务完成、失败或等待输入时及时回到对应会话。
- 减少任务已经结束但用户不知道的空等时间。
