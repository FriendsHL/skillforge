# Tech Design — iOS Interactive Artifact V1

> 状态：approved implementation baseline
> 日期：2026-07-17
> Pipeline：Full

## 1. 目标与边界

V1 在现有 assistant message 中增加一个 `interactive_artifact_ref`，iOS 将它显示为 Personal App 卡片，
用户点击后进入隔离的全屏 Viewer。页面离线运行，只能读取本 Artifact 的初始数据和已保存状态；任何状态
回传必须经过原生预览、schema 校验和用户确认。

V1 不提供网络、Tool Bridge、设备 token、完整 transcript、任意文件系统、远程依赖或独立 App Library。

## 2. 方案决策

采用“扩展现有 ChatAttachment + 单 HTML payload + 独立 manifest metadata”。

- Payload 是 UTF-8 `text/html`，CSS、JavaScript 和静态图形必须内联。
- 不在 V1 解压 zip。单文件仍是自包含 bundle，并直接消除 Zip Slip、symlink、重复 entry、深路径和
  解压炸弹风险。
- Manifest 作为受控 JSON metadata 保存，不把 HTML 字面量写进 transcript。
- 消息只保存 ref、fallback、展示 metadata 和 manifest version。
- 下载继续使用现有 device/session/user ownership 检查。

多文件 zip 是 V1.1 候选；只有真实 dogfood 证明单文件不足时才进入独立安全设计。

## 3. Manifest V1

```json
{
  "schemaVersion": 1,
  "title": "七月家庭预算",
  "fallback": "已生成可离线调整的七月预算规划器。",
  "permissions": [],
  "network": [],
  "initialData": {},
  "stateSchema": {
    "type": "object",
    "additionalProperties": false,
    "properties": {}
  }
}
```

约束：

- `schemaVersion` 必须为 `1`。
- `title` 1–80 字符；`fallback` 1–500 字符。
- `permissions`、`network` V1 必须是空数组。
- `initialData` 最大 32 KiB；服务端在序列化前以迭代遍历限制 nesting depth 64，避免恶意深链把错误升级为
  `StackOverflowError`。
- `stateSchema` 只允许 object 根；type 子集为 string/number/integer/boolean/object/array。object 只支持
  properties/required/additionalProperties（boolean 或同子集 schema），array 只支持 items，scalar 只支持 type；
  其它 keyword fail-closed。最大 16 KiB、深度不超过 8、最多 1024 个 JSON value node；服务端与 iOS 使用
  相同计数（object/array container 与 scalar 各计一个 node）。
- state snapshot 最大 64 KiB。

## 4. 消息形状

```json
{
  "type": "interactive_artifact_ref",
  "attachment_id": "uuid",
  "mime_type": "text/html",
  "filename": "july-budget.html",
  "caption": "已生成可离线调整的七月预算规划器。",
  "title": "七月家庭预算",
  "artifact_schema_version": 1
}
```

同一 assistant message 最多一个该 block。ChatService 持久化 JSON 与 AgentLoopEngine 内存 JSON 必须保持
同形；compact/rewrite 只搬运 ref，不展开 HTML。

## 5. 后端流程

1. `PublishInteractiveArtifact` 接收严格二选一的来源：自定义页面使用 `file_path`、manifest 和可选 caption；
   平台参考页面使用固定 allowlist 的 `template_id` 与结构化 `initial_data`。
2. 自定义模式确认文件位于当前 run artifact workspace、是 regular file、非 symlink、UTF-8 且在大小上限内；
   模板模式直接读取通过 SHA-256 与 validator 校验的 classpath bytes，不把平台模板复制到 Agent workspace。
3. HTML 静态门禁不再“全文出现 URL 即拒绝”。Jsoup DOM 只允许 URL 位于 text node、可解析的
   `script[type=application/json]`、通过 iOS 同边界校验的 `data-sf-url`；executable script 内 URL literal
   fail-closed。结构化拒绝主动 src/srcset/href/xlink:href/action/formaction/poster、script src/link href、
   meta refresh、iframe/object/embed/portal/base、静态 `input[type=file]`/`capture` 和所有 inline `on*`
   handler；CSS/JS 作用域 scanner 继续拒绝 network/navigation/worker/device-permission/clipboard/动态 DOM
   与常见编码混淆。comment DOM 遍历使用显式栈，不因合法深层 DOM 泄漏 `StackOverflowError`。静态 scanner
   是第一层，CSP 与 runtime
   delegate 仍是硬边界。
4. 保存为 `ChatAttachment(kind=interactive, mime=text/html)`，manifest 保存到专用 metadata 列。
5. Tool 通过 out-of-band `PublishedArtifact` 返回 metadata，engine 生成 ref block。
6. 移动端沿用受保护下载 endpoint；跨 user/session/device revoke 均失败。

## 6. iOS runtime

