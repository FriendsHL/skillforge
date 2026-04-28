# Development Pipeline

> 本文件是 SkillForge 的开发协作规范，同时可被其他项目作为模板复制使用。
>
> **结构**：第一部分（When）决定走哪档；第二部分（SkillForge 项目策略）是本项目的覆盖；第三部分起（How）规定 Full Pipeline 如何执行。

---

## 一、何时走哪档（When）

### 🎯 TL;DR 决策卡

**在 SkillForge 项目**（见第二部分"项目策略"强制规则）：

| 任务类型 | 走哪档 |
| --- | --- |
| 单行改 / 纯注释 / docs 更新 / 配置常量调整 | 🟢 **Solo** |
| 机械重命名 / 文件移动 / 已锁死纯函数小改动 | 🟢 **Solo** |
| **以上之外的一切**（包括看起来很小的 bug 修复 / UI 调整 / 加字段） | 🔴 **Full** |

**在没有 SkillForge 强制规则的其他项目**（套用本文件作模板）：

| 任务类型 | 走哪档 |
| --- | --- |
| 文档 / 注释 / 配置常量 / 单文件改 / 纯 rename / 删 dead code / UI polish | 🟢 **Solo** |
| 3+ 文件 / 跨 2+ 模块 / 新 REST endpoint / 改 test 断言 / 新依赖 / serializer 配置 / build 工具 plugin | 🟡 **Light** |
| 核心文件（本项目自己维护清单） / 成对协议 / 新 entity 或 schema / 跨模块新增 / brief >800 字 | 🔴 **Full** |

**混合时取最高档**（红灯 + 黄灯 = Full，Full > Light > Solo）。详细升级触发条件见本节下方"红灯 5 条 / 黄灯 6 条"子节。

### 三种模式

| 模式 | 流程 | Token 倍率 | 适用 |
| --- | --- | --- | --- |
| 🟢 **Solo**  | 1 dev（通常是 Claude 自己）→ 人工/浏览器 e2e → commit | 1x | 小改动 |
| 🟡 **Light** | 1 dev agent → 1 reviewer agent → self-judge（Claude 自己 consolidate + fix）→ e2e → commit | ~2x | 中等 |
| 🔴 **Full**  | **Agent Team**：TeamCreate → [Plan phase（对抗循环）] → 1+ dev → 2 reviewer（对抗循环） → Judge → Phase 4 verify & commit | ~3-5x | 重磅 |

### 🔴 Full 的 5 个强制升级红灯

命中**任何一条**必须走 Full，即使任务听起来小：

1. **触碰项目的"核心文件清单"** —— 见本文件第二部分 SkillForge 项目策略
2. **多不变量成对的协议** —— 配对语义（tool_use ↔ tool_result、lock/unlock、transaction begin/commit、request/response handshake、lease/heartbeat 等）
3. **新增持久化 entity / 修改 schema / 改 JPQL 或原生 SQL** —— 类型系统 + 数据库的边界是 Solo 漏检高发区
4. **跨模块新增** —— 新 maven/gradle/npm 模块、或新 REST/RPC 端点 + 前端 + 测试三者同时改
5. **brief 超过 ~800 字** —— 脑内不确定性的 proxy。Claude 自己写 brief 都要 8KB 解释，solo 实现不可能没遗漏

### 🟡 Light 的升级条件（没命中红灯时）

命中任一条 → Light：

1. 动到 3+ 个文件 或 跨 2+ 模块
2. 新增 REST endpoint（即使是 simple read）
3. 改动现有 test 文件的断言（意味着在修 spec 或行为）
4. 引入新依赖（pom.xml / package.json / Cargo.toml / requirements.txt）
5. 动到任何 serializer/deserializer 配置（Jackson、Gson、serde、protobuf），特别是时间/日期字段处理
6. 改了 build 工具 plugin 配置（shade、assembly、webpack、vite、cargo features）

