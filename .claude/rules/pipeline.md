# Development Pipeline

> 本文件是 SkillForge 的开发协作规范，同时可被其他项目作为模板复制使用。
>
> **结构**：第一部分（When）决定走哪档；第二部分（SkillForge 项目策略）是本项目的覆盖；第三部分起（How）规定各档如何执行。
>
> **元内容**（流水线演进观察 / ROI 判断 / 迁移到新项目）拆在 [`pipeline-meta.md`](pipeline-meta.md)，**不自动加载**，维护 pipeline 时再读。

---

## 一、何时走哪档（When）

### 🎯 TL;DR 决策卡

**在 SkillForge 项目**（见第二部分"项目策略"）：

| 任务类型 | 走哪档 |
| --- | --- |
| 单行改 / 纯注释 / docs 更新 / 配置常量调整 | 🟢 **Solo** |
| 机械重命名 / 文件移动 / 已锁死纯函数小改动 | 🟢 **Solo** |
| 触碰核心文件 / 改 schema / 多不变量协议 / 跨 3+ 模块 / brief >800 字 | 🔴 **Full** |
| **以上之外的一切**（普通 bug 修复 / UI 调整 / 加字段 / 单模块新功能 / 新依赖 / serializer / build 配置） | 🟠 **Mid** |

**在没有 SkillForge 强制规则的其他项目**（套用本文件作模板）：

| 任务类型 | 走哪档 |
| --- | --- |
| 文档 / 注释 / 配置常量 / 单文件改 / 纯 rename / 删 dead code / UI polish | 🟢 **Solo** |
| 3+ 文件 / 跨 2+ 模块 / 新 REST endpoint / 改 test 断言 / 新依赖 / serializer 配置 / build 工具 plugin | 🟡 **Light** |
| 核心文件 / 成对协议 / 新 entity 或 schema / 跨模块新增 / brief >800 字 | 🔴 **Full** |

> SkillForge 不用 Light（self-judge 漏检率高），改用更结构化的 Mid（保留 reviewer 对抗 1 轮 + Judge 仲裁）。Light 留给其他项目作模板。

**混合时取最高档**（红灯 + Mid 区 = Full）。

### 四种模式

| 模式 | 流程 | Token 倍率 | 适用 |
| --- | --- | --- | --- |
| 🟢 **Solo**  | Claude 自己做 → 人工/浏览器 e2e → commit | 1x | 小改动 |
| 🟡 **Light** | 1 dev → 1 reviewer → self-judge（Claude 自己 consolidate + fix）→ e2e → commit | ~2x | 中等（**SkillForge 不用**，保留给其他项目） |
| 🟠 **Mid**   | TeamCreate → 1+ dev → 2 reviewer 对抗 **1 轮**（不循环）→ Judge → Phase Final verify & commit | ~2-3x | SkillForge 默认档 |
| 🔴 **Full**  | TeamCreate → [可选 Plan 对抗循环最多 2 轮] → 1+ dev → 2 reviewer 对抗循环最多 2 轮 → Judge → Phase Final | ~3-4x | 红灯触发的重磅任务 |

### 🔴 Full 的强制升级红灯

命中**任何一条**必须走 Full（不能 Mid）：

1. **触碰项目"核心文件清单"** —— 见第二部分
2. **多不变量成对的协议** —— tool_use ↔ tool_result、lock/unlock、transaction begin/commit、request/response handshake、lease/heartbeat
3. **新增持久化 entity / 修改 schema / 改 JPQL 或原生 SQL** —— 类型系统 + 数据库的边界
4. **跨 3+ 模块新增** —— 新 maven/gradle/npm 模块，或新 REST/RPC 端点 + 前端 + 测试三者同时改
5. **brief 超过 ~800 字** —— 脑内不确定性的 proxy

### 🟠 Mid 的适用条件

没命中红灯，且不属于 Solo 例外的：

- 普通 bug 修复（含 regression test）
- UI 调整 / 文案改动以外的可见行为变更
- 加字段 / 新 REST endpoint（不动 schema）
- 引入新依赖 / 改 serializer 配置 / 改 build 工具 plugin
- 单/双模块新功能、跨 2 模块改动
- 改现有 test 文件的断言

### 🟢 Solo 例外

- 单文件改 / 纯 rename / 删 dead code（且任务原意就是删）
- 配置常量调整
- 注释、docs、README 改动
- 已被强单元测试锁死的纯函数小改动
- 机械重命名 / 文件移动

### 🔍 开工前 3 问自检

1. **会不会触碰核心文件清单 / schema / 协议 / 跨 3+ 模块？** → 是 = Full
2. **brief 超过 800 字吗？** → 是 = Full
3. **是 Solo 例外吗？** → 是 = Solo

