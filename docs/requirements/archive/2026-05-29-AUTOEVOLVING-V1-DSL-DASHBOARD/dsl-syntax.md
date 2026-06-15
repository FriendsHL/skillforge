# DSL Syntax Reference — SkillForge V1 Workflow

> 你写 `.workflow.js` 文件时直接查这个。严格参考 [Claude Code Workflow](../../../../research-docs/research/claude%20code%20源码/08%20Workflow%20工具与编排指南.md)（5 原语 + 4 全局），加 SkillForge 独家 `humanApprove`。

## 1. 文件位置

```
skillforge-server/src/main/resources/workflows/
├── opt-report.workflow.js          # 系统内置 demo
├── memory-curation.workflow.js     # DREAMING 改造（V2）
└── 你自己写的.workflow.js
```

V1 文件存仓库；V3+ 支持 DB 存储 + dashboard 编辑器。

## 2. Minimal Skeleton

每个 workflow 文件都长这样：

```js
export const meta = {
  name: 'your-workflow-name',                  // 必填，全局唯一
  description: '你的 workflow 干啥',            // 必填，dashboard 显示
  phases: [                                     // 可选，跟 phase() 标题对齐
    { title: 'Phase1' },
    { title: 'Phase2' },
  ],
}

// 脚本主体 — async 上下文，直接 await
phase('Phase1')
const result = await agent('do something', {
  agentSlug: 'orchestrator',
  schema: SOME_SCHEMA,
})

return { result }
```

**关键约束**：
- `meta` 必须是**纯字面量**（禁变量 / 函数调用 / 模板插值 / spread）
- 脚本主体跑在沙箱里（见 §7 沙箱限制）
- 必须 `return` 一个 JSON-serializable 值作为 workflow 最终输出

## 3. 6 个核心原语

### 3.1 `agent(prompt, opts?)` — 跑一个 sub-agent

```js
const batches = await agent('Load sessions windowDays=' + args.windowDays, {
  agentSlug: 'orchestrator',              // 引 t_agent.name
  schema: BATCHES_SCHEMA,                 // 强制 JSON 输出 + 失败自动重试 3 次
  model: 'sonnet',                        // 'sonnet' | 'opus' | 'haiku'，不传继承
  label: 'load-sessions',                 // dashboard 显示标签
})
// batches 是 schema-validated 的 JS 对象
console.log(batches.items.length)
```

**返回**：schema validated 的 Object（如果传了 schema）/ raw text（如果没传 schema）。

**重试逻辑**：sub-agent 输出违反 schema → workflow runtime 自动让 sub-agent 重试（最多 3 次），仍失败抛 `SchemaViolationError`。

### 3.2 `parallel(thunks)` — 同时跑，等齐才往下（barrier）

```js
const reviews = await parallel(REVIEWERS.map(r => () =>
  agent('review ' + r.name, {
    agentSlug: 'java-reviewer',
    schema: REVIEW_SCHEMA,
  })
))
// 所有 reviewer 跑完才返回；reviews 是 array of result（失败的位置是 null）
const validReviews = reviews.filter(r => r !== null)
```

**特性**：
- thunk = `() => agent(...)` 形式的箭头函数
- ⚠️ **V1 约束（offload 模型妥协）**：thunk 必须以单个 `agent()` 调用作为 tail 表达式（`() => agent(...)`），**不能对 `agent()` 的结果做后处理**（如 `() => agent(...).then(...)` / `() => { const r = agent(...); return r.field }`）。V1 在 workflow 线程上顺序求值每个 thunk 拿到 `agent()` 的 offload 占位符后才 barrier-join；非 tail-call 形态会在求值期拿到占位符而非真实结果。后处理放到 barrier 之后对返回数组做（`reviews.map(...)`）。
- **barrier**：必须全跑完才往下
- 失败 item 返 `null`，调用本身不 reject，需 `.filter(Boolean)`
- **真并发**：N 个 `agent()` 的 `engine.run` offload 到 worker 线程并发跑（与 `pipeline` 的 V1 串行不同）
- 并发上限：单 workflow 内 `min(16, cpu cores - 2)`

### 3.3 `pipeline(items, stage1, stage2, ...)` — 流水线（每 item 独立流过所有 stage，**无 barrier**）

