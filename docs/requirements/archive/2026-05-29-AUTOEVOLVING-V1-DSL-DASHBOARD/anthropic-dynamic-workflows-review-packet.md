---
type: review-packet
audience: review-agent(读 SkillForge V1 DSL 现有方案 + 跟本 packet 对照产出报告)
created: 2026-05-29
trigger: Anthropic Dynamic Workflows 在 2026-05-28 同 Opus 4.8 发布,跟 SkillForge V1 DSL 同一天独立设计,需要 cross-check 是否有关键架构 gap
read_time: 10-15 min
expected_output: 3 段对照报告(✅ 对齐 / ⚠️ 缺口 / 🤔 分歧)+ 优先级排序的 action items
related:
  - "[../../../research-docs/research/anthropic-blog-translations/introducing-dynamic-workflows-in-claude-code-2026-05-28.md](../../../../../research-docs/research/anthropic-blog-translations/introducing-dynamic-workflows-in-claude-code-2026-05-28.md)"(本 packet 详细出处)
  - "[../../../research-docs/research/anthropic-blog-translations/scaling-managed-agents-decoupling-brain-from-hands-2026-04-08.md](../../../../../research-docs/research/anthropic-blog-translations/scaling-managed-agents-decoupling-brain-from-hands-2026-04-08.md)"(meta-harness 哲学背景)
  - "[../../../research-docs/research/agent-harness-wiki/concept/karpathy-autoresearch-thinking.md](../../../../../research-docs/research/agent-harness-wiki/concept/karpathy-autoresearch-thinking.md)"(思想根基)
---

# Review Packet — Anthropic Dynamic Workflows vs SkillForge V1 DSL

## 0. 给 review agent 的 brief

**任务**:你在 review SkillForge `AUTOEVOLVING-V1-DSL-DASHBOARD` 当前的设计(prd.md / tech-design.md / dsl-syntax.md)。本 packet 是 Anthropic Dynamic Workflows 的关键事实清单 —— **Anthropic 在 2026-05-28 发布了一个同样"plan-as-code workflow engine"产品,跟 V1 DSL 同一天独立设计**。

**目标**:对照两者,产出一份**简短报告**,内容必须包含 3 段:

1. **✅ 已对齐** — V1 DSL 设计点跟 Anthropic 选择一致(验证方向)
2. **⚠️ 缺口** — Anthropic 有但 V1 DSL 没有的**关键架构特性**(必须在 ship 前修)
3. **🤔 分歧** — V1 DSL 有但 Anthropic 没有的(**可能是合法 divergence,也可能是 over-engineering**,需判断)

最后给**优先级排序的 action items**(P0 / P1 / P2)。

**当前用户原话(2026-05-29)**:
> "当前在做这个事情的时候 主要是考虑到 evolvingLoop plan as workflow 这个能力 没有想的很复杂。"

所以你的任务**不是**重新设计 V1,而是**用 Anthropic 的设计当 oracle 检查 V1 没漏掉关键东西**。

---

## 1. Anthropic Dynamic Workflows 一句话核心

> **Claude 写 JavaScript orchestration script,runtime 跑 tens to hundreds of parallel subagents,Claude context 只保留 final answer(不被中间结果污染)。研究预览,2026-05-28 同 Opus 4.8 发布,Claude Code v2.1.154+。**

### 关键 architectural shift(必须 review V1 是否吃到)

| 维度 | SubAgent / Skill 模式 | Workflow 模式 |
|---|---|---|
| **plan 在哪** | Claude 对话 context | JavaScript runtime |
| **orchestrator** | Claude(turn-by-turn 决定) | script 本身(loop + branching) |
| **中间结果** | 都进 Claude context | 留在 runtime,**只 final 进 Claude** |
| **context 膨胀** | 严重(N agent → N 份结果) | 不存在 |
| **可 resume** | 不能 | **能**(journal + cache) |

---

## 2. Workflow 工具栈(Anthropic 的选择,精确)

Anthropic **没有**为 workflow 单独建一堆专门工具。而是:

### 2.1 核心新工具:`Workflow`(eagerly-loaded)

**1 个工具,3 种输入模式**:

```typescript
Workflow({
  // 3 种互斥输入(用其一):
  script?: string,           // 直接传完整 JS 脚本(inline)
  scriptPath?: string,       // 已存在的 .js 文件路径
  name?: string,             // 已注册的 workflow 名(从 .claude/workflows/ 自动发现)

  args?: any,                // 传给脚本的 args 全局变量
  resumeFromRunId?: string,  // ⭐ 从某次 run 恢复(cache hit 未变 prefix)
})
```

**返回**:`runId` + 后台跑(`<task-notification>` 完成时推回)+ persisted `scriptPath`(inline 模式自动落地到 session 目录)。

### 2.2 配套 Task 管理工具(deferred,按需 ToolSearch 加载)

| 工具 | 作用 |
|---|---|
| `TaskCreate` | 创建后台任务(也用于普通 todo) |
| `TaskList` | 列出所有 running tasks |
| `TaskGet` | 拿单个 task 详情 |
| `TaskOutput` | 拿 task output stream |
| `TaskStop` | 杀死 workflow run(必须在 resume 前) |
| `TaskUpdate` | 更新 task 状态 |

### 2.3 `/workflows` slash command

实时进度 dashboard。**不是新工具,是 UI surface**。

### 2.4 修改 workflow — **没有专门工具!⭐ 关键设计选择**

Anthropic **故意没建** `WorkflowEdit` / `WorkflowCreate` / `WorkflowDelete`。**workflow = .js 文件 → 直接用 Edit / Write 改**。

Anthropic 系统 prompt 原话(译者一手):
> "To iterate on a workflow, edit that file with **Write/Edit** and re-invoke Workflow with `{scriptPath: \"<path>\"}` instead of resending the full script."

---

## 3. 6 个核心 API 函数(Workflow 脚本内可用)

```typescript
// 1. agent — 起一个 subagent(必备)
agent(prompt: string, opts?: {
  label?: string,           // 显示名
  phase?: string,           // 进度组归属
  schema?: object,          // ⭐ JSON Schema,强制 structured output
  model?: string,           // 模型覆盖
  isolation?: 'worktree',   // ⭐ git worktree 并行改文件防冲突
  agentType?: string,       // 自定义 subagent type
}): Promise<any>

// 2. pipeline — 流水线(默认 pattern,不要随便用 parallel)
pipeline(items, stage1, stage2, ...): Promise<any[]>
// 每个 item 独立走完所有 stage,无 barrier
// item A 在 stage 3,item B 还在 stage 1 — OK
// stage 回调签名:(prevResult, originalItem, index) => Promise<any>

// 3. parallel — 屏障(barrier,慎用)
parallel(thunks: Array<() => Promise<any>>): Promise<any[]>
// 等所有 thunk 完成才返
// 某个 thunk throw → 返 null,不抛(用 .filter(Boolean))

// 4. phase — 进度组
phase(title: string): void
// 后续 agent() 调用归到这个 group

// 5. log — 用户可见 narrator
log(message: string): void

// 6. workflow — 嵌套调用(最多 1 层)
workflow(nameOrRef: string | {scriptPath: string}, args?: any): Promise<any>
```

**全局可用**:`budget`(`total / spent() / remaining()`)+ `args`(传入参数)

---

## 4. 硬限制 + 关键约束

### 4.1 硬限制(必须照抄)

| 限制 | 数值 | 理由 |
|---|---|---|
| **并发 agent** | `min(16, cpu cores - 2)` | 本地资源 aware,防 CPU 占满 |
| **单 run 总 agent** | **1,000 total** | runaway-loop backstop |
| **嵌套 workflow 深度** | **1 层**(workflow() inside child throws) | 防递归失控 |
| **脚本权限** | **不能直接 filesystem / shell** | 只有 agent() 调用能读写文件 / 跑命令 |

> ⚠️ **这两个数字 SkillForge V1 应该直接照抄**,理由:Anthropic 在 production 验证过是 sweet spot。SkillForge 不要重新发明轮子。

### 4.2 `meta` 必须 PURE LITERAL(强约束)

```javascript
// ✅ 正确
export const meta = {
  name: 'find-bugs',
  description: 'Find bugs in branch',
  phases: [{ title: 'Scan' }, { title: 'Verify' }],
}

// ❌ 错误 — 含变量 / 函数调用 / spread / template interpolation
export const meta = {
  name: `find-${branchName}`,  // 不行
  phases: [...defaultPhases, { title: 'Verify' }],  // 不行
}
```