### 🟢 Solo 默认

什么红灯黄灯都没命中 → Solo：

- 单文件改 / 纯 rename / 删 dead code
- 配置常量调整
- 前端 UI polish（CSS、文案、小 layout）
- 注释、docs、README 改动
- 已有 unit test 覆盖的纯函数改逻辑
- Bug 修复（修完有 regression test）

### 🔍 开工前 3 问自检

启动任务时先问：

1. **会不会触碰项目的核心文件清单？** → 是 = Full
2. **动没动 entity/schema / serializer / build 配置 / 新依赖 / 新 REST？** → 是 = 至少 Light
3. **Brief 写起来超过 800 字吗？** → 是 = Full

3 个都否 → Solo。

### ⚠️ 批次污染规则

**一批改动只要混入任何一个红灯子项，整批要升级。** 不要把红灯改动和 Solo 改动混在同一 batch 里 Solo 做，否则 reviewer 不看那个点就漏检。

---

## 二、SkillForge 项目策略（Project Policy）

> **迁移到其他项目时**：把这一整节替换成新项目的策略即可，其余节（一、三、四…）直接复用。

### 强制级别：所有非 trivial 任务走 Full

用户 2026-04-15 明确要求：**SkillForge 所有开发任务强制走 Full Pipeline**，即使红黄灯都没命中也走。理由：核心引擎 / 并发 / 多 LLM 兼容 / JPA 边界交织，漏检成本高于多花的 token。

**唯一例外（可以 Solo）**：
- 单行改 / 纯注释 / docs 更新 / 配置常量调整
- 机械重命名 / 文件移动
- 已被强单元测试锁死的纯函数小改动

### 核心文件清单（触碰必 Full）

> 每次新增时补进来。标准："这里的 bug 不是跑起来会错，是特定 race/边界才错，浏览器测不出来"。

- `skillforge-core/src/main/java/com/skillforge/core/engine/AgentLoopEngine.java` — Agent Loop 核心
- `skillforge-core/src/main/java/com/skillforge/core/engine/hook/*` — Hook dispatcher / HookHandler / LifecycleHooksConfig
- `skillforge-core/src/main/java/com/skillforge/core/llm/**` — LlmProvider 抽象和各 provider 实现（ClaudeProvider / OpenAi-compatible 等，SSE streaming 细节）
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

## 三、Full Pipeline 如何执行（How）

### 前置：开启 Agent Teams（一次性）

Full Pipeline 依赖 `TeamCreate` / `TeamDelete` / `SendMessage` 三个 agent team 工具。这是 Claude Code 的 experimental feature，默认未开启。

**如果可用工具列表里看不到 `TeamCreate`**（或调用时报 schema 不存在），按下面任一层级开启 env flag：

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

**改完必须退出 Claude Code 重新进**，env 变量在启动时读取，热加载不生效。重进后可用工具列表里应该能看到 `TeamCreate` / `TeamDelete` / `SendMessage`。

### 完整流程

