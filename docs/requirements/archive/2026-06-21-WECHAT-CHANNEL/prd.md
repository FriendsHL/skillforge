# PRD — WECHAT-CHANNEL（路线 B-native：原生 iLink adapter）

> 状态：prd-ready（路线已拍 = B-native；2026-06-18）
> 路线决策：用户要"用个人微信跟 SkillForge 沟通 + 后续发报告文件 + 本机部署"。→ 企业微信(A)不满足(是另一个 App);openclaw bridge(B-bridge)借壳+额外进程。**选 B-native：SkillForge 原生实现腾讯 ClawBot 的 iLink HTTP JSON 协议**,落进现有 ChannelAdapter 架构,无 openclaw、无额外进程。

## 目标

让用户**从个人微信**跟 SkillForge agent 双向对话,并能**把 SkillForge 生成的报告文件发到微信**;**本机可部署**(出站 long-poll,无需公网回调)。

## 非目标

- 不做企业微信(A)/ 不做 openclaw bridge(B-bridge)。
- 不做个人微信逆向 hack——走腾讯官方 ClawBot 通道(微信"设置-插件-ClawBot"),SkillForge 只实现其 iLink 后端协议。
- **ACP 操作本地 codex/cc 不在本需求**(单独立项)。
- 不做群组/@提及/卡片等高级能力(MVP 先文本 + 文件)。

## 工作流

1. **扫码绑定**:dashboard 点"微信扫码配置"→ 后端调 `get_bot_qrcode` 拿二维码 → 前端展示 → 用户微信(设置-插件-ClawBot)扫 → 后端轮询 `get_qrcode_status` 到 confirmed → 存 `bot_token` + `baseurl` 到 channel config。
2. **收**:后端 long-poll `getupdates`(带 `get_updates_buf` cursor)→ parse → 现有 `ChannelSessionRouter` → SkillForge agent loop。
3. **发**:agent 回复 → `sendmessage`(回显 `context_token`,`item_list` type 1 文本)。
4. **发文件(报告)**:`getuploadurl` → AES-128-ECB 加密 PUT CDN → `sendmessage` type 4 引用。

## 功能需求

- FR1：扫码登录流(get_bot_qrcode + 轮询 get_qrcode_status),拿到 bot_token/baseurl 持久化。
- FR2：long-poll `getupdates` 收信,**cursor `get_updates_buf` 持久化去重**,断线退避重连(参照 Feishu WS reconnect policy)。
- FR3：`sendmessage` 发文本,**回显 inbound 的 context_token**(否则不关联会话窗)。
- FR4：发文件——getuploadurl + AES-128-ECB + CDN PUT + type 4 引用(覆盖"报告发微信")。
- FR5：落进现有 channel 架构(ChannelAdapter SPI + 复用 router/dedup/delivery),platform="weixin",config 进 t_channel_config(无 migration)。
- FR6：本机部署可用(纯出站 HTTPS,无入站回调)。

## 验收点

- AC1：dashboard 扫码 → bound;微信里给 bot 发文本 → SkillForge agent 在**个人微信**回复。
- AC2：SkillForge 主动把一个报告文件 push 到微信,用户能在微信收到并打开。
- AC3：cursor 去重生效(不重复收);long-poll 断线能自动重连。
- AC4：context_token 正确回显(回复落在正确会话窗)。
- AC5：本机部署(无公网)全流程跑通。
- AC6（冒烟,部署后 qa）：真扫码 + 真收发 + 真发文件,贴 raw 证据。

## 风险（eyes-open）

- **协议非官方**:iLink-for-custom-backend 是社区逆向(非腾讯官方文档),腾讯可改/限流/停。写成独立 adapter 隔离影响;真变了只此一处。
- **个人账号**:用你的个人微信身份。建议**小号** + (隔离机器,云端后)。
- **媒体 AES-128-ECB**:加密/CDN 流程需实测对齐。
- **iLink 协议细节需实现期验证**(逆向文档,字段/流程以实跑为准)。

## 冒烟用例（设计时预写）

1. 扫码 → get_qrcode_status confirmed → bot_token 存库。
2. 微信发"hi" → getupdates 收到 → agent 回 → 微信见回复。
3. SkillForge 发一个 .md 报告文件 → 微信收到文件。
4. 断网重连 → long-poll 恢复、cursor 不丢、不重复收。
5. 早失败检测：bot_token 失效 / get_qrcode 超时 → 明确报错,跟功能 bug 区分。
