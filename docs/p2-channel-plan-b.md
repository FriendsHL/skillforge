# Plan B — Channel Adapter（skillforge-server 内部扩展）

> Planner-B 设计稿 · 2026-04-20 · **v2.4 — 修订版（第5轮 review 后）**
> 方向：**不新增 Maven 模块**，将所有消息网关逻辑封装在 `skillforge-server` 内，通过 Spring 注册表分发多平台 Adapter。

**v2.1 变更摘要**（Reviewer-B 第1轮反馈）：
- HIGH-2：集成契约重设计——放弃 callback 闭包，改为 ApplicationEvent（`ChannelSessionOutputEvent`）异步解耦，彻底消除闭包跨重启丢失问题
- HIGH-3：ReplyDeliveryService 重设计——放弃 `TaskScheduler.schedule()` 内存重试，改为 `@Scheduled` DB 轮询（`SKIP LOCKED`），天然支持重启恢复和多实例部署
- MEDIUM-5：所有 Entity 时间字段 `LocalDateTime` → `Instant`（对齐项目 Known Footgun 规范）
- LOW-9：新增 `MockChannelAdapter` 可测试性设计

*以下为所有已确认修复项*：H1（飞书签名算法）、H3/H4（幂等 + 并发约束）、H5（重启恢复）、H6（线程池 bean）、H7→§7（集成契约，已重写）；M1（WebhookContext）、M2（DeliveryResult）、M3（Telegram null）、M4（飞书 bot 自消息）、M5（PATCH 语义）、M6（SecurityConfig）、M7（token 刷新并发）；L1（CHECK 约束）、L2（FK 删除策略）、L3（Unicode 分段）。

---

## 目录

