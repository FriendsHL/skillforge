# IOS-PROTOTYPE-APP-PARITY — iOS 原型与真实 App 一致性

> 状态：Current 基线已同步至柔和蓝 Query 与安全 Markdown 语义块；新一轮 Chat/Control/Agents Target 待设计
> 模式：Full（可能触及多页面 SwiftUI、导航、视觉设计、XCUITest 与原型资产）
> 优先级：P1，Interactive Artifact 实施前置设计门

## 用户反馈

当前 `docs/prototypes/ios-assistant-companion/index.html` 与已经实现的 SkillForge iOS App 在导航、页面
结构、视觉样式和部分交互上存在明显差异。后续希望原型图与 App 的真实展示保持一致：要么 App 按目标
原型优化，要么原型按当前 App 重画，不能让两套界面继续各自演进。

## 目标

建立一个可持续的一致性工作流，使原型能够准确表达：

1. 当前已经实现并可运行的 App 状态。
2. 已批准但尚未实现的目标状态。
3. 两者之间明确、可追踪的差异，而不是混在同一张原型里。

## 推荐原则

采用“双层原型”，不在“只信原型”与“只信实现”之间二选一：

- **Current / As-built**：以真实 App 和稳定截图为事实源，原型必须同步当前导航、组件和状态。
- **Target / Proposed**：展示已批准的未来改动，或用户明确要求先看的视觉候选；每个差异必须链接需求 ID，
  并明确区分“待确认”与“已批准”。
- 未批准概念不得伪装成已经存在的页面；已实现页面也不得继续沿用过期原型。

## 立项时已知漂移（已处理）

- 原型仍是早期四 Tab：Chat / Sessions / Pending / Settings；当前 App 已采用 Companion Tab 结构，包含
  Chat、Control、Agents、Settings 等真实页面。
- 原型中的 Push Inbox、独立 Sessions/Pending 页面、Quick Actions sheet 与真实 App 当前信息架构不完全一致。
- 真实 App 已包含附件下载、连接健康、Agent 列表、schedule/control 等实现，而旧原型覆盖不完整。
- 原型视觉基于早期自定义 light shell；真实 SwiftUI 的间距、字体、颜色、导航栏、tab bar 和系统组件表现
  已经发生变化。
- 立项时 Personal App 页面只是目标态；Interactive Artifact 与 Personal Apps Library 落地后，已在 2026-07-17
  合并回 Current，并保留真实 SwiftUI owner、accessibility ID 与自动化证据。

## 功能需求

1. 先采集当前 App 的关键页面与状态截图，建立 As-built 基线。
2. 重画现有原型，使导航结构、页面名称、组件层级、文案、颜色和关键状态与真实 App 一致。
3. 原型提供 Current / Target 明确切换或分区；Target 页面展示需求 ID 与未实现标识。
4. 每个关键页面维护一张 parity checklist：原型元素、SwiftUI owner、实现状态、XCUITest/截图证据。
5. 后续所有显著 iOS UI 需求先更新 Target prototype；实现完成并验收后再合并到 Current prototype。
6. App UI 改动如果偏离已批准原型，必须在评审中明确说明并更新原型或修正实现。
7. Prototype 不保存 token、不访问真实后端；deterministic fixture 与生产 View 尽量共享布局语义。

## 实施方案

### Phase 1：原型追平当前 App（推荐先做）

- 从真机/模拟器采集 Chat、Control、Agents、Settings、附件、pending interaction、连接异常等关键状态。
- 对照 SwiftUI hierarchy 与 accessibility identifiers 建页面清单。
- 把旧原型重构为 Current 基线，删除或移到 Proposed 的虚构/过期入口。
- 尚未实现的 Personal App 卡片和全屏 Viewer 保留在 Proposed 区域；实现并通过开发门后再迁入 Current。

### Phase 2：评估 App 是否按目标原型优化

- 对 Current 与 Proposed 做逐页差异评审。
- 用户选择需要落地的视觉/交互改进。
- 每个改进拆成独立验收项，按风险进入 Mid/Full 实现，避免一次性重写整个 App。

### Phase 3：建立持续校验

- XCUITest deterministic fixtures 生成稳定截图。
- 原型页面与 App 页面共享状态命名和 accessibility ID 对照表。
- PR/Full pipeline iOS review 增加 prototype parity 检查。

## 验收标准

1. 原型 Current 导航与真实 App Tab、页面名称和关键入口一致。
2. Chat、Control、Agents、Settings 至少各覆盖正常、loading/empty、error/offline 中相关状态。
3. Current 原型中的每个关键控件都能在 App 中找到；Target 独有控件均标记 Proposed + requirement ID。
4. App 中已实现的关键页面不得在 Current 原型缺失。
5. 使用同一页面清单进行一次人工视觉对照，所有 blocker drift 清零。
6. 建立可重复的 simulator screenshot 命令和截图索引。
7. Interactive Artifact 开工前，Personal App 的 Target 原型必须建立在最新 Current shell 上。

## 非目标