```js
const verified = await pipeline(FINDINGS,
  finding => agent('review ' + finding, { schema: REVIEW_SCHEMA }),         // stage 1
  review => agent('verify ' + JSON.stringify(review), { schema: VERIFY_SCHEMA })  // stage 2
)
// item A 在 stage 2 时，item B 可能还在 stage 1
// 墙钟时间 = 最慢单链
```

**特性**：
- ⚠️ **V1 串行执行**：pipeline 的 stage 是读 Rhino scope 的 JS 回调，不能 offload 到 worker 线程（Rhino 单线程），所以 V1 是**串行**实现——每个 item 依次流过所有 stage，一个 item 全跑完再下一个，每个 `agent()` 内联阻塞。语义正确（每 item 独立流过所有 stage，某 stage 抛错则该 item 后续 stage 跳过 → `null`），但**没有真并发**（fully-pipelined 真并发 V2 起，§15）。runtime 会 `log()` 一行声明串行执行，避免误当并发。**要真并发用 `parallel()`**（offload 模型）。
- 上面"墙钟时间 = 最慢单链"是 V2 真并发版的语义；V1 串行版墙钟 = 所有 item × 所有 stage 之和。
- 某 stage 抛错 → 该 item 后续 stage 跳过 → 结果是 `null`

### 3.4 `phase(title)` — 在 dashboard 分组显示

```js
phase('Load')
const batches = await agent(...)

phase('Annotate')
const annotations = await parallel(...)
```

**特性**：
- 每个 phase 期间的 agent() 调用归属该 phase
- dashboard workflow DAG viz 按 phase 分组着色
- phase title 必须在 `meta.phases` 里出现（schema 校验）

### 3.5 `log(message)` — 进度推 dashboard

```js
log('Loaded ' + batches.items.length + ' batches')
```

**特性**：
- 实时推 WS event 到 dashboard 顶部进度条
- 仅文本，不支持结构化（结构化用 return 值或 t_llm_trace）

### 3.6 `humanApprove(payload, opts?)` — SkillForge 独家，暂停等用户审

```js
const decision = await humanApprove(attribution, {
  uiTemplate: 'opt-report-attribution',     // dashboard 渲染哪个 React 组件 (V2 完整支持)
})

if (decision.approved) {
  // ...
} else {
  log('User rejected: ' + decision.reason)
}
```

**特性**：
- workflow 暂停 → dashboard 弹 review card → user click approve/reject → workflow resume
- 返 `{ approved: boolean, reviewerId: string, reason?: string, decisionAt: string }`
- V1 简化版：只支持 payload + dashboard 默认渲染（无 uiTemplate / multi-reviewer / timeout）
- V2 完整版：uiTemplate / multi-reviewer / timeout-reject / multi-approve

## 4. 4 个全局

### 4.1 `args` — 启动参数

```js
const agentId = args.agentId           // 从 POST /api/workflows/{name}/run 传入
const windowDays = args.windowDays
```

启动 workflow 时 REST body 的 `args` 字段透传到这。

### 4.2 `log(msg)` — 见 §3.5

### 4.3 `ctx.runId()` — 当前 run 的 ID

```js
log('Current run: ' + ctx.runId())
```

用来串联日志 / 关联外部 ticket。

### 4.4 `ctx.json(obj)` — JSON 序列化 helper（沙箱禁 ObjectMapper 直接访问）

```js
const summary = await agent('aggregate: ' + ctx.json(annotations), {...})
```

## 5. Schema 写法（JSON Schema）

```js
const BATCHES_SCHEMA = {
  type: 'object',
  required: ['items'],
  properties: {
    items: {
      type: 'array',
      items: {
        type: 'object',
        required: ['id', 'sessions'],
        properties: {
          id: { type: 'string' },
          sessions: { type: 'array', items: { type: 'string' } },
        },
      },
    },
  },
}
```

