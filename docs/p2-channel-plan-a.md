# Plan A：新 Maven 模块 `skillforge-channels` + 正式 SPI

**版本**: v5.0（第2轮 Reviewer-B 反馈已修订）  
**方向**: 严格层次分离 — 在 `skillforge-core` 定义 `MessageChannel` SPI，新建 `skillforge-channels` 模块承载所有平台实现。

**修订摘要**（v2.0 → v3.0，新增 Reviewer-B 修订）：
- [B-M6] 新增 `SessionCompletedEvent` Spring ApplicationEvent + `ChannelOutboxService` 监听，明确 AgentLoop 完成 → Outbox 写入的完整链路
- [B-M7] OutboxPoller 改用 `SELECT FOR UPDATE SKIP LOCKED`，防多实例重复投递
- [B-L8] 删除 `t_channel_inbound_queue` 和 `InboundQueuePoller`（重复设计）：`ChatService.chatAsync()` 已有 `ctx.enqueueUserMessage()` 处理 running 状态，Channel Gateway 直接调用即可，Session 繁忙时仅需发送"处理中"提示
- [B-L9] 前端 /channels 页新增 Outbox 失败消息列表（含重发按钮）
- [B-L10] 新增 `MockChannel` 实现，供 CI/本地测试使用

**v4.0 → v5.0 修订摘要**（Reviewer-B 第2轮）：
- [B-M7完整修复] `claimPendingBatch` 改为 `UPDATE...WHERE id IN (SELECT...FOR UPDATE SKIP LOCKED) RETURNING id`，原子地将 status 从 PENDING 改为 IN_FLIGHT；新增 IN_FLIGHT 超时回收（5 分钟超时重置为 PENDING）；outbox CHECK 约束新增 IN_FLIGHT 和 DROPPED 状态

**v3.0 → v4.0 修订摘要**（Reviewer-A 第2轮）：
- [A-M残留] `OutboxPoller` 移到 `skillforge-server`（直接注入 Service，无需额外接口层）；`InboundQueuePoller` 已在 v3.0 删除（B-L8）
- [A-L新] 飞书 bot 自身消息过滤：`parseIncoming` 补充 `sender.sender_type == "app"` 判断

**v1.0 → v2.0 修订摘要**（Reviewer-A 第1轮）：
- [H1] 新增 `InboundMessageRouter` SPI 解决 WebhookController → ChannelGatewayService 循环依赖
- [H2] 修正飞书签名算法（SHA256 非 HMAC，encryptKey 非 verificationToken）
- [H3] 修复 DB 唯一约束逻辑（改为部分唯一索引 `WHERE is_active = TRUE`）
- [H4] 拆分 OutboxPoller 事务，网络调用移出事务边界
- [H5] 澄清 Migration 版本号：实际最新为 V16，新建 V17（已通过 `ls db/migration/` 确认）
- [H6] 增加 Session 路由并发安全机制（部分索引 + `INSERT ON CONFLICT DO NOTHING`）
- [H7] 修复飞书 Challenge echo：从 body JSON 读取，通过 `WebhookVerifyResult.challengeValue` 传递
- [H8] ~~P2 入站队列表~~ → 已在 B-L8 中删除，改为直接调用 chatAsync() + 发送提示
- [M1] 用 `WebhookRequest` 包装 HttpServletRequest，消除 servlet API 依赖污染 core
- [M2] `isEnabled()` 职责移到 `ChannelRegistry`，Channel SPI 不再持有该方法
- [M3] 明确幂等去重：入站消息新增 `t_channel_message_dedup` 表
- [M4] 凭证 API 改为 PATCH 语义，null/`***` 字段不覆盖
- [M5] Feishu token 刷新加 `ReentrantLock`
- [L2] 统一退避公式与文字描述
- [L3] 补充 CHECK 约束

---

## 1. Maven 模块变更

### 1.1 新增模块结构

```
skillforge/
├── skillforge-core          # [修改] 新增 MessageChannel SPI、WebhookRequest、InboundMessageRouter、规范化数据模型
├── skillforge-skills        # [不变]
├── skillforge-server        # [修改] 实现 InboundMessageRouter、ChannelGatewayService、OutboxPoller、Entity、Flyway V17
├── skillforge-channels      # [新增] 所有平台实现 + WebhookController（仅依赖 core，不含 Poller）
│   ├── pom.xml
│   └── src/main/java/com/skillforge/channels/
│       ├── core/            # ChannelRegistry（OutboxPoller 已移到 server — 修订 A-M残留）
│       ├── feishu/          # FeishuChannel 实现
│       ├── telegram/        # TelegramChannel 实现
│       ├── mock/            # MockChannel（dev/test）
│       └── web/             # WebhookController（统一入口）
├── skillforge-dashboard     # [修改] 新增 Channel 管理页面
└── skillforge-cli           # [不变]
```

### 1.2 pom.xml 层级关系

**根 pom.xml** — 新增模块声明：
```xml
<modules>
    <module>skillforge-core</module>
    <module>skillforge-skills</module>
    <module>skillforge-channels</module>   <!-- 新增，必须在 skillforge-server 前 -->
    <module>skillforge-server</module>
    <module>skillforge-cli</module>
</modules>
```

同时在 `<dependencyManagement>` 中新增：
```xml
<dependency>
    <groupId>com.skillforge</groupId>
    <artifactId>skillforge-channels</artifactId>
    <version>${project.version}</version>
</dependency>
```

**skillforge-channels/pom.xml**：
```xml
<parent>
    <groupId>com.skillforge</groupId>
    <artifactId>skillforge</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</parent>
<artifactId>skillforge-channels</artifactId>

<dependencies>
    <!-- SPI、数据模型、InboundMessageRouter 均来自 core，无 server 依赖 -->
    <dependency>
        <groupId>com.skillforge</groupId>
        <artifactId>skillforge-core</artifactId>
    </dependency>
    <!-- Webhook Controller 需要 Spring Web -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <!-- HTTP Client 调用平台 API -->
    <dependency>
        <groupId>com.squareup.okhttp3</groupId>
        <artifactId>okhttp</artifactId>
    </dependency>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
</dependencies>
```

