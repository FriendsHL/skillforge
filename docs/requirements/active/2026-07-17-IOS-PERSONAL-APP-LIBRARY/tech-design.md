# Tech Design — Personal App Library

> 状态：implemented / automated verification complete；real-device acceptance pending
> 日期：2026-07-17
> Pipeline：Full；代码、交叉审查与自动化验证已完成（2026-07-17）

## 1. 方案决策

采用混合权威模型：

- `t_chat_attachment` 继续是 Interactive Artifact 权威实体与权限边界。
- 服务端偏好表保存收藏和最近打开，支持正确的全局排序、筛选与未来跨设备同步。
- “已下载”只由 iOS 本地缓存索引判断，服务端不伪造设备状态。
- 列表使用服务端 projection + keyset pagination，不扫描 transcript、不产生 N+1。

拒绝两种替代方案：客户端逐 Session 扫消息会产生无界请求；纯本地收藏会让服务端分页后的全局筛选错误。

## 2. 数据模型

V176：

1. `t_chat_attachment.source_message_seq BIGINT NULL`，记录来源 assistant message seq，配合
   `(session_id, source_message_seq)` 索引。发布消息持久化后可靠回填，不从 `content_json` 在线扫描。
2. `t_personal_app_preference`：`user_id`、`attachment_id`、`favorite`、`last_opened_at Instant`、审计时间；
   attachment FK cascade；唯一 `(user_id, attachment_id)`。
3. partial/index：interactive/published 列表热路径、favorite、last_opened keyset。

`interactive_artifact_ref` 必须加入 publish reconciliation 的 ref 判断，避免只能依赖维护任务修复 published 状态。

## 3. API

设备鉴权、当前用户 ownership：

```http
GET  /api/mobile/client/personal-apps?cursor=&limit=&sort=&q=&agentId=&sessionId=&favorite=
PATCH /api/mobile/client/personal-apps/{artifactId}/preference
POST /api/mobile/client/personal-apps/{artifactId}/opened
```

列表不返回 HTML，只返回 artifact identity、session/message source、title/caption、manifest 展示字段、Agent、Session、
created/lastOpened/favorite/availability。limit 最大 50；cursor 为 `(sortTimestamp,id)` keyset。

为保持单条 bounded projection，列表在 raw keyset page 读取后只校验会进入响应的展示/安全字段；新发布继续执行完整
manifest 校验。畸形 JSON、非法标题/回退文案、非空权限/网络能力或非 object 根 state schema 会 fail-closed，但历史
`stateSchema` 中的 `enum`、`minimum/maximum` 不会导致已发布且当前用户有权访问的 Artifact 从 Library 消失。因此合法
响应仍可能出现 `items=[]` 但 `nextCursor!=null`。客户端必须继续追该 cursor，直到得到可显示记录或
`nextCursor=null`，并使用 consumed-cursor/cycle guard 防止错误 cursor 无限循环；不得依赖“当前页最后一张卡片出现”
作为唯一的下一页触发条件。

HTML/manifest 下载继续走现有 session-scoped endpoint，每次重新执行 user/session/device revoke 校验。

## 4. iOS

- `Control → Workspace → Personal Apps` 进入 Library。
- 最近使用、收藏、已下载、全部；搜索；Agent/Session 筛选。
- 本地 download metadata index 与服务端页合并；从 Library/Chat 打开共享相同 artifact-scoped state。
- Open 进入现有 Viewer；“查看来源对话”跳转 Chat 并定位 `sourceMessageSeq`。
- 处理 empty/offline/unavailable/404/revoked；关闭 Viewer 返回原列表位置。
- `.loading` 且当前没有可展示记录时，不使用空白页或孤立 spinner：搜索/范围/筛选 header 保持稳定，内容区显示两张
  与真实卡片几何结构一致的 skeleton；失败后切到可恢复的 unavailable/offline 状态。
- `PersonalAppLibraryCardPresentation.compactSummary` 只负责视图投影：折叠空白、最长 96 字符并优先在单词边界截断；
  DTO、本地 metadata 与服务端搜索继续保留完整 caption。普通字号限制两行，Accessibility Dynamic Type 可扩展到三行。

## 5. 安全与性能

- 所有查询包含 userId 和 production-owned session predicate；禁止跨用户和 eval/不可见 child 泄露。
- 查询参数长度、limit、sort、cursor 严格校验；SQL 参数绑定。
- projection 单查询；用 PostgreSQL `EXPLAIN` 验证索引和无 N+1。
- 删除 Session 后 attachment cascade；客户端陈旧缓存遇到 404 显示 unavailable 并清理索引。