Schema 强制 sub-agent 输出，失败自动重试。引擎用 [networknt/json-schema-validator](https://github.com/networknt/json-schema-validator) 校验。

## 6. 控制流（JS 原生）

```js
if (batches.items.length === 0) {
  log('No batches, skip')
  return { status: 'empty' }
}

while (round <= 2 && verdict === 'FAIL') {
  verdict = await agent('judge', {...})
  round++
}

try {
  const result = await agent('risky-call', {...})
} catch (e) {
  log('Failed: ' + e.message)
  return { status: 'error', error: e.message }
}

// 三元 / 数组 map / spread 都支持
const tasks = items.map(i => ({ ...i, processed: true }))
```

## 7. 沙箱限制（违反会抛错）

| 禁用 | 原因 |
|---|---|
| `Date.now()` / `new Date()` 无参 | 破坏 resume 缓存 |
| `Math.random()` | 同上 |
| `require(...)` / `import` | 没绑模块系统 |
| `eval(...)` / `new Function(...)` | 防 prompt injection |
| `process.*` / `globalThis.process` | 沙箱不暴露 |
| 文件系统访问 / `fs` 模块 | 没绑 |
| 网络访问 / `fetch` / `XMLHttpRequest` | 没绑 |
| 任何 Java class 访问（`java.lang.System` / `org.springframework.*`） | ClassShutter 禁 |
| 自己 `Promise.all` 玩并发 | 引擎要管并发上限，用 `parallel()` |

## 8. 可以用

- `JSON.stringify/parse`、`Math`（除 `random`）、`Array`、`Object`、`Map`、`Set`、字符串模板
- `if / else / while / for / try-catch`、三元、解构、spread、async/await
- 自定义函数 / 闭包 / closure
- 6 host bindings（agent / parallel / pipeline / phase / log / humanApprove）+ 4 globals（args / ctx / log / 暂未实现 budget）

## 9. 错误处理约定

- `agent()` 失败抛 → workflow 中止（除非 try/catch）
- `parallel()` 里某 thunk 失败 → 那位置变 `null`，其他继续；**调用本身不 reject**
- `pipeline()` 里某 stage 抛错 → 该 item 后续 stage 跳过，结果是 `null`
- Schema 验证 3 次都失败 → 抛 `SchemaViolationError`
- 超 budget（instruction / agent calls / 30min timeout） → 抛 `WorkflowBudgetExceededError`
- 想"≤2 轮还不过就回主会话决策" → `if (round === 2 && verdict === 'FAIL') throw new Error('escalate')`

## 10. Budget Cap (V1)

| 项 | 上限 |
|---|---|
| 单 workflow 总 wall-clock | 30 分钟 |
| 单 workflow `agent()` 调用累计 | 1,000 次 |
| 单 workflow JS 指令累计 | 1,000,000 |
| Rhino 堆内存 | 256 MB |
| `parallel()` 并发上限 | min(16, cpu cores - 2) |
| Schema 重试次数 | 3 |
| `agent()` 内部 sub-agent retry | 1 (网络错) |

## 11. 完整示例：OPT-REPORT workflow

```js
export const meta = {
  name: 'opt-report',
  description: '生成优化报告: load → fanout annotate → aggregate → 人工 approve',
  phases: [
    { title: 'Load' },
    { title: 'Annotate' },
    { title: 'Aggregate' },
    { title: 'Attribute' },
    { title: 'Approve' },
  ],
}

const BATCHES_SCHEMA = { /* ... */ }
const ANNOTATION_SCHEMA = { /* ... */ }
const SUMMARY_SCHEMA = { /* ... */ }
const ATTRIBUTION_SCHEMA = { /* ... */ }

log('Starting opt-report for agent ' + args.agentId + ' window=' + args.windowDays + 'd')

// ---- Phase 1: 加载 sessions ----
phase('Load')
const batches = await agent('Load sessions: agentId=' + args.agentId + ' windowDays=' + args.windowDays, {
  agentSlug: 'orchestrator',
  schema: BATCHES_SCHEMA,
})
log('Loaded ' + batches.items.length + ' batches')

if (batches.items.length === 0) {
  log('No sessions in window, skipping')
  return { status: 'empty' }
}

// ---- Phase 2: fanout 标注（barrier，必须全完才往下）----
phase('Annotate')
const annotations = await parallel(
  batches.items.map(batch => () =>
    agent('Annotate batch ' + batch.id, {
      agentSlug: 'session-batch-annotator',
      schema: ANNOTATION_SCHEMA,
    })
  )
)
const validAnnotations = annotations.filter(a => a !== null)
log('Annotated ' + validAnnotations.length + ' batches (skipped ' + (annotations.length - validAnnotations.length) + ')')

// ---- Phase 3: 聚合 ----
phase('Aggregate')
const summary = await agent('Aggregate: ' + ctx.json(validAnnotations), {
  agentSlug: 'aggregator',
  schema: SUMMARY_SCHEMA,
  model: 'opus',                     // 聚合用 opus
})

// ---- Phase 4: 归因 ----
phase('Attribute')
const attribution = await agent('Attribute: ' + ctx.json(summary), {
  agentSlug: 'attributor',
  schema: ATTRIBUTION_SCHEMA,
})

// ---- Phase 5: 人工 approve（workflow 暂停，dashboard 上等用户点）----
phase('Approve')
const decision = await humanApprove(attribution, {
  uiTemplate: 'opt-report-attribution',
})

return {
  status: decision.approved ? 'approved' : 'rejected',
  summary: summary,
  attribution: attribution,
  reviewerId: decision.reviewerId,
  reason: decision.reason,
}
```

## 12. 你 dashboard 上看到的 workflow 进度

```
opt-report [running] runId=abc-123
├─ ✅ Load (3.2s)
│  └─ ✅ orchestrator (3.0s) — Loaded 5 batches
├─ ✅ Annotate (14.5s) [parallel × 5]
│  ├─ ✅ session-batch-annotator [batch-1] (4.1s)
│  ├─ ✅ session-batch-annotator [batch-2] (4.8s)
│  ├─ ✅ session-batch-annotator [batch-3] (3.9s)
│  ├─ ✅ session-batch-annotator [batch-4] (5.0s) ← slowest, blocks barrier
│  └─ ✅ session-batch-annotator [batch-5] (4.2s)
├─ ✅ Aggregate (8.1s) [opus]
│  └─ ✅ aggregator (8.1s)
├─ ✅ Attribute (5.4s)
│  └─ ✅ attributor (5.4s)
└─ ⏸️ Approve [awaiting human]    ← workflow 暂停等你审
   └─ Click to review → Default review card
```

点击「approve」→ workflow resume → 写 `t_flywheel_run.summary_json` + `status=completed`。

## 13. 速查卡

| 我想 | 用 |
|---|---|
| 调一个 sub-agent | `await agent(prompt, { agentSlug, schema })` |
| 同时跑 N 个、全完才走（**真并发**） | `await parallel([() => agent(...), () => agent(...)])` |
| 每个 item 独立流过所有 stage（**V1 串行**，真并发 V2 起）| `await pipeline(items, stage1, stage2)` |
| 给当前操作分组（dashboard 显示） | `phase('Title')` |
| 推一行进度文字 | `log('msg')` |
| 暂停等用户审 | `const d = await humanApprove(payload)` |
| 取启动参数 | `args.xxx` |
| 取当前 run ID | `ctx.runId()` |
| JSON 序列化对象 | `ctx.json(obj)` |
| 控制流 | `if/while/for/try-catch` 原生 JS |
| 严格 schema 输出 | 传 `{ schema: MY_SCHEMA }` 给 agent() |
| 提前终止 workflow | `return { status: 'whatever' }` |
| 抛错回主会话决策 | `throw new Error('escalate')` |

## 14. 与 Claude Code Workflow 差异

| 维度 | Claude Code Workflow | SkillForge V1 |
|---|---|---|
| `agent()` 参数 | `agentType` | `agentSlug`（引 `t_agent.name`） |
| 路径 | 由 Claude Code 自己管理 | `skillforge-server/src/main/resources/workflows/*.workflow.js` |
| 沙箱实现 | Claude Code 内部 | Rhino + L1 capability |
| `budget.*` 全局 | 有（user 加 +500k 时有值） | V1 不实现，V2 加 |
| `workflow(name, args)` 嵌套 | 一层 | V1 不实现，V2 加 |
| `humanApprove` | 没有 | ✅ SkillForge 独家 |
| `worktree` 隔离 | 有 | 不实现（SkillForge 跑 sub-agent 不动 fs） |
| `resumeFromRunId` | crash recovery + 改脚本续跑 | V1 仅 humanApprove resume（journal-replay）；crash 不自动 resume（挂了重跑） |
| journal/resume range | 完整 | V1 journal cache 仅服务 humanApprove resume；crash recovery 推 V2 |

## 15. 等 V2-V5 加的能力

- V2: `workflow(name, args)` 嵌套 / `budget.*` / `humanApprove` 完整版（uiTemplate / multi-reviewer / timeout）
- V3: outer loop wrap workflow（K-4） / 3 信号源融合接入
- V4: SkillsBench 公开打榜 workflow demo
- V5: 框架自进化 workflow（agent 写 PR）
- V99: Bilevel Autoresearch（workflow 自己改自己）
