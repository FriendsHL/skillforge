# Threat Model — Interactive Artifact V1

## 资产与信任边界

受保护资产包括 device Bearer token、session transcript、其他用户/会话的附件、本地文件、Agent tools 和
用户未确认的聊天写入。Artifact HTML、manifest、bridge payload 和生成文件路径全部视为不可信输入。

```text
Agent workspace (untrusted output)
  -> server validator (security boundary)
  -> owned attachment storage
  -> authenticated mobile download
  -> local cache
  -> WKWebView sandbox (security boundary)
  -> native bridge validator (security boundary)
  -> explicit user confirmation
  -> ordinary user message
```

平台维护的 Personal App 模板不经过 Agent workspace。固定 `template_id` 仅从 classpath allowlist 解析，
打包 HTML 与 state schema 由服务端提供，并直接进入同一 validator 与受管 attachment storage；Agent 只能
提供受大小限制的结构化 `initial_data` 和展示字段。自定义 `file_path` 仍属于上图的不可信输入路径。
这样既不依赖 macOS 上缺失的 `SecureDirectoryStream` 写能力，也不把平台原件暴露给同 UID 的 Agent 写入。

## 威胁与控制

| 威胁 | V1 控制 | 阻断测试 |
| --- | --- | --- |
| 外传 token / 数据 | WebView 不注入 token；CSP `connect-src 'none'`；navigation 全拒 | fetch、XHR、WebSocket、image beacon |
| 任意远程代码 | 单 HTML；Jsoup DOM 拒绝外链/相对 script、link 与主动资源属性；CSS/可执行脚本按作用域扫描 | CDN script、remote CSS、module import |
| 本地文件读取 | 不开放 file scope；只加载 payload；document-start file guard + iOS 18.4+ open-panel native deny | `file:///`、相对目录跳转、prototype click/showPicker、动态 file/capture input |
| iframe / popup / form 导航 | 静态门禁 + CSP + navigation delegate | iframe/portal、window.open、form action、meta refresh |
| Bridge 越权 | main-frame method/identity/size/schema 校验；save 与 confirmation 分桶限速；确认 first-wins + cooldown | 未知 method、伪造 id、大 payload、并发/重复 submit/open |
| 跨用户/会话读取 | 复用 mobile device + session owner + attachment owner 检查 | wrong user/session、revoked device |
| 持久化 XSS 扩散 | HTML 不进入 transcript/dashboard DOM；只存 ref/fallback | catch-up/replay/compact shape |
| 资源耗尽 | HTML、manifest、state 大小；schema depth/node；initialData nesting 上限；DOM/manifest 迭代遍历；Web process 可终止恢复 | 大 HTML、20k 深 DOM、10k 深 JSON、深 schema、大 state、timer storm |
| 设备权限入口 | `permissions=[]`；静态 scanner；document-start file guard；native open-panel/media/motion deny | input type/setAttribute/capture、HTMLElement prototype click、showPicker、mediaDevices |
| Clipboard 读写 | scanner 拒绝 clipboard/execCommand；document-start guard 捕获原生引用并在 host-first Window capture 阻断事件和 API | execCommand、Clipboard API、Set/String/Event prototype tampering、Window capture 抢占 |
| 本地 state 损坏/漂移 | missing/valid/invalid 三态加载；invalid 不注入页面，回退 initialData 并显示诊断；invalid submit 不进入确认 | schema 漂移、损坏 JSON、invalid submit |
| 隐式副作用 | 无 Tool Bridge；submit 必须原生确认 | 页面自动 submit、重复 submit |
| 平台模板路径换链/篡改 | 模板不落 per-run workspace；固定 `template_id` 从 classpath 读取并复验 | path-like/未知 id、缺资源、replay payload 冲突 |
| 历史 run 越界发布 | prompt/tool/service contract 只接受当前 run 的最终路径；历史文件只能读作参考并在当前 run 重写 | 直接 publish 历史 staging path 或同 session managed attachment |
| URL 数据被误判或升级为能力 | 仅 text node、可解析 `script[type=application/json]`、`data-sf-url` 可携带 URL；可执行 script 内 URL literal fail-closed | inert URL allow corpus、script URL literal deny corpus |
| 编码/动态规避 | DOM 解码 HTML entity 后检查属性；所有 inline `on*` 拒绝；CSS/JS canonicalize 后检查 | entity/case/whitespace/comment/escape、dynamic obfuscation corpus |
| server/iOS schema 漂移 | 发布时递归限制为 iOS 已实现的 schema 子集，未知 keyword fail-closed | minimum/enum/const/错误 items/required |

