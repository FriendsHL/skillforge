# IOS-TASK-COMPLETION-PUSH — Task 结束后的 App 消息推送

> 状态：implemented（本地验证通过；真实 APNs 到机验收待凭据和真机）
> 模式：Full（APNs、device token、安全持久化、后台生命周期和 deep link）
> 优先级：P1

## 摘要

当前 App 在前台可通过 WebSocket/REST 看到任务状态，但服务端尚未实现 APNs 投递。因此 App 在后台
或关闭时，task 结束不会产生 iOS 系统通知。

本包补齐 APNs token 注册、服务端安全存储与投递、任务终态事件映射、通知点击跳转目标 session，
并覆盖完成、失败以及需要用户操作的长任务状态。

## 阅读顺序

1. [mrd.md](mrd.md)
2. [prd.md](prd.md)
3. [tech-design.md](tech-design.md)

## 当前进度

- 已完成 APNs token 注册、加密存储、通知 outbox、按设备投递、有限重试和失效 token 停用。
- 已完成 completed/error/action-required 事件映射、根任务过滤与 task 级幂等。
- 已完成 iOS 权限申请、远程通知注册、token 上传、点击跳转 session 和上传错误反馈。
- 服务端完整测试 3324 个（0 失败，175 跳过）、iOS `MobileApiClientTests` 16 个测试及 Release
  模拟器构建已通过。
- 待部署方配置 Apple `.p8` 凭据，并用真机完成后台、terminated、多设备和 token rotation 验收。
