# P5 前端体验优化 — 技术方案

> 产出过程：Plan A（系统性升级）+ Plan B（务实修复）→ Challenger A/B 交叉挑战 → Judge 圆桌仲裁 → 整合
> 日期：2026-04-17
> 预估工期：5 天（4 个 Phase）

---

## 设计理念

先修高价值 bug（Chat 消息丢失、Session 分页），再建轻量 token 基础 + dark mode，最后打磨体验细节。每个 Phase 独立可交付，不存在跨 Phase 阻塞依赖。

Dark mode 唯一路径：AntD `ConfigProvider theme={{ algorithm: darkAlgorithm }}`，不建独立 CSS 变量覆盖层——两套体系并行的结构性冲突在圆桌中被裁定为不可行。

---

## Phase 划分

### Phase 1: Bug First（1.5 天）

解决两个最痛的功能 bug：Chat 消息丢失和 Session 分页。

#### P5-2 Chat 历史消息修复

**问题根因：** Context compaction 后 `messages_snapshot` WS 事件静默替换消息数组，用户无感知；`isNearBottom` 逻辑在消息数量骤减时行为不确定。

**修复方案：**

1. **Compaction Banner（列表外渲染）**
   - 文件：`src/components/ChatWindow.tsx`
   - 在消息列表上方条件渲染 `<Alert type="info" banner closable>` — "Context was compacted to save tokens. Earlier messages may be summarized."
   - 触发条件：`Chat.tsx` 维护 `compactionNotice: boolean` state，由 `useChatWsEventHandler` 的 `messages_snapshot` 分支置 `true`，5 秒后自动清除
   - **不向消息数组注入虚拟消息**（圆桌否决：侵入 `Message[]` 类型，污染所有下游消费）

2. **Snapshot 版本号 Guard（防竞态）**
   - 文件：`src/hooks/useChatWsEventHandler.ts`
   - `messages_snapshot` 事件携带 `snapshotVersion: number`，前端维护 `lastSnapshotVersion` ref
   - 后续 `message_delta` 事件的 `snapshotVersion` 必须 >= `lastSnapshotVersion` 才追加，否则丢弃
   - 替换 Plan B 的布尔 `isSnapshot` flag（圆桌裁定布尔 flag 存在竞态）

3. **滚动位置保持**
   - 文件：`src/components/ChatWindow.tsx`
   - `messages_snapshot` 到达时，记录当前 `scrollTop`，消息替换后恢复
   - 仅当 `isNearBottom` 为 true 时才自动滚到底部

4. **IntersectionObserver 懒渲染（替代 react-window）**
   - 文件：`src/components/ChatWindow.tsx`
   - 圆桌否决 react-window VariableSizeList（Markdown + tool call 动态高度导致 `resetAfterIndex` 跳变）
   - 替代方案：消息容器外用 `IntersectionObserver` 监测，仅渲染视口内 ± 5 条消息的完整内容，其余用占位符（记录上次测量高度）
   - 消息数 < 100 时不启用（避免短对话引���复杂度）
   - 已知限制：`Ctrl+F` 浏览器搜索无法命中未渲染消息，需在 UI 提示

#### P5-1 Session 列表分页

**方案：前端分页（短期），后端分页（P6 长期）**

- 文件：`src/pages/SessionList.tsx`
- 删除 `virtual` prop + `scroll={{ y: 500 }}` 硬编码
- 改为 AntD `Table` 内置分页：`pagination={{ pageSize: 20, showSizeChanger: false, showTotal: (t) => \`${t} sessions\` }}`
- `scroll={{ x: 'max-content' }}` 保留横向滚动
- WS 实时更新 + react-query cache 逻辑不变，分页纯渲染层
- **已知技术债**：Session 数量 > 500 时全量加载慢，需 P6 引入后端 cursor-based 分页 API

#### 中文字符串清理

- 文件：`src/pages/Chat.tsx` 6 处中文 → 英文
  - `'服务器繁忙,请稍后再试'` → `'Server is busy, please try again later'`
  - `'已切换为 ${mode} 模式'` → `'Switched to ${mode} mode'`
  - `'已压缩:释放 ${reclaimed} tokens'` → `'Compacted: reclaimed ${reclaimed} tokens'`
  - `'Session 正在运行, 无法压缩'` → `'Session is running, cannot compact'`
  - `'当前没有正在运行的 loop'` → `'No active loop running'`
  - Dashboard.tsx: `'暂无数据'`/`'暂无会话'` → `'No data'`/`'No sessions'`

---

### Phase 2: Token & Dark Mode（1 天）

#### Token 字典建立

- 新建文件：`src/styles/tokens.ts`
- 定义约 20 个语义 token 常量，覆盖当前 139 处 raw hex 的去重映射：