**理由**:**支持 resume**(下面 4.3)。运行时需要 hash(prompt, opts) 来 cache,meta 动态化会破坏 cache。

### 4.3 ⭐⭐⭐ Resume 机制(SkillForge V1 必须有等价物)

**为什么**:plan-as-code 的核心价值不是"跑得快",是"debug iteration 快"。第 1 次跑 workflow 可能 30 分钟,改一行重跑应该<5 分钟。

**怎么做**:
- 第 1 次跑 → journal 记每个 `agent()` 调用的 `(prompt, opts) → result`
- 编辑 .js 脚本
- 第 2 次跑带 `resumeFromRunId`:
  - 完全相同的 `agent()` 调用 → 瞬时返 cached result
  - 第一个改过的 `agent()` 调用 + 之后所有 → live run

**对 meta 的约束**:
- 同 script + 同 args = 100% cache hit
- 这就是为什么 `Date.now()` / `Math.random()` / argless `new Date()` **在 workflow script 里 throw**(否则 hash 永远变)

---

## 5. 设计哲学(Anthropic 选择的 WHY,SkillForge 应该对齐)

### 5.1 Don't proliferate tools, generalize existing ones

Anthropic 的选择 vs 反例:

| 需求 | Anthropic 选择 | 反模式(不要做) |
|---|---|---|
| 创建 workflow | 复用 `Write` + `Workflow({script})` 自动落盘 | 新建 `WorkflowCreate` 工具 |
| 执行 workflow | `Workflow` 多模式(script/path/name) | 拆 3 个工具 |
| 修改 workflow | 复用 `Edit` / `Write` | 新建 `WorkflowEdit` 工具 |
| 删除 workflow | 复用 `Bash rm` 或跳过 | 新建 `WorkflowDelete` 工具 |
| 管理 run | 复用通用 `Task*` 工具 | 新建 `WorkflowRunList / Stop / Get` |
| UI | 1 个 `/workflows` slash command | dashboard 散落 N 个 entry |

**原则**:LLM 不用学新工具(降 cognitive cost)+ 工具 schema 不爆炸(保护 prompt cache prefix)+ 复用现有 deferred tool 加载机制。

### 5.2 Plan 移出 Claude context,只 final answer 进

这是 [[../../../research-docs/research/agent-harness-wiki/concept/prompt-caching-is-everything]] 铁律 1 "用 message 传更新,不改 prompt" 的根本落地。Workflow 是这条铁律的工程极致 —— **中间结果根本不进 Claude context**。

### 5.3 pipeline 默认 / parallel 慎用

Anthropic 系统 prompt 反复强调:

> "DEFAULT TO pipeline(). Only reach for a barrier (parallel between stages) when you genuinely need ALL prior-stage results together."

**3 种 parallel 才正确的场景**:
1. **Dedup / merge** across full result set before expensive downstream
2. **Early-exit** if total count is zero
3. **Cross-item context required** in stage N(prompt 引用"其他 finding")

不在这 3 种 → 用 pipeline。

### 5.4 Schema 强制 structured output

Schema 验证在 **tool-call layer**,LLM 不符合会被 **force retry**。不需要业务代码 parse JSON。

### 5.5 adversarial verification(agent 互相 refute)

> "Agents address the problem from independent angles, **other agents try to refute what they found**, and the run keeps iterating until the answers converge."

**这就是 falsification 的产品化形态**(对应 [[../../../research-docs/research/agent-harness-wiki/papers/agentic-harness-engineering]] AHE `predicted_impact`)。

---

## 6. Bun rewrite 案例(verify 这套架构能跑通的现实案例)

Jarred Sumner(Bun creator)用 Dynamic Workflows 把 Bun 从 Zig 移植到 Rust:

| 维度 | 数值 |
|---|---|
| **代码量** | ~750,000 行 Rust |
| **时间** | 11 天(其他来源 6 天) |
| **测试通过率** | **99.8%** |

### 4 个串联 workflow(看 workflow 怎么 compose)

1. **Lifetime mapping**:每个 Zig struct field → 正确的 Rust lifetime
2. **Port**:每个 .zig → behavior-identical .rs(**hundreds of agents in parallel + 2 reviewers per file**)
3. **Fix loop**:驱动 build + test 直到通过
4. **Overnight cleanup**:消除 unnecessary data copy,每 fix 一个 PR