```
TeamCreate("feature-name")
  │
  ├─【Phase 1 — Plan（对抗循环，最多 3 轮）】
  │   Planner ─ draft plan → Write /tmp/plan-r{n}.md
  │        ↑                                ↓
  │        │                  Reviewer ─ Write /tmp/plan-review-r{n}.md
  │        │                                ↓
  │        │         Judge 看 plan + review → OK ? exit : 继续
  │        │                                ↓ (若未通过)
  │        └── SendMessage(Planner, "见 /tmp/plan-review-r{n}.md 修复")
  │                                         ↓ 循环
  │    → Judge 通过 → plan 文件即 dev brief
  │
  ├─【Phase 2 — Dev（并行）】
  │   纯后端任务 → Backend Dev 1 个
  │   纯前端任务 → Frontend Dev 1 个
  │   跨前后端   → Backend Dev + Frontend Dev 并行
  │
  ├─【Phase 3 — Review（对抗循环，最多 3 轮）】
  │   Claude 主会话先 `git diff HEAD -- ':!docs'` 收集改动
  │   并行启动：
  │     Backend Reviewer  ← diff-in-prompt ─ Write /tmp/review-be-r{n}.md
  │     Frontend Reviewer ← diff-in-prompt ─ Write /tmp/review-fe-r{n}.md
  │        ↑                                              ↓
  │        │                         Judge 评双方 report → OK ? exit : 继续
  │        │                                              ↓ (若未通过)
  │        ├── SendMessage(Backend Dev,  "见 /tmp/review-be-r{n}.md 修复")
  │        └── SendMessage(Frontend Dev, "见 /tmp/review-fe-r{n}.md 修复")
  │             dev 修完 SendMessage 回对应 reviewer
  │             Claude 重新收 diff → Reviewer 再挑一轮
  │    → Judge 通过 → 进 Phase 4
  │
  └─【Phase 4 — Verify & Commit（Claude 亲自做）】
     agent-browser goto <url> + eval "document.body.innerText" 断言
     curl API 校数据落库（不能只信 UI 按钮变绿）
     git commit（等用户批准）
     TeamDelete
```

### 核心规则（9 条）

1. **循环上限 = 3 轮**：Phase 1 和 Phase 3 各独立计数。第 3 轮 Judge 仍不过 → 回主会话让用户决策，不自动 abort
2. **Judge 每轮触发**：reviewer 产出后立即喂 Judge 看最新 report。不用"reviewer 自己满意才喂 Judge"，避免放水
3. **Dev agent 拆分**：纯后端=1、纯前端=1、跨栈=2（BE + FE 并行）。测试 / docs / 纯配置算到主任务栈
4. **diff-in-prompt**：见本文第五节
5. **Reviewer 写文件发路径**：大 report 必须 `Write` 到 `/tmp/review-*.md`，`SendMessage` 只带路径（避免 idle 不发内容的坑）
6. **Dev 保留上下文做 fix**：通过 `SendMessage` 让原 dev agent 修复，**不开新 dev**。原 dev idle 在 team 里持续存在
7. **Claude 只编排 + Verify**：Phase 1-3 Claude 不自己 merge review 结论、不自己 apply fix；只看 Judge 通过信号推进 phase。Phase 4 e2e + commit 亲自做
8. **TeamCreate 必须**：用 Agent Team 机制编排，不用裸 Agent worktree 替代
9. **Plan phase 触发条件**（Full 内部是否启用 Plan）：
   - 必启用：多种合理实现方向 / 迁移范围不明 / 上轮 reviewer 发现"架构方向不对"
   - 可跳过（直接进 Dev）：brief 完整唯一 / 纯 bug 修复 / 已有模式扩展

### 对抗约束 A / B / C（2026-04-22 P13-2 首跑后固化）

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
- Phase 4 commit message 顺便提一句 nit follow-up（如果有）

**Why**：循环预算用在非关键路径上。Nit 本来就是"未来再看"的事，不该触发 dev fix 循环。

### 为什么不加其他听起来合理的约束

- **"moving goalposts 禁令"**（禁 reviewer 下轮升级 severity）：Judge 靠 severity 重判捕获真 bug（P13-2 实测），禁了反而漏真问题
- **"自动升级规则"**（某轮 blocker 没减就升模型 / 重启 agent）：3 轮上限 + 回主会话决策已经够用
- **"Judge 裁决必须引用行号"**：听起来严谨但实操 Judge 会变啰嗦
- **"新发现阈值"**（reviewer 不能下轮提完全不同维度的问题）：会阻止真发现

---

## 四、子 agent 模型选择策略

### 默认策略（其他项目 / Light 模式可采用）

