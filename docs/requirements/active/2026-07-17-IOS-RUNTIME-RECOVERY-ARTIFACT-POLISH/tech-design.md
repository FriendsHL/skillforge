# Tech Design — iOS Runtime Recovery & Artifact Polish

> 状态：approved / implementation baseline
> 日期：2026-07-17
> Pipeline：Full

## 1. 交付顺序

严格按用户批准顺序实施：

1. P0：错误语义色 + iOS 安全 Retry。
2. P1-A：Personal App 卡片追平 Target 原型。
3. P1-B：平台模板和 AI 早报参考页面。
4. P1-C：发布工具、workspace 和 inert URL 安全契约。
5. P1-D：持久化结构化 failure fact，并统一 REST/WS/iOS 展示。

## 2. P0 — 状态与安全 Retry

### iOS presentation

新增纯 presentation policy，把运行事实映射为文案、语义颜色、图标和 Retry 可见性。`errorText` 继续只代表
连接/API 层错误，不能替代 Session runtime status。

- running：蓝/绿运行语义。
- waiting_user：黄色等待语义。
- error：红色警报语义。
- cancelled：灰色中性语义。
- idle：绿色已连接语义。

### mobile contract

P0 在 `MobileSessionResponse` 增加 `runtimeStep`、`runtimeError` 和服务端计算的 `retryable`。兼容阶段的
`retryable` 由 `runtimeStatus=error && runtimeStep=retryable` 计算，iOS 不直接解析 step。

新增设备鉴权端点：

```http
POST /api/mobile/client/sessions/{sessionId}/retry
Authorization: Bearer <device token>
```

端点要求 `chat:write`、当前用户 ownership，并复用 `ChatService.retryFailedTurnAsync`。返回 202/409/429；
不得接受客户端 userId。iOS Retry 不追加 optimistic user message，等待 WS/REST 真相。

## 3. P1-A — Personal App card

从通用 attachment card 中提取专用 SwiftUI 组件。采用 Target 原型的层级：

- 深色、非执行型视觉预览；不在 transcript 内加载 WKWebView。
- title/caption + PERSONAL APP badge。
- Offline / No permissions / 来源与时间 metadata（只展示真实可得字段）。
- 中性深色全宽 Open CTA + 44pt 分享操作，不继承全局橙色 tint。
- system colors、Dynamic Type 重排、VoiceOver 合并摘要、深浅色适配。

### First-frame continuity

系统启动层使用 asset catalog 中的 `LaunchBackground` 与 `LaunchMark`，由 `project.yml` 生成
`UILaunchScreen.UIColorName/UIImageName`；SwiftUI `AppState.phase == .loading` 使用 `SkillForgeLaunchView` 延续同一
品牌语义。这里不读取或展示会话正文，也不缩短、跳过 `loadStoredSession()` 的 endpoint/device credential 校验。

Personal Apps Library 在没有旧列表可展示且刷新尚未结束时保留 header，并以两张静态同构 skeleton 代替空白内容。
骨架不持续闪烁/平移动画，兼容 Reduce Motion；成功、offline 与 unavailable 仍由原有状态机切换。

## 4. P1-B — platform-owned templates

平台维护两个单文件、自包含、受扫描器验证的参考模板：预算规划器与 AI 早报。统一 CSS tokens：系统字体、
8pt rhythm、12/16/20 radius、safe-area、44px hit target、sticky actions、light/dark、reduced motion。

数据通过 embedded JSON / `initialData` 注入，模板不硬编码用户数据。平台模板不复制到 Agent 可写的
per-run workspace：标准页面通过 `PublishInteractiveArtifact.template_id` 从固定 classpath allowlist 直接发布，
当前仅允许 `ai-daily-brief-v1` 与 `budget-planner-v1`。服务端使用打包 HTML 与 state schema，Agent 只提供
业务 `initial_data` 和展示字段，避免平台原件被篡改、半写入或经父目录换链写出 workspace。

需要自定义信息架构时仍使用原有高级路径：Agent 在当前 run workspace 生成单文件 HTML，再通过
`file_path` 与 manifest 参数发布。`template_id` 与 `file_path` 严格二选一。此设计是针对 macOS/JDK 17
默认文件系统不提供 `SecureDirectoryStream` 的安全收敛；不引入 native `openat`，也不让 workspace 创建
因为平台能力缺失而全量 fail-closed。