```typescript
export const tokens = {
  // Backgrounds (dark)
  bgVoid:    '#07080a',
  bgBase:    '#0f1011',
  bgSurface: '#191a1b',
  bgHover:   '#28282c',
  bgOverlay: '#1b1c1e',

  // Text
  textPrimary:   '#f7f8f8',
  textSecondary: '#d0d6e0',
  textTertiary:  '#8a8f98',
  textDisabled:  '#62666d',

  // Accent
  accentPrimary:      '#5e6ad2',
  accentPrimaryHover: '#7170ff',

  // Status
  statusSuccess: '#10b981',
  statusWarning: '#ffbc33',
  statusError:   '#ff6363',
  statusInfo:    '#55b3ff',
  statusActive:  '#c2ef4e',

  // Agent operation colors (Cursor pattern)
  opThinking: '#dfa88f',
  opSearch:   '#9fc9a2',
  opRead:     '#9fbbe0',
  opWrite:    '#c0a8dd',
  opExecute:  '#ffbc33',

  // Borders
  borderStrong: '#23252a',
  borderMedium: '#34343a',

  // Focus
  focusRing: '#7170ff',
} as const;

export const lightTokens = {
  bgVoid:    '#f5f4f2',
  bgBase:    '#fafafa',
  bgSurface: '#ffffff',
  bgHover:   '#eceae6',

  textPrimary:   '#1a1915',
  textSecondary: '#6b6760',
  textTertiary:  '#7a7870',

  borderStrong: '#b8b5af',
  borderMedium: '#d1cfc9',
} as const;
```

#### ThemeContext + ConfigProvider 集成

- 新建文件：`src/contexts/ThemeContext.tsx`
- `ThemeProvider` 维护 `theme: 'dark' | 'light'` 状态
- 初始值读 `localStorage('sf-theme')` ?? `window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'`
- 切换时写入 `localStorage`
- `src/main.tsx` 中 `ConfigProvider` 的 `theme` prop 跟随：

```tsx
<ThemeProvider>
  <ThemeConsumer>
    {({ theme }) => (
      <ConfigProvider
        theme={{
          algorithm: theme === 'dark' ? antTheme.darkAlgorithm : antTheme.defaultAlgorithm,
          token: theme === 'dark' ? darkAntTokens : lightAntTokens,
        }}
      >
        <App />
      </ConfigProvider>
    )}
  </ThemeConsumer>
</ThemeProvider>
```

- 不建立独立 CSS 变量 dark 覆盖层（圆桌裁定：CSS 变量与 AntD CSS-in-JS 两套体系并行会闪烁）
- AntD 组件颜色完全由 `ConfigProvider token` 控制
- 自定义组件中的颜色从 `tokens.ts` import，通过 `useTheme()` hook 选择 dark/light 分支

#### Raw Hex 批量替换

- 按 `tokens.ts` 字典 grep 替换 139 处 raw hex
- 优先处理 Eval.tsx（62 处）、Teams.tsx（26 处）、Dashboard.tsx（9 处）
- **实施前需 ConfigProvider 全局搜索**：确认是否有局部 `ConfigProvider` 嵌套（如 Modal、Drawer），确保 darkAlgorithm 覆盖所有层级

#### AuthContext 扩展 userId

- 文件：`src/contexts/AuthContext.tsx`
- 增加 `userId: number`（从 JWT decode 或 login 时存入，fallback 1）
- 删除 4 处页面级硬编码：`SessionList.tsx`、`Chat.tsx`、`MemoryList.tsx`、`SkillList.tsx`

---

### Phase 3: UX & 空状态（1.5 天）

#### P5-3 用户输入样式优化

- 文件：`src/components/ChatWindow.tsx`、`src/index.css`
- 用户气泡：`--bg-user-msg` dark mode 下用 `#28282c`（Linear hover），light mode 下 `#DDD9D3`（略深于现有）
- 加 `border: 1px solid var(--border-subtle)` 增加区分度
- `maxWidth` 72% → 68%，增加呼吸感
- Send button `border-radius: 6px`（Linear 风格），disabled 态降低 opacity 到 0.4
- Input 区域加 focus ring：`box-shadow: var(--shadow-focus)`

#### P5-4 Agent 列表 UX

- 文件：`src/pages/AgentList.tsx`
- Agent 为空时：AntD `<Empty description="Create your first Agent">` + CTA 按钮
- Agent 卡片 hover：`transform: translateY(-2px)` + `box-shadow: var(--shadow-raised)`
- Status badge 改 pill 形（`border-radius: 9999px`）+ 语义色

#### P5-5 空状态 / Loading / Error 补全

