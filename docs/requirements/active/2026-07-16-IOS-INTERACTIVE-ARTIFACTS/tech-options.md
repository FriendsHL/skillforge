# Tech Options — iOS Interactive Artifact
> 本文不是最终设计。用户确认产品原型后，Full pipeline 需要独立 plan + security/database/iOS/message-shape review。

## 推荐数据流

```text
Agent workspace
  → PublishInteractiveArtifact(manifest + bundle)
  → protected attachment/artifact storage
  → interactive_artifact_ref in assistant message
  → mobile REST/WebSocket DTO
  → native card
  → authenticated bundle download
  → isolated WKWebView host
```

## 方案 A：扩展现有 ChatAttachment（推荐 V1）

- 新增 `kind=interactive` 与 manifest metadata。
- 复用 owner/session/status/hash/storage/download/cleanup。
- message 新增 `interactive_artifact_ref` block。
- iOS 增加卡片、bundle repository 和 Viewer。

优点：复用现有完整传输和权限边界。缺点：ChatAttachment 从单文件扩展为 bundle，需要明确封装格式与
解包安全。

## 方案 B：新建 InteractiveArtifact 聚合

- 独立 entity、versions、bundle resources 和 state/snapshot。
- 更适合 Library、版本、发布、分享和 MCP Apps。

优点：长期模型清晰。缺点：V1 migration/API/清理/权限工作更大，可能在验证价值前过度建设。

## 方案 C：HTML 字面量直接进 ContentBlock

不采用。它放大 transcript/token、消息 shape、compact/replay、XSS 和 channel downgrade 风险，也无法
表达 bundle、权限和状态。

## V1 关键技术门

1. bundle 格式：建议 zip，仅允许 manifest 声明的相对文件；解压时防 Zip Slip、symlink 和 zip bomb。
2. manifest：服务端 JSON Schema 校验，entrypoint 必须存在，network/permissions V1 必须为空。
3. 消息：只保存 ref + fallback，不保存 HTML 字面量；ChatService 与 AgentLoopEngine shape 必须字节一致。
4. iOS：`WKWebsiteDataStore.nonPersistent()`、导航 allowlist、自定义 scheme、本地资源范围限制。
5. bridge：V1 最多 `ready/getInitialData/saveState/requestShare/submitSnapshot`，每个 payload 校验大小和 schema。
6. 生命周期：下载缓存与 state 分离；artifact 更新改变 bundle version，但不隐式迁移旧 state。
7. 清理：消息仍引用的 bundle不可删；失联缓存可删，state 清理策略需产品确认。

## Full pipeline 建议切片

1. Phase 0：5 个 dogfood fixture + threat model + bundle schema spike。
2. Backend：publish/import/manifest/message ref/download/authorization/cleanup。
3. iOS runtime：card、download、unpack、WKWebView sandbox、state bridge。
4. iOS product：全屏导航、权限面板、错误/恢复、accessibility、分享。
5. Review：security、database、persistence-shape、Java、iOS code/product 双 reviewer。
6. Verify：server full tests、iOS XCTest/XCUITest、真机 offline/restart/revoke、恶意 bundle corpus。

## 红灯测试集

- `../`、绝对路径、symlink、重复 entry、超深路径、压缩炸弹。
- inline/remote script、fetch/WebSocket/image beacon、form navigation、window.open。
- bridge 大 payload、未知 method、重复 submit、伪造 session/artifact id。
- 无限定时器/DOM/Canvas、崩溃后重开、内存警告、后台恢复。
- cross-user/session/device revoke、缓存文件在 revoke 后的访问。