3 个都否 → **Mid**（默认档）。

### ⚠️ 批次污染规则

**一批改动只要混入任何一个红灯子项，整批升 Full。** 不要把红灯改动和 Mid 改动混在一个 batch 里 Mid 做，否则 reviewer 不看那个点就漏检。

---

## 二、SkillForge 项目策略（Project Policy）

> **迁移到其他项目时**：把这一整节替换成新项目的策略即可，其余节直接复用。

### 强制级别（2026-04-30 调整）

**历史**：
- 2026-04-15 用户要求"所有非 trivial 任务强制 Full" —— 当时 token 换"漏检 0%"。
- 2026-04-30 实际跑下来日常需求要 1-2 小时（最少 7-8 个 agent 调用，对抗循环全开 15-20+ 次），效率瓶颈明显。

**当前策略**：
- **Solo 例外**保持（单行 / 注释 / docs / 配置常量 / 机械 rename）
- **Full 强制**只在红灯触发（核心文件 / schema / 协议 / 跨 3+ 模块 / brief>800 字）
- **其他默认 Mid** —— 保留 reviewer 对抗 1 轮 + Judge 仲裁，砍 Plan 阶段、砍 Review 多轮循环

**为什么 Mid 够用**：核心引擎 / 并发 / JPA 边界 **不在每个改动里都出现**，对它们触碰才是 Full 的真正客户。原"全员 Full" 用 token 买"漏检 0%"，但日常需求漏检影响低。Mid 保留对抗 reviewer 抓非平凡 bug，跳过 Plan 循环和多轮 review 节省 60%+ 时间。

### 核心文件清单（触碰必 Full）

> 每次新增时补进来。标准："这里的 bug 不是跑起来会错，是特定 race/边界才错，浏览器测不出来"。

- `skillforge-core/src/main/java/com/skillforge/core/engine/AgentLoopEngine.java` — Agent Loop 核心
- `skillforge-core/src/main/java/com/skillforge/core/engine/hook/*` — Hook dispatcher / HookHandler / LifecycleHooksConfig
- `skillforge-core/src/main/java/com/skillforge/core/llm/**` — LlmProvider 抽象和各 provider 实现（ClaudeProvider / OpenAi-compatible，SSE streaming）
- `skillforge-core/src/main/java/com/skillforge/core/context/CompactionService.java` — 上下文压缩（3-phase split + stripe lock）
- `skillforge-server/src/main/java/com/skillforge/server/service/ChatService.java` — session 状态机 + tool_use/tool_result 不变量
- `skillforge-server/src/main/java/com/skillforge/server/service/SessionService.java` + `SessionMessageRepository` — 消息行存储
- `skillforge-server/src/main/resources/db/migration/V*.sql` — Flyway migration（schema 边界）
- `skillforge-dashboard/src/components/ChatWindow.tsx` + `Chat.tsx` — 流式渲染节流
- `skillforge-dashboard/src/components/LifecycleHooksEditor.tsx` + `hooks/useLifecycleHooks.ts` + `constants/lifecycleHooks.ts` — Hook editor discriminated union + Zod schema

### Known footguns（踩过的坑，触碰相关代码自动升 Full）

1. **ObjectMapper 必须 `registerModule(new JavaTimeModule())`** —— 否则 `Instant`/`LocalDateTime` 序列化不报错但输出错误时间戳
2. **`@Transactional` 只在 Service 层 + public 方法** —— Repository 和 private 方法上不生效，AOP 陷阱
3. **LLM `chatStream` 不重试** —— 重试会导致已推送 delta 重复交付 handler
4. **前端 useImperativeHandle ref 不触发父组件 re-render** —— 状态要用 useState，ref 只做 Save-time snapshot
5. **前端 WebSocket useEffect cleanup 必须 close** —— 否则组件卸载后仍 setState 告警 + 内存泄漏
6. **Jackson discriminated union** —— `@JsonTypeInfo` + `@JsonSubTypes` 时子类必须有无参构造器，`@JsonIgnoreProperties(ignoreUnknown = true)` 防向后兼容失败

---

## 三、Pipeline 如何执行（How）

### 前置：开启 Agent Teams（一次性）

Mid 和 Full 都依赖 `TeamCreate` / `TeamDelete` / `SendMessage` 三个 agent team 工具。这是 Claude Code 的 experimental feature，默认未开启。

**如果可用工具列表里看不到 `TeamCreate`**，按下面任一层级开启 env flag：