**skillforge-server/pom.xml** — 新增依赖：
```xml
<dependency>
    <groupId>com.skillforge</groupId>
    <artifactId>skillforge-channels</artifactId>
</dependency>
```

### 1.3 依赖方向（修订后，消除循环）

```
skillforge-core
    ↑ (SPI + InboundMessageRouter + WebhookRequest)
    │
skillforge-channels ──── (WebhookController 调用 InboundMessageRouter 接口)
    ↑
skillforge-server ───── (ChannelGatewayService 实现 InboundMessageRouter)
```

`skillforge-channels` 不依赖 `skillforge-server`，通过 `InboundMessageRouter` 接口（定义在 `core`）回调 server 层逻辑，彻底无循环依赖。

---

## 2. MessageChannel SPI 接口

### 2.1 前置：`WebhookRequest` 抽象（修订 M1）

定义在 `skillforge-core`，消除 SPI 对 `jakarta.servlet.http.HttpServletRequest` 的依赖：

```java
package com.skillforge.core.channel;

import java.util.List;
import java.util.Map;

/**
 * 平台 webhook 请求的轻量抽象，与 HTTP 框架解耦。
 * WebhookController 将 HttpServletRequest 转换为此类型后传给 SPI。
 */
public record WebhookRequest(
    /** 请求头（全部小写键名） */
    Map<String, List<String>> headers,
    /** 完整请求体字节，签名验证时必须用原始字节（不能是解析后的对象） */
    byte[] rawBody,
    /** rawBody 解码为 UTF-8 字符串的便捷引用 */
    String rawBodyString
) {
    public String firstHeader(String name) {
        List<String> values = headers.get(name.toLowerCase());
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }
}
```

### 2.2 `MessageChannel` 接口

```java
package com.skillforge.core.channel;

import java.util.Optional;

/**
 * 消息平台接入 SPI。每个平台（飞书、Telegram 等）提供一个实现。
 *
 * <p>实现类须注册为 Spring {@code @Component}，由 {@link ChannelRegistry} 按
 * {@link #getChannelId()} 自动发现。
 *
 * <p>生命周期：
 * <ol>
 *   <li>Webhook 到达 → {@link #verifyWebhook} 验签（失败抛异常）
 *   <li>{@link #parseIncoming} 解析平台消息体为规范化 {@link ChannelMessage}
 *   <li>框架路由到 Agent Session，执行对话
 *   <li>Agent 回复 → {@link #sendReply} 推送给平台
 * </ol>
 */
public interface MessageChannel {

    /**
     * 全局唯一的平台标识符，小写下划线，如 {@code "feishu"}, {@code "telegram"}。
     * 同时用作 webhook 路由路径段：{@code /webhook/{channelId}}。
     */
    String getChannelId();

    /**
     * 人类可读的平台名称，用于 UI 展示。
     */
    String getDisplayName();

    /**
     * 验证 webhook 请求的合法性（签名、时间戳、token 等）。
     *
     * <p>验证通过：返回 {@link WebhookVerifyResult}（含平台 userId、convId、challengeValue）。
     * <p>飞书 challenge 握手：设置 {@code WebhookVerifyResult.isChallengeOnly=true}
     *    + {@code challengeValue} 字段，Controller 直接 echo 该值，无需后续处理。
     * <p>验证失败：抛 {@link WebhookVerificationException}，框架返回 HTTP 401。
     *
     * @param request 规范化 webhook 请求（含 headers 和 rawBody）
     * @return 验证结果
     * @throws WebhookVerificationException 签名不合法或 token 不匹配
     */
    WebhookVerifyResult verifyWebhook(WebhookRequest request) throws WebhookVerificationException;

    /**
     * 将平台原始请求体解析为规范化消息。
     *
     * <p>如果该请求无需处理（bot 自身消息、不支持的事件类型等），返回 empty。
     *
     * @param request     原始 webhook 请求
     * @param verifyResult {@link #verifyWebhook} 的返回值
     * @return 规范化消息，或 empty（忽略该请求）
     */
    Optional<ChannelMessage> parseIncoming(WebhookRequest request, WebhookVerifyResult verifyResult);

    /**
     * 将 Agent 回复推送回平台。
     *
     * <p>网络错误时抛 {@link ChannelDeliveryException}，由 Outbox Poller 捕获并重试。
     *
     * @param reply 待推送的回复
     * @throws ChannelDeliveryException 推送失败（网络/限流/token 过期等）
     */
    void sendReply(ChannelReply reply) throws ChannelDeliveryException;
}
```

**注意**：`isEnabled()` 已从 SPI 接口移除，职责归属 `ChannelRegistry`（见 §2.4）。

### 2.3 辅助类型

```java
package com.skillforge.core.channel;

/** verifyWebhook 的结构化返回值 */
public record WebhookVerifyResult(
    String platformUserId,    // 平台端用户 ID
    String platformConvId,    // 平台端会话/群组 ID
    boolean isChallengeOnly,  // true = 仅 challenge 握手
    String challengeValue     // isChallengeOnly=true 时，从 body JSON 解析出的 challenge 字符串
) {}

public class ChannelDeliveryException extends RuntimeException {
    private final boolean retryable;
    public ChannelDeliveryException(String msg, boolean retryable, Throwable cause) {
        super(msg, cause);
        this.retryable = retryable;
    }
    public boolean isRetryable() { return retryable; }
}

public class WebhookVerificationException extends RuntimeException {
    public WebhookVerificationException(String msg) { super(msg); }
}
```

### 2.4 `InboundMessageRouter` 接口（修订 H1）

定义在 `skillforge-core`，由 `skillforge-server` 实现，解决 `WebhookController`（在 channels 模块）需要调用 `ChannelGatewayService`（在 server 模块）的循环依赖：