## 5. P1-C — Artifact contract and scanner

### prompt/tool contract

workspace prompt 必须给出当前 run 的 canonical absolute path，并明确：普通 image/PDF/Word/Excel/CSV 用
`PublishChatArtifact`，该工具明确拒绝 HTML/HTM；标准 AI 早报/预算 Personal App 用
`PublishInteractiveArtifact.template_id`；自定义单文件离线 HTML 用当前 run 的 `file_path` 与
`state_schema`。历史 run 只能读取作为参考，最终文件必须重新写入当前 run，不能直接 publish 历史路径。
工具执行中的路径/校验/导入错误继续作为 `SkillResult` 返回给 LLM，由模型决定修正参数或重写文件；本阶段不把
tool error 改成 Harness 终态，也不修改 `AgentLoopEngine`。

`state_schema` 与 iOS validator 使用同一显式子集并 fail-closed：type 仅支持 `string`、`number`、`integer`、
`boolean`、`object`、`array`；object 仅支持 `properties`、字符串数组 `required`、以及 boolean 或 schema 的
`additionalProperties`；array 仅支持 schema object `items`；scalar 只允许 `type`。任何其它 keyword（包括
`minimum`、`maximum`、`enum`、`const`、`minLength`）或结构错误都在发布前拒绝；schema 最大 16 KiB、depth 8、
1024 JSON value nodes，与 iOS 使用相同计数。prompt 与 Tool schema description 同步公开这个子集和上限，避免生成“服务端发布成功、iOS 永远不能提交”
的 Personal App。

### URL policy

删除“全文出现 URL 即拒绝”的 blanket regex。允许 URL 的 HTML source 位置严格限定为 text node、实际可解析的
`script[type=application/json]`、受控 `data-sf-url`；manifest `initialData` 不属于 HTML source，同样可以携带
业务 URL。任意 executable script 中的 remote URL literal fail-closed，不能以“普通 JS 数据字符串”名义绕过。

服务端直接声明 Jsoup 依赖，先按 DOM 解码/归一化 tag、attribute、HTML entity，再使用只作用于 style、
executable script 与 inline handler 的有界 regex。结构化拒绝 iframe/object/embed/base、meta refresh；拒绝主动
`src/srcset/href/xlink:href/action/formaction/poster`、script src、link href；拒绝 CSS remote `url()`/`@import`；
拒绝 fetch/XHR/WebSocket/EventSource/sendBeacon/import()/importScripts/window.open/location、clipboard 以及
atob/btoa/eval/Function/String.fromCharCode 等动态混淆。大小写、空白和 HTML entity 变体必须产生相同结论。
平台 P1-B 两个模板必须继续通过同一 scanner。

R1 scanner 的 canonicalization/deny corpus 还包括：`portal`；所有 inline `on*` attribute；CSS comment 与 escape
变体（`@im\70ort`、`u\72l()`、`u/**/rl()`）；JS block/line comment、optional chaining、bracket property、
unicode escape（template literal 的 `${...}` 仍按 executable code 扫描）；
Worker/SharedWorker/serviceWorker、document.write、主动属性 setAttribute、execCommand copy、string timer、
DOMParser、innerHTML/outerHTML/insertAdjacentHTML、String.fromCodePoint。静态 `input[type=file]`/`capture` 以及
mediaDevices/getUserMedia/geolocation 等设备权限入口同样拒绝；DOM comment 扫描使用显式栈，20k 深层 HTML
不得泄漏 `StackOverflowError`。`data-sf-url` 存在时必须 <=2048 UTF-8
bytes、无 control/首尾空白/反斜线/非法 percent escape，并且是带 conservative ASCII host、无 userinfo 的绝对
http/https URL。server/iOS 共享 allow corpus 为 localhost、合法 ASCII DNS/punycode、标准四段 IPv4；underscore、
percent/Unicode authority、空 label、label 首尾 hyphen、IPv4 缩写/前导零/越界值与 IPv6 均拒绝。
普通 attribute、HTML comment 与 CSS 中的 protocol-relative `//host` 必须 token-anywhere 拒绝，不能只检查
字符串开头；executable JavaScript 继续使用独立的 lexical comment/capability 扫描规则。

