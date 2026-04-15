---
paths:
  - "**/*.tsx"
  - "**/*.css"
  - "skillforge-dashboard/**"
---
# Design Quality Standards for SkillForge Dashboard

SkillForge 是一个面向开发者的 AI Agent 平台。UI 风格目标：**精准、专业、有开发者质感**，参考 Linear / Raycast / Cursor 风格。

---

## ⚠️ Anti-Template Policy

**不允许**提交「看起来像模板」的 UI。所有前端产出必须是有主见的、针对产品本身设计的，而不是库的默认样式。

### 禁止模式

- 千篇一律间距的卡片网格，没有视觉层次
- 居中大标题 + 渐变背景 + 通用 CTA 的 Hero Section
- 未经修改的 Ant Design 默认样式直出
- 没有层次感、深度、动效的平铺布局
- 所有组件用完全相同的圆角/间距/阴影
- 灰底白字 + 一个装饰色的「安全」配色
- 侧边栏 + 卡片 + 图表的「教科书 Dashboard」布局（没有设计观点）
- 无充分理由使用系统默认字体

---

## Required Design Qualities

每个有意义的页面/功能区域，必须体现以下至少 4 条：

1. **Clear hierarchy through scale contrast** — 字号、字重、间距的对比引导视觉重要性
2. **Intentional rhythm in spacing** — 不是统一 padding，有呼吸感地留白
3. **Depth or layering** — 通过层叠、阴影、毛玻璃、z-index 表达信息优先级
4. **Typography with character** — 有明确字体选型策略（不是「用默认就好」）
5. **Color used semantically** — 颜色有功能意义，不只是装饰
6. **Designed interaction states** — hover / focus / active / loading 状态都经过设计
7. **Motion that clarifies flow** — 动效辅助理解，不是干扰
8. **Data visualization as design system** — 图表是设计系统的一部分，而非事后添加

---

## Style Direction: Developer Precision

SkillForge 的视觉方向：参考 Linear / Raycast 的「dark precision + subtle depth」：

- **色彩**：深背景为主（`#0f0f10` / `#1a1a1e`），主色用单色 accent（当前紫色系 `#6366f1`）
- **字体**：等宽/代码感的地方用 `JetBrains Mono` / `Fira Code`；正文/UI 用 Inter 或系统 sans-serif
- **间距**：8px 基础网格，大区块用 24/32/48px 节奏
- **圆角**：小元素 `4px`，卡片 `8px`，大容器 `12px`，不要全部用同一个值
- **阴影**：有层次的阴影（ambient + directional），不要用单层 box-shadow 全搞定

---

## Component Checklist

在提交任何前端改动前：

- [ ] 是否避免了 Ant Design 默认样式直出？
- [ ] hover / focus / active 状态是否有设计（不是浏览器默认高亮）？
- [ ] 是否用了视觉层次，而不是"所有内容同样重要"？
- [ ] 放到真实产品截图里，是否显得专业？
- [ ] 如果有 dark 和 light 主题，两者是否都显得是「有意为之」？

---

## References (Developer Productivity Tools)

启动新 UI 功能时，可从这些设计系统中找灵感（getdesign.md）：

| Product | URL | 关键特征 |
|---------|-----|---------|
| Linear | https://getdesign.md/linear.app/design-md | 极简、精准、快速、紫色 accent |
| Raycast | https://getdesign.md/raycast/design-md | 深色、键盘优先、活力 gradient |
| Cursor | https://getdesign.md/cursor/design-md | AI-first、VSCode 风格 |
| Vercel | https://getdesign.md/vercel/design-md | 黑白精确、Geist 字体 |
| Stripe | https://getdesign.md/stripe/design-md | gradient 精致感、weight-300 |
| Notion | https://getdesign.md/notion/design-md | 暖色极简、衬线标题 |
| Posthog | https://getdesign.md/posthog/design-md | 开发者数据工具，图表设计参考 |
| Sentry | https://getdesign.md/sentry/design-md | 错误监控，状态/严重性颜色系统 |