```java
package com.skillforge.core.channel;

/**
 * 入站消息路由回调接口。
 * 定义在 core，由 skillforge-server 的 ChannelGatewayService 实现，
 * 注入到 skillforge-channels 的 WebhookController，打破循环依赖。
 */
public interface InboundMessageRouter {
    /**
     * 将规范化入站消息路由到对应的 Agent Session。
     * 异步执行，立即返回（不等待 Agent 完成）。
     */
    void route(ChannelMessage message);
}
```

### 2.5 `ChannelRegistry`（修订 M2 — `isEnabled` 移到 Registry）

```java
package com.skillforge.channels.core;

import com.skillforge.core.channel.MessageChannel;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ChannelRegistry {
    private final Map<String, MessageChannel> channels;
    private final ChannelConfigProvider configProvider; // 注入自 server 层（通过接口）

    public ChannelRegistry(List<MessageChannel> channels, ChannelConfigProvider configProvider) {
        this.channels = channels.stream()
            .collect(Collectors.toMap(MessageChannel::getChannelId, Function.identity()));
        this.configProvider = configProvider;
    }

    public Optional<MessageChannel> find(String channelId) {
        return Optional.ofNullable(channels.get(channelId));
    }

    /** enabled = t_channel_config.enabled = true */
    public boolean isEnabled(String channelId) {
        return configProvider.isEnabled(channelId);
    }
}
```

`ChannelConfigProvider` 接口同样定义在 `skillforge-core`，由 `skillforge-server` 的 Repository 层实现，避免 channels → server 依赖。

---

## 3. 规范化数据模型

定义在 `skillforge-core`：`com.skillforge.core.channel`

### 3.1 ChannelMessage（入站）

```java
public record ChannelMessage(
    String channelId,          // "feishu" / "telegram"
    String platformMessageId,  // 幂等去重键
    String platformUserId,
    String platformConvId,     // 飞书 open_chat_id / Telegram chat_id
    String textContent,
    List<ChannelAttachment> attachments,
    Instant sentAt,
    String rawMsgType
) {
    public boolean hasText() {
        return textContent != null && !textContent.isBlank();
    }
}

public record ChannelAttachment(
    String type,      // "image" / "file" / "audio" / "video"
    String url,
    String mimeType,
    Long sizeBytes
) {}
```

### 3.2 ChannelReply（出站）

```java
public record ChannelReply(
    String channelId,
    String platformConvId,
    String platformUserId,
    String textContent,    // Markdown，平台实现按需转换
    String outboxId        // t_channel_outbox.id，用于送达确认
) {}
```

---

## 4. Webhook 路由策略

### 4.1 统一端点设计

**选择：统一端点 `/webhook/{channelId}`**。新增平台只注册新 `MessageChannel` Bean，无需改 Controller。

```java
package com.skillforge.channels.web;

@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private final ChannelRegistry registry;
    private final InboundMessageRouter router; // 注入 server 层实现，无循环依赖

    public WebhookController(ChannelRegistry registry, InboundMessageRouter router) {
        this.registry = registry;
        this.router = router;
    }

    @PostMapping("/{channelId}")
    public ResponseEntity<Object> handleWebhook(
            @PathVariable String channelId,
            HttpServletRequest httpRequest) throws IOException {

        MessageChannel channel = registry.find(channelId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (!registry.isEnabled(channelId)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        // 将 HttpServletRequest 转换为 WebhookRequest（消除 SPI 对 servlet API 的依赖）
        byte[] rawBody = httpRequest.getInputStream().readAllBytes();
        WebhookRequest webhookReq = new WebhookRequest(
            extractHeaders(httpRequest), rawBody, new String(rawBody, StandardCharsets.UTF_8));

        WebhookVerifyResult verifyResult;
        try {
            verifyResult = channel.verifyWebhook(webhookReq);
        } catch (WebhookVerificationException e) {
            log.warn("[{}] Webhook verification failed: {}", channelId, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // 飞书 challenge：challengeValue 来自 verifyResult（从 body JSON 解析），不从 query param 读
        if (verifyResult.isChallengeOnly()) {
            return ResponseEntity.ok(Map.of("challenge", verifyResult.challengeValue()));
        }

        channel.parseIncoming(webhookReq, verifyResult)
            .ifPresent(router::route);

        // 立即 200，不等待 Agent 执行
        return ResponseEntity.ok().build();
    }

    private Map<String, List<String>> extractHeaders(HttpServletRequest req) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        Collections.list(req.getHeaderNames()).forEach(name ->
            headers.put(name.toLowerCase(), Collections.list(req.getHeaders(name))));
        return headers;
    }
}
```

### 4.2 签名验证机制

| 平台 | 签名方式 |
|------|---------|
| 飞书 | 见 §7.2（已修正算法）|
| Telegram | `X-Telegram-Bot-Api-Secret-Token` header，与注册 webhook 时的 `secret_token` 比对 |

### 4.3 幂等去重（修订 M3）

在 `ChannelGatewayService.route()` 入口，用 `t_channel_message_dedup` 表做 DB 级去重：

```sql
-- 在 V17 migration 中（见 §5）
CREATE TABLE t_channel_message_dedup (
    channel_id          VARCHAR(64)   NOT NULL,
    platform_message_id VARCHAR(256)  NOT NULL,
    processed_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (channel_id, platform_message_id)
);
```

路由入口：
```java
// INSERT ... ON CONFLICT DO NOTHING，返回受影响行数
int inserted = dedupeRepo.insertIfAbsent(channelId, platformMessageId);
if (inserted == 0) return; // 已处理，幂等跳过
```

过期记录由定时任务每天清理（`processed_at < NOW() - INTERVAL '7 days'`）。

---

## 5. DB Schema（V17 Migration）

**确认**：`ls db/migration/` 实际最新为 V16__skill_evolution_run.sql，新建 **V17**（无版本跳跃）。