1. [模块结构](#1-模块结构)
2. [ChannelAdapter 接口](#2-channeladapter-接口)
3. [规范化数据模型](#3-规范化数据模型)
4. [Webhook 路由策略](#4-webhook-路由策略)
5. [DB Schema & V17 Migration](#5-db-schema--v17-migration)
6. [Session 路由](#6-session-路由)
7. [SessionService 集成契约](#7-sessionservice-集成契约)
8. [飞书实现要点](#8-飞书实现要点)
9. [Telegram 实现要点](#9-telegram-实现要点)
10. [前端设计](#10-前端设计)
11. [错误处理 & 重试](#11-错误处理--重试)
12. [Plan B 的 Tradeoffs](#12-plan-b-的-tradeoffs)

---

## 1. 模块结构

### 1.1 原则

Plan B 在 `skillforge-server` 内新增包，**不修改 pom.xml 层级，不新建 Maven 模块**。当前实际最新 Flyway migration 为 V16（`V16__skill_evolution_run.sql`），下一个 migration 为 V17。

```
skillforge-server/src/main/java/com/skillforge/server/
├── channel/                         ← 新增顶层包
│   ├── spi/
│   │   ├── ChannelAdapter.java      ← 核心 SPI 接口
│   │   ├── WebhookContext.java      ← 替代 HttpServletRequest（M1）
│   │   ├── ChannelMessage.java      ← 规范化入站消息
│   │   ├── ChannelReply.java        ← 规范化出站回复
│   │   └── DeliveryResult.java      ← 投递结果（携带 retryAfterMs）（M2）
│   ├── registry/
│   │   └── ChannelAdapterRegistry.java
│   ├── router/
│   │   ├── ChannelSessionRouter.java
│   │   └── SessionRouteResult.java
│   ├── delivery/
│   │   ├── ReplyDeliveryService.java    ← 含重启恢复（H5）
│   │   └── ChannelRouterConfig.java     ← 线程池 bean 定义（H6）
│   ├── platform/
│   │   ├── feishu/
│   │   │   ├── FeishuChannelAdapter.java
│   │   │   ├── FeishuWebhookVerifier.java  ← 使用正确签名算法（H1）
│   │   │   ├── FeishuEventParser.java      ← 过滤 bot 自消息（M4）
│   │   │   └── FeishuClient.java
│   │   └── telegram/
│   │       ├── TelegramChannelAdapter.java
│   │       ├── TelegramWebhookVerifier.java ← null 校验（M3）
│   │       └── TelegramBotClient.java
│   └── web/
│       ├── ChannelWebhookController.java
│       └── ChannelConfigController.java     ← PATCH 语义（M5）
│
├── config/
│   └── ChannelSecurityConfig.java           ← Security 放行规则（M6）
│
├── entity/
│   ├── ChannelConfigEntity.java
│   ├── ChannelConversationEntity.java
│   ├── ChannelMessageDedupEntity.java       ← 幂等去重表（H3）
│   └── UserIdentityMappingEntity.java
│
└── repository/
    ├── ChannelConfigRepository.java
    ├── ChannelConversationRepository.java
    ├── ChannelMessageDedupRepository.java
    └── UserIdentityMappingRepository.java
```

### 1.2 依赖关系图

```
skillforge-core   (Skill SPI, AgentLoop)
      ↑
skillforge-skills (Skill 实现)
      ↑
skillforge-server (REST + JPA + WebSocket + channel/* 所有逻辑)
```

---

## 2. ChannelAdapter 接口

**M1 修复**：`verifyWebhook` 改为接收 `WebhookContext`（headers 快照 + rawBody），消除对 Servlet API 的依赖，使接口可在非 HTTP 场景复用。
**M2 修复**：`deliver` 返回 `DeliveryResult` 而非 `boolean`，携带 `retryAfterMs` 让上层精确等待。

```java
package com.skillforge.server.channel.spi;

import org.springframework.http.ResponseEntity;
import java.util.Optional;

/**
 * 消息平台适配器 SPI。
 *
 * <p>每个平台实现一个 {@code ChannelAdapter}，注册为 Spring Bean（{@code @Component}）。
 * {@link com.skillforge.server.channel.registry.ChannelAdapterRegistry} 以
 * {@link #platformId()} 为键维护全局注册表，由
 * {@link com.skillforge.server.channel.web.ChannelWebhookController} 按路径变量分发。
 */
public interface ChannelAdapter {

    /**
     * 平台唯一标识，与 URL 路径变量一一对应，如 "feishu"、"telegram"。
     * 不能包含 '/' 或空格，全部小写。
     */
    String platformId();

    /** 平台友好名称，用于 UI 展示，如 "飞书"、"Telegram"。*/
    String displayName();

    /**
     * 验证 webhook 请求的签名/token，防止伪造请求。
     *
     * <p>{@link WebhookContext} 包含所有请求头（快照 Map，不依赖 Servlet API）和原始请求体字节。
     * 签名必须基于 rawBody，不能用反序列化后的对象。验签失败应抛出
     * {@link WebhookVerificationException}。
     *
     * @param ctx     请求头快照 + 原始 body（M1：不再传入 HttpServletRequest）
     * @param config  该平台在数据库中的配置（已解密）
     * @throws WebhookVerificationException 签名/token 不匹配时抛出
     */
    void verifyWebhook(WebhookContext ctx, ChannelConfigDecrypted config)
            throws WebhookVerificationException;

    /**
     * 将平台原始 webhook body 解析为规范化 {@link ChannelMessage}。
     *
     * <p>如果 body 是平台的 URL verification challenge（如飞书的 url_verification），
     * 返回 {@link Optional#empty()}，controller 直接返回 challenge 响应。
     */
    Optional<ChannelMessage> parseIncoming(byte[] rawBody, ChannelConfigDecrypted config);

    /**
     * 当 body 是 challenge/verification 事件时，生成平台要求的响应体。
     * 仅在 {@link #parseIncoming} 返回 {@link Optional#empty()} 时调用。
     */
    ResponseEntity<?> handleVerificationChallenge(byte[] rawBody);

    /**
     * 将规范化回复投递回平台用户。
     *
     * <p>M2：返回 {@link DeliveryResult} 而非 boolean。
     * {@link DeliveryResult#retryAfterMs()} 告知上层实际应等待多久（来自平台 429 响应头），
     * 避免比平台要求的等待时间短（再次 429）或长（不必要的延迟）。
     *
     * @param reply   规范化出站回复
     * @param config  该平台配置（含 access token / bot token 等，已解密）
     * @return 投递结果，含成功标志、retryAfterMs、是否永久失败
     */
    DeliveryResult deliver(ChannelReply reply, ChannelConfigDecrypted config);

    /** 投递超时（毫秒）。默认 10 000 ms，各平台可按 SLA 覆写。*/
    default long deliveryTimeoutMs() { return 10_000L; }

    /** 最大重试次数（不含首次投递）。默认 3 次。*/
    default int maxRetries() { return 3; }
}
```

### 2.1 WebhookContext（M1）

```java
package com.skillforge.server.channel.spi;

import java.util.Map;

/**
 * Webhook 请求的轻量快照。替代 HttpServletRequest，消除 Servlet API 依赖。
 * Controller 在收到请求后立即构建，传给 ChannelAdapter#verifyWebhook。
 */
public record WebhookContext(
    /** 请求头快照（key 小写，值为首个匹配值）。*/
    Map<String, String> headers,
    /** 原始请求体字节，签名验证必须基于此字段。*/
    byte[] rawBody
) {
    /** 读取 header，大小写不敏感。*/
    public String header(String name) {
        return headers.get(name.toLowerCase());
    }
}
```

### 2.2 ChannelConfigDecrypted（M1 配套）

```java
/**
 * 传递给 ChannelAdapter 的已解密配置视图，只含 adapter 需要的字段。
 * ChannelConfigService 在服务层完成 AES-GCM 解密，adapter 只见明文，不接触 Entity。
 */
public record ChannelConfigDecrypted(
    Long id,
    String platform,
    /** 已解密的 webhook 签名 secret（飞书 encryptKey / Telegram secret_token）。*/
    String webhookSecret,
    /** 已解密的平台 API 凭证（JSON，各平台自行解析）。*/
    String credentialsJson,
    /** 非敏感的平台额外配置（JSON，明文）。*/
    String configJson,
    Long defaultAgentId
) {}
```

### 2.3 DeliveryResult（M2）

```java
package com.skillforge.server.channel.spi;

/**
 * ChannelAdapter#deliver 的返回值，携带平台侧的重试等待时间。
 */
public record DeliveryResult(
    boolean success,
    /**
     * 平台要求的重试等待时间（毫秒）。
     * success = true 时忽略；success = false 且 retryAfterMs > 0 时，
     * ReplyDeliveryService 以此值覆盖默认指数退避延迟。
     */
    long retryAfterMs,
    /**
     * true 表示永久失败（如 invalid bot token），不应重试。
     * false 表示暂时失败（超时、限流），可重试。
     */
    boolean permanent
) {
    public static DeliveryResult ok() {
        return new DeliveryResult(true, 0, false);
    }

    public static DeliveryResult retry(long retryAfterMs) {
        return new DeliveryResult(false, retryAfterMs, false);
    }

    public static DeliveryResult failed(String reason) {
        return new DeliveryResult(false, 0, true);
    }
}
```

### 2.4 辅助类型

```java
/** 签名验证失败时抛出，controller 捕获后返回 HTTP 401。*/
public class WebhookVerificationException extends RuntimeException {
    public WebhookVerificationException(String platform, String reason) {
        super("Webhook verification failed for [" + platform + "]: " + reason);
    }
}
```

---

## 3. 规范化数据模型

### 3.1 ChannelMessage（入站）

```java
package com.skillforge.server.channel.spi;

import java.time.Instant;
import java.util.Map;

/**
 * 平台无关的规范化入站消息。
 * ChannelAdapter 实现负责将平台原生格式转换为此结构。
 */
public record ChannelMessage(

    /** 平台标识，如 "feishu"、"telegram"。*/
    String platform,

    /**
     * 平台侧对话标识。
     * 飞书：open_chat_id。Telegram：chat.id（Long 转 String）。
     */
    String conversationId,

    /** 平台侧发送者标识（open_id / telegram user_id）。*/
    String platformUserId,

    /**
     * 平台侧消息 ID（幂等去重键）。
     * 写入 t_channel_message_dedup 做 DB 级唯一约束去重（H3）。
     */
    String platformMessageId,

    MessageType type,

    /**
     * 文本内容。type = TEXT/AT_BOT 时有效（AT_BOT 已去除 @ 提及）。
     * 其他类型为文件 URL 或描述。
     */
    String text,

    /** 附件 URL（图片、文件、语音等），type = TEXT 时为 null。*/
    String attachmentUrl,

    Instant timestamp,

    /**
     * 平台原始字段透传（飞书 receive_id、Telegram update_id 等）。
     * 不参与通用路由逻辑。
     */
    Map<String, Object> rawFields

) {
    public enum MessageType {
        TEXT,
        AT_BOT,
        IMAGE,
        FILE,
        VOICE,
        UNSUPPORTED
    }
}
```

### 3.2 ChannelReply（出站）

```java
package com.skillforge.server.channel.spi;

public record ChannelReply(
    String inboundMessageId,
    String platform,
    String conversationId,
    /** 回复文本（Markdown）。平台 adapter 负责转换为平台支持的富文本格式。*/
    String markdownText,
    boolean useRichFormat,
    /** null = 普通消息；非 null = 线程回复目标消息 ID。*/
    String replyToMessageId
) {}
```

---

## 4. Webhook 路由策略

### 4.1 统一入口

```
POST /api/channels/{platform}/webhook
```

- `{platform}` 对应 `ChannelAdapter#platformId()`
- URL 不鉴权（平台 push，无 JWT），改用**签名验证**
- Spring Security 放行路径：`/api/channels/*/webhook`（见 §4.5 M6）

### 4.2 Controller 实现

```java
@RestController
@RequestMapping("/api/channels")
public class ChannelWebhookController {

    private final ChannelAdapterRegistry registry;
    private final ChannelSessionRouter sessionRouter;
    private final ChannelConfigService configService;      // 负责解密 credentials
    private final ChannelMessageDedupRepository dedupRepo; // H3：幂等去重

    public ChannelWebhookController(
            ChannelAdapterRegistry registry,
            ChannelSessionRouter sessionRouter,
            ChannelConfigService configService,
            ChannelMessageDedupRepository dedupRepo) {
        this.registry = registry;
        this.sessionRouter = sessionRouter;
        this.configService = configService;
        this.dedupRepo = dedupRepo;
    }

    /**
     * 处理步骤：
     * 1. 找 adapter；2. 读取并解密 config；3. 构建 WebhookContext（M1）；
     * 4. 验签；5. parseIncoming（challenge 事件直接返回）；
     * 6. DB 级幂等去重（H3）；7. 异步路由；8. 立即返回 200。
     */
    @PostMapping(value = "/{platform}/webhook", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<?> handleWebhook(
            @PathVariable String platform,
            HttpServletRequest request,
            @RequestBody byte[] rawBody) {

        ChannelAdapter adapter = registry.get(platform)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Unknown platform: " + platform));

        ChannelConfigDecrypted config = configService.getDecryptedConfig(platform)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "Platform not configured: " + platform));

        // M1：构建 WebhookContext，不向 adapter 暴露 HttpServletRequest
        WebhookContext ctx = buildContext(request, rawBody);

        try {
            adapter.verifyWebhook(ctx, config);
        } catch (WebhookVerificationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "verification_failed"));
        }

        Optional<ChannelMessage> parsed = adapter.parseIncoming(rawBody, config);
        if (parsed.isEmpty()) {
            return adapter.handleVerificationChallenge(rawBody);
        }

        ChannelMessage msg = parsed.get();

        // H3：DB 级幂等去重（唯一约束，INSERT ON CONFLICT IGNORE）
        if (!dedupRepo.tryInsert(msg.platform(), msg.platformMessageId())) {
            return ResponseEntity.ok(Map.of("status", "duplicate_ignored"));
        }

        sessionRouter.routeAsync(msg, adapter, config);
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    private WebhookContext buildContext(HttpServletRequest request, byte[] rawBody) {
        Map<String, String> headers = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames())
                .forEach(name -> headers.put(name.toLowerCase(), request.getHeader(name)));
        return new WebhookContext(Collections.unmodifiableMap(headers), rawBody);
    }
}
```

### 4.3 ChannelAdapterRegistry

```java
@Component
public class ChannelAdapterRegistry {

    private final Map<String, ChannelAdapter> adapters;

    public ChannelAdapterRegistry(List<ChannelAdapter> adapterList) {
        this.adapters = adapterList.stream()
                .collect(Collectors.toMap(ChannelAdapter::platformId, Function.identity()));
    }

    public Optional<ChannelAdapter> get(String platformId) {
        return Optional.ofNullable(adapters.get(platformId));
    }

    public Set<String> registeredPlatforms() {
        return Collections.unmodifiableSet(adapters.keySet());
    }
}
```

### 4.4 签名验证规范（修订）

| 平台 | 验证方式 | 实现位置 |
|------|---------|---------|
| 飞书 | SHA-256（非 HMAC）：`SHA256(timestamp + "\n" + nonce + "\n" + encryptKey + "\n" + body_string)` | `FeishuWebhookVerifier`（§8.2） |
| Telegram | 比对 `X-Telegram-Bot-Api-Secret-Token` header | `TelegramWebhookVerifier`（§9.2） |

**关键约束**：rawBody 在反序列化前捕获（`@RequestBody byte[]` 保证）；飞书 `encryptKey` 与 `verificationToken` 是两个独立字段，前者用于签名验证，后者用于 URL 验证 challenge。

### 4.5 Spring Security 放行规则（M6）

```java
@Configuration
@EnableWebSecurity
public class ChannelSecurityConfig {

    @Bean
    @Order(1) // 优先级高于主 SecurityFilterChain
    public SecurityFilterChain channelWebhookSecurityFilterChain(HttpSecurity http)
            throws Exception {
        http
            .securityMatcher("/api/channels/*/webhook")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable())   // webhook 走签名验证，不用 CSRF token
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
```

### 4.6 channelRouterExecutor 线程池（H6）

```java
@Configuration
@EnableAsync
public class ChannelRouterConfig {

    /**
     * 供 ChannelSessionRouter#routeAsync 使用的线程池。
     * CallerRunsPolicy：线程池满时，webhook 请求线程直接执行路由（反压，不丢消息）。
     * 注意：反压会使 webhook 响应超过 5s，需监控 queue 水位，必要时调大 queueCapacity。
     */
    @Bean("channelRouterExecutor")
    public Executor channelRouterExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(16);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("ch-router-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }
}
```

---

## 5. DB Schema & V17 Migration

> V16 是当前实际最新 migration（`V16__skill_evolution_run.sql`）。V17 是本次新增。

### 5.1 ChannelConfigEntity

```java
@Entity
@Table(name = "t_channel_config",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_channel_config_platform",
                columnNames = {"platform"}))
@EntityListeners(AuditingEntityListener.class)
public class ChannelConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String platform;

    @Column(length = 128)
    private String displayName;

    @Column(nullable = false)
    private boolean active = true;

    /**
     * Webhook 签名 secret，AES-GCM 加密存储。
     * 飞书存 encryptKey；Telegram 存 secret_token。
     * ChannelConfigService 解密后以 ChannelConfigDecrypted 传给 adapter。
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String webhookSecret;

    /**
     * 平台 API 凭证（JSON），AES-GCM 加密存储。
     * 飞书：{"app_id":"...","app_secret":"...","verification_token":"...","encrypt_key":"..."}
     * Telegram：{"bot_token":"..."}
     * 注意：飞书的 encrypt_key 和 verification_token 是分开的两个字段。
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String credentialsJson;

    /** 非敏感额外配置（JSON），明文存储。*/
    @Column(columnDefinition = "TEXT")
    private String configJson;

    @Column(nullable = false)
    private Long defaultAgentId;

    // MEDIUM-5 修复：使用 Instant（对齐项目 Known Footgun 规范，LocalDateTime 不带时区）
    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
```

### 5.2 ChannelConversationEntity（H4 修复：含并发安全注释）

```java
/**
 * 平台对话 → SkillForge Session 映射。
 *
 * H4 修复：Migration 中对 (platform, conversation_id) WHERE closed_at IS NULL
 * 建部分唯一索引，防止并发创建重复活跃映射。resolveSession 用 SELECT FOR UPDATE。
 * MEDIUM-5 修复：时间字段改用 Instant。
 */
@Entity
@Table(name = "t_channel_conversation",
        indexes = {
            @Index(name = "idx_ch_conv_platform_conv",
                    columnList = "platform, conversation_id"),
            @Index(name = "idx_ch_conv_session_id",
                    columnList = "session_id")
        })
@EntityListeners(AuditingEntityListener.class)
public class ChannelConversationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String platform;

    @Column(name = "conversation_id", nullable = false, length = 256)
    private String conversationId;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(nullable = false)
    private Long channelConfigId;

    @CreatedDate
    private Instant createdAt;

    /** null = 活跃；非 null = 已关闭。*/
    private Instant closedAt;
}
```

### 5.3 ChannelMessageDedupEntity（H3 新增）

```java
/**
 * 消息幂等去重表。
 * platformMessageId 为主键，INSERT 成功表示首次处理，ON CONFLICT 表示重复。
 * 比 lastPlatformMessageId 方案更可靠：覆盖任意历史消息的重推，不仅最后一条。
 */
@Entity
@Table(name = "t_channel_message_dedup")
public class ChannelMessageDedupEntity {

    @Id
    @Column(name = "platform_message_id", length = 256)
    private String platformMessageId;

    @Column(nullable = false, length = 64)
    private String platform;

    @Column(nullable = false)
    private Instant createdAt;
}
```

### 5.4 UserIdentityMappingEntity

（结构同 v1.0，无变更）

### 5.5 V17 Flyway Migration 草稿（含全部修复）

```sql
-- V17__channel_gateway.sql

-- 1. 平台配置
CREATE TABLE t_channel_config (
    id               BIGSERIAL     NOT NULL PRIMARY KEY,
    platform         VARCHAR(64)   NOT NULL,
    display_name     VARCHAR(128),
    active           BOOLEAN       NOT NULL DEFAULT TRUE,
    webhook_secret   TEXT          NOT NULL,
    credentials_json TEXT          NOT NULL,
    config_json      TEXT,
    default_agent_id BIGINT        NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_channel_config_platform UNIQUE (platform)
);

-- 2. 对话 → Session 映射
CREATE TABLE t_channel_conversation (
    id                BIGSERIAL    NOT NULL PRIMARY KEY,
    platform          VARCHAR(64)  NOT NULL,
    conversation_id   VARCHAR(256) NOT NULL,
    session_id        VARCHAR(36)  NOT NULL,
    channel_config_id BIGINT       NOT NULL
        REFERENCES t_channel_config(id) ON DELETE RESTRICT,  -- L2：显式声明
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    closed_at         TIMESTAMPTZ
);
CREATE INDEX idx_ch_conv_platform_conv ON t_channel_conversation (platform, conversation_id);
CREATE INDEX idx_ch_conv_session_id    ON t_channel_conversation (session_id);
-- H4：部分唯一索引防并发重复建立活跃映射
CREATE UNIQUE INDEX uq_ch_conv_active
    ON t_channel_conversation (platform, conversation_id)
    WHERE closed_at IS NULL;

-- 3. 消息幂等去重（H3）
CREATE TABLE t_channel_message_dedup (
    platform_message_id VARCHAR(256) NOT NULL PRIMARY KEY,
    platform            VARCHAR(64)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
-- 可定期清理 30 天前的记录（保留窗口足够覆盖平台重试 TTL）
CREATE INDEX idx_ch_dedup_created ON t_channel_message_dedup (created_at);

-- 4. 平台用户身份映射
CREATE TABLE t_user_identity_mapping (
    id                    BIGSERIAL    NOT NULL PRIMARY KEY,
    platform              VARCHAR(64)  NOT NULL,
    platform_user_id      VARCHAR(256) NOT NULL,
    skillforge_user_id    BIGINT       NOT NULL,
    platform_display_name VARCHAR(256),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_identity_platform_user UNIQUE (platform, platform_user_id)
);
CREATE INDEX idx_identity_skillforge_user ON t_user_identity_mapping (skillforge_user_id);

-- 5. 投递记录（重试追踪）
CREATE TABLE t_channel_delivery (
    id                 VARCHAR(36)  NOT NULL PRIMARY KEY,
    platform           VARCHAR(64)  NOT NULL,
    conversation_id    VARCHAR(256) NOT NULL,
    inbound_message_id VARCHAR(256) NOT NULL,
    session_id         VARCHAR(36),
    status             VARCHAR(32)  NOT NULL DEFAULT 'PENDING'
        CONSTRAINT chk_ch_delivery_status
            CHECK (status IN ('PENDING', 'IN_FLIGHT', 'RETRY', 'DELIVERED', 'FAILED')),  -- L1 + MEDIUM-11
    retry_count        INT          NOT NULL DEFAULT 0,
    last_error         TEXT,
    scheduled_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    delivered_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ch_delivery_status_sched ON t_channel_delivery (status, scheduled_at)
    WHERE status IN ('PENDING', 'RETRY');
-- 唯一约束：同一入站消息只有一条投递记录
CREATE UNIQUE INDEX uq_ch_delivery_inbound ON t_channel_delivery (inbound_message_id);
```

**Migration 安全要点**：
- 全部 `CREATE TABLE`/`CREATE INDEX` 无锁（无 `ALTER TABLE ... ADD COLUMN NOT NULL`）
- `uq_ch_conv_active` 为部分唯一索引（`WHERE closed_at IS NULL`），只对活跃行加约束，历史记录不受影响
- `ON DELETE RESTRICT` 显式声明（L2），删 channel config 前需先删 conversations，UI/API 层给出友好提示

---

## 6. Session 路由

### 6.1 ChannelMessageDedupRepository（H3）

```java
public interface ChannelMessageDedupRepository extends JpaRepository<ChannelMessageDedupEntity, String> {

    /**
     * 尝试插入去重记录。返回 true 表示首次处理；false 表示重复（ON CONFLICT）。
     * 使用原生 SQL 的 INSERT ON CONFLICT DO NOTHING 保证原子性。
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO t_channel_message_dedup (platform_message_id, platform, created_at)
        VALUES (:messageId, :platform, NOW())
        ON CONFLICT (platform_message_id) DO NOTHING
        """, nativeQuery = true)
    int insertIgnore(@Param("platform") String platform,
                     @Param("messageId") String messageId);

    default boolean tryInsert(String platform, String messageId) {
        return insertIgnore(platform, messageId) > 0;
    }
}
```

### 6.2 ChannelSessionRouter 核心逻辑（H4 修复）

```java
@Service
public class ChannelSessionRouter {

    private final ChannelConversationRepository conversationRepo;
    private final UserIdentityMappingRepository identityRepo;
    private final SessionService sessionService;
    private final ReplyDeliveryService deliveryService;
    // MEDIUM-13 修复：补全缺失的 ChatService 和 ChatWebSocketHandler 依赖
    private final ChatService chatService;
    private final ChatWebSocketHandler chatWebSocketHandler;

    public ChannelSessionRouter(
            ChannelConversationRepository conversationRepo,
            UserIdentityMappingRepository identityRepo,
            SessionService sessionService,
            ReplyDeliveryService deliveryService,
            ChatService chatService,
            ChatWebSocketHandler chatWebSocketHandler) {
        this.conversationRepo = conversationRepo;
        this.identityRepo = identityRepo;
        this.sessionService = sessionService;
        this.deliveryService = deliveryService;
        this.chatService = chatService;
        this.chatWebSocketHandler = chatWebSocketHandler;
    }

    /**
     * H6：引用已在 ChannelRouterConfig 中定义的线程池 bean。
     * MEDIUM-10：调用现有 chatService.chatAsync()，无 callback，回复投递由事件驱动。
     * MEDIUM-14：registerChannelTurn 在每轮 chatAsync 前调用（而非只在建 session 时），
     *   覆盖新建 session 和复用 session 两种场景，确保每轮文本累积器和 platformMessageId 都更新。
     * B2-H2：注册当轮 platformMessageId，事件发布时携带每轮唯一 ID，避免 unique 约束冲突。
     */
    @Async("channelRouterExecutor")
    public void routeAsync(ChannelMessage msg, ChannelAdapter adapter,
                           ChannelConfigDecrypted config) {
        try {
            SessionRouteResult route = resolveSession(msg, config);
            Long userId = resolveUser(msg);

            // MEDIUM-14 + B2-H2：注册当轮上下文，每轮（新建或复用 session）都调用，
            // 确保 ChatWebSocketHandler 持有正确的 platformMessageId 和新的文本累积器
            chatWebSocketHandler.registerChannelTurn(
                    route.sessionId(), msg.platformMessageId());

            // 调用现有 ChatService 方法，不修改其签名
            chatService.chatAsync(route.sessionId(), msg.text(), userId);
        } catch (Exception e) {
            log.error("Channel routing failed [{}] msg [{}]",
                    msg.platform(), msg.platformMessageId(), e);
        }
    }

    /**
     * 查找活跃 conversation 映射或创建新 Session。
     *
     * H4 修复：
     * 1. findActiveForUpdate 使用 SELECT FOR UPDATE，防止并发事务同时通过"不存在"检查
     * 2. 数据库层部分唯一索引 uq_ch_conv_active 作为最终防线：若两个事务同时 INSERT，
     *    后者抛 DataIntegrityViolationException，调用方应视为"已有活跃 session"重试查询
     */
    @Transactional
    public SessionRouteResult resolveSession(ChannelMessage msg, ChannelConfigDecrypted config) {
        Optional<ChannelConversationEntity> existing =
                conversationRepo.findActiveForUpdate(msg.platform(), msg.conversationId());

        if (existing.isPresent()) {
            String sessionId = existing.get().getSessionId();
            if (sessionService.isChannelSessionActive(sessionId)) {  // LOW-15：与 §7.2 方法名对齐
                return new SessionRouteResult(sessionId, false);
            }
            existing.get().setClosedAt(Instant.now());
        }

        String newSessionId = sessionService.createChannelSession(
                config.defaultAgentId(),
                buildSessionTitle(msg));

        ChannelConversationEntity newConv = new ChannelConversationEntity();
        newConv.setPlatform(msg.platform());
        newConv.setConversationId(msg.conversationId());
        newConv.setSessionId(newSessionId);
        newConv.setChannelConfigId(config.id());
        conversationRepo.save(newConv);

        return new SessionRouteResult(newSessionId, true);
    }

}
```

### 6.3 ChannelConversationRepository（H4 配套）

```java
public interface ChannelConversationRepository
        extends JpaRepository<ChannelConversationEntity, Long> {

    /**
     * H4：SELECT FOR UPDATE 防止并发事务同时走"不存在 → 新建"路径。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ChannelConversationEntity c " +
           "WHERE c.platform = :platform AND c.conversationId = :conversationId " +
           "AND c.closedAt IS NULL")
    Optional<ChannelConversationEntity> findActiveForUpdate(
            @Param("platform") String platform,
            @Param("conversationId") String conversationId);
}
```

### 6.4 Session 复用策略摘要

| 场景 | 行为 |
|------|------|
| 同一 conversationId，session active | 复用 session，追加消息 |
| 同一 conversationId，session archived | 关闭旧 conv，新建 session |
| 全新 conversationId | 新建 session + conv |
| 重复 platformMessageId | DB 唯一约束拒绝，返回 accepted |
| 并发两条消息，竞争创建 session | SELECT FOR UPDATE 串行化；最终唯一索引兜底 |

---

## 7. SessionService 集成契约（v2.1 重设计）

### 7.1 设计原则（v2.1 变更）

**不使用 `Consumer<String> replyCallback` 闭包**，原因：
1. 闭包捕获 `adapter`/`config` Bean 引用，AgentLoop 可运行 10-30 分钟
2. Server 重启时闭包消失 → 回复永久丢失
3. 需要修改 `SessionService`/`ChatService` 的核心方法签名

**改为 Spring ApplicationEvent 异步解耦**：
- `ChannelSessionRouter` 调用**现有** `ChatService.chatAsync()`（无需修改）
- AgentLoop 完成后发布 `ChannelSessionOutputEvent`
- `ChannelReplyEventListener` 订阅事件，从 DB 读取最终回复文本，投递到平台

### 7.2 仅需在 SessionService 新增两个轻量方法

```java
// SessionService 中新增（不修改现有 chatAsync/sendMessage 方法）：

/**
 * 创建专用于外部渠道的 Session。
 * 与 UI 发起的 Session 的唯一区别是 title 携带 channel 来源标记，方便过滤。
 */
String createChannelSession(Long agentId, String title);

/**
 * 检查 session 是否活跃可接受新消息
 * （status = 'active' 且 runtimeStatus IN ('idle', 'waiting_user')）。
 */
boolean isChannelSessionActive(String sessionId);
```

### 7.3 ChannelSessionRouter 调用现有 ChatService（MEDIUM-10 已与 §6.2 对齐）

```java
@Async("channelRouterExecutor")
public void routeAsync(ChannelMessage msg, ChannelAdapter adapter,
                       ChannelConfigDecrypted config) {
    try {
        SessionRouteResult route = resolveSession(msg, config);
        Long userId = resolveUser(msg);

        // 调用现有 ChatService 方法，无需 callback 参数。
        // AgentLoop 完成后由 ChatWebSocketHandler.assistantStreamEnd() 发布
        // ChannelSessionOutputEvent，ChannelReplyEventListener 负责投递。
        chatService.chatAsync(route.sessionId(), msg.text(), userId);
    } catch (Exception e) {
        log.error("Channel routing failed [{}] msg [{}]",
                msg.platform(), msg.platformMessageId(), e);
    }
}
```

### 7.4 ChannelSessionOutputEvent + Listener（核心设计，LOW-12 修复）

**LOW-12 修复**：事件携带已聚合的 `replyText`（流式传输期间在内存中累积完毕），Listener 无需再从 DB 读取 `messagesJson`，消除持久化竞争窗口。

```java
/**
 * B2-H2 修复：新增 platformMessageId 字段，携带当前轮次的入站消息 ID。
 * 每轮对话的 platformMessageId 不同，确保 t_channel_delivery.inbound_message_id 唯一。
 * 若使用 sessionId 作为 inboundMessageId，多轮对话第 2 条起触发 uq_ch_delivery_inbound
 * 唯一约束冲突，回复静默丢失。
 */
public record ChannelSessionOutputEvent(
    String sessionId,
    String platformMessageId,  // B2-H2：每轮入站消息 ID（唯一），非 sessionId
    String replyText           // LOW-12：已聚合完毕的完整回复文本
) {}

// 发布点：ChatWebSocketHandler（实现 ChatEventBroadcaster）
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler
        implements ChatEventBroadcaster {

    /**
     * B2-H2 修复：存储 sessionId → ChannelTurnContext（platformMessageId + 文本累积器）。
     * 每轮消息到来时通过 registerChannelTurn() 更新 platformMessageId，
     * assistantStreamEnd 取出后发布至事件，确保每轮回复使用当轮唯一的 platformMessageId。
     */
    private final Map<String, ChannelTurnContext> channelContexts = new ConcurrentHashMap<>();

    /** 每轮对话的上下文：入站消息 ID（唯一）+ 流式文本累积器。*/
    record ChannelTurnContext(String platformMessageId, StringBuilder text) {}

    @Override
    public void assistantDelta(String sessionId, String text) {
        broadcast(sessionId, Map.of("type", "assistant_delta", "text", text));
        channelContexts.computeIfPresent(sessionId,
                (k, ctx) -> { ctx.text().append(text); return ctx; });
    }

    @Override
    public void assistantStreamEnd(String sessionId) {
        broadcast(sessionId, Map.of("type", "assistant_stream_end"));

        ChannelTurnContext ctx = channelContexts.remove(sessionId);
        if (ctx != null) {
            eventPublisher.publishEvent(new ChannelSessionOutputEvent(
                    sessionId,
                    ctx.platformMessageId(),  // B2-H2：当轮唯一入站消息 ID
                    ctx.text().toString()));   // LOW-12：已聚合完整文本
        }
    }

    /**
     * 每轮消息开始时由 ChannelSessionRouter.routeAsync() 调用，注册当轮上下文。
     * 同一 session 多轮复用时，每轮覆盖前一轮的 platformMessageId，不会累积旧文本。
     *
     * @param sessionId         AgentLoop 的 session
     * @param platformMessageId 当轮入站消息的平台 ID（飞书 message_id / Telegram message_id）
     */
    public void registerChannelTurn(String sessionId, String platformMessageId) {
        channelContexts.put(sessionId, new ChannelTurnContext(platformMessageId,
                new StringBuilder()));
    }
}

// 事件监听器
@Component
public class ChannelReplyEventListener {

    private final ChannelConversationRepository conversationRepo;
    private final ReplyDeliveryService deliveryService;
    private final ChannelAdapterRegistry adapterRegistry;
    private final ChannelConfigService configService;

    @Async("channelRouterExecutor")
    @EventListener
    public void onSessionOutput(ChannelSessionOutputEvent event) {
        if (event.replyText() == null || event.replyText().isBlank()) {
            log.debug("Session [{}] has empty reply, skipping delivery", event.sessionId());
            return;
        }

        // 查找对应的 channel conversation
        Optional<ChannelConversationEntity> conv =
                conversationRepo.findBySessionIdAndClosedAtIsNull(event.sessionId());
        if (conv.isEmpty()) {
            return; // 非 channel session（普通 UI 对话），忽略
        }

        ChannelAdapter adapter = adapterRegistry.get(conv.get().getPlatform())
                .orElseThrow();
        ChannelConfigDecrypted config = configService
                .getDecryptedConfig(conv.get().getPlatform())
                .orElseThrow();

        ChannelReply reply = new ChannelReply(
                event.platformMessageId(),  // B2-H2：每轮唯一 ID，不再用 sessionId
                conv.get().getPlatform(),
                conv.get().getConversationId(),
                event.replyText(),
                true,
                null
        );

        deliveryService.deliver(reply, adapter, config);
    }
}
```

### 7.5 线程安全约束

- `ChannelReplyEventListener.onSessionOutput()` 标注 `@Async`，在 `channelRouterExecutor` 线程池执行，不阻塞 WebSocket 广播
- `ReplyDeliveryService.deliver()` 及所有 HTTP client 必须线程安全
- `FeishuClient.getAccessToken()` 加 `synchronized`（见 §8.6）

---

## 8. 飞书实现要点

### 8.1 事件订阅

飞书机器人通过**事件订阅**接收消息，有两个独立的安全字段：
- **Encrypt Key**：用于消息加密和 signature 验证（`X-Lark-Signature` 头）
- **Verification Token**：用于 URL 验证 challenge 响应（`{"challenge":"..."}` 的明文 token）

两者存储在 `credentialsJson` 的不同 key 中，不得混用。

### 8.2 签名验证（H1 修复）

**旧算法（错误）**：`HMAC-SHA256(key=verificationToken, data=timestamp+nonce+verificationToken+body)`

**正确算法**（飞书 Event Callback v2.0）：`SHA256(timestamp + "\n" + nonce + "\n" + encryptKey + "\n" + body_string)`，使用普通 SHA-256（非 HMAC），key 是 `encryptKey`，不是 `verificationToken`。

```java
public class FeishuWebhookVerifier {

    /**
     * H1：修正签名算法。
     * 飞书签名 = SHA256(timestamp + "\n" + nonce + "\n" + encryptKey + "\n" + body_string)
     * 使用普通 SHA-256，非 HMAC；encryptKey ≠ verificationToken。
     */
    public void verify(WebhookContext ctx, String encryptKey) {
        String timestamp = ctx.header("x-lark-request-timestamp");
        String nonce     = ctx.header("x-lark-request-nonce");
        String signature = ctx.header("x-lark-signature");

        if (timestamp == null || nonce == null || signature == null) {
            throw new WebhookVerificationException("feishu", "missing required headers");
        }

        // 时间窗口：防重放攻击
        long ts = Long.parseLong(timestamp);
        if (Math.abs(Instant.now().getEpochSecond() - ts) > 300) {
            throw new WebhookVerificationException("feishu", "timestamp out of window");
        }

        String bodyString = new String(ctx.rawBody(), StandardCharsets.UTF_8);
        String toSign = timestamp + "\n" + nonce + "\n" + encryptKey + "\n" + bodyString;

        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] expected = sha256.digest(toSign.getBytes(StandardCharsets.UTF_8));
            byte[] actual   = Hex.decodeHex(signature);

            if (!MessageDigest.isEqual(expected, actual)) {
                throw new WebhookVerificationException("feishu", "SHA-256 mismatch");
            }
        } catch (NoSuchAlgorithmException | DecoderException e) {
            throw new WebhookVerificationException("feishu", "crypto error: " + e.getMessage());
        }
    }
}
```

### 8.3 飞书 Bot 自消息过滤（M4）

```java
// FeishuEventParser.parseMessage() 中：
/**
 * M4：过滤 bot 自身发送的消息，避免 Agent 处理自己的回复产生无限循环。
 * 飞书事件中 sender.sender_type == "app" 表示消息由 bot 发送。
 */
private boolean isBotSelfMessage(JsonNode event) {
    JsonNode senderType = event.path("sender").path("sender_type");
    return "app".equals(senderType.asText());
}

public Optional<ChannelMessage> parse(byte[] rawBody, ChannelConfigDecrypted config) {
    // ...解析 JSON...
    JsonNode event = root.path("event");
    if (isBotSelfMessage(event)) {
        log.debug("Feishu: ignoring bot self-message");
        return Optional.empty();  // controller 将返回 200 accepted
    }
    // 继续正常解析...
}
```

### 8.4 消息内容解析

```
飞书消息类型 → ChannelMessage.MessageType：
- msg_type = "text"  → TEXT（p2p）/ AT_BOT（群消息含 @ bot）
- msg_type = "image" → IMAGE
- msg_type = "file"  → FILE
- 其他               → UNSUPPORTED（记录 debug 日志，返回 200）
```

sender_type = "app" 的事件在到达此逻辑前已被 §8.3 过滤掉。
群消息中净文本提取：去除 `<at user_id="..."></at>` 标签。

### 8.5 飞书回复格式（Interactive Card）

```json
{
  "msg_type": "interactive",
  "card": {
    "elements": [
      { "tag": "markdown", "content": "{{markdownText}}" }
    ],
    "header": { "title": { "tag": "plain_text", "content": "SkillForge" } }
  }
}
```

### 8.6 Access Token 刷新（M7 修复）

```java
@Component
public class FeishuClient {

    private final AtomicReference<String> cachedToken = new AtomicReference<>();
    private volatile Instant tokenExpiry = Instant.EPOCH;

    /**
     * M7：synchronized 防止并发刷新。
     * 两个线程同时发现 token 过期时，只有一个线程执行 HTTP 刷新，
     * 另一个线程等待后直接读取已刷新的 token。
     */
    public synchronized String getAccessToken(ChannelConfigDecrypted config) {
        if (Instant.now().isBefore(tokenExpiry.minusSeconds(60))) {
            return cachedToken.get();
        }
        String newToken = refreshAccessToken(config);
        cachedToken.set(newToken);
        tokenExpiry = Instant.now().plusSeconds(7200);  // 飞书 token 有效期 2h
        return newToken;
    }

    /**
     * B2-M1 修复：@Scheduled 方法无法接收参数，原空方法无实际效果，已删除。
     * 懒刷新策略足够：getAccessToken() 内部检查 tokenExpiry.minusSeconds(60)，
     * 在 token 到期前 60s 就触发刷新，覆盖正常调用频率下的预热窗口。
     * 若确实需要主动预热，应注入 ChannelConfigService 从 DB 查 config 后调用：
     *   configService.getAllActiveConfigs("feishu").forEach(this::getAccessToken);
     * 当前 P2 场景不要求，留作 known limitation 记录。
     */
}
```

---

## 9. Telegram 实现要点

### 9.1 Webhook 模式（优先）

选择 **Bot API Webhook**（`setWebhook`），不使用 `getUpdates` 轮询。
注册 webhook 时设置 `secret_token`（随机 UUID），存入 `ChannelConfigEntity.webhookSecret`。

### 9.2 签名验证（M3 修复）

```java
public class TelegramWebhookVerifier {

    public void verify(WebhookContext ctx, String secretToken) {
        String headerToken = ctx.header("x-telegram-bot-api-secret-token");

        // M3：null 校验，避免调用 getBytes() 时 NPE
        if (headerToken == null) {
            throw new WebhookVerificationException("telegram",
                    "missing X-Telegram-Bot-Api-Secret-Token header");
        }

        if (!MessageDigest.isEqual(
                secretToken.getBytes(StandardCharsets.UTF_8),
                headerToken.getBytes(StandardCharsets.UTF_8))) {
            throw new WebhookVerificationException("telegram", "secret_token mismatch");
        }
    }
}
```

### 9.3 Update 解析

```
Telegram Update 类型 → ChannelMessage：
- update.message.text     → TEXT（私聊）/ AT_BOT（群内含 /cmd@bot_name 或 entities.type=mention）
- update.message.photo    → IMAGE
- update.message.document → FILE
- update.message.voice    → VOICE
- callback_query          → UNSUPPORTED（暂不处理）
- inline_query            → UNSUPPORTED（暂不处理）
```

`conversationId` = `update.message.chat.id`（Long → String）。
`platformUserId` = `update.message.from.id`（Long → String）。

### 9.4 回复（L3 修复）

```java
private List<String> splitMessage(String text) {
    // L3：按 Unicode 代码点数（而非 Java String.length()）切割，
    // 避免在 emoji / surrogate pair 中间截断
    List<String> parts = new ArrayList<>();
    int[] codePoints = text.codePoints().toArray();
    int limit = 4096;
    int start = 0;
    while (start < codePoints.length) {
        int end = Math.min(start + limit, codePoints.length);
        parts.add(new String(codePoints, start, end - start));
        start = end;
    }
    return parts;
}
```

使用 Bot API `sendMessage`（`parse_mode = HTML`）：
- Markdown → Telegram HTML：`**bold**` → `<b>bold</b>`，`` `code` `` → `<code>code</code>`
- `reply_to_message_id` 设置线程回复

### 9.5 群组 vs 私聊

| 场景 | 处理 |
|------|------|
| 私聊 | 所有 text 消息处理 |
| 群组 | 仅处理 @ 机器人的消息（`entities.type = mention`，mention 对象为 bot）|

---

## 10. 前端设计

### 10.1 新增页面 & 组件

```
skillforge-dashboard/src/
├── pages/
│   └── Channels.tsx                  ← 消息渠道管理主页面
└── components/channels/
    ├── ChannelList.tsx               ← 已配置渠道列表（Ant Design Table）
    ├── ChannelConfigDrawer.tsx       ← 新建/编辑渠道（Drawer + Form）
    ├── ChannelStatusBadge.tsx        ← 在线/离线状态指示
    ├── ChannelConversationList.tsx   ← 渠道下的对话列表（带 session 跳转）
    └── DeliveryRetryPanel.tsx        ← 失败投递列表 + 手动重试
```

### 10.2 API 端点

```
GET    /api/channel-configs            → 列表（不含敏感字段）
POST   /api/channel-configs            → 新建
PATCH  /api/channel-configs/{id}       → 部分更新（M5：null 字段 = 不修改）
DELETE /api/channel-configs/{id}       → 禁用（soft delete，不删 conversations）
GET    /api/channel-configs/{id}/test  → 连通性测试

GET    /api/channel-conversations      → 对话列表（分页）
GET    /api/channel-deliveries         → 投递记录（status 过滤）
POST   /api/channel-deliveries/{id}/retry → 手动重试
```

### 10.3 凭证更新语义（M5）

使用 **PATCH** 语义：请求体中 `credentials` 字段为 `null` 或缺失 = "不修改"。
服务端只更新请求体中明确传入非 null 的字段，防止误覆盖未发送给前端的密文。

```typescript
// PATCH body 示例：仅更新 displayName，不修改 credentials
{ "displayName": "飞书测试环境" }

// PATCH body 示例：更新 credentials（用户重新填写后才传）
{ "credentials": { "app_id": "...", "app_secret": "..." } }
```

### 10.4 类型定义

```typescript
export interface ChannelConfig {
  id: number;
  platform: string;
  displayName: string;
  active: boolean;
  defaultAgentId: number;
  createdAt: string;
  updatedAt: string;
  // webhookSecret / credentialsJson 不下发到前端
}

export interface ChannelConversation {
  id: number;
  platform: string;
  conversationId: string;
  sessionId: string;
  createdAt: string;
  closedAt: string | null;
}

export interface ChannelDelivery {
  id: string;
  platform: string;
  conversationId: string;
  inboundMessageId: string;
  status: 'PENDING' | 'DELIVERED' | 'RETRY' | 'FAILED';
  retryCount: number;
  lastError: string | null;
  scheduledAt: string;
  deliveredAt: string | null;
}
```

---

## 11. 错误处理 & 重试

### 11.1 ReplyDeliveryService（v2.2 修复：3 段事务，网络 IO 在事务外）

**v2.2 MEDIUM-11 修复**：`pollAndRetry()` 去掉 `@Transactional`，拆分为 3 个短事务，DB 连接在 HTTP 调用期间释放，消除连接池耗尽风险。

```java
@Service
public class ReplyDeliveryService {

    private final ChannelDeliveryRepository deliveryRepo;
    private final ChannelAdapterRegistry adapterRegistry;
    private final ChannelConfigService configService;

    /**
     * 首次投递触发点（由 ChannelReplyEventListener 调用）。
     * 写入 DB 记录后立即尝试一次（事务外 HTTP 调用），失败则由 pollAndRetry() 接管。
     */
    public void deliver(ChannelReply reply, ChannelAdapter adapter,
                        ChannelConfigDecrypted config) {
        // ① 短事务：写入投递记录
        String deliveryId = persistRecord(reply);

        // ② 事务外：HTTP 调用
        DeliveryResult result = adapter.deliver(reply, config);

        // ③ 短事务：更新状态
        applyResult(deliveryId, result, 0, adapter.maxRetries());
    }

    @Transactional
    protected String persistRecord(ChannelReply reply) {
        String deliveryId = UUID.randomUUID().toString();
        deliveryRepo.save(buildRecord(deliveryId, reply));
        return deliveryId;
    }

    /**
     * MEDIUM-11 修复：3 段事务模式。
     *
     * 每 30s 扫描一次到期的 PENDING/RETRY 记录：
     * ① 短事务 claimBatch()：标记 IN_FLIGHT，获取 ID 列表后立即提交，释放 DB 连接
     * ② 事务外：逐条执行 HTTP 投递（持续数秒到数十秒，不占用连接）
     * ③ 短事务 updateStatus()：写入最终状态
     *
     * 重启后自动恢复：IN_FLIGHT 超过 2 分钟（delivered_at IS NULL）视为孤儿，
     * 重置为 PENDING 等待下一轮 poll。
     */
    @Scheduled(fixedDelay = 30_000)
    public void pollAndRetry() {
        // ① 短事务：claim batch，提交后 DB 连接立即归还连接池
        List<String> claimed = claimBatch();
        if (claimed.isEmpty()) return;

        // ② 事务外：执行 HTTP 调用
        for (String id : claimed) {
            try {
                ChannelDeliveryEntity record = deliveryRepo.findById(id).orElseThrow();
                ChannelAdapter adapter = adapterRegistry.get(record.getPlatform())
                        .orElseThrow();
                ChannelConfigDecrypted config = configService
                        .getDecryptedConfig(record.getPlatform())
                        .orElseThrow();

                DeliveryResult result = adapter.deliver(
                        buildReplyFromRecord(record), config);

                // ③ 短事务：写最终状态
                applyResult(id, result, record.getRetryCount(), adapter.maxRetries());
            } catch (Exception e) {
                log.error("Retry failed for delivery [{}]", id, e);
                markFailedTx(id, e.getMessage());
            }
        }
    }

    /** ① 短事务：原子 UPDATE ... RETURNING，标记为 IN_FLIGHT。*/
    @Transactional
    protected List<String> claimBatch() {
        return deliveryRepo.claimBatch(Instant.now(), 50);
        // SQL: UPDATE t_channel_delivery
        //      SET status = 'IN_FLIGHT', scheduled_at = NOW()
        //      WHERE id IN (
        //          SELECT id FROM t_channel_delivery
        //          WHERE status IN ('PENDING','RETRY') AND scheduled_at <= :now
        //          LIMIT :batchSize FOR UPDATE SKIP LOCKED
        //      )
        //      RETURNING id
    }

    /** ③ 短事务：更新最终状态。*/
    @Transactional
    protected void applyResult(String deliveryId, DeliveryResult result,
                                int attempt, int maxRetries) {
        if (result.success()) {
            deliveryRepo.markDelivered(deliveryId);
            return;
        }
        if (result.permanent() || attempt >= maxRetries) {
            deliveryRepo.markFailed(deliveryId,
                    "permanent=" + result.permanent() + ", attempt=" + attempt);
            return;
        }
        // M2：平台 Retry-After 优先，否则指数退避
        long delayMs = result.retryAfterMs() > 0
                ? result.retryAfterMs()
                : (long) Math.pow(4, attempt) * 60_000L;
        deliveryRepo.scheduleRetry(deliveryId, attempt + 1,
                Instant.now().plusMillis(delayMs));
    }

    @Transactional
    protected void markFailedTx(String id, String reason) {
        deliveryRepo.markFailed(id, reason);
    }

    /**
     * IN_FLIGHT 孤儿恢复：启动时或定期将超过 2min 的 IN_FLIGHT 记录重置为 PENDING。
     * 防止进程崩溃（SIGKILL）时 claim 后未更新状态导致永久卡住。
     */
    @Scheduled(fixedDelay = 120_000)
    @Transactional
    public void recoverOrphanedInFlight() {
        deliveryRepo.resetOrphanedInFlight(Instant.now().minusSeconds(120));
    }
}
```

**Migration 补充**：`t_channel_delivery` 新增 `IN_FLIGHT` 状态（需更新 CHECK 约束）：

```sql
-- CHECK 约束更新（IN_FLIGHT 为 claim 后、HTTP 完成前的中间状态）
CONSTRAINT chk_ch_delivery_status
    CHECK (status IN ('PENDING', 'IN_FLIGHT', 'RETRY', 'DELIVERED', 'FAILED'))
```
```

### 11.2 重试策略

| 重试次数 | 默认延迟（无平台提示） | 平台 429 时 |
|---------|------|-------------|
| 第 1 次 | 1 分钟 | 读取 Retry-After |
| 第 2 次 | 4 分钟 | 读取 Retry-After |
| 第 3 次 | 16 分钟 | 读取 Retry-After |
| 超过 3 次 | 标记 FAILED | 进入前端告警 |

### 11.3 FAILED 告警

FAILED 状态投递触发 `ChatEventBroadcaster#userEvent` 推送前端通知（type = `channel_delivery_failed`）。

---

## 12. Plan B 的 Tradeoffs

### 12.1 优势

| 维度 | Plan B 做法 | 收益 |
|------|------------|------|
| **上线速度** | 不新增 Maven 模块，无需修改父 pom.xml | P2 开发周期缩短 ~30% |
| **依赖图简单** | channel 包与现有 Service、Repo 直接注入 | 无循环依赖风险 |
| **测试简单** | 所有 Bean 在同一 Spring Context | Integration Test 配置零成本 |
| **Spring 注册表** | `List<ChannelAdapter>` 自动收集 | 新平台只需 `@Component`，扩展性等价 Plan A |
| **运维一体** | 与 server 同进程 | 无额外部署单元，无跨服务延迟 |

### 12.2 局限与缓解

| 风险 | 表现 | 缓解 |
|------|------|------|
| **server JAR 膨胀** | 飞书/Telegram HTTP 依赖进入 classpath | 优先用已有 OkHttp 直接调用，不引入平台原生 SDK |
| **单点重启影响投递** | 内存重试任务丢失 | `t_channel_delivery` 持久化 + `ApplicationReadyEvent` 恢复（已实现，H5） |
| **流量混用** | webhook 与 UI API 共用端口 | Nginx 按 `/api/channels/*/webhook` 单独限流；SecurityFilterChain 已隔离 |
| **测试隔离差** | channel 与 eval/hook 共享 Context | 包级 Mock + Spring Profile 隔离 |

### 12.3 何时升级为独立模块（Plan A）

以下**任一**条件成立时，将 `channel/*` 包抽离为 `skillforge-channels` 模块：

1. 接入平台 ≥ 5 个，且各平台 SDK 造成类冲突
2. channel webhook 流量超过 server 总流量 30%，需独立扩容
3. 需要独立发布节奏（频繁发版不应影响 server 稳定版）
4. 需要多团队协作

**P2 首批（飞书 + Telegram）预计不触发以上条件，Plan B 是更合适的起点。**

---

## 13. 可测试性设计（LOW-9 新增）

### 13.1 MockChannelAdapter

```java
package com.skillforge.server.channel.platform.mock;

/**
 * 测试用 ChannelAdapter，仅在 Spring Profile "test" 或 "dev" 下激活。
 * 不需要真实的飞书/Telegram 账号即可测试完整消息路由链路。
 */
@Component
@Profile({"test", "dev"})
public class MockChannelAdapter implements ChannelAdapter {

    private final List<ChannelMessage> received = new CopyOnWriteArrayList<>();
    private final List<ChannelReply> delivered = new CopyOnWriteArrayList<>();

    @Override
    public String platformId() { return "mock"; }

    @Override
    public String displayName() { return "Mock Channel (测试用)"; }

    @Override
    public void verifyWebhook(WebhookContext ctx, ChannelConfigDecrypted config) {
        // 不验签，直接通过
    }

    @Override
    public Optional<ChannelMessage> parseIncoming(byte[] rawBody,
                                                    ChannelConfigDecrypted config) {
        // rawBody 直接是 ChannelMessage JSON，方便测试注入任意消息
        ChannelMessage msg = parseJson(rawBody, ChannelMessage.class);
        received.add(msg);
        return Optional.of(msg);
    }

    @Override
    public ResponseEntity<?> handleVerificationChallenge(byte[] rawBody) {
        return ResponseEntity.ok(Map.of("challenge", "mock"));
    }

    @Override
    public DeliveryResult deliver(ChannelReply reply, ChannelConfigDecrypted config) {
        delivered.add(reply);
        return DeliveryResult.ok();
    }

    /** 测试断言用：获取最近收到的消息列表。*/
    public List<ChannelMessage> getReceived() {
        return Collections.unmodifiableList(received);
    }

    /** 测试断言用：获取最近投递的回复列表。*/
    public List<ChannelReply> getDelivered() {
        return Collections.unmodifiableList(delivered);
    }

    public void clearAll() {
        received.clear();
        delivered.clear();
    }
}
```

### 13.2 测试注入端点（仅 dev/test profile）

```
POST /api/channels/mock/webhook
Body: ChannelMessage JSON（直接注入测试消息）

用途：
- CI 集成测试：验证完整 webhook → Session → 回复 链路
- 本地开发：无需真实飞书/Telegram 账号，直接注入消息调试 Agent 行为
```

### 13.3 Integration Test 示例

```java
@SpringBootTest
@ActiveProfiles("test")
class ChannelIntegrationTest {

    @Autowired
    MockChannelAdapter mockAdapter;

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void givenMockMessage_whenWebhookPosted_thenAgentReplies() throws Exception {
        mockAdapter.clearAll();

        ChannelMessage msg = new ChannelMessage(
            "mock", "conv-001", "user-001", "msg-001",
            MessageType.TEXT, "你好，SkillForge", null,
            Instant.now(), Map.of()
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/channels/mock/webhook",
            msg,
            Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 等待 AgentLoop 完成（最多 30s）
        await().atMost(30, SECONDS).until(
            () -> !mockAdapter.getDelivered().isEmpty()
        );

        assertThat(mockAdapter.getDelivered()).hasSize(1);
        assertThat(mockAdapter.getDelivered().get(0).conversationId())
            .isEqualTo("conv-001");
    }
}
```

---

---

## 14. Known Limitations（实现时注意）

### 14.1 ReplyDeliveryService 的 `protected @Transactional` self-invocation（Reviewer-A LOW 观察）

`claimBatch()` 和 `applyResult()` 声明为 `protected @Transactional`，但由 `pollAndRetry()` 通过 `this.` 调用（self-invocation）。Spring AOP 代理不拦截 self-invocation，`@Transactional` 注解实际不生效。

当前不影响行为（每个方法只含一条 SQL，auto-commit 天然原子），但注解有误导性。

**建议实现时修复**：将 `claimBatch()` 和 `applyResult()` 提取到独立的 `@Service DeliveryTransactionHelper` 中：

```java
@Service
public class DeliveryTransactionHelper {
    @Transactional  // 被 Spring 代理，事务语义清晰
    public List<String> claimBatch(Instant now) { ... }

    @Transactional
    public void applyResult(String id, DeliveryResult result, int attempt, int maxRetries) { ... }
}
```

P2 范围内不要求，实现阶段再处理。

---

*文档版本：v2.4 · 2026-04-20 · Planner-B（第5轮 review 后修订）*
