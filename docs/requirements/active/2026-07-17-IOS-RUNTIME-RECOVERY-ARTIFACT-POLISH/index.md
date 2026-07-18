# IOS-RUNTIME-RECOVERY-ARTIFACT-POLISH — iOS 失败恢复与 Personal App 体验收口

> 状态：implemented / Full pipeline verified（2026-07-17）
> 优先级：P1；其中错误状态颜色与安全 Retry 为 P0 UX 修复
> 模式：Full（ChatView、移动端契约、Agent runtime、Artifact 安全与视觉跨层）

## 用户反馈

真机 dogfood 暴露了五类问题：

1. iOS 顶部把“运行异常”显示成绿色成功态。
2. Web 已支持安全 Retry，iOS 没有对应入口。
3. 真机 Personal App 消息卡片明显弱于已批准原型。
4. Agent 生成的 HTML 缺少 SkillForge 设计语言，与 App shell 割裂。
5. Artifact 工具提示、workspace 约束和安全扫描器互相冲突，Agent 会选错工具、复用旧 run 路径，
   甚至把原文 URL base64 编码来绕过扫描器。

用户同时要求把 Harness、模型服务、网络、工具和用户动作的失败责任区分清楚，不能统一显示为
“运行异常”。

## 目标

- 正确表达运行状态的颜色、文案、失败来源和可恢复性。
- iOS 仅在服务端判定无副作用且 `retryable` 时提供 Retry。
- 让真实 Personal App 卡片追平 Target 原型，而不是继续视觉漂移。
- 为 Agent 提供 SkillForge 原生 Personal App 模板与设计约束，减少每次临场生成的质量波动。
- 修正 Artifact 发布契约，使普通文件、交互 HTML、当前 run workspace 和 URL 安全边界清晰一致。

## Harness 故障分类需求

| failure source | 示例 | 用户文案 | 默认可重试 |
| --- | --- | --- | --- |
| `model_provider` | 401、429、provider 5xx、模型拒绝 | 模型服务异常 | 按错误码决定 |
| `network` | 连接失败、首包超时、零 SSE chunk | 模型连接异常 | 无副作用时是 |
| `tool` | 参数校验、workspace 越界、工具执行错误 | 工具执行失败 | 否；交回 Agent 修正 |
| `harness` | loop、消息配对、持久化、状态机异常 | Agent Runtime 异常 | 默认否 |
| `user_action` | 用户取消、确认超时 | 已取消 / 等待已超时 | 否 |
| `unknown` | 尚未分类 | 未知运行异常 | 否 |

服务端是分类与 `retryable` 的唯一事实源；客户端不得靠错误字符串猜测是否安全重试。

## 当前范围

### A. iOS 状态与 Retry

- `running` 使用运行色，`waiting_user` 使用警告色，`error` 使用红色，`cancelled` 使用灰色。
- 错误 Banner 显示失败来源、简短原因和“是否产生副作用”。
- 仅当 `runtimeStatus=error && retryable=true` 时显示 Retry。
- Retry 调用移动端受控入口 `POST /api/mobile/client/sessions/{sessionId}/retry`，loading 期间防重复点击。
- REST catch-up、WebSocket 重连和 App 重启后必须保持同样状态。

### B. Personal App 消息卡片

- 真实 SwiftUI 卡片按 Target 原型优化：预览区、`PERSONAL APP` badge、离线/权限 metadata、来源与时间、
  主操作按钮和分享操作形成清晰层级。
- 不再让全局橙色 tint 把主按钮渲染成与原型不一致的大面积橙色。
- 卡片支持浅色/深色、Dynamic Type、VoiceOver 和窄屏。

### B2. App 首帧与 Library 加载体验

- 系统 `UILaunchScreen` 必须提供与 App 首帧一致的 SkillForge 背景与品牌标识，不能使用空配置产生冷启动白屏。
- SwiftUI 恢复已配对端点期间显示品牌恢复页，不提前展示受保护的缓存内容，也不绕过原有设备/端点校验。
- Personal Apps Library 首次拉取期间保留搜索和筛选框架，并显示与最终卡片同构的骨架屏；加载失败继续进入明确的
  offline/unavailable 恢复状态。

### C. Personal App HTML 视觉基线

- 提供平台维护、可通过固定 `template_id` 直接发布的 mobile-first starter template/design tokens，而不是只要求“生成好看的 HTML”。
- 页面与 App shell 对齐字体、圆角、间距、背景、按钮、safe area、浅色/深色与无障碍语义。
- 业务数据与视觉模板分离；AI 早报、预算规划器至少各有一份参考实现。
- 标准页面使用平台模板与结构化数据；需要定制信息架构时，Agent 可在当前 run workspace 生成自定义 HTML，
  但不得通过混淆、base64 或动态解码规避安全校验。