```sql
-- V17__channel_gateway.sql

-- ============================================================
-- 1. 平台凭证配置
-- ============================================================
CREATE TABLE t_channel_config (
    id              BIGSERIAL       PRIMARY KEY,
    channel_id      VARCHAR(64)     NOT NULL UNIQUE,
    display_name    VARCHAR(128)    NOT NULL,
    enabled         BOOLEAN         NOT NULL DEFAULT FALSE,
    -- AES-GCM 加密存储（密钥来自环境变量 CHANNEL_SECRET_KEY）
    config_json     TEXT            NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- ============================================================
-- 2. 平台用户 → SkillForge 用户映射
-- ============================================================
CREATE TABLE t_channel_user_mapping (
    id                  BIGSERIAL       PRIMARY KEY,
    channel_id          VARCHAR(64)     NOT NULL,
    platform_user_id    VARCHAR(256)    NOT NULL,
    skillforge_user_id  BIGINT          NOT NULL,
    bound_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_channel_user UNIQUE (channel_id, platform_user_id)
);
CREATE INDEX idx_cum_skillforge_user ON t_channel_user_mapping (skillforge_user_id);

-- ============================================================
-- 3. 平台会话 → Agent Session 映射
-- ============================================================
CREATE TABLE t_channel_session_mapping (
    id                  BIGSERIAL       PRIMARY KEY,
    channel_id          VARCHAR(64)     NOT NULL,
    platform_conv_id    VARCHAR(256)    NOT NULL,
    session_id          VARCHAR(36)     NOT NULL,
    agent_id            BIGINT          NOT NULL,
    skillforge_user_id  BIGINT          NOT NULL,
    is_active           BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);
-- 修订 H3：改为部分唯一索引，仅限制 is_active=TRUE 的行唯一
-- 这样同一会话结束后可以创建多条历史记录（is_active=FALSE），不冲突
CREATE UNIQUE INDEX uq_channel_conv_active
    ON t_channel_session_mapping (channel_id, platform_conv_id)
    WHERE is_active = TRUE;
CREATE INDEX idx_csm_session ON t_channel_session_mapping (session_id);

-- ============================================================
-- 4. 可靠投递 Outbox
-- ============================================================
CREATE TABLE t_channel_outbox (
    id                  VARCHAR(36)     PRIMARY KEY,
    channel_id          VARCHAR(64)     NOT NULL,
    platform_conv_id    VARCHAR(256)    NOT NULL,
    platform_user_id    VARCHAR(256),
    session_id          VARCHAR(36)     NOT NULL,
    reply_text          TEXT            NOT NULL,
    status              VARCHAR(32)     NOT NULL DEFAULT 'PENDING'
                            -- IN_FLIGHT: 已被 Poller 认领、正在投递中；防多实例重复发送
                            CHECK (status IN ('PENDING', 'IN_FLIGHT', 'SENT', 'FAILED', 'DROPPED')),
    retry_count         INT             NOT NULL DEFAULT 0,
    max_retries         INT             NOT NULL DEFAULT 3,
    next_retry_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    claimed_at          TIMESTAMPTZ,    -- Poller 认领时间，用于 IN_FLIGHT 超时回收
    failure_reason      TEXT,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    sent_at             TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON t_channel_outbox (next_retry_at)
    WHERE status = 'PENDING';
-- IN_FLIGHT 超时回收索引（定时任务扫描 claimed_at 过期的记录）
CREATE INDEX idx_outbox_inflight ON t_channel_outbox (claimed_at)
    WHERE status = 'IN_FLIGHT';

-- ============================================================
-- 5. 幂等去重表（修订 M3）
-- ============================================================
CREATE TABLE t_channel_message_dedup (
    channel_id          VARCHAR(64)   NOT NULL,
    platform_message_id VARCHAR(256)  NOT NULL,
    processed_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (channel_id, platform_message_id)
);

-- 注：t_channel_inbound_queue 已删除（修订 B-L8）
-- ChatService.chatAsync() 已通过 LoopContext.enqueueUserMessage() 处理 running 状态的消息排队
-- Channel Gateway 直接调用 chatAsync()，Session 繁忙时 ChatService 内部自动队列，无需外部表
```

---

## 6. Session 路由逻辑（修订 H6、B-L8）

`ChannelGatewayService` 实现 `InboundMessageRouter`，位于 `skillforge-server`。

### 6.1 路由流程

```
收到 ChannelMessage
       ↓
1. 幂等去重（t_channel_message_dedup，INSERT ON CONFLICT DO NOTHING）
   inserted=0 → return（已处理）
       ↓
2. 查 t_channel_user_mapping
   命中 → skillforge_user_id
   未命中 → 自动注册（根据 channel_config.auto_register 开关）
       ↓
3. 查活跃 Session mapping（SELECT FOR UPDATE — 持行锁防并发）
   ┌── 命中 → session_id ← existing
   └── 未命中 → INSERT INTO t_channel_session_mapping ... ON CONFLICT DO NOTHING
               → 若 inserted=0，并发竞争落败，重查获取 session_id
               → 否则 SessionService.createSession() 创建新 Session，写入 mapping
       ↓
4. 调用 ChatService.chatAsync(sessionId, textContent, userId)
   ┌── Session idle → loop 立即启动
   └── Session running → ChatService 内部 ctx.enqueueUserMessage() 自动排队
                         （ChatService.java L116-143 已实现此逻辑，无需额外队列表）
                         Channel Gateway 仅需额外发送平台提示：
                         channel.sendReply("⏳ 您的消息已排队，请稍候")
       ↓
5. AgentLoop 完成 → ChatService 发布 SessionCompletedEvent
   → ChannelOutboxService 监听事件
   → 查 t_channel_session_mapping 找到 channel 上下文
   → 写 t_channel_outbox（status=PENDING）
   → OutboxPoller 异步投递给平台
```

