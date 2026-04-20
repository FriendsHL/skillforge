# 飞书 WebSocket 长连接模式设计

**状态**：方案已评审，待实现  
**日期**：2026-04-20  
**背景**：飞书 webhook 模式要求公网 IP，本地开发需 ngrok。飞书提供 WebSocket 长连接模式，由我方主动建连，无需公网 IP。

---

## 问题

飞书现有实现（`FeishuChannelAdapter`）仅支持 webhook 模式（飞书→HTTP POST→我方），本地开发必须用 ngrok 才能收到事件。

---

## 决策：采用 Plan B（修订版）

经 Plan A / Plan B 双方案并行设计 + 2 轮交叉 Review 后，最终采用 **Plan B 骨架 + Plan A 热更新思路（简化版）**。

### 核心设计

引入 `ChannelPushConnector` SPI，`FeishuWsConnector` 实现该接口，`ChannelPushManager`（SmartLifecycle）统一管理连接生命周期。webhook 模式保持不变，两种模式通过 `configJson` 中的 `"mode"` 字段切换。

---

## 新增类清单

| 类 | 包 | 行数 | 职责 |
|---|---|---|---|
| `ChannelPushConnector` | `channel/spi` | ~25 | SPI：`start(config)` / `stop()` |
| `FeishuWsConnector` | `channel/platform/feishu` | ~220 | OkHttp WS 连接，ping/pong，断线重连，不注册 @Component |
| `FeishuWsEventDispatcher` | `channel/platform/feishu` | ~60 | 剥开 WS 帧 → FeishuEventParser → ChannelSessionRouter，发 ack（含 service_id）|
| `FeishuWsReconnectPolicy` | `channel/platform/feishu` | ~35 | record，纯值对象，指数退避 nextDelayMs(attempt)，1s→64s，±20% jitter |
| `ChannelPushManager` | `channel/push` | ~130 | SmartLifecycle，扫 DB，start/stop 所有 Connector |

**修改现有文件**：
- `FeishuChannelAdapter`：`implements ChannelPushConnector`（+15 行）
- `ChannelConfigController.patch()`：修改 WS 配置时返回 warn 提示

---

## 架构图

```
飞书服务器
    │  wss (主动建连)
    ▼
FeishuWsConnector.onMessage(frame)
    │
    ▼
FeishuWsEventDispatcher
    ├─ FeishuEventParser.parse()      ← 复用，零改动
    ├─ ChannelSessionRouter.routeAsync()  ← 复用，零改动
    └─ ws.send(ack with service_id)
```

---

## 生命周期

```
Spring start
  → ChannelPushManager.start()          [SmartLifecycle, phase=DEFAULT_PHASE+100]
      → 扫描所有 ChannelPushConnector Bean
      → 读 DB：configService.getDecryptedConfig(platform)
      → configJson["mode"] == "websocket" → connector.start(config)
      → connector 内：getAccessToken → 建 wss 连接

Spring stop
  → ChannelPushManager.stop(Runnable callback)
      → connector.stop()（等 in-flight ACK，最多 3s 超时）
      → callback.run()                  ← Spring 感知 drain 完成
```

**注意**：phase 设为 `DEFAULT_PHASE + 100`（晚启动），保证 JPA / DataSource 已就绪后再建连。

---

## 重连策略

- 初始延迟：1s，退避系数：2x，上限：64s，±20% jitter（ThreadLocalRandom）
- 断线（`onFailure` / `onClosed`）→ `ScheduledFuture` 调度重连
- 重连前重取 token（token 可能已过期）
- `stop()` 后 `ScheduledFuture.cancel(true)`，不再重连
- ping 响应：`onMessage` 识别 `header.type == "ping"` 立即回 pong

---

## 配置切换

`t_channel_config.config_json` 中增加 `"mode"` 字段：

```json
// webhook 模式（默认，现有行为不变）
{"mode": "webhook"}

// WebSocket 模式（本地开发推荐）
{"mode": "websocket"}
```

`mode` 缺省或为 `"webhook"` 时 `ChannelPushManager` 不建 WS 连接，webhook 路径正常工作。

**热更新**：初版要求重启生效。`ChannelConfigController.patch()` 在 `mode` 字段被修改时返回：
```json
{"warning": "ws mode change requires server restart"}
```

---

## 关键约束（Review 发现的 HIGH 问题修复）

### 1. Connector 实例化方式
`FeishuWsConnector` 不注册 `@Component`，由 `ChannelPushManager` 工厂方法创建，**通过构造器注入已注册的 `FeishuClient`**（持有正确的 ObjectMapper Bean），避免手动 `new ObjectMapper()` 触发项目 footgun。

```java
// ChannelPushManager 内部
FeishuWsConnector connector = new FeishuWsConnector(feishuClient, eventDispatcher, reconnectPolicy);
```

### 2. stop() 超时 guard
`SmartLifecycle.stop(Runnable callback)` 内等待 in-flight ACK，但必须设上限：

```java
@Override
public void stop(Runnable callback) {
    connectors.forEach(c -> c.stop());
    // 最多等 3s，超时强制继续
    drainLatch.await(3, TimeUnit.SECONDS);
    callback.run();
}
```

### 3. 重复消息保护
断线重连期间飞书可能重推消息，由现有 `ChannelMessageDedupRepository`（platformMessageId 去重）兜底，无需额外处理。

---

## Plan A vs Plan B 对比（存档）

| 维度 | Plan A | Plan B（采用） |
|---|---|---|
| 生命周期 | @PostConstruct / @PreDestroy | SmartLifecycle（优雅 drain）|
| 热更新 | 支持（CAS 替换连接） | 不支持（重启生效） |
| SPI 扩展 | 无 | ChannelPushConnector（Telegram 复用）|
| 主要风险 | CAS 竞态 + watchdog/Scheduler 双触发 | stop 超时 + 实例化歧义（已修复）|
| 推荐结论 | 不采用 | ✅ 采用 |

---

## Review 问题记录

### Plan A HIGH 问题
1. `@PostConstruct` 调 JPA 有时序风险
2. CAS 替换时旧 onMessage 仍飞行，可能重复投递
3. watchdog 和 ReconnectScheduler 并发建两条 WS 连接

### Plan B HIGH 问题（已在设计中修复）
1. `FeishuWsConnector` 手动 new 时 ObjectMapper footgun → 改为构造器传入 FeishuClient
2. `stop(Runnable)` 等 ACK 无超时 guard → 加 3s timeout

---

## 待办

- [ ] 实现 `ChannelPushConnector` SPI
- [ ] 实现 `FeishuWsConnector`（含重连、ping/pong、ack with service_id）
- [ ] 实现 `FeishuWsEventDispatcher`
- [ ] 实现 `FeishuWsReconnectPolicy`（record）
- [ ] 实现 `ChannelPushManager`（SmartLifecycle）
- [ ] 修改 `FeishuChannelAdapter` implements ChannelPushConnector
- [ ] 修改 `ChannelConfigController.patch()` 加 warn
- [ ] 修改 dashboard UI：ChannelConfigDrawer 添加 mode 切换选项
- [ ] E2E 验证：本地无 ngrok 收发飞书消息