| 位置 | 影响范围 | 何时选 |
|---|---|---|
| `~/.claude/settings.json` | 全部项目 | 推荐，跟项目无关的 harness 开关 |
| `<project>/.claude/settings.json` | 协作者也开 | 想强制队友都用 |
| `<project>/.claude/settings.local.json` | 仅本机本项目 | 只想本机试一下 |

JSON 顶层加：

```json
{
  "env": {
    "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS": "1"
  }
}
```

**改完必须退出 Claude Code 重新进**，env 变量在启动时读取，热加载不生效。

### 🟠 Mid 流程

```
TeamCreate("feature-name")
  │
  ├─【Phase 1 — Dev】
  │   纯后端 → 1 Backend Dev / 纯前端 → 1 Frontend Dev / 跨栈 → 2 并行
  │   Dev 实现 + 自跑 build/test
  │
  ├─【Phase 2 — Review（对抗 1 轮，不循环）】
  │   Claude 主会话先 git diff HEAD -- ':!docs' → /tmp/<team>-diff.patch
  │   并行启动：
  │     Backend Reviewer  ← diff-in-prompt ─ Write /tmp/review-be-r1.md
  │     Frontend Reviewer ← diff-in-prompt ─ Write /tmp/review-fe-r1.md
  │   Judge 评双方 report：
  │     ✅ PASS（无 blocker）→ 进 Phase Final
  │     ⚠️ 仅 warning + 可一次 fix → SendMessage dev 修一次 → Claude 主会话目检 → 进 Phase Final（不再 review）
  │     ❌ blocker / 架构方向问题 → **升 Full**（进对抗循环），或回主会话决策
  │
  └─【Phase Final — Verify & Commit（Claude 亲自做）】见第六节
     git commit（等用户批准）→ TeamDelete
```

### 🔴 Full 流程

```
TeamCreate("feature-name")
  │
  ├─【Phase 1 — Plan（可选 / 对抗循环最多 2 轮）】
  │   触发条件：多种合理实现方向 / 迁移范围不明 / 上轮 reviewer 提"架构方向不对"
  │   不触发 → 直接进 Phase 2
  │
  │   Planner ─ draft plan → /tmp/plan-r{n}.md
  │        ↑                                ↓
  │        │                  Reviewer ─ /tmp/plan-review-r{n}.md
  │        │                                ↓
  │        │         Judge → OK ? exit : 继续
  │        │                                ↓ (若未通过 / r=1)
  │        └── SendMessage(Planner, "见 review，修复")
  │             第 2 轮还不过 → 回主会话决策
  │    → Judge 通过 → plan 文件即 dev brief
  │
  ├─【Phase 2 — Dev（并行）】
  │   纯后端 → 1 Backend Dev / 纯前端 → 1 Frontend Dev / 跨栈 → 2 并行
  │
  ├─【Phase 3 — Review（对抗循环最多 2 轮）】
  │   git diff → /tmp/<team>-diff.patch
  │   并行启动 Backend / Frontend Reviewer → Write /tmp/review-{be,fe}-r{n}.md
  │   Judge → OK ? exit : 继续
  │     若未通过：SendMessage 对应 Dev 修复 → Dev 修完 SendMessage 回 reviewer
  │              Claude 重新收 diff → r=2 再挑一轮
  │     第 2 轮还不过 → 回主会话决策（**砍掉第 3 轮**）
  │
  └─【Phase Final — Verify & Commit】见第六节
     git commit → TeamDelete
```

### 核心规则（10 条）

1. **循环上限 = 2 轮**（Mid 是 1 轮不循环；Full 的 Plan 和 Review 各独立计数最多 2 轮）。第 2 轮 Judge 仍不过 → 回主会话让用户决策（**已砍第 3 轮**）
2. **Mid 不循环**：Mid 的 Phase 2 review 只跑 1 轮。Reviewer 提 blocker 或架构问题 → 直接升 Full（进对抗循环）；只有 warning → dev 一次 fix 后 Claude 主会话目检 → 进 Phase Final
3. **Judge 每轮触发**：reviewer 产出后立即喂 Judge 看最新 report。不用"reviewer 自己满意才喂 Judge"，避免放水
4. **Dev agent 拆分**：纯后端=1、纯前端=1、跨栈=2（BE + FE 并行）。测试 / docs / 纯配置算到主任务栈
5. **diff-in-prompt**：见第五节
6. **Reviewer 写文件发路径**：大 report 必须 `Write` 到 `/tmp/review-*.md`，`SendMessage` 只带路径（避免 idle 不发内容的坑）
7. **Dev 保留上下文做 fix**：通过 `SendMessage` 让原 dev agent 修复，**不开新 dev**。原 dev idle 在 team 里持续存在
8. **Claude 只编排 + Verify**：Phase 1-3 Claude 不自己 merge review 结论、不自己 apply fix；只看 Judge 通过信号推进 phase。Phase Final e2e + commit 亲自做
9. **TeamCreate 必须**：用 Agent Team 机制编排，不用裸 Agent worktree 替代
10. **Plan phase 默认跳过**（2026-04-30 更新）：
    - **默认跳过**（直接进 Dev）：brief 完整唯一 / 普通 bug 修复 / 已有模式扩展 / **大多数任务**
    - 必启用：多种合理实现方向 / 迁移范围不明 / 上轮 reviewer 发现"架构方向不对"