**注意（修订 B-L8）**：`t_channel_inbound_queue` 已从方案中删除。`ChatService.chatAsync()` 已通过 `LoopContext.enqueueUserMessage()` 处理 running 状态下的消息排队，Channel Gateway 无需重复实现。

### 6.2 并发安全（修订 H6）

```java
@Transactional
public String resolveOrCreateSession(String channelId, String platformConvId,
                                      Long userId, Long agentId) {
    // SELECT ... FOR UPDATE 锁住 mapping 行，防止并发新建
    Optional<ChannelSessionMappingEntity> existing =
        mappingRepo.findActiveForUpdate(channelId, platformConvId);
    if (existing.isPresent()) return existing.get().getSessionId();

    // INSERT ON CONFLICT DO NOTHING（部分唯一索引保证）
    String newSessionId = sessionService.createSession(userId, agentId).getId();
    int inserted = mappingRepo.insertIfAbsent(channelId, platformConvId, newSessionId, agentId, userId);
    if (inserted == 0) {
        // 并发竞争落败，重查
        return mappingRepo.findActive(channelId, platformConvId)
            .map(ChannelSessionMappingEntity::getSessionId)
            .orElseThrow();
    }
    return newSessionId;
}
```

### 6.3 AgentLoop 完成 → Outbox 写入（修订 B-M6）

**问题**：`ChatService.runLoop()` 完成后没有通知 Channel 层的机制，outbox 永远不会被写入。

**解决方案**：Spring `ApplicationEvent` + 监听器，保持 ChatService 不感知 Channel 概念。

**Step 1**：定义事件（`skillforge-server`）：
```java
package com.skillforge.server.channel;

/** ChatService 在 AgentLoop 正常完成（idle）或取消（cancelled）后发布此事件 */
public class SessionCompletedEvent {
    private final String sessionId;
    private final String finalAssistantMessage; // Agent 最后一条回复
    private final String status; // "completed" / "cancelled" / "error"

    public SessionCompletedEvent(String sessionId, String finalAssistantMessage, String status) {
        this.sessionId = sessionId;
        this.finalAssistantMessage = finalAssistantMessage;
        this.status = status;
    }
    // getters...
}
```

**Step 2**：`ChatService.runLoop()` 在 session 保存为 idle 后发布事件（在现有 L412 保存 session 之后，L425 触发 smart rename 之前插入）：
```java
// 现有代码：sessionService.saveSession(s);  ← L412
// 新增：发布 SessionCompletedEvent
if (!"error".equals(finalStatus)) {
    applicationEventPublisher.publishEvent(
        new SessionCompletedEvent(sessionId, finalMessage, finalStatus));
}
```

**Step 3**：`ChannelOutboxService` 监听（`skillforge-server`）：
```java
@Component
public class ChannelOutboxService {

    @EventListener
    @Async  // 不阻塞 ChatService 的完成流程
    public void onSessionCompleted(SessionCompletedEvent event) {
        if ("error".equals(event.getStatus())) return;
        if (event.getFinalAssistantMessage() == null) return;

        // 查是否是 channel-triggered session
        Optional<ChannelSessionMappingEntity> mapping =
            mappingRepo.findBySessionId(event.getSessionId());
        if (mapping.isEmpty()) return; // 普通 session，非 channel 触发

        ChannelSessionMappingEntity m = mapping.get();
        ChannelOutboxEntity outbox = new ChannelOutboxEntity();
        outbox.setId(UUID.randomUUID().toString());
        outbox.setChannelId(m.getChannelId());
        outbox.setPlatformConvId(m.getPlatformConvId());
        outbox.setSessionId(event.getSessionId());
        outbox.setReplyText(event.getFinalAssistantMessage());
        outbox.setStatus("PENDING");
        outbox.setNextRetryAt(Instant.now());
        outboxRepo.save(outbox);
    }
}
```

### 6.4 Session 生命周期策略

| 场景 | 策略 |
|------|------|
| 首次消息（无 mapping） | 创建新 Session，写 mapping（is_active=TRUE） |
| Session 正在运行 | ChatService 内部排队；Gateway 发送平台"排队中"提示 |
| 用户发"新对话"指令 | 旧 mapping 设 is_active=FALSE，创建新 Session |
| Session.status="ended" | 视同无 mapping，自动新建 |
| 群聊 | platformConvId = open_chat_id（群成员共享 Session） |
| 私聊 | platformConvId = 用户维度 ID（独立 Session） |

---

## 7. 飞书实现要点

`com.skillforge.channels.feishu.FeishuChannel`

### 7.1 事件订阅（Event Callback v2）

需处理事件类型：
- `url_verification`：challenge 握手（在 `verifyWebhook` 中处理，设置 `isChallengeOnly=true`）
- `im.message.receive_v1`：接收消息（核心业务事件）

配置：
```yaml
channel:
  feishu:
    app-id: ${FEISHU_APP_ID}
    app-secret: ${FEISHU_APP_SECRET}
    verification-token: ${FEISHU_VERIFICATION_TOKEN}  # 事件订阅 token 校验
    encrypt-key: ${FEISHU_ENCRYPT_KEY}                # 消息加密 key（可选）
```

### 7.2 签名验证（修订 H2 — 修正算法）

飞书 Event Callback v2 签名规范（**不是 HMAC，是 SHA256**；用的是 `encryptKey` 而非 `verificationToken`）：

```
signature = hex(SHA256(timestamp + "\n" + nonce + "\n" + body + "\n" + encryptKey))
```

