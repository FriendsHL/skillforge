# 调研报告：SkillForge 手机端 Interactive Artifact / Personal App
> 日期：2026-07-16
> 结论级别：具备产品与工程可操作性；推荐受控 V1，不推荐恢复旧的“Markdown 旁直接渲染任意 HTML”方案

## 1. 研究问题

本报告回答四个问题：

1. 个人手机端把 Task 结果变成即时生成的小应用，是否比 Markdown/文件更合适？
2. 行业是否已经证明这种交互形态，而非概念演示？
3. SkillForge 之前尝试 HTML 输出为什么“不太好用”，本次怎样避免重蹈覆辙？
4. 在现有 Java Agent Loop、附件交付和 SwiftUI App 上，能否以可控成本落地？

## 2. 结论摘要

### 2.1 核心判断

手机端更适合“操作型结果”，电脑端更适合“生产型与审阅型结果”。因此 Interactive Artifact 的价值
不是让 HTML 替代 Markdown，而是给 Task 结果增加一个新的交付形态：

| 用户目标 | 最佳形态 |
| --- | --- |
| 快速知道结论 | Markdown 摘要 |
| 保存、打印、交付给别人 | PDF / Word / Excel / CSV / 图片 |
| 筛选、调整、打勾、模拟、逐项执行 | Personal App |

Personal App 最适合预算规划器、旅行日程、健身/饮食计划、交互测验、数据筛选、清单执行、参数模拟、
审批对比等“用户下一步是触摸控件而不是继续阅读”的结果。

### 2.2 推荐产品公式

```text
Task result = Markdown summary + optional file artifacts + optional interactive artifact
```

三者互补，而不是三选一。消息始终有可搜索、可引用、可降级的文本摘要；正式文件负责长期交付；
Personal App 负责短期操作。

## 3. 行业证据

### 3.1 Claude：Artifact 已从展示窗演进为可复用应用