11. **Reviewer 两阶段评审**（来自 superpowers/subagent-driven-development）：每个 reviewer report 必须**先 Spec 后 Quality**：
    - **Stage 1 — Spec Compliance**：对照 plan / brief / 验收点，每条 ✓ / ✗ 列出。**"要的没做"或"做了 plan 没要求的（scope creep）"= blocker**
    - **Stage 2 — Code Quality**：仅在 Stage 1 通过的前提下，按对抗约束 B 的 severity checklist 评通用代码质量
    - 不允许 Stage 1 ✗ 时直接跳到 Stage 2 评 quality（避免"代码很美但功能没做"）

12. **Dev 4 状态分诊**（来自 superpowers/subagent-driven-development）：每个 dev 完成 `SendMessage` 给 Claude 主会话时**显式声明 4 种状态之一**：
    - **DONE**：完成无保留 → 进 reviewer
    - **DONE_WITH_CONCERNS**：完成但有疑虑（写在 message 里）。读 concerns，若涉及正确性 / scope 则**先解决再 review**；仅 observation（"这个文件越来越大"）则记下，进 reviewer
    - **NEEDS_CONTEXT**：缺关键信息没法继续。Claude 主会话补 context 后用 `SendMessage` 给**原 dev**（不开新 dev，保留上下文）
    - **BLOCKED**：完成不了。Claude 主会话评估：(a) context 问题 → 补 context 重发；(b) reasoning 不够 → 单次升 Opus 重发；(c) 任务太大 → 拆小后重发；(d) plan 错 → 回主会话升级用户决策。**永远不要忽略 BLOCKED 让原 dev 不变 retry**

### 对抗约束 A / B / C（Mid 和 Full 共用）

> 2026-04-22 P13-2 首跑后固化。Mid 和 Full 都强制使用。

#### A. Planner / Dev 提交前强制 self-check

在 planner / dev 的 prompt 最后加：

```
在 SendMessage 之前做 self-check：
1. 读一遍你自己的产出
2. 列出最可能被挑出的 3 个错（事实不一致 / 内部矛盾 / 明显漏字段 / plan 漂移）
3. 逐条修掉或给出 why-it's-ok 的解释
4. 改完再 SendMessage（最终产出不要含 self-check 列表本身，只含修好的版本）
```

**Why**：拦截 planner/dev 自己读一遍就能发现的低垂果子，不浪费 reviewer 循环。

#### B. Reviewer 用 severity checklist（零成本必加）

reviewer prompt 的"每条打 severity"章节必须用严格 checklist：

```
**blocker 必须满足至少一条**：
- 数据丢失 / 错误计算 / 违反不变量（tool_use↔tool_result 配对等）
- 编译 / 运行时错误
- 安全 / 权限 / 认证 bug
- 明文 plan 要求未实现
- 静默失败（功能看起来 work 但实际丢数据 / 吞错误）

**warning**：性能 / 可读性 / 可维护性 / 命名 / 测试薄
**nit**：style / 格式 / 文档 / 变量名小改
```

**Why**：统一 severity 判定，Judge 不再做二次校准，PASS/FAIL 更直接。

#### C. Nit 折叠到 follow-up 文件，不回传循环

- Reviewer 发现 nit **只写到 `/tmp/nits-followup-{team-name}.md`**，不 SendMessage 给 planner/dev
- Judge PASS/FAIL 判决只看 blocker + warning
- Phase Final commit message 顺便提一句 nit follow-up（如果有）

**Why**：循环预算用在非关键路径上。Nit 本来就是"未来再看"的事，不该触发 dev fix 循环。

### 为什么不加其他听起来合理的约束

- **"moving goalposts 禁令"**（禁 reviewer 下轮升级 severity）：Judge 靠 severity 重判捕获真 bug（P13-2 实测），禁了反而漏真问题
- **"自动升级规则"**（某轮 blocker 没减就升模型 / 重启 agent）：2 轮上限 + 回主会话决策已经够用
- **"Judge 裁决必须引用行号"**：听起来严谨但实操 Judge 会变啰嗦
- **"新发现阈值"**（reviewer 不能下轮提完全不同维度的问题）：会阻止真发现

