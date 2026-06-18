# Tech Design — WECHAT-CHANNEL（B-native iLink adapter）

> 状态：design-draft（协议为社区逆向，字段/流程以实现期实跑验证为准）
> 触碰：新 `channel/platform/weixin/` 包 + `ChannelConfigController` 2 处 switch + 新扫码 endpoint + FE 扫码配置 + `t_channel_config`（无 migration）。Full pipeline + security-reviewer。

## 架构落点（复用现有 channel SPI）

SkillForge channel = 适配器 SPI 自动注册（见 [现状勘察](#)）。微信连接模型是**出站 long-poll**（非 webhook），所以走 **`ChannelPushConnector` SPI**（Feishu websocket 模式同款路径），而非 `ChannelWebhookController`。

新增 `channel/platform/weixin/`（参照 feishu 包，但用 long-poll 替代 WS）：

| 类 | 职责 |
|---|---|
| `WeixinChannelAdapter` | `@Component implements ChannelAdapter + ChannelPushConnector`；platformId="weixin"；`verifyWebhook`/`handleVerificationChallenge` no-op（无 webhook）；`deliver` → iLink sendmessage |
| `WeixinIlinkClient` | iLink HTTP JSON 客户端：扫码登录（get_bot_qrcode + get_qrcode_status）/ getupdates long-poll / sendmessage / 媒体 getuploadurl + AES-128-ECB + CDN PUT |
| `WeixinPushConnector`(或并入 adapter) | `start()` 起 long-poll 循环（cursor 持久化）→ parse → `ChannelSessionRouter.routeAsync`；`stop()` 停；断线退避重连（参照 `FeishuWsReconnectPolicy`） |
| `WeixinMessageParser` | iLink msg → `ChannelMessage`（稳定 platformMessageId 供去重；保留 context_token 到 rawFields 供回复回显） |

## 关键流程

**扫码登录（新 endpoint + FE）**：
- 新 `POST /api/channel-configs/weixin/qr-login`（或复用 config controller）：调 `get_bot_qrcode?bot_type=3` 返 `{qrcode, qrcode_img_content}` 给 FE 展示；后端起轮询 `get_qrcode_status` → confirmed 拿 `bot_token`+`baseurl` 写入 `t_channel_config.credentials_json`。
- FE：channel 配置页加"微信扫码"按钮 → 展示二维码 → 轮询/WS 通知绑定成功（参照阿里云控制台"扫码配置"UX）。

**收（long-poll）**：`ChannelPushManager` 在 config.mode=push 时 `start()` connector → 循环 POST `getupdates`（`get_updates_buf` cursor，`base_info.channel_version`）→ `WeixinMessageParser` → `dedupRepo.tryInsert` → `router.routeAsync`。cursor 持久化（config_json 或新轻量存储）防重复。

**发**：`deliver` → `sendmessage`（`to_user_id`=inbound from_user_id；**`context_token` 回显**；`item_list` type1 文本）。

**发文件**：`getuploadurl` → AES-128-ECB 加密 → PUT CDN → `sendmessage` item type4 + aes_key/CDN 参数。

**鉴权**：header `Authorization: Bearer {bot_token}` + `AuthorizationType: ilink_bot_token` + `X-WECHAT-UIN`（base64(随机 uint32)，每请求变）。host `ilinkai.weixin.qq.com`。

## 配置模型（无 migration）

`t_channel_config`：platform="weixin"；`credentials_json` = `{bot_token, baseurl}`（扫码后写入）；`config_json` = `{mode:"push", cursor:..., channel_version:"1.0.2"}`；`webhook_secret` 不用（无验签）。`ChannelConfigController` 加 `weixin` case：连接测试（getupdates 探活）+ resolveWebhookSecret（n/a 跳过）。

## 关键不变量 / 风险

- **INV-1 cursor 持久化**：`get_updates_buf` 必须每轮存,重启接得上、不重复收（漏存=重复消息风暴）。
- **INV-2 context_token 回显**：回复必须带 inbound 的 context_token,否则不关联会话。parser 须把它带进 ChannelMessage.rawFields,deliver 取回。
- **INV-3 long-poll 生命周期**：start/stop 干净（参照 Feishu connector）；35s 超时正常返回继续；断线退避重连;停机不泄漏线程。
- **INV-4 出站-only**：不开任何入站 endpoint（除扫码 FE 流），本机部署成立靠此。
- **风险**：① 逆向协议（非官方）—— 字段/流程实现期以实跑校准,Tencent 可变；② AES-128-ECB 媒体加密 + CDN 流程需实测；③ 个人账号（小号 + 隔离）；④ bot_token 失效处理(重新扫码)。

## 实现拆分（建议顺序）

1. `WeixinIlinkClient` 扫码登录 + getupdates + sendmessage（文本）+ 鉴权 header —— 先打通文本双向。
2. `WeixinChannelAdapter` + `WeixinPushConnector` + parser，接 router/dedup；cursor 持久化 + 重连。
3. 扫码 endpoint + FE 扫码配置 UX。
4. 文件发送（getuploadurl + AES-128-ECB + CDN + type4）—— 覆盖报告需求。
5. ChannelConfigController 2 switch。

## 测试 / 冒烟

- 单测：WeixinIlinkClient（mock HTTP：扫码状态机、getupdates cursor 推进、sendmessage payload/context_token、鉴权 header、AES-128-ECB 往返）；WeixinMessageParser（iLink msg → ChannelMessage + context_token 保留 + dedup id）。
- 冒烟（部署后,按 prd 5 用例）：真扫码 + 真收发 + 真发文件 + 断线重连,贴 raw 证据。

## pipeline

Full（外部传输 + crypto + 密钥 + 新平台）。对抗 review：java-reviewer + **security-reviewer**（密钥处理 / AES / 外部输入 / token 存储——叠 SEC-1 明文存储重评）+ typescript-reviewer（FE 扫码 UX）。无 schema 迁移故不必 database-reviewer。