Anthropic 在 2024 年宣布 Artifacts 全量可用时，已经覆盖网站和交互仪表盘，并明确支持 iOS 与
Android 查看。官方披露预览期用户已创建数千万个 Artifact：
[Artifacts are now generally available](https://www.anthropic.com/news/artifacts)。

2025 年 Anthropic 进一步把 Artifact 定义为可分享、可与 Claude 交互的 AI-powered app，而不是
单次代码预览，并披露累计创建超过五亿个 Artifact：
[Turn ideas into interactive AI-powered apps](https://www.anthropic.com/news/build-artifacts)。

这证明两个事实：

- 用户愿意从对话中生成应用，而不仅是阅读回答。
- 成功形态是独立的 Artifact 空间、版本和分享模型，不是在聊天气泡里直接执行一段任意 HTML。

### 3.2 Gemini Canvas：研究报告可以转为网页、测验和小应用

Gemini Canvas 官方支持把 Deep Research 结果转换成 web page、infographic、quiz 和 audio overview，
也支持直接生成可工作的 app/game；Canvas 项目可在移动 App 中访问：
[Gemini Canvas](https://gemini.google/gp/overview/canvas/)。

这个路径与 SkillForge 场景高度一致：长 Task 在电脑/Agent 端完成，手机端消费的不只是长报告，而是
由报告派生的可操作界面。

### 3.3 ChatGPT Apps SDK：对话结果正在变成“逻辑 + UI”

OpenAI Apps SDK 允许开发者定义同时包含逻辑和界面的 app，并在 ChatGPT 内运行；其基础是 MCP，
目标是让交互应用嵌入对话而不是跳出对话：
[Build with the Apps SDK](https://help.openai.com/en/articles/12515353-build-with-the-apps-sdk.iso)。

OpenAI 对这种形态的描述强调地图、播放列表、演示等熟悉控件与对话结合：
[Introducing apps in ChatGPT](https://openai.com/index/introducing-apps-in-chatgpt/)。

### 3.4 MCP Apps：行业开始收敛到可移植 UI Resource 协议

MCP Apps 已把交互 UI 定义为正式扩展：Tool 声明 `ui://` resource，Host 在 sandboxed iframe 中渲染，
页面通过受控 `postMessage` / JSON-RPC 与 Host 通信：
[MCP Apps Overview](https://modelcontextprotocol.io/extensions/apps/overview)。

协议同时提供：

- inline / fullscreen / picture-in-picture 展示模式；
- 声明式 CSP 与网络域白名单；
- camera、microphone、geolocation 等显式权限请求；
- Host 控制 tool call、open link 和上下文回写；
- 页面不能读取 Host DOM、Cookie 或 storage。

这为 SkillForge 提供了长期兼容方向：V1 可先实现本地 InteractiveArtifact，协议字段尽量向 MCP Apps
靠拢，后续再支持 MCP server 提供的 UI resource。

## 4. 为什么个人手机是合适的试验场

### 4.1 手机天然偏向“短动作闭环”

手机触摸交互适合 tap、swipe、drag 等直接操作。Apple HIG 将这些视为跨平台标准手势：
[Gestures](https://developer.apple.com/design/human-interface-guidelines/gestures)。

Markdown 要求用户阅读、记住状态，再用自然语言描述修改；Personal App 可以把状态和合法操作直接
呈现出来。例如预算从 5000 调到 6500，滑块比“请把预算调整到 6500 后重新计算”更快、更少歧义。

### 4.2 个人场景的数据范围更小、生命周期更短

个人旅行表、购物清单、健身计划、家庭预算和学习测验通常只服务一个人、一个 Task，适合即时生成，
不需要先设计组织级权限、多人协同和长期部署。这降低了 V1 的产品复杂度。

### 4.3 全屏比气泡内嵌更适合复杂操作

Apple 建议 WebView 用于在不离开 App 上下文的情况下短暂访问丰富 Web 内容，而不是在 App 内复制
完整浏览器：[Web views](https://developer.apple.com/design/human-interface-guidelines/web-views)。

因此建议消息中只显示摘要卡片，复杂交互进入全屏容器。这样既保留聊天上下文，也不会把可用宽度
压缩成难用的小 iframe。

## 5. SkillForge 旧方案复盘

仓库历史记录中存在 `GEN-UI-HTML-RENDERING` 调研项，来源是用户希望 chart/可视化输出。旧提案主线是：

- 给 `ContentBlock` 增加 `html`；
- 在 MarkdownRenderer 旁增加 HTML 卡片；
- 甚至自动把 fenced `html` code block 升级为渲染结果；
- 中期再开放 iframe script，长期接 MCP Apps。

历史位置：`docs/references/legacy-todo-2026-06-16.md`，原提交 `85292591`。

这个方案“不太好用”并不意外，主要是产品对象选择错误：

1. **把展示格式当成用户成果。** HTML 是实现载体，不是稳定的产品对象；没有 title、manifest、状态、
   capability、版本和降级摘要。
2. **气泡尺寸不适合应用。** 复杂页面嵌进 Markdown 流后宽度、滚动、键盘、返回和全屏关系都不清楚。
3. **自动升级代码块不可预测。** 用户想看源码还是运行页面无法区分，模型偶然输出 HTML 就可能改变 UI。
4. **持久化形状风险大。** HTML 字面量塞进消息会扩大 token、影响 compact/cache，还可能触发消息形状
   不一致；历史调研已经明确记录这一风险。
5. **没有强降级路径。** Dashboard、iOS、微信、飞书无法一致渲染；如果 HTML 是唯一结果，其他端会丢失
   语义。
6. **安全边界过晚。** sanitize 只适合只读标签；一旦允许 JavaScript，必须采用独立 origin/沙箱/CSP/
   bridge allowlist，而不是继续扩充 DOMPurify allowlist。

本次手机方案的关键变化是：不再让消息本身变成 HTML，而是消息引用一个一等公民的受管 Artifact。

## 6. 推荐体验

### 6.1 生成

用户说：“根据这份账单做一个可以调整分类和预算的手机页面。”Agent 先完成数据整理，再显式调用
发布工具，产出：

- Markdown：结论与使用说明；
- InteractiveArtifact bundle：manifest、index.html、可选本地资源、初始 data.json；
- 可选 CSV/PDF：用于导出和长期保存。

### 6.2 聊天气泡

气泡显示 Personal App 卡片：标题、用途、数据更新时间、离线/权限标识、“打开”按钮。卡片不直接运行
任意脚本，因此聊天列表滚动保持稳定。

### 6.3 全屏运行

全屏 Host 提供原生导航栏：返回、标题、权限状态、更多菜单。页面区域由 WKWebView 承载。返回聊天后，
卡片可显示“已调整 3 项”之类的本地状态，但不能让页面直接修改 transcript。

### 6.4 与 Agent 继续协作

V1 可提供原生“把当前设置发给 Agent”按钮。Host 从 Artifact 读取受 schema 限制的 state snapshot，展示
预览并经用户确认后追加一条结构化用户消息。页面本身不能静默发消息。

## 7. 技术可操作性

### 7.1 与现有能力的复用

SkillForge 已有：

- 每个 run 的受管 artifact workspace；
- `PublishChatArtifact` 安全导入与 user/session/tool-use 绑定；
- assistant attachment ref sidecar、消息持久化与 WebSocket/REST 重放；
- iOS 鉴权下载、缓存、图片预览、Quick Look 和分享；
- 文件类型、路径包含、大小、hash 和清理租约基础。

因此不用重做文件传输，只需新增一种 artifact kind 和专用 Viewer。最大的新增工作在 manifest 校验、
bundle 封装、WKWebView sandbox、状态桥接和生命周期，而不是传输协议本身。

### 7.2 iOS 承载能力

`WKWebView` 支持加载本地 HTML/文件、控制导航、Cookie和脚本配置：
[WKWebView](https://developer.apple.com/documentation/webkit/wkwebview)。

`WKContentWorld` 可以把 Host bridge 的 JavaScript namespace 与页面脚本隔离，但 DOM 仍共享，因此它是
纵深防御而非完整安全边界：
[WKContentWorld](https://developer.apple.com/documentation/webkit/wkcontentworld)。

V1 应使用 non-persistent website data store、禁止任意导航、默认无外网、独立 custom URL scheme 或
受控本地加载目录，并让所有 Host 能力通过少量 typed message handler 暴露。

### 7.3 服务端模型

推荐新增独立 manifest，而不是把 HTML 放进 ContentBlock：

```json
{
  "schemaVersion": 1,
  "title": "七月家庭预算",
  "entrypoint": "index.html",
  "displayModes": ["fullscreen"],
  "network": { "mode": "none", "domains": [] },
  "permissions": [],
  "state": { "mode": "artifact-scoped", "schema": "state.schema.json" },
  "fallback": { "summary": "7 月预算为 8,200 元，餐饮占比最高。" }
}
```

assistant message 只持久化 `interactive_artifact_ref`、artifact id、title 和 fallback summary；历史喂给
LLM 时使用 fallback/结构化 state 摘要，不重复注入整个 HTML bundle。

## 8. 安全与隐私

### 8.1 威胁模型

模型生成的 HTML/JS 必须视为不可信代码。主要风险包括：

- 读取 Host token、Cookie、其他 artifact storage 或本地文件；
- 通过 fetch、图片、字体、WebSocket、导航进行数据外传；
- 欺骗用户点击伪造的系统/SkillForge 界面；
- 无限循环、巨量 DOM、Canvas 或存储导致资源耗尽；
- bridge 参数注入、越权 tool call、静默发消息；
- bundle 内路径穿越、symlink、远程 script 和动态 import。

### 8.2 V1 默认拒绝策略

- network=`none`；远程 script/style/font/image 全部拒绝。
- 不共享 SkillForge Cookie、Keychain、device token、session bearer。
- 不开放 camera/microphone/location/clipboard/file picker。
- 不开放任意 `window.webkit.messageHandlers`；只暴露版本化、schema 验证的最小桥。
- 外链只能由 Host 捕获，原生确认后交给 Safari。
- bundle 只允许白名单扩展名和规范化相对路径，不允许 symlink。
- 限制 bundle 总大小、单文件大小、文件数、state 大小和页面运行时内存。
- 用户看得到来源 Agent、生成时间、离线状态和所请求权限。

MCP Apps 同样采用 sandbox、CSP、声明权限和 Host 仲裁能力的模型，且默认不声明域就不能联网：
[MCP Apps security](https://modelcontextprotocol.io/extensions/apps/overview)。

## 9. 方案比较

| 方案 | 体验 | 安全 | 跨端 | 对现有消息影响 | 结论 |
| --- | --- | --- | --- | --- | --- |
| Markdown 内直接执行 HTML | 中 | 差 | 差 | 高 | 不采用 |
| HTML 普通附件 + Quick Look | 低 | 中 | 中 | 低 | 只能下载，不像产品能力 |
| 受管 InteractiveArtifact + WKWebView | 高 | 可控 | 可扩展 | 低 | 推荐 |
| 原生组件 JSON tree | 高且原生 | 高 | 需要每端 renderer | 中高 | 长期可选 |
| 直接完整实现 MCP Apps Host | 高 | 标准化 | 最强 | 高 | V2/V3，不作为首版 |

## 10. MVP 建议

### V1：离线 Personal App

- 支持 self-contained HTML/CSS/JS bundle。
- 消息卡片 + 全屏 Viewer。
- 默认无网络、无设备权限、无 tool call。
- artifact-scoped local state。
- fallback Markdown summary。
- 用户主动把 state snapshot 发回 Agent。
- Dashboard 只需安全预览或下载，不要求与 iOS 完全同交互。

### V1.1：受控数据与导出

- 只读读取同 bundle 的 data.json。
- Host 提供系统分享、导出 snapshot、重置状态。
- 加入 App Library / 收藏入口需单独确认。

### V2：Tool Bridge

- manifest 声明能力。
- Host 显示权限和逐次确认。
- bridge 只代理 allowlist tool；所有调用留审计记录。
- 评估兼容 MCP Apps `ui://` resource 和 AppBridge。

## 11. 成功指标与失败条件

建议用 5 个真实个人场景做 dogfood：预算规划、旅行日程、健身计划、学习测验、数据筛选。

成功指标：

- 相比 Markdown，完成一次调整所需消息轮次下降至少 50%。
- 80% 任务能在首次生成后完成核心交互，无布局阻塞。
- 页面首次打开 P95 小于 1 秒（本地 bundle）。
- 0 次未确认网络请求、Tool 调用或跨 Artifact 数据访问。
- 重启后状态恢复；删除 session/device revoke 后不可继续读取受保护 Artifact。

停止或缩减条件：

- 大多数结果仍只是阅读，交互控件没有被使用。
- 模型生成的移动布局稳定性低，修复轮次抵消收益。
- WKWebView 资源占用或无障碍体验明显劣于原生 Markdown。
- 安全模型需要在 V1 就开放广泛网络/Tool 权限才能产生价值。

## 12. 最终建议

批准一个受限的 iOS-first V1 是合理的。它能验证“个人手机上的即时小应用”是否真正减少对话轮次，
同时把风险限制在离线 bundle 内。

不建议批准“任意 HTML ContentBlock 替代 Markdown”；这正是旧方案体验和安全问题的来源。长期方向应是
InteractiveArtifact 一等公民，并逐步向 MCP Apps 的 manifest、sandbox 和 Host capability 模型靠拢。