对应代码：
```java
@Override
public WebhookVerifyResult verifyWebhook(WebhookRequest request) throws WebhookVerificationException {
    String bodyStr = request.rawBodyString();
    JsonNode body = objectMapper.readTree(bodyStr);

    // 1. 处理 url_verification（challenge 握手）
    if ("url_verification".equals(body.path("type").asText())) {
        String challenge = body.path("challenge").asText();
        return new WebhookVerifyResult(null, null, true, challenge);
        // challengeValue 从 body JSON 读取，不从 query param 读（修订 H7）
    }

    // 2. 若开启消息加密，先解密（AES-256-CBC）
    // 3. 验签：SHA256(timestamp + "\n" + nonce + "\n" + body + "\n" + encryptKey)
    String timestamp = request.firstHeader("x-lark-request-timestamp");
    String nonce = request.firstHeader("x-lark-request-nonce");
    String expectedSig = request.firstHeader("x-lark-signature");
    String computed = sha256Hex(timestamp + "\n" + nonce + "\n" + bodyStr + "\n" + encryptKey);
    if (!MessageDigest.isEqual(computed.getBytes(), expectedSig.getBytes())) {
        throw new WebhookVerificationException("Feishu signature mismatch");
    }

    // 4. 提取 platformUserId / platformConvId
    String platformUserId = body.path("event").path("sender").path("sender_id").path("open_id").asText();
    String platformConvId = body.path("event").path("message").path("chat_id").asText();
    return new WebhookVerifyResult(platformUserId, platformConvId, false, null);
}
```

**verificationToken** 的用途：用于旧版 Event v1 的 token 字段校验（与签名独立），v2 主要靠上述 SHA256 签名。

### 7.3 parseIncoming：过滤 bot 自身消息（修订 A-L新）

`parseIncoming` 必须过滤 bot 自身发出的消息，否则 Agent 回复后会再次触发自己形成死循环：

```java
@Override
public Optional<ChannelMessage> parseIncoming(WebhookRequest request, WebhookVerifyResult result) {
    JsonNode event = body.path("event");
    // sender_type == "app" 表示发送方是 bot 应用本身，必须过滤
    String senderType = event.path("sender").path("sender_type").asText("");
    if ("app".equals(senderType)) {
        return Optional.empty();
    }
    // ... 正常解析逻辑
}
```

### 7.4 消息发送

- 纯文本：`POST /open-apis/im/v1/messages`，`msg_type=text`
- 富文本/卡片：`msg_type=interactive`，飞书卡片 JSON

**Token 管理**（修订 M5 — 加并发保护）：

```java
public class FeishuTokenManager {
    private final ReentrantLock lock = new ReentrantLock();
    private volatile String cachedToken;
    private volatile Instant expiresAt;

    public String getToken() {
        if (cachedToken != null && Instant.now().isBefore(expiresAt.minusSeconds(300))) {
            return cachedToken;
        }
        lock.lock();
        try {
            // double-check inside lock
            if (cachedToken != null && Instant.now().isBefore(expiresAt.minusSeconds(300))) {
                return cachedToken;
            }
            refreshToken();
            return cachedToken;
        } finally {
            lock.unlock();
        }
    }
}
```

---

## 8. Telegram 实现要点

`com.skillforge.channels.telegram.TelegramChannel`

### 8.1 Webhook 模式

注册：
```
POST https://api.telegram.org/bot{BOT_TOKEN}/setWebhook
{"url": "https://your-domain/webhook/telegram", "secret_token": "{WEBHOOK_SECRET}"}
```

配置：
```yaml
channel:
  telegram:
    bot-token: ${TELEGRAM_BOT_TOKEN}
    webhook-secret: ${TELEGRAM_WEBHOOK_SECRET}
```

### 8.2 签名验证

验证 `X-Telegram-Bot-Api-Secret-Token` header 与 `TELEGRAM_WEBHOOK_SECRET` 一致（常量时间比较，防时序攻击）。

### 8.3 消息类型处理

| Update 类型 | 处理方式 |
|------------|---------|
| `message.text` | 提取 text，路由 Agent |
| `message.photo` / `document` | 提取 file_id，作附件 |
| `callback_query` | 暂忽略（P3） |
| `edited_message` | 忽略 |

### 8.4 消息发送

```
POST https://api.telegram.org/bot{BOT_TOKEN}/sendMessage
{"chat_id": "{platformConvId}", "text": "{escaped}", "parse_mode": "MarkdownV2"}
```

**注意**：MarkdownV2 需转义特殊字符，`TelegramMarkdownEscaper` 处理 `.!-()` 等；长消息（>4096 UTF-16 code units）自动分段（修订 L1：按 Telegram 实际限制计算，非 Java `length()`）。

---

## 9. 前端设计

### 9.1 Channel 管理页面（`/channels`）（修订 B-L9）

**页面结构**：
```
Channel 管理
├── 已集成平台卡片列表
│   ├── 飞书（enabled 开关 + 配置按钮 + 在线状态指示）
│   └── Telegram（enabled 开关 + 配置按钮 + 在线状态指示）
├── 配置抽屉（点击"配置"打开）
│   ├── 飞书：App ID / App Secret / Verification Token / Encrypt Key
│   └── Telegram：Bot Token / Webhook Secret
├── Webhook URL（只读展示 + 一键复制）
└── 投递失败消息（Outbox 监控，status=FAILED 的记录）
    ├── 失败时间 / 平台 / 会话 ID / 失败原因
    ├── "重试"按钮（重置 status=PENDING, next_retry_at=NOW()）
    └── "忽略"按钮（永久标记 DROPPED，不再重试）
```

**监控必要性**：飞书/Telegram API 存在不可恢复失败（token 永久失效、用户封禁 bot 等），`status=FAILED` 消息运维人员必须可见，否则用户消息静默丢失无法感知。

### 9.2 新增 API

```
GET    /api/channels                         # 列出 channel 配置（secret 字段脱敏为 ***）
PATCH  /api/channels/{channelId}             # 更新配置（PATCH 语义，null/*** 字段不覆盖 — 修订 M4）
GET    /api/channels/{channelId}/status      # 测试连通性
GET    /api/channels/outbox/failed           # 列出 FAILED 的 outbox 记录（监控用）
POST   /api/channels/outbox/{id}/retry       # 重置 status=PENDING 触发重投
POST   /api/channels/outbox/{id}/drop        # 标记忽略（status=DROPPED）
```