静态 scanner 是第一层而不是完整 JavaScript 证明器。即使内容通过 scanner，CSP（尤其
`default-src/connect-src/form-action/frame-src 'none'`）、临时 WKWebView、navigation/popup delegate 和原生
Bridge 校验仍是运行时硬边界。scanner 不再对整个 HTML 搜索 URL：普通文本、可解析的
`application/json` 数据块与 `data-sf-url` 可以存放原文链接；任意其他 script 都视为 executable，URL literal
也拒绝。业务 URL 应经 manifest `initialData` 注入，后续打开链接必须走用户确认的 `requestOpenURL`。

iOS runtime 不信任页面在 document-start 之后的 JavaScript realm。文件与 clipboard guard 在页面代码运行前
保存关键原生 getter/method，并使用不可配置 wrapper 与最早的 Window capture listener；页面覆写
`Set.prototype.has`、global `String`、Event 方法、DOM click/showPicker 或抢先在 document 截断事件，都不能恢复
文件选择或 clipboard 能力。原生 permission delegate 是第二层；其中 open-panel delegate 仅覆盖 iOS 18.4+，
因此 iOS 17 的 document-start file guard 不是可选优化。CSP 插入器只接受真实 document prefix，并通过
`securitypolicyviolation` 对精确 remote image/fetch target 的运行时事件验证；process recovery 只重载保存的
secured HTML 并重新武装 one-shot navigation gate。

`data-sf-url` 并不是任意字符串豁免：存在时必须不超过 2048 UTF-8 bytes，必须是带 host 的绝对 http/https
URL，且不得包含 userinfo、control character、首尾空白、反斜线或非法 percent escape。host 采用 server/iOS
共享 conservative ASCII corpus：允许 localhost、合法 ASCII DNS/punycode、标准四段 IPv4；拒绝 underscore、
percent/Unicode authority、空 label、label 首尾 hyphen、IPv4 缩写/前导零/越界值与 IPv6。CSS 在检查前去除
comment 并解码 escape，防止 `@im\70ort`、
`u\72l()`、`u/**/rl()`；executable script 同样归一化 block/line comment、optional chaining、bracket property 与
JS unicode escape；template literal 的 `${...}` 仍按 executable code 扫描。
除原网络/导航 API 外，还拒绝 Worker/SharedWorker/serviceWorker、document.write、主动 setAttribute、
execCommand clipboard、string timer、DOMParser、innerHTML/outerHTML/insertAdjacentHTML 与 fromCodePoint。
普通 attribute、HTML comment 与 CSS 中的 protocol-relative `//host` token 在任意位置均视为 URL；该规则不
复用于 executable JavaScript 的 comment 解析，避免把 JS 字符串和 line comment 混为同一语法域。

V1 `stateSchema` 只接受 `string`、`number`、`integer`、`boolean`、`object`、`array`。object 只接受
`properties`、`required`、`additionalProperties`（boolean 或同子集 schema）；array 只接受 `items`；scalar
只有 `type`。`minimum`、`enum` 等尚未在 iOS 实现的 keyword 必须在发布前拒绝，避免“发布成功但永远无法
提交”。原有 JSON byte limit 与 depth limit 继续生效，并与 iOS 一致限制为最多 1024 个 JSON value node。
`initialData` 在 32 KiB 前另做迭代 depth 64 preflight，避免序列化恶意深链时抛出 `Error`。

## Phase 0 fixture corpus

1. `budget-valid.html`：离线预算滑杆与本地 state。
2. `inert-url-valid.html`：text、合法 JSON data block、`data-sf-url` 中的 URL。
3. `external-fetch.html`：尝试 fetch/XHR/WebSocket。
4. `remote-resource.html`：远程 script/CSS/image beacon。
5. `navigation-escape.html`：iframe、window.open、form、meta refresh。
6. `active-attribute-variants.html`：HTML entity、大小写变化后的主动属性与 `data-sf-url` 边界。
7. `active-css.html`：CSS `@import` / remote `url()` 及 comment/escape 变体。
8. `active-apis.html`：sendBeacon、Worker 与 DOM 写入等主动 API。
9. `device-permission.html`：file input/capture 与 mediaDevices 权限入口。
10. `active-navigation.html`：base / location 导航。
11. `active-obfuscation.html`：动态解码/代码构造。
12. `bridge-abuse.html`：未知 method、伪造 artifact id、超大 payload、重复 submit（由原生 Bridge 测试拒绝，
    因平台模板本身需要受控 bridge，不能由 HTML scanner blanket 拒绝）。

V1 不解压 zip，因此 Zip Slip、symlink、重复 entry、深路径和 zip bomb 在输入模型层被消除；任何 zip 输入
都应因 MIME/扩展名不符被拒绝。

## 安全发布门

以下任一项失败即 blocker：ownership 绕过、网络请求成功、token/transcript 可见、任意本地文件读取、页面
可静默写消息、未知 bridge method 被接受、HTML 字面量进入持久化 transcript。