**不新建公共组件库**（圆桌否决：AntD 已有 Empty/Skeleton/Alert，YAGNI）。各页面直接用 AntD 组件：

| 页面 | 缺失 | 修复 |
|------|------|------|
| ChatSidebar | empty + loading | `List locale={{ emptyText: ... }}` + `Skeleton active rows={3}` |
| MemoryList | loading 用原始 div | 改 `<Spin spinning={loading}>` 包裹 |
| SessionList | error 态 | `Alert type="error"` + Retry 按钮调 `refetch()` |
| Dashboard | 空数据中文 | `'暂无数据'` → `'No data yet'` |

---

### Phase 4: 响应式 & Traces（1 天）

#### 响应式 Sidebar

- 文件：`src/components/Layout.tsx`
- `useBreakpoint('md')` 监听（AntD Grid）
- `< 768px` 时 Sider 改为 `<Drawer placement="left">`，汉堡按钮触发
- **不做硬截断**（圆桌否决：平板用户会直接失去功能入口）

#### P5-6 Traces 页面增强

- 文件：`src/pages/Traces.tsx`
- Span 类型色彩编码，使用 `tokens.ts` 中的 `op*` 色：
  - LLM_CALL → `opThinking`
  - TOOL_CALL → `opExecute`
  - LIFECYCLE_HOOK → `opWrite`
  - ERROR → `statusError`
- `durationMs` 列加 inline bar（纯 CSS `width: ${percent}%`，background `accentPrimary`）
- waterfall raw hex 全替换
- `formatTime` locale 统一为 `en`

#### a11y 基础

- ChatSidebar session 列表：`role="listbox"` + `aria-selected`
- StatusDot：`role="status"` + `aria-label`
- Icon-only button（send/cancel）：`aria-label`
- 约 2 小时工作量

---

## 设计 Token 来源

| 维度 | 来源 | 具体 token |
|------|------|-----------|
| 背景层级 | Linear | void → base → surface → hover 四级 |
| 文字层级 | Linear | primary → secondary → tertiary → disabled 四级 |
| 主色调 | Linear | indigo #5e6ad2 / violet #7170ff |
| 状态色 | Raycast | success/warning/error/info 四色 + lime active |
| 操作色 | Cursor | thinking/search/read/write/execute 五色 |
| 阴影 | Linear | border-ring 为主，dark 不用 drop shadow |
| 字体 | Inter（UI）+ GeistMono/JetBrains Mono（代码） | |
| 圆角 | Linear | 2/4/6/8/12/9999px 六级 |
| 间距 | Linear | 4px 基础网格 |

---

## 被圆桌否决的决策

| 决策 | 原方案 | 否决原因 |
|------|--------|---------|
| CSS 变量 dark-first + AntD darkAlgorithm 并行 | Plan A | AntD CSS-in-JS 与 CSS 变量两套体系切换时序不同步，必然闪烁 |
| 纯 CSS @media dark mode | Plan B | AntD 组件颜色是运行时注入，不响应 CSS 媒体查询，产出"半暗"界面 |
| react-window VariableSizeList | Plan A | Markdown + tool call 动态高度导致 resetAfterIndex 跳变，引入成本远超收益 |
| 先建 3 个全局公共组件 | Plan A | AntD 已有 Empty/Skeleton/Alert，YAGNI |
| 768px 硬截断隐藏 sidebar | Plan B | 平板用户直接失去功能入口 |
| Compaction 虚拟消息注入 | Plan A | 侵入 Message[] 类型，污染所有下游消费 |

---

## 遗留风险

1. **前端分页技术债**：Session > 500 条时全量加载慢，P6 需后端 cursor-based 分页
2. **Raw hex 替换覆盖率**：Phase 2 批量替换后需 dark mode 截图验证，遗漏的 hex 在 dark 下颜色错误
3. **IntersectionObserver 限制**：未渲染消息无法被 `Ctrl+F` 搜索，需 UI 提示
4. **ConfigProvider 嵌套**：若已有局部 ConfigProvider（Modal/Drawer 内），需确保顶层 darkAlgorithm 覆盖所有实例
5. **snapshotVersion 后端支持**：Chat compaction 的版本号 guard 需要后端 WS 事件增加 `snapshotVersion` 字段

---

## 工作量总览

| Phase | 内容 | 天数 |
|-------|------|------|
| Phase 1 | Chat 消息修复 + Session 分页 + 中文清理 | 1.5 天 |
| Phase 2 | Token 字典 + ThemeContext + Dark mode + raw hex 替换 + userId | 1 天 |
| Phase 3 | 用户输入样式 + Agent UX + 空状态补全 | 1.5 天 |
| Phase 4 | 响应式 Drawer + Traces 色彩 + a11y | 1 天 |
| **合计** | | **5 天** |