**Jarred 评论**(给 SkillForge V1 重要 framing):
> "Workflows split up work **more deterministically than subagents**. It's closer to a **bespoke build system** for a project than chat."
>
> "I've seen individual workflows run for **10 hours continuously**."

---

## 7. SkillForge V1 DSL 需要对照检查的 10 个问题

review agent 请逐条对照 V1 DSL 现有 prd.md / tech-design.md / dsl-syntax.md,产出对每个问题的判断 ✅ / ⚠️ / 🤔。

### Q1:V1 DSL 是否选择"plan-as-code"(plan 移出 Claude context)?
- ✅ 如果是 → 跟 Anthropic 同源,验证方向
- ⚠️ 如果不是 → 严重缺口,context 膨胀问题没解

### Q2:V1 DSL 的执行 surface 是 1 个工具多模式,还是拆多个?
- ✅ 1 个工具多模式(对应 `Workflow({script/scriptPath/name})`)
- 🤔 拆多个 → 评估是否 over-engineering

### Q3:V1 DSL 修改 workflow 用什么?
- ✅ 复用现有 file editor(Edit/Write 等价)
- ⚠️ 新建专门工具 → 反 Anthropic 设计哲学

### Q4:V1 DSL 是否支持 `resumeFromRunId` 等价机制?
- ⭐⭐⭐ **P0 必须有**
- ⚠️ 如果没有 → debug iteration 体验差 10×,会严重打击采用率

### Q5:V1 DSL 6 原语(`agent` / `pipeline` / `parallel` / `phase` / `log` / `workflow`)是否一一对应?
- ✅ 命名 + 语义对应 → cognitive cost 最低,迁移 Claude Code workflow 用户成本最低
- 🤔 不同命名 / 不同语义 → 评估理由 + 是否值得

### Q6:V1 DSL 是否有 `pipeline` vs `parallel` 区分 + 推荐 `pipeline` 默认?
- ⭐ 这是性能 + 正确性根本差异,**P1 必须明确**

### Q7:V1 DSL 是否支持 `schema` 参数强制 structured output(validation 在 tool-call layer)?
- ⭐ 这是 LLM 不靠谱时的工程兜底,**P1 必须有**

### Q8:V1 DSL 硬限制(并发上限 / 总 agent 上限 / 嵌套深度)是否跟 Anthropic 一致?
- 并发:`min(16, cpu cores - 2)`
- 总数:1,000
- 嵌套:1 层
- ⚠️ 不一致 → 必须有强理由

### Q9:V1 DSL 的 `meta` 块是否要求 PURE LITERAL?
- ⭐ 这是 resume 机制的前提,**Q4 答 yes 的话 Q9 必然 yes**

### Q10:V1 DSL 是否设计了 adversarial verification 路径(agent 互相 refute)?
- ⚠️ 如果没有 → AUTOEVOLVING V3 falsification stage 落不下来
- ✅ 如果有 → 跟 K-2 / R-AHE 路线对齐

---

## 8. 给 review agent 的产出模板

请按这个 schema 写 review report,**报告写在新文件**:`anthropic-comparison-review-2026-05-29.md`(同目录):

```markdown
---
type: review-report
created: 2026-05-29
input_packet: anthropic-dynamic-workflows-review-packet.md
reviewed_files:
  - prd.md
  - tech-design.md
  - dsl-syntax.md
reviewer: <你是哪个 agent / model>
---

# Review Report — V1 DSL vs Anthropic Dynamic Workflows

## ✅ 已对齐(N 项)

| # | V1 DSL 设计点 | Anthropic 对应 | 一句话证据(file:line) |
|---|---|---|---|
| 1 | ... | ... | ... |

## ⚠️ 缺口 — 必须补齐(N 项)

| # | Anthropic 有的 | V1 DSL 缺的 | 影响 | P0/P1/P2 | 建议改法 |
|---|---|---|---|---|---|
| 1 | resumeFromRunId | 没有 | debug iteration 体验差 10× | **P0** | 加 RunWorkflow 工具 resumeFromRunId 参数 + journal 表 |

## 🤔 分歧 — 需判断(N 项)

| # | V1 DSL 有的 | Anthropic 没有 | 判断 | 理由 |
|---|---|---|---|---|
| 1 | ... | ... | 合法 divergence / over-engineering | ... |

## 优先级排序的 action items

### P0(必须在 ship 前修)
- [ ] action 1 ...
- [ ] action 2 ...

### P1(强烈建议但可 V1.1)
- [ ] action 3 ...

### P2(nice to have)
- [ ] action 4 ...

## 总结
1-2 段总结:V1 DSL 整体跟 Anthropic 路线对齐度如何,最大风险点是什么,建议是否能按时 ship。
```

