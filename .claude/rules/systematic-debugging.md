# Systematic Debugging

> 来源：[superpowers/skills/systematic-debugging](https://github.com/obra/superpowers)。SkillForge 适配：补 [`think-before-coding.md`](think-before-coding.md) "困惑就停"的方法论缺位 —— Agent Loop / SSE streaming / JPA 跨层 bug 是 SkillForge 高频场景，配 `performance-optimizer` agent 用。

## The Iron Law

```
NO FIXES WITHOUT ROOT CAUSE INVESTIGATION FIRST
```

完成 Phase 1 之前不准提 fix 方案。

## 何时强制用本规则

任何技术问题：测试失败 / 生产 bug / 行为异常 / 性能问题 / build 失败 / 集成问题。

**特别需要时**：
- 时间压力下（emergencies 让人想猜，越猜越乱）
- "就一个简单 fix" 看起来很显然
- 已经试过多次 fix
- 上次 fix 没用
- 你没完全理解问题

**不要跳过的诱惑**：
- "问题很简单不需要流程" → 简单问题也有根因，流程跑得快
- "急着要 fix" → 系统化比 guess-and-check 快
- "manager 现在就要" → thrashing 比慢一点更慢

## 四阶段（必须按顺序）

### Phase 1：Root Cause Investigation

1. **完整读错误信息**：stack trace、行号、error code 全读完，不要只看第一行
2. **稳定重现**：能稳定触发吗？步骤是什么？不稳定 → 先收数据，**不要猜**
3. **检查近期改动**：`git log -p` / 最近 commit / 新依赖 / 配置改动 / 环境差异
4. **多组件系统取证**（**SkillForge 高频**）：当 bug 跨层（HTTP → Service → LLM provider → SSE → JPA → DB），先在每个 boundary 加证据，跑一次看哪一层断的，**再去查那一层**

   SkillForge 5 层取证模板：

   ```bash
   # Layer 1：HTTP 请求进来
   curl -v http://localhost:8080/api/sessions/<id>/messages
   # Layer 2：Service 层
   grep "ChatService" logs/skillforge-server.log | tail -20
   # Layer 3：LLM provider
   grep "ClaudeProvider\|chatStream" logs/skillforge-server.log | tail -20
   # Layer 4：SSE delta 推送
   grep "delta event\|tool_use\|tool_result" logs/ | tail -30
   # Layer 5：JPA 落库
   psql -h localhost -p 15432 -c "SELECT id, role, content_type FROM session_messages WHERE session_id='...' ORDER BY created_at DESC LIMIT 10"
   ```

   **结果**：哪一层断（HTTP ✓ / Service ✓ / LLM ✗）→ 查那一层

5. **追数据流**：bug 表现在 X，但坏值哪来的？反向追到源头修，**不要在 X 点修**

### Phase 2：Pattern Analysis

- 找代码库里**类似但工作正常**的代码（"哪个 Service 也用了 @Transactional 但没 AOP 陷阱"）
- 列出**所有差异**（再小都列，不要假设"这个差异不重要"）
- 完整读参考实现（不是 skim），理解模式再套

### Phase 3：Hypothesis & Test

- 一个**清晰假设**："我认为 X 是根因因为 Y"（写下来，不要模糊）
- **最小 change** 测试（一次只改一个变量）
- 验证：work 了 → Phase 4；没 work → **重新做假设**，不要叠改动

### Phase 4：Implementation

1. **先写一个失败 test case**（reproduce 当前 bug 的 regression test）
2. **单一 fix**（不要顺手 cleanup / 重构 —— 见 [`think-before-coding.md`](think-before-coding.md) 第 5 条）
3. **验证 fix**（test 过 + 没破坏其他 test + 实际症状消失，遵守 [`verification-before-completion.md`](verification-before-completion.md)）
4. **如果 fix 不工作**：
   - 数一下"我试了几次"
   - <3 次 → 回 Phase 1 重新分析
   - **≥3 次 → 停下来，质疑架构**（见下条）

### 第 5 阶段（隐藏阶段）：3-Fix Architecture Rule

**3 次 fix 都失败 → 不是 fix 问题，是架构问题**。

征兆：
- 每次 fix 都暴露另一个地方的耦合 / 共享状态
- 每个 fix 都需要"大重构"才能落
- 每个 fix 都引发新症状

**停下来，跟用户讨论是不是模式选错了**。这不是失败的假设，是错的架构。

## Red Flags（看到停下回 Phase 1）

- "先快速修一下，回头再看根因"
- "试试 X 看看会不会好"
- "一次改多处，跑测试看"
- "应该是 X 吧，我修一下"
- "我没完全懂但这样可能行"
- 跳过 reproduce 直接提 fix
- "Pattern 是 X 但我改一改适配一下"
- **"再试一次"**（已经 ≥2 次失败了）
- 每个 fix 都暴露不同位置的新问题

## SkillForge 高频场景手册

| 症状 | 不要先做什么 | Phase 1 怎么走 |
|---|---|---|
| **JPA 不变量违反** （tool_use ↔ tool_result 配对断） | 直接改 ChatService 试 | 用 5 层取证模板，看哪一层 message 行没写 / 写错 type；可能在 SSE 中断 |
| **SSE delta 重复 / 丢失** | 加 retry / 加 dedup | 在 LlmProvider 出口 + handler 入口分别加日志，看是 provider 重发还是 handler 重处理 |
| **流式渲染卡顿** | 加 throttle / debounce | 反向追：DOM update → React render → setState → message 收到时机；可能是 useEffect cleanup 漏了 |
| **`@Transactional` 不生效** | 改 propagation / isolation | Phase 2 找类似工作的 Service public 方法，列差异（90% 是 self-invocation 或 private 方法 —— [`java.md`](java.md)） |
| **测试过 / 生产挂** | 加 try-catch | Phase 1 第 4 条：测试 vs 生产环境差异（embedded H2 vs PostgreSQL，Mock vs 真 LLM） |
| **多 LLM provider 兼容崩** | 抽象层加 if-else | Phase 2 找其他 provider 的工作实现，列 SSE 协议差异 |

## 与现有规则关系

- 与 [`think-before-coding.md`](think-before-coding.md) "困惑就停"互补：那条管"该停还是该问"，本条管"停下来后系统化怎么调"
- 与 [`verification-before-completion.md`](verification-before-completion.md) 互补：Phase 4 step 3 验证 fix = Iron Law 的具体应用
- 与 `performance-optimizer` agent 互补：性能问题先按本规则 Phase 1 取证，再交给 agent 分析

## Real-World Impact

- 系统化方法：15-30 分钟修完
- 随机 fix 方法：2-3 小时 thrashing
- 一次修对率：95% vs 40%
- 引入新 bug：近 0 vs 常发生