- 使用 `WKWebViewConfiguration.websiteDataStore = .nonPersistent()`。
- HTML 从已鉴权本地缓存读取，不向 WebView 注入 Bearer token。
- navigation delegate 仅允许初始本地 document；拒绝 `http/https/ws/wss/file` 的后续导航、popup 和下载。
- 注入 CSP：`default-src 'none'; img-src data: blob:; style-src 'unsafe-inline'; script-src 'unsafe-inline';
  connect-src 'none'; font-src data:; media-src data: blob:; form-action 'none'; frame-src 'none';
  object-src 'none'; worker-src 'none'; base-uri 'none';`。
- 页面 API 仅暴露 `window.SkillForgeArtifact.initialData`、`savedState`、`saveState(state)`、
  `submitSnapshot(state)`、`requestOpenURL(url)`；原生 message method allowlist 仅为 `saveState`、
  `submitSnapshot`、`requestOpenURL`，不存在 ready/getInitialData/requestShare。
- 每个 bridge message 校验 method、artifact identity、payload 大小和 manifest schema。
- 保存状态放入 Application Support 的 artifact-scoped JSON；bundle 缓存和 state 分离。
- `submitSnapshot` 只打开原生确认页；确认后由原生客户端构造普通 user message。
- `requestOpenURL` 先按 <=2048 UTF-8 bytes、absolute http/https + conservative ASCII host、无
  userinfo/control/backslash 等规则 fail-closed 校验；host 仅接受 localhost、合法 ASCII DNS/punycode 与标准四段
  IPv4，拒绝 Unicode/percent authority、非法 label、IPv4 缩写/前导零/越界值与 IPv6。通过后再打开原生确认；
  只有用户确认后才调用系统 URL opener，页面不能直接导航或静默打开。
- CSP 只能插入真实 document prefix 中的 `<head>`；comment、script/style raw text、template/textarea/title 等
  `<head>` decoy 不能影响插入点。页面无合法 head 时，在首个非 prefix construct 前合成 head，fail-closed 保证
  CSP 先于任何页面资源。
- 初始 `about:blank` main-frame document 使用 one-shot navigation gate；Web content process 恢复时重新装载原始
  secured HTML 并显式 re-arm，不使用可能保留逃逸状态的通用 `reload()`。
- Bridge 在完整 JSON 序列化前先验证 main frame、handler、artifact identity 和 method；`saveState` 最短间隔
  0.5 秒，`submitSnapshot`/`requestOpenURL` 共享 2 秒确认桶。原生确认 first-wins，取消或消费后仍有 2 秒
  cooldown，防止页面用并发/连点替换用户正在确认的操作。
- saved state 加载区分 missing、valid、invalid。invalid state 不进入 bootstrap，页面继续使用 `initialData`，
  Viewer 同时展示非阻断诊断；schema-invalid submit 不弹确认、不发送消息，并在 Viewer 中显示诊断。
- 文件与 clipboard 是 document-start runtime hard boundary。文件 guard 保存原生 DOM getter/method，以
  non-configurable wrapper 覆盖 `HTMLElement.click`、`HTMLInputElement.click/showPicker`、type/capture/setAttribute
  same-stack 路径，并在页面 listener 之前注册 Window capture；MutationObserver 只作纵深防御。Clipboard guard
  同样保存 Reflect/Set/String/Event/dispatch 原生引用，在 Window capture 阻断 copy/cut/paste，锁定
  `execCommand` 与可用的 Clipboard read/write 方法，页面后续 prototype tampering 不能恢复能力。
- `WKUIDelegate` 对 media capture 与 device motion/orientation 一律 `.deny`；iOS 18.4+ open panel 返回 nil。
  deployment target iOS 17 缺少 open-panel delegate 回调时，由上述 document-start file guard 补齐边界。

## 7. 失败与恢复

- manifest/HTML 不合法：服务端拒绝发布，普通 Markdown fallback 保留。
- 下载失败：卡片展示重试，不打开空 Viewer。
- state 损坏或 schema 不匹配：隔离旧 state，使用 initialData，并显示可诊断错误。
- Web content process 崩溃：提供重新载入；不自动提交或清空 state。
- device revoke：删除 bundle cache；本地 state 不再可打开，后续清理由缓存策略完成。

## 8. 验收与测试

- Backend：manifest 边界、HTML 安全门、ownership、消息 shape、重复 publish、cleanup。
- iOS unit：decode、cache、CSP、navigation policy、bridge validation、state restart。
- XCUITest：卡片→Viewer→调整→关闭→重开；submit 原生确认；offline；错误恢复；VoiceOver/Dynamic Type。
- 恶意语料：见 [threat-model.md](threat-model.md)。

## 9. 分阶段交付

1. Phase 0：schema、威胁模型、fixtures。
2. Backend slice：发布与 ref/download。
3. iOS runtime slice：只读打开 + state。
4. Product slice：确认回传、分享、无障碍。
5. 真机验收后再把 Proposed 合并进 Current 原型。