`PublishChatArtifact` 不只在 description 声明拒绝 HTML/HTM；execute 必须按 final basename（大小写不敏感）
在 import/storage 前返回 validation `SkillResult`，并明确引导使用 `PublishInteractiveArtifact`。

静态扫描只是第一层；CSP `connect-src 'none'`、非持久化 WKWebView、navigation/popup delegate 继续是硬边界。
页面不得用 base64/动态解码规避规则，也不得静默使用 clipboard。原文链接后续通过原生确认的
`requestOpenURL` Bridge 打开，仅允许 http/https。

### iOS runtime hardening

iOS Viewer 必须把通过 scanner 的 HTML 继续视为不可信。CSP 插入器仅在真实 document prefix 中识别 head，
遇到 raw-text/template/textarea/title/text 等 decoy 时先合成 head，保证 CSP 位于资源之前；初始 document 使用
one-shot `about:` navigation gate，Web process 恢复只重新装载原始 secured HTML 并 re-arm gate。

Bridge 在序列化 payload 前校验 main frame、handler、artifact identity 和 method；`saveState` 0.5 秒限速，
`submitSnapshot` 与 `requestOpenURL` 共享 2 秒限速。原生确认 first-wins，取消/消费后保留 2 秒 cooldown。
saved state 必须区分 missing/valid/invalid；invalid 不进入 bootstrap，使用 initialData 并显示非阻断诊断；
schema-invalid submit 不弹确认、不发送消息，诊断在 Viewer 有 manifest 时仍可见。

deployment target iOS 17 没有 iOS 18.4 open-panel delegate 兜底，因此 document-start file guard 必须保存原生
DOM getter/method，锁定 `HTMLElement.click`、`HTMLInputElement.click/showPicker`、type/capture/setAttribute，
并先于页面注册 Window capture。Clipboard guard 同样保存 Reflect/Set/String/Event/dispatch 原生引用，在最早
Window capture 阻断 copy/cut/paste，并锁定 `execCommand` 与 Clipboard read/write。页面后续 prototype tampering
不得改变结论。native delegate 对 iOS 18.4+ open panel 返回 nil，对 media capture 与 motion/orientation 一律 deny。

## 6. P1-D — structured failure fact

V175 在 `t_session` 增加：

- `runtime_failure_source VARCHAR(32)`
- `runtime_failure_code VARCHAR(64)`
- `runtime_retryable BOOLEAN NOT NULL DEFAULT FALSE`
- `runtime_side_effects VARCHAR(16)`，值为 none/possible/observed

失败分类由服务端 `RuntimeFailureClassifier` 产生，客户端不得解析字符串。开始新 turn/retry、成功、等待、取消时
清理旧 failure fact。兼容期双写 `runtimeStep=retryable`；最终 Retry gate 仍以“无工具调用 + 尾部为 user turn”
作为保守硬约束。

REST、mobile REST、`session_updated`、`session_status` 统一返回：source、code、retryable、sideEffects、sanitized
error。初始分类通过 cause chain 与窄范围 HTTP 状态识别；不在本包重构全部 provider exception hierarchy。

终态 WebSocket 到达后若 metadata catch-up 暂时失败，客户端必须通过有界重试或下一次 reconnect/foreground
对账恢复 failure fact，不能永久停留在只有通用红色状态、没有原因和 Retry 判定的半状态。Retry 的 409/429
等错误统一解码受控 error envelope，只展示服务端安全文案，不把原始 JSON body 直接暴露给用户。

## 7. 测试与验收

- Server：移动端 ownership/scope/retry、分类矩阵、状态清理、migration、REST/WS shape。
- iOS unit：presentation policy、DTO backward compatibility、retry request/error mapping。
- XCUITest：红色 error、Retry→Retrying→成功、无重复用户消息、不可重试失败无按钮。
- Card/Template：Dynamic Type、VoiceOver、深浅色、DOM/filter/save/submit、截图人工对照。
- Security：inert URL allow corpus、active network/navigation deny corpus、Bridge confirmation 与 navigation delegate。

## 8. Ownership

- Backend runtime/migration/mobile contract：单一 backend owner。
- Artifact prompt/validator/templates：独立 security owner，避免并行改 ChatService。
- iOS ChatView/API/Card：单一 production owner；测试可由独立 test owner。
- 主会话负责 shared files、review judge、live API/DB、simulator 与 Release verification。