---

## 四、子 agent 模型选择策略（2026-04-30 更新）

### 当前策略：Reviewer Sonnet / Judge & Dev Opus（Mid 和 Full 共用）

| 角色 | 模型 | Agent 参数 | 理由 |
|---|---|---|---|
| **Dev agent**（写代码） | Opus | 不传 `model`（继承父级） | 代码生成质量对结果影响最大 |
| **Reviewer A/B**（挑刺） | Sonnet | `model: "sonnet"` | careful reading + pattern match Sonnet 够用 |
| **Judge**（仲裁） | Opus | 不传 `model` | severity 重判断 / 跨轮次一致性需要深度（P13-2 实测） |
| **Planner / Explorer** | Sonnet | `model: "sonnet"` | 读代码 + 整理信息 |

### 历史与回滚

- **2026-04-22**：用户基于 P13-2 一次案例选了"全 Opus"（Planner + Reviewer + Judge + Dev），用 ~40% 额外 token 换深度
- **2026-04-30**：评估实际任务时间过长，回滚 Reviewer + Planner 到 Sonnet，**保留 Judge Opus**（保留 P13-2 的深度收益）。预期省 ~30% token + 时间

**为什么 Judge 不一起回 Sonnet**：P13-2 实测 Sonnet Judge 漏判隐性 blocker（agent 切换 baseline 污染 / canonical legacy 静默丢失）。Reviewer 找问题 Sonnet 够；Judge 重判 severity / 跨轮次一致性，深度需求是 Judge 这一层。

> 其他项目（套用本文件作模板）：可全 Sonnet（pipeline.md 默认策略）；要 SkillForge 强度，按本节当前策略。

---

## 五、diff-in-prompt 优化

**原理**：reviewer / judge 不自己 Read 文件探索 codebase，因为每个 reviewer 都会重复读 dev 已读过的文件，浪费 ~50-80% input tokens。

**标准做法**：

1. Dev agent 完成后，Claude 主会话先跑 `git diff HEAD -- ':!docs'` 收集改动
2. 写到 `/tmp/<team-name>-diff.patch`
3. 塞进 reviewer 的 prompt：「Here is the complete diff at /tmp/...patch — Read it first. Don't systematically read source files; only spot-check specific lines when you need context.」
4. Reviewer 只需要 Read 个别文件做 spot-check（验证某行的上下文），不系统性地 Read

**效果**：
- reviewer input tokens 从"所有文件全文 ~50K"降到"diff ~10K + brief ~3K"
- 单 reviewer 省 ~70%，pipeline 总计省 ~30-40%

**例外**：
- Diff >500 行时，让 reviewer 自己 Read 关键文件的 full content——超长 diff 作为 prompt 反而让 LLM 注意力稀释
- 新增测试文件是 untracked 不在 diff 里，reviewer prompt 需显式告知要 Read 哪几个测试文件
- 不适用于 Dev agent 的 brief（Dev 需要读完整文件才能写代码）

---

## 六、Phase Final Verify 清单（Claude 亲自做，Mid 和 Full 共用）

- [ ] 前端改动：`npx agent-browser goto <url>` 打开实际页面（不只 `npm run build` 通过）
- [ ] 关键交互：`npx agent-browser eval "document.body.innerText"` 或 `snapshot -i` 断言 DOM（不只靠截图）
- [ ] 数据落库：curl API 查 DB 实际值（不能只信"按钮变绿"）
- [ ] 回滚测试：如有 Revert / Undo，走一次
- [ ] 测试与 build：dev 自己跑过，Claude 再 `npm run build` / `mvn test` 校一次
- [ ] **PRD 决策回写**：Plan 阶段 ratify 的"草拟"决策更新为"已定"；实现中超出原方案的决策补回 `prd.md` / `tech-design.md`，避免下次读 PRD 仍是 stale 草拟
- [ ] **完成事实归档**：`docs/delivery-index.md` 增加交付行（ID / 日期 / commit / migration / 关键验证）；需求包从 `requirements/active/` 移到 `requirements/archive/<yyyy-MM-dd>-<ID>-<slug>/`；`docs/todo.md` 队列行移到"最近完成"
- [ ] commit 前等用户批准（用户 durable 授权过的不需要）

---

> **流水线演进观察 / Pipeline ROI 判断 / 迁移到新项目** 等元工作内容已拆到 [`pipeline-meta.md`](pipeline-meta.md)，**不自动加载**，维护本 pipeline 时再读。