**PATCH 处理逻辑**（修订 M4）：
```java
// 只更新请求中明确传入且非 *** 的字段
if (req.getAppSecret() != null && !"***".equals(req.getAppSecret())) {
    config.put("app_secret", req.getAppSecret());
}
```

### 9.3 Session 溯源

Session 列表给 channel 来源的 session 加平台图标（飞书/Telegram），区分手动创建和 channel 接入的 session。

---

## 10. 错误处理 & 重试

### 10.1 OutboxPoller（修订 H4 + B-M7 + A-M残留 — 拆分事务 + SKIP LOCKED + 移到 skillforge-server）

**位置**：`skillforge-server/src/main/java/com/skillforge/server/channel/OutboxPoller.java`

原来放在 `skillforge-channels/core/` 导致编译错误（channels 不依赖 server，无法调用 `ChannelOutboxService` 方法）。移到 `skillforge-server` 后可直接注入 `ChannelOutboxRepository` 和 `ChannelRegistry`，无需额外接口层。

```java
@Component
public class OutboxPoller {

    private static final int IN_FLIGHT_TIMEOUT_MINUTES = 5;

    @Scheduled(fixedDelay = 5000)
    public void poll() {
        // 步骤 0：先回收超时的 IN_FLIGHT 记录（Poller crash 保护）
        outboxService.recoverStalledInFlight(IN_FLIGHT_TIMEOUT_MINUTES);

        // 步骤 1：原子地将 PENDING → IN_FLIGHT（短事务内完成，防多实例重复拿到）
        List<String> ids = outboxService.claimBatchAsInFlight(MAX_BATCH_SIZE);

        for (String outboxId : ids) {
            ChannelOutboxEntity entry = outboxService.findById(outboxId);
            MessageChannel channel = registry.find(entry.getChannelId()).orElse(null);
            if (channel == null) {
                outboxService.markFailed(outboxId, "Channel not found", false);
                continue;
            }

            // 步骤 2：事务外做 HTTP 网络调用（不占用 DB 连接）
            boolean success = false;
            String failureReason = null;
            boolean retryable = false;
            try {
                channel.sendReply(toReply(entry));
                success = true;
            } catch (ChannelDeliveryException e) {
                failureReason = e.getMessage();
                retryable = e.isRetryable();
            }

            // 步骤 3：短事务，将 IN_FLIGHT → SENT / FAILED / PENDING（重试）
            if (success) {
                outboxService.markSent(outboxId);        // IN_FLIGHT → SENT
            } else {
                outboxService.markFailedOrRetry(outboxId, failureReason, retryable);
                // retryable=true: IN_FLIGHT → PENDING（设 next_retry_at 退避）
                // retryable=false 或超出 max_retries: IN_FLIGHT → FAILED
            }
        }
    }
}
```

**`claimBatchAsInFlight` 原生 SQL**（修订 B-M7 完整版）：

```java
// ChannelOutboxRepository
@Modifying
@Query(value = """
    UPDATE t_channel_outbox
    SET status = 'IN_FLIGHT', claimed_at = NOW()
    WHERE id IN (
        SELECT id FROM t_channel_outbox
        WHERE status = 'PENDING' AND next_retry_at <= :now
        ORDER BY next_retry_at
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
    )
    RETURNING id
    """, nativeQuery = true)
List<String> claimBatchAsInFlight(@Param("now") Instant now, @Param("limit") int limit);
```

原子性保证：`SELECT...FOR UPDATE SKIP LOCKED`（阻止同一时刻其他实例看到这些行）+ 同一事务内 `UPDATE status='IN_FLIGHT'`（提交后这些行不再满足 `status='PENDING'`）。下一个轮询周期其他实例扫描到的是 IN_FLIGHT 记录，不会重复认领。

**`recoverStalledInFlight`**（Poller crash 保护）：

```java
@Transactional
@Modifying
@Query(value = """
    UPDATE t_channel_outbox
    SET status = 'PENDING', next_retry_at = NOW(), claimed_at = NULL
    WHERE status = 'IN_FLIGHT'
      AND claimed_at < :cutoff
    """, nativeQuery = true)
void recoverStalledInFlight(@Param("cutoff") Instant cutoff);
// 调用方：cutoff = Instant.now().minus(IN_FLIGHT_TIMEOUT_MINUTES, MINUTES)
```

防止 Poller 在 HTTP 调用后、markSent 之前 crash，导致记录永久卡在 IN_FLIGHT。
```

每条记录独立处理，单条失败不影响其他记录。

### 10.2 退避策略（修订 L2 — 统一公式与描述）

重试次数与退避时间（`Math.pow(5, retryCount)` 秒）：

| 重试次 | 退避时间 |
|-------|---------|
| 1 | 5 秒 |
| 2 | 25 秒 |
| 3 | 125 秒（约 2 分钟） |

超过 `max_retries`（默认 3）后置 `FAILED`。

### 10.3 限流处理

| 平台 | 限流响应 | 处理 |
|------|---------|------|
| 飞书 | HTTP 400 code=99991663 | `isRetryable=true`，退避 30s |
| Telegram | HTTP 429，`Retry-After` header | `isRetryable=true`，退避 `Retry-After` 值（优先于默认退避） |

### 10.4 告警

连续投递 `FAILED` 后写 `ActivityLog(type=channel_delivery_failed)`，Dashboard 可监控。

---

## 10.5 MockChannel（修订 B-L10 — CI/本地测试）

`com.skillforge.channels.mock.MockChannel`（仅在 `spring.profiles.active=dev,test` 下激活）

```java
@Component
@Profile({"dev", "test"})
public class MockChannel implements MessageChannel {

    private final List<ChannelReply> sentReplies = new CopyOnWriteArrayList<>();

    @Override
    public String getChannelId() { return "mock"; }

    @Override
    public String getDisplayName() { return "Mock (Testing)"; }