---

## 9. 附录:Workflow 脚本完整例子(给你看 API 用法)

```javascript
export const meta = {
  name: 'review-changes',
  description: 'Review changed files across dimensions, verify each finding',
  phases: [{ title: 'Review' }, { title: 'Verify' }],
}

const DIMENSIONS = [
  {key: 'bugs', prompt: 'Find bugs in this diff'},
  {key: 'perf', prompt: 'Find performance issues in this diff'},
]

// pipeline:每个 dimension 独立走完 review + verify,无 barrier
const results = await pipeline(
  DIMENSIONS,
  // Stage 1:review
  d => agent(d.prompt, {
    label: `review:${d.key}`,
    phase: 'Review',
    schema: FINDINGS_SCHEMA,
  }),
  // Stage 2:adversarial verify(对当前 dimension 的 finding)
  review => parallel(review.findings.map(f => () =>
    agent(`Adversarially verify: ${f.title}`, {
      label: `verify:${f.file}`,
      phase: 'Verify',
      schema: VERDICT_SCHEMA,
    }).then(v => ({...f, verdict: v}))
  ))
)

const confirmed = results.flat().filter(Boolean).filter(f => f.verdict?.isReal)
return { confirmed }
```

**这个例子展示**:
- `pipeline` 默认 + 无 barrier(Q6)
- `schema` 强制 structured output(Q7)
- `parallel` 在第 2 stage 只对单 dimension 用(Q6 例外正确场景:cross-item context 不需要)
- `phase` 分组(Q5)
- `label` 显示名(Q5)
- `.filter(Boolean)` 防 null(parallel thunk throw → null,不抛)

---

## 10. 我推荐 review agent 的执行顺序

1. **先读** `prd.md` 整体了解 V1 DSL 设计意图(5 分钟)
2. **再读** `tech-design.md` 看具体 schema + API(5 分钟)
3. **再读** `dsl-syntax.md` 看 6 原语具体定义(3 分钟)
4. **回到本 packet** §7 10 个问题,逐条对照打勾(10 分钟)
5. **写报告** 按 §8 模板(10 分钟)

**总时间预算:30-40 分钟产出报告**

---

## 11. 反馈给原用户的预期格式

review report ship 后,原用户(youren)将看到:

- **P0 数量 + 简述**:让他立刻知道 ship 前必须修几个
- **总分歧度**:V1 DSL 跟 Anthropic 路线对齐百分比(0-100%)
- **最大风险点**:1-2 句话,他能拿这个去跟团队讨论
- **建议 ship 时间影响**:多少天 / 多少 sprint 因为补 P0 要延迟

希望你 review 的产出能直接驱动 V1 DSL `prd.md` 后续修订,不需要他二次翻译。

---

## 12. 相关文档(深度学习路径)

如果你想深入理解 Anthropic 的整套架构演化,这 6 篇按顺序读(已经在 research-docs 翻译目录):

1. `scaling-managed-agents-decoupling-brain-from-hands-2026-04-08.md` — meta-harness 架构基础
2. `an-update-on-recent-claude-code-quality-reports-2026-04-23.md` — 内部架构暴露(effort enum / thinking cache)
3. `lessons-from-building-claude-code-prompt-caching-is-everything-2026-04-30.md` — context 不能膨胀的根本原则
4. `new-in-claude-managed-agents-2026-05-06.md` — Dreaming / Outcomes / Multi-Agent
5. `introducing-claude-opus-4-8-2026-05-28.md` — 模型升级 + Effort Control UI
6. `introducing-dynamic-workflows-in-claude-code-2026-05-28.md` — **plan-as-code,本 packet 主出处**

但**做这次 review 不必看 6 篇**,本 packet 已经提炼了你需要的所有事实。
