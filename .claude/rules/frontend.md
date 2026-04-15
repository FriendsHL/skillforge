---
paths:
  - "**/*.tsx"
  - "**/*.ts"
  - "skillforge-dashboard/**"
---
# Frontend Rules for SkillForge Dashboard

React 19 + TypeScript + Ant Design 6 + Vite + React Router 7。

---

## ⚠️ Known Footguns

### 1. API 函数禁止 `data: any`

`src/api/index.ts` 中现有 4 处 `any` 是历史遗留。**新增 API 函数必须定义入参类型**，已有函数在触碰时补类型。

```ts
// BAD
export const createAgent = (data: any) => api.post('/agents', data);

// GOOD — 定义 request 类型
export interface CreateAgentRequest {
  name: string;
  description: string;
  systemPrompt?: string;
  modelId?: string;
}
export const createAgent = (data: CreateAgentRequest) => api.post('/agents', data);
```

### 2. WebSocket 订阅必须在 cleanup 里取消

WebSocket 连接如果不在 `useEffect` cleanup 中关闭，组件卸载后仍会收到消息并 setState，
导致"Can't perform a React state update on an unmounted component"警告和内存泄漏。

```ts
// GOOD
useEffect(() => {
  const ws = new WebSocket(url);
  ws.onmessage = (e) => { /* handle */ };
  return () => ws.close();  // ← 必须有 cleanup
}, [url]);
```

### 3. 流式渲染用节流，不要每个 delta 都 setState

参照 `ChatWindow.tsx` 里的 `ThrottledMarkdown`：高频 delta 直接 setState 会卡死 UI。
流式内容先写 `ref`，每 200ms 同步一次到 state。

---

## 类型规范

- 优先用 `interface` 定义对象类型；联合类型、工具类型用 `type`
- 组件 props 统一用 `interface XxxProps`
- API 响应类型放在 `src/api/index.ts` 或同目录的 `types.ts` 中定义，不要在组件内内联
- 禁止 `@ts-ignore`；必须绕过时用 `@ts-expect-error` 并加注释说明原因
- 避免 `as unknown as Xxx` 双重断言；如果确实需要，说明为何

```ts
// 组件 props
interface ChatWindowProps {
  sessionId: string;
  userId: number;
  onClose?: () => void;
}

// API 响应类型
export interface SessionDto {
  id: string;
  agentId: number;
  title: string;
  status: 'active' | 'archived';
  messageCount: number;
  createdAt: string;
}
```

---

## 组件规范

- 文件名：`PascalCase.tsx`（组件）、`camelCase.ts`（工具/hooks）
- 每个文件只导出一个主组件；小型内部子组件可以同文件定义但不导出
- 用 `React.memo` 包裹**纯展示**组件，避免父组件更新时无谓重渲染
- 复杂逻辑提取成自定义 hook（`use` 前缀），保持组件 JSX 部分简洁

```tsx
// GOOD — 展示组件用 memo
const MessageBubble: React.FC<MessageBubbleProps> = React.memo(({ message, role }) => (
  <div className={`bubble bubble--${role}`}>{message}</div>
));

// GOOD — 复杂逻辑提取成 hook
function useChatSession(sessionId: string) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  // ...
  return { messages, sendMessage, cancel };
}
```

---

## 状态管理

- 局部状态用 `useState` / `useReducer`（项目当前无 Redux/Zustand，保持一致）
- 跨页面共享状态（如当前 userId）通过 React Context 或 URL params 传递，不要通过 localStorage hack
- 大型页面（如 Chat.tsx）状态过多时，拆分成多个自定义 hook，而不是一个组件里塞几十个 `useState`

---

## API 调用规范

- API 调用统一放在 `src/api/index.ts`，组件内不直接用 `axios`
- 所有 API 调用都要处理 loading 和 error 状态，不能只处理成功路径
- 并发请求用 `Promise.all`，不要串行等待

```tsx
// GOOD
const [loading, setLoading] = useState(false);
const [error, setError] = useState<string | null>(null);

useEffect(() => {
  setLoading(true);
  Promise.all([getAgents(), getSkills()])
    .then(([agentsRes, skillsRes]) => {
      setAgents(agentsRes.data);
      setSkills(skillsRes.data);
    })
    .catch((e) => setError(e.message))
    .finally(() => setLoading(false));
}, []);
```

---

## Ant Design 使用规范

- 表单用 `Form` 组件 + `Form.Item`，不要自己管理表单状态
- 弹窗/确认用 `Modal.confirm`，不要自己实现遮罩
- 错误/成功提示用 `message.error()` / `message.success()`，保持全局一致
- 列表数据加载用 `Table` 的 `loading` prop，不要自定义 loading 遮罩
- 不要覆写 Ant Design 组件的内部样式（`.ant-xxx`），用 `token` / `theme` 配置

---

## 样式规范

- 优先用 CSS 变量（项目已有 `var(--bg-primary)` 等）
- 组件内联 style 只用于动态计算值（如根据状态改变宽度），静态样式放 CSS 文件或 CSS Modules
- 不要用 `!important`

---

## 性能规范

- `useCallback` / `useMemo` 用于：传给子组件的回调函数、计算开销大的派生值
- 不要对所有函数都包 `useCallback`（不必要的 memo 反而增加开销）
- 长列表（>100 条）使用虚拟滚动（Ant Design `Table` 的 `virtual` prop 或 `react-window`）
- 图表（ECharts）在组件卸载时调用 `echartsInstance.dispose()` 释放内存

---

## 文件组织

```
src/
├── api/
│   └── index.ts          # 所有 API 函数 + 请求/响应类型
├── components/            # 可复用组件（跨页面使用）
├── pages/                 # 页面级组件（对应路由）
├── hooks/                 # 自定义 hooks（当前缺失，需要时创建）
└── types/                 # 全局共享类型（当前缺失，需要时创建）
```

新增可复用逻辑时，优先提取到 `hooks/` 目录，而不是内联在页面组件里。