    @Override
    public WebhookVerifyResult verifyWebhook(WebhookRequest request) {
        // 任何请求均通过验证
        String body = request.rawBodyString();
        String userId = extractField(body, "platform_user_id", "test_user");
        String convId = extractField(body, "platform_conv_id", "test_conv");
        return new WebhookVerifyResult(userId, convId, false, null);
    }

    @Override
    public Optional<ChannelMessage> parseIncoming(WebhookRequest request, WebhookVerifyResult result) {
        // 解析 JSON body：{"text": "...", "platform_message_id": "..."}
        String body = request.rawBodyString();
        return Optional.of(new ChannelMessage(
            "mock", extractField(body, "platform_message_id", UUID.randomUUID().toString()),
            result.platformUserId(), result.platformConvId(),
            extractField(body, "text", ""), List.of(), Instant.now(), "text"));
    }

    @Override
    public void sendReply(ChannelReply reply) {
        sentReplies.add(reply); // 记录到内存，供测试断言
    }

    public List<ChannelReply> getSentReplies() { return List.copyOf(sentReplies); }
    public void clearReplies() { sentReplies.clear(); }
}
```

**用途**：
- `POST /webhook/mock` 注入测试消息（本地开发无需 ngrok）
- 单元测试 `ChannelGatewayService` 时注入 MockChannel，断言 `getSentReplies()`
- CI 流水线 E2E：发消息 → 等 outbox → 断言 `MockChannel.sentReplies`，无需真实平台凭证

---

## 11. Plan A Tradeoffs

### 优点

| 维度 | 说明 |
|------|------|
| **清晰边界** | `core` 定义契约，`channels` 实现，`server` 编排。各层职责单一 |
| **零循环依赖** | `InboundMessageRouter` + `ChannelConfigProvider` 接口彻底解耦 |
| **可扩展** | 新增平台 = 新增 `@Component`，零改 Controller/Router |
| **可独立测试** | Channel 实现可完全脱离 server 单测 |
| **未来可拆** | `skillforge-channels` 自成一体，未来可作独立 gateway 服务 |

### 缺点 / 代价

| 维度 | 说明 |
|------|------|
| **初始成本** | 多一个 Maven 模块 + 额外接口层（`InboundMessageRouter`、`ChannelConfigProvider`） |
| **接口层数多** | 跨模块调用链路长，调试时需跨模块 breakpoint |

### 与 Plan B 的核心差异

| 对比项 | Plan A（新模块） | Plan B（内嵌 server） |
|-------|---------------|---------------------|
| 实现位置 | `skillforge-channels` | `skillforge-server` |
| 抽象层级 | `core` SPI（跨模块契约） | `server` 内部接口 |
| 新平台成本 | 只加 Bean，无需改路由 | 可能需改 Controller |
| 循环依赖风险 | 已通过接口解耦为零 | 无（同模块） |
| 适合阶段 | 中长期、平台 ≥ 3 个 | 快速验证、平台 ≤ 2 个 |

---

## 附录：目录快照（修订后）

```
skillforge-core/src/main/java/com/skillforge/core/channel/
├── MessageChannel.java
├── InboundMessageRouter.java       ← 新增，解决 H1 循环依赖
├── ChannelConfigProvider.java      ← 新增，isEnabled 接口
├── WebhookRequest.java             ← 新增，替代 HttpServletRequest（M1）
├── ChannelMessage.java
├── ChannelReply.java
├── ChannelAttachment.java
├── WebhookVerifyResult.java        ← 新增 challengeValue 字段（H7）
├── WebhookVerificationException.java
└── ChannelDeliveryException.java

skillforge-channels/src/main/java/com/skillforge/channels/
├── core/
│   └── ChannelRegistry.java        ← isEnabled 在此处（M2）；OutboxPoller 已移到 server（A-M残留）
├── feishu/
│   ├── FeishuChannel.java          ← 签名算法修正（H2）、challengeValue（H7）、bot 消息过滤（A-L新）
│   ├── FeishuConfig.java
│   └── FeishuTokenManager.java     ← ReentrantLock（M5）
├── telegram/
│   ├── TelegramChannel.java
│   ├── TelegramConfig.java
│   └── TelegramMarkdownEscaper.java ← Unicode-aware 分段（L1）
├── mock/
│   └── MockChannel.java            ← dev/test profile（B-L10）
└── web/
    └── WebhookController.java      ← InboundMessageRouter 接口（H1）

skillforge-server/src/main/java/com/skillforge/server/
├── channel/
│   ├── ChannelGatewayService.java  ← 实现 InboundMessageRouter（H1）、并发安全路由（H6）、调用 chatAsync()（B-L8）
│   ├── OutboxPoller.java           ← 移自 channels（A-M残留）、事务拆分 + SKIP LOCKED（H4 + B-M7）
│   ├── ChannelOutboxService.java   ← 监听 SessionCompletedEvent（B-M6）
│   ├── SessionCompletedEvent.java  ← Spring ApplicationEvent（B-M6）
│   └── ChannelConfigProviderImpl.java ← 实现 ChannelConfigProvider（M2）
├── entity/
│   ├── ChannelConfigEntity.java
│   ├── ChannelUserMappingEntity.java
│   ├── ChannelSessionMappingEntity.java
│   ├── ChannelOutboxEntity.java
│   └── ChannelMessageDedupEntity.java ← 新增（M3）
│   (ChannelInboundQueueEntity 已删除 — B-L8)
├── repository/（各 Entity 对应 Repository，含 SKIP LOCKED 原生查询）
└── controller/
    └── ChannelManagementController.java ← PATCH 语义（M4）+ Outbox 监控 API（B-L9）

skillforge-server/src/main/java/com/skillforge/server/service/
└── ChatService.java                ← 新增 publishEvent(SessionCompletedEvent)（B-M6）

skillforge-server/src/main/resources/db/migration/
└── V17__channel_gateway.sql        ← 确认版本正确（H5），无 inbound_queue 表
```
