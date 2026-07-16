# IOS-PROTOTYPE-APP-PARITY — iOS 原型与真实 App 一致性

> 状态：Phase 1 implemented，待用户视觉确认
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
- **Target / Proposed**：只展示已批准的未来改动，并为每个差异链接对应需求 ID。
- 未批准概念不得伪装成已经存在的页面；已实现页面也不得继续沿用过期原型。

## 当前已知漂移

- 原型仍是早期四 Tab：Chat / Sessions / Pending / Settings；当前 App 已采用 Companion Tab 结构，包含
  Chat、Control、Agents、Settings 等真实页面。
- 原型中的 Push Inbox、独立 Sessions/Pending 页面、Quick Actions sheet 与真实 App 当前信息架构不完全一致。
- 真实 App 已包含附件下载、连接健康、Agent 列表、schedule/control 等实现，而旧原型覆盖不完整。
- 原型视觉基于早期自定义 light shell；真实 SwiftUI 的间距、字体、颜色、导航栏、tab bar 和系统组件表现
  已经发生变化。
- 新增 Personal App 页面目前是目标态，不是已实现功能，需要明确标记为 Proposed。

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
- Personal App 卡片和全屏 Viewer 保留在 Proposed 区域。

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

Phase 1 已完成：原型已追平当前 App 的 Chat / Control / Agents / Settings 信息架构，并将 Personal App
卡片及全屏 Viewer 隔离在 Proposed 模式。逐页 owner、状态和验证证据见
[parity-matrix.md](parity-matrix.md)。

下一步由用户视觉确认 Current 与 Proposed；确认后再决定哪些 Proposed 视觉改进反向优化真实 App，并启动
`IOS-INTERACTIVE-ARTIFACTS` Full pipeline。

## Phase 1 实施记录（2026-07-17）

- 使用 `--ui-testing-tabs` deterministic fixture 在 iPhone 17 Pro / iOS 26.5 模拟器采集四个当前页面。
- 新增 `PrototypeParityUITests`，逐 Tab 断言真实页面的关键 accessibility element，并附加稳定截图。
- 重构 HTML 原型为 Current / Proposed 双层结构；移除旧 Sessions / Pending / Push Inbox 导航。
- Current 保留真实的四 Tab shell、页面层级与关键状态；Personal App 只存在于 Proposed，且标记需求 ID。
- HTML DOM 冒烟覆盖模式切换、六个页面切换、预算滑杆重算与重置。
- 本阶段没有把 Personal App 功能写入生产 SwiftUI，也没有改变真实 App 的视觉实现。