- 本需求不直接决定所有视觉差异都以原型为准。
- 不在一次变更里重写全部 SwiftUI 页面。
- 不追求 HTML 原型与 iOS 系统渲染逐像素一致；要求信息架构、状态、组件语义和关键视觉令牌一致。
- 不把截图测试当作唯一 UI 验收；关键交互仍需 XCUITest 和 accessibility 断言。

## 下一步

Phase 1 及后续已批准页面同步已经完成：原型先追平 Chat / Control / Agents / Settings，随后把已经落地的
Personal App 消息卡片、全屏 Viewer、Personal Apps Library、品牌恢复页和 Library 骨架加载态迁入 Current。
逐页 owner、状态和验证证据见
[parity-matrix.md](parity-matrix.md)。

`IOS-CHAT-MARKDOWN-VISUAL-POLISH` 已由用户选择柔和蓝 Query、语义 Markdown 和代码复制，并通过 Mid 实现门
迁入 Current。下一步继续真机视觉确认；新的 Chat 顶栏、工具卡片与 Control/Agents 信息架构先进入 Target 原型，
用户确认后再修改生产 SwiftUI。

## Agent-first Chat Current 同步（2026-07-18）

- 根据“手机端应让 Agent 回复占比更大、弱化管理端卡片感”的反馈，新增
  `IOS-AGENT-FIRST-CHAT` 目标页，用户确认后已按 Full pipeline 落到生产 SwiftUI。
- Current 现采用紧凑柔和蓝用户 Query、接近全宽的 Agent 正文、内联 Tool/文件/Personal App 和紧邻 Tab Bar 的输入区；
  已删除原型中生产不存在的复制、重新生成、朗读、回复分享及模型/模式控件。
- 独立的 Personal App Card 场景复用同一 Agent-first header、assistant turn 与 composer，不再保留旧聊天壳。
- Current/As-built 以真实 surface identifier、生产 `CompanionTabView` fixture 和 light/dark/XXXL 截图为事实源；
  蓝色 Query 与 Markdown 完成开发门后已迁入 Current；三色选择仅作为 Design record 保留。
- 最新完整 scheme 已于 2026-07-18 通过 337/337（268 XCTest + 69 XCUITest），Release simulator build
  通过；真机视觉与 VoiceOver 仍为 `NOT_RUN`。
- 自动浏览器运行时在当前环境不可用；HTML 使用 DOM/JavaScript 冒烟、Quick Look 静态渲染与人工截图复核，
  关键生产交互仍由 XCUITest 证明，不声称浏览器点击验证通过。

## Query / Markdown Current 同步（2026-07-18）

- 用户选择柔和蓝 Query；生产 token、Light/Dark/XXXL 自动化与 Current Chat 已同步。
- Markdown Current 只使用生产 renderer 支持的三级标题、列表、引用、链接、代码语言栏与精确复制反馈；
  不执行 HTML，也不承诺 table/citation。
- 三页均完成 JavaScript/DOM 断言和 Quick Look 静态渲染复核；应用内浏览器当前无可用实例，
  因此点击切换仍未记为 PASS。
- 完整 iOS scheme 337/337 与 Release simulator build 通过；结果包为
  `/tmp/SkillForge-Markdown-Full-20260718.xcresult`。

## Phase 1 实施记录（2026-07-17）

- 使用 `--ui-testing-tabs` deterministic fixture 在 iPhone 17 Pro / iOS 26.5 模拟器采集四个当前页面。
- 新增 `PrototypeParityUITests`，逐 Tab 断言真实页面的关键 accessibility element，并附加稳定截图。
- 重构 HTML 原型为 Current / Proposed 双层结构；移除旧 Sessions / Pending / Push Inbox 导航。
- Current 保留真实的四 Tab shell、页面层级与关键状态；Personal App 只存在于 Proposed，且标记需求 ID。
- HTML DOM 冒烟覆盖模式切换、六个页面切换、预算滑杆重算与重置。
- 本阶段没有把 Personal App 功能写入生产 SwiftUI，也没有改变真实 App 的视觉实现。

## 后续 Current 同步记录（2026-07-17）

- `IOS-INTERACTIVE-ARTIFACTS`、`IOS-RUNTIME-RECOVERY-ARTIFACT-POLISH` 和 `IOS-PERSONAL-APP-LIBRARY`
  完成开发门后，Personal App 消息卡片、Viewer 与跨 Session Library 已从 Proposed 迁入 Current。
- 增加真实冷启动的 SkillForge 品牌恢复页，以及 Library 首次请求的筛选框架 + 同构卡片骨架，避免白屏/空窗跳变。
- Library Current 卡片同步真实实现：64×82 monogram、最多两行/96 字符摘要、来源与时间事实、离线/权限状态、
  Open/Source/Share/Clear 操作，以及 unavailable 红色语义。
- 自动浏览器控制在本机验证环境不可用；本轮使用 inline JavaScript 语法检查、DOM marker/count 冒烟与 macOS
  Quick Look 静态渲染兜底。真实交互与视觉事实仍以 XCUITest 截图和用户真机确认作为最终证据。