### D. Artifact 工具契约与安全

- workspace 系统提示同时解释 `PublishChatArtifact`、`PublishInteractiveArtifact.template_id` 与自定义 `file_path` 的适用类型。
- 明确当前 run 路径；禁止直接发布历史 run 路径，并指导 Agent 在当前 workspace 重新生成。
- `PublishChatArtifact` 的描述明确拒绝 HTML；`PublishInteractiveArtifact` 明确要求 manifest 参数。
- 扫描器从“全文出现 URL 即拒绝”收敛为“拒绝主动网络/导航能力”：远程 `src/href`、fetch/XHR、
  WebSocket、EventSource、location/window.open、form/meta refresh 等。
- 是否允许把普通原文 URL 作为 inert data 展示，必须经过威胁模型复审；V1 不新增静默外网访问。
- Clipboard 仍不作为网页隐式权限开放；分享/打开链接应走受控原生动作和用户确认。

## 验收标准

1. 真机/模拟器错误状态为红色，不能再出现绿色“运行异常”。
2. 首包超时且零工具调用时 iOS 显示 Retry；点击后不重复用户消息，成功恢复原 turn。
3. 已执行有副作用工具的失败不显示 Retry。
4. 错误 UI 明确区分 model/network/tool/harness/user_action。
5. Personal App 卡片与 Target 原型的组件层级、视觉令牌和文案通过人工对照。
6. AI 早报页面基于平台模板生成，与 App shell 在字体、间距、颜色和操作层级上协调。
7. Agent 首次选择正确发布工具，并使用当前 run workspace；不再需要用户提示其换工具或复制路径。
8. 原文 URL 不需要 base64 绕过；主动网络、导航逃逸和静默 clipboard 仍被阻止。
9. iOS XCTest、URLProtocol contract test、专属 XCUITest、Server regression 与真机 dogfood 全部有证据。
10. 冷启动和 Library 首次加载不再出现纯白空窗；系统 Launch Screen、SwiftUI 恢复页和 Library skeleton 之间语义连续。

## 分阶段建议

1. **P0**：错误红色 + iOS 安全 Retry，复用现有 Web Retry 契约。
2. **P1-A**：Personal App 卡片追平原型。
3. **P1-B**：Personal App starter template 与 AI 早报参考页面。
4. **P1-C**：Artifact prompt/tool contract 与扫描器精确化、安全复审。
5. **P1-D**：结构化 failure source 契约与跨端责任展示。

## 实施与验证记录（2026-07-17）

- P0–P1-D 已全部实现；服务端使用结构化 failure fact、保守副作用证据与统一 Retry gate，iOS 使用红色失败态、灰色取消态和安全 Retry。
- 系统 `UILaunchScreen` 已从空字典改为自适应背景 + SkillForge mark；SwiftUI 恢复阶段使用全屏品牌恢复页，
  Library 冷加载使用稳定 header + 两张同构卡片骨架。设备 token 与 endpoint 校验时序保持不变。
- V175 对 failure fact 做数据库原子性约束：错误态必须具备非空 source/code/sideEffects/sanitized error；非错误态不得残留旧 failure fact 或 runtime error。
- 后端最终完整 reactor：3924 tests，0 failures，0 errors，180 skipped，5 个模块 `BUILD SUCCESS`。
- iOS 全量 XCTest：263/263；完整 XCUITest：60/60；合计 323/323，Release simulator build 通过。
- XcodeGen 连续两次生成哈希一致；`git diff --check` 通过。
- 真机签名、APNs、后台行为与实际网络 dogfood 留给用户验收，不以模拟器结果替代。

## 非目标

- 不为收到过 SSE chunk 或执行过有副作用工具的 turn 自动重试。
- 不允许 HTML 直接访问网络、token、完整 transcript、文件系统或任意设备权限。
- 不把所有 Markdown 结果强制转换为 Personal App。
- 不在本包中建设独立 Personal App Library。

## 关联需求

- [IOS-INTERACTIVE-ARTIFACTS](../2026-07-16-IOS-INTERACTIVE-ARTIFACTS/index.md)
- [IOS-PERSONAL-APP-LIBRARY](../2026-07-17-IOS-PERSONAL-APP-LIBRARY/index.md) — 跨 Session 聚合、搜索和再次打开。
- [IOS-PROTOTYPE-APP-PARITY](../2026-07-16-IOS-PROTOTYPE-APP-PARITY/index.md)
- [IOS-ASSISTANT-COMPANION](../2026-07-09-IOS-ASSISTANT-COMPANION/index.md)