## 6. 测试

- Migration/repository：约束、cascade、source link、单 SQL projection、equal timestamp cursor 无重复。
- API：搜索/过滤/分页、preference 幂等、ownership/scope/revoke/stale。
- iOS：DTO/URLProtocol/pagination/local download merge/state identity。
- XCUITest：Control→Library、搜索筛选收藏、打开返回、来源消息定位、offline/unavailable、VoiceOver/Dynamic Type。
- 真机：50 个 Artifact dogfood、缓存/重启、跨 Session 定位。

## 7. 实施记录（2026-07-17）

后端、iOS 与共享 Artifact 状态链路已经落地：

- V176 增加 `source_message_seq`、偏好表、cascade/约束和热路径索引；历史回填对畸形 JSON fail-closed，
  同一个 Artifact 出现多次时以最后一条 assistant message 为来源。
- Session 行存储 append/rewrite/restore 与 legacy rewrite 都接入 source-link reconciliation；rewrite 先清理旧链接，
  持久化新消息后再绑定新 seq，避免恢复旧快照后仍跳到已不存在的消息。
- `GET /api/mobile/client/personal-apps` 使用单条 bounded projection SQL、严格 ownership/production predicate、
  signed token-scoped keyset cursor 和过滤条件绑定；不返回 HTML、`initialData`、`stateSchema`、磁盘路径或 hash。
- 收藏与最近打开写入使用 guarded atomic upsert；不可见与不存在 Artifact 统一返回 404，避免存在性泄露。
- Controller 严格拒绝未知/重复 query 参数和非精确 preference JSON（包括重复 `favorite` key），并返回
  `Cache-Control: no-store`；cursor codec 使用 Spring-managed `ObjectMapper`，有最小 Spring context 装配回归。
- 修复真机验收发现的历史数据兼容缺陷：3 个已发布 Artifact 因旧 state schema 关键字被完整发布校验静默过滤；
  改为列表专用安全投影校验后，真实 API 返回两个“面试追踪”和一个“AI 早报”，临时验证设备已清理。
- iOS 已实现统一入口、服务端搜索/筛选、bounded pagination、本地下载索引合并、收藏、打开/分享/清缓存、
  来源消息定位、离线与 unavailable/revoked 状态，以及 Dynamic Type、深色和窄屏适配。
- Library loading 已改为稳定页面框架 + 两张卡片骨架；卡片采用 64×82 monogram、受限摘要、紧凑事实 badge 与
  44pt 可触达操作区，并保留 unavailable 红色语义和 Accessibility Dynamic Type 的纵向布局。
- Chat 与 Library 共享 `AttachmentDownloadStore` 和 artifact-scoped state；下载提交前后校验 artifact/session identity、
  cancellation 与 operation token，防止已取消或已切换 Session 的旧任务污染 metadata。

后端聚焦验证命令：

```bash
mvn -pl skillforge-server -am \
  -Dtest=PersonalAppLibraryMigrationIT,PersonalAppLibraryRepositoryIT,PersonalAppCursorCodecTest,\
PersonalAppLibraryServiceTest,MobilePersonalAppControllerTest,PersonalAppLibraryWiringTest,\
PersonalAppSourceLinkReconcilerTest,SessionServicePersonalAppSourceHookTest,\
ChatAttachmentGeneratedImportTest,MobileAuthInterceptorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

最终自动化证据：

- 服务端完整 reactor：3924 tests，0 failures，0 errors，180 skipped；5 个模块 `BUILD SUCCESS`。
- 当前服务进程已应用 V173–V176；真实 API/数据库链路覆盖隔离、cursor、过滤、偏好、来源、响应脱敏、
  manifest/download/Range、revoke、cascade 与 `EXPLAIN` 索引命中，并清理全部一次性测试数据。
- iOS XCTest 263/263、XCUITest 60/60（合计 323/323），Release simulator build 通过；XcodeGen 两次生成哈希一致。
- Release `SkillForge.app` 的 `Info.plist` 与 compiled asset catalog 已确认同时包含
  `LaunchBackground` / `LaunchMark`；全量结果包为 `/tmp/SkillForge-Final-LoadingCards-20260717.xcresult`。
- Full pipeline 交叉审查结论为 PASS，无代码或规格 blocker。

仅剩用户真机验收：50 个 Artifact 的实际流畅度、缓存/重启、跨 Session 定位、revoke 恢复和真实网络行为。
签名、APNs、摄像头、LAN/Tailscale、后台与 Keychain 等设备门继续按各自需求包验收，不并入本需求的代码完成定义。