| 角色 | 推荐模型 | Agent 参数 | 理由 |
|---|---|---|---|
| **Dev agent**（写代码） | Opus | 不传 `model`（继承父级） | 代码生成质量对结果影响最大 |
| **Reviewer A/B**（挑刺） | Sonnet | `model: "sonnet"` | 核心是 careful reading + pattern match；Sonnet 够用 |
| **Judge**（仲裁） | Sonnet | `model: "sonnet"` | 规则化决策 + 结构化输出 |
| **Planner / Explorer** | Sonnet | `model: "sonnet"` | 读代码 + 整理信息 |

> 混合策略实测节省 ~40% token，review/judge 质量基本无损。Judge 碰到疑难分歧可个案升级回 Opus。

### SkillForge 策略：Full Pipeline 全 Opus

对抗循环对 Reviewer/Judge 的判断深度要求更高（尤其是 severity 重判断、跨轮次一致性），用户 2026-04-22 明确要求**Planner + Reviewer + Judge + Dev 全部 Opus**。

代价：比混合策略多 ~40% token。
收益：P13-2 实测对抗循环 Judge 把 2 个 warning 重判为隐性 blocker（agent 切换 baseline 污染 / canonical legacy 静默丢失），避免真 bug 上生产。Sonnet Judge 未必判得出这种深度。

**结论**：SkillForge Full 任务启动 sub-agent 时一律不传 `model` 参数（继承父级 Opus）。其他项目可按"默认策略"混搭省 token。

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

## 六、Phase 4 Verify 清单（Claude 亲自做）

- [ ] 前端改动：`npx agent-browser goto <url>` 打开实际页面（不只 `npm run build` 通过）
- [ ] 关键交互：`npx agent-browser eval "document.body.innerText"` 或 `snapshot -i` 断言 DOM（不只靠截图）
- [ ] 数据落库：curl API 查 DB 实际值（不能只信"按钮变绿"）
- [ ] 回滚测试：如有 Revert / Undo，走一次
- [ ] 测试与 build：dev 自己跑过，Claude 再 `npm run build` / `mvn test` 校一次
- [ ] commit 前等用户批准（用户 durable 授权过的不需要）

---

## 七、流水线演进观察（post-mortem）

**第一次跑新 pipeline 或改动约束后做一次 post-mortem**，记录：
- 哪些 blocker 是 self-check 自己就能挡的 → 调约束 A 强度
- Reviewer severity 错判率 → 调约束 B checklist 细度
- Nit 是否真的是 nit → 调约束 C 阈值
- 循环触顶（3 轮用完）频率 → 考虑调上限或入口门槛

**日常任务不写 post-mortem**。Commit message 只写代码变更本身，不带 pipeline 元数据。

---

## 八、Pipeline ROI 判断

Pipeline review 的价值 = **catch rate × impact**。

- **高价值场景**：触碰核心引擎、并发、事务、schema migration、新协议 —— 单次运行就能防 3-8 个生产 bug，值
- **低价值场景**：单文件 bug 修复 / rename / docs / UI polish —— 跑 pipeline 是纯浪费

用红黄绿灯就是在显式选 ROI。SkillForge 强制 Full 是用户主动放弃了部分 ROI 换安全边际。

---

## 九、迁移到新项目（~2 分钟）

第一次在新项目用这套框架：

1. `cp skillforge/.claude/rules/pipeline.md <新项目>/.claude/rules/pipeline.md`
2. 改**第二部分「项目策略」**三处：
   - **强制级别**：是否"所有任务强制 Full"（SkillForge 策略）还是"按红黄灯判断"（默认）
   - **核心文件清单**（~5-10 个）—— 列出"如果这里写错整个系统关键功能会炸，浏览器/curl 测不出来"的文件
   - **Known footguns 清单** —— 踩过的坑，第一条通常在第一次踩坑后立刻加
3. 其余部分（一、三、四、五、六、七、八）直接复用，一般不需要改

**通用框架走本文件**，**项目特化数据走本文件的第二部分** —— 规则归规则，数据归数据。
