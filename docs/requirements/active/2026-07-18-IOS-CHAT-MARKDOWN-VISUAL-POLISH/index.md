# IOS-CHAT-MARKDOWN-VISUAL-POLISH — 蓝色 Query 与 Markdown 阅读体验

> 状态：implemented / Mid automated verified（337/337）；待真机视觉与 VoiceOver 确认
> 模式：Mid
> 优先级：P1

## 用户问题

1. 用户 Query 改成蓝色是否比当前暖桃色更好看。
2. Assistant 返回较长 Markdown 时，希望标题、结论、列表、引用和代码更容易扫读，并先在原型中看几个真实例子。

原型入口：[iOS Companion Prototype](../../../prototypes/ios-assistant-companion/index.html)

## Query 颜色结论与用户决策

蓝色能更明确地区分“用户输入”和“Assistant 结果”，但高饱和实心蓝会在长 Query 上形成很重的色块，并与
SkillForge 橙色主品牌争夺注意力。原型比较了三种方案：

| 方案 | Light | Text | 对比度 | 判断 |
| --- | --- | --- | --- | --- |
| 历史暖桃 | `#FDEBE2` | `#352017` | 13.25:1 | 品牌一致、较温暖 |
| 柔和蓝 | `#EAF2FF` | `#17233A` | 13.93:1 | **用户已选择并实现**；角色清楚且适合长文本 |
| 高饱和实心蓝 | `#3478F6` | `#FFFFFF` | 4.07:1 | 色块过重，普通正文对比余量较小 |

生产已使用浅色 `#EAF2FF` + `#17233A`、深色 `#182A44` + `#F5F8FF`，并分别配套低强调边框。
橙色继续只承担品牌和主要操作，蓝色只承担用户轮次 surface。

## Markdown 方案比较

### A. 只调字体和间距

不改 parser；统一 heading、列表、引用与代码的 padding/颜色。风险最小，但 H1/H2/H3 仍无法形成层级，长回复提升有限。

### B. 保留现有安全边界的语义块升级（已采用）

- 保留 Markdown → SwiftUI block，不执行 HTML。
- parser 保存 H1/H2/H3 level；标题形成三档字号和段前间距。
- 引用呈现为低强调度 callout，但不凭空改写文本或增加不存在的标签。
- 无序列表使用小蓝点和稳定缩进；有序列表使用数字 badge，增强流程扫读。
- 代码块增加 language header、等宽正文和复制入口；横向滚动仍保留。
- 链接继续走系统 Link，不允许任意脚本或 HTML。

这能覆盖大多数任务总结、调研报告和执行步骤，不扩大到 table/citation parser。

### C. Rich Markdown（未纳入）

table、footnote/citation、task list、图片等结构会扩大 parser、无障碍、横向布局和协议边界，应另立需求，
不与本轮视觉优化混做。

## Current 原型与生产形态

1. **Chat / Blue Query**：Current 使用用户已选的柔和蓝；三色比较仅保留为已完成的 Design record。
2. **Markdown · 调研报告**：Current 展示三级标题、要点列表、引用和链接。
3. **Markdown · 执行结果**：Current 展示数字步骤、代码语言栏、精确复制和可见的“已复制”反馈。

Markdown 示例只使用真实 SwiftUI renderer 已支持的语义，不伪造 table、citation、HTML 或额外 callout 类型。

## 原型与实现验证（2026-07-18）

- inline JavaScript 可编译；11 个 screen ID 唯一；Markdown 两页已迁入 Current；最终默认仅 Current Chat active。
- Query Design record、Markdown 调研报告、Markdown 执行结果完成 Quick Look 静态渲染和人工复核。
- 首次渲染发现长 Query 对比卡片横向溢出，已通过容器 `min-width: 0`、固定 82% 气泡宽度与安全换行修复，
  复渲染无裁切。
- 应用内浏览器当前无可连接实例，且其运行时故障说明资源缺失；因此没有把模式切换和点击操作记录为 PASS。
- 颜色、标题层级与精确复制的聚焦 XCTest/XCUITest 均通过；最大辅助字号截图暴露代码 header 过度放大后，
  已限制 header Dynamic Type，代码正文仍保留大字号与横向滚动。
- 完整 scheme：337/337 PASS（268 XCTest + 69 XCUITest，0 failure、0 skipped），结果包：
  `/tmp/SkillForge-Markdown-Full-20260718.xcresult`。
- Release simulator build：PASS，derived data：`/tmp/SkillForge-Markdown-Release-20260718`。
- XcodeGen 连续生成的 `project.pbxproj` SHA-256 均为
  `048f4ac6b474317a5fc9d7718020a1d327a3b2f80e1357e4c4547fe28dab86ed`。

## 验收结论与剩余发布门

1. 柔和蓝 Query、Light/Dark/XXXL、普通字号 user ≤80% 策略已自动化验证。
2. Markdown 标题、段落、两种列表、引用、代码和链接已有真实 SwiftUI/XCTest/XCUITest 覆盖。
3. 代码复制点击区为 44pt；单元测试证明 exact payload 转发，UI 测试证明“复制”到“已复制”的可见反馈。
4. 未新增任意 HTML、table/citation 虚假承诺或 Assistant 内容重写。
5. 真机视觉、VoiceOver 顺序与系统剪贴板交互仍为 `NOT_RUN`，不影响 simulator 自动化完成结论。
