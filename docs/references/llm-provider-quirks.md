# LLM Provider Quirks（OpenAI 兼容协议踩坑录）

> 记录 SkillForge 在接入第三方 OpenAI 兼容 provider 时遇到的协议 / 行为差异，以及当前的处理策略。
> 任何接入新 provider、修改 `OpenAiProvider` / `AgentLoopEngine` 时必读。
> 更新于：2026-04-28

---

## 1. 通用 OpenAI-compatible 协议族（SkillForge 视角）

| Provider | base-url | 示例模型 | 协议 family（`ProviderProtocolFamily`）|
| --- | --- | --- | --- |
| 阿里云 DashScope（百炼） | `https://coding.dashscope.aliyuncs.com` | qwen3.5-plus / qwen3.6-plus / glm-5 / kimi-k2.5 / MiniMax-M2.5 | `QWEN_DASHSCOPE` 或 fallback |
| DeepSeek 官方 | `https://api.deepseek.com` | deepseek-chat / deepseek-v4-pro | `DEEPSEEK_V4` / `DEEPSEEK_CHAT_LEGACY` |
| OpenAI o-series | `https://api.openai.com` | o1 / o3-mini | `OPENAI_REASONING` |

> 同一个 provider 实例可以宿主**多个 family**（典型：bailian 同时挂 qwen 系 + glm-5 + Kimi），所以 family 必须从 **请求时的 model 字段** 解析（`ProviderProtocolFamilyResolver.resolve(model)`），不能拿 provider name 当索引。

---

## 2. Qwen on DashScope — `reasoning_content` 默认开启泄漏

**触发版本**：2026-04-28 之前（修复 commit 待提）。
**触发条件**：bailian provider + `qwen3.x-plus`，请求体未显式带 `enable_thinking` 字段。
**症状**：

| 表现 | 现场证据 |
| --- | --- |
| Agent loop 1 个 iteration 就空退、`LlmResponse.content=""` | session 6c147569（thinking on）|
| Session smart title 渲染成 `Here's a thinking process:` | `doSmartRename: LLM returned, raw=Here's a thinking process:` 在日志里复现 6 次 |
| `OpenAiProvider.chatStream` `handler.onText(reasoning)` 把思考链当文本发出 | 见 `OpenAiProvider.java` reasoning_content delta 分支 |

**根因**：
1. DashScope 的 qwen 系列在 `enable_thinking` 字段缺失时**默认开启 thinking** —— 服务端 SSE 流大量返回 `reasoning_content` delta，`content` 可能整段为空。
2. `OpenAiProvider.chatStream` 把 `reasoning_content` delta 通过 `handler.onText()` 透传给上层（`SessionTitleService` / 前端），但**不**累加进 `fullText` —— 导致 `LlmResponse.content` 空，agent loop 当成"completed text response"直接退出；同时 title 等消费 `onText` 的 caller 把思考链当成正文。

**当前处理策略（P0 fix）**：
- `OpenAiProvider.buildRequestBody`：当 `mode == null || mode == AUTO` 且 family 是 `QWEN_ENABLE_THINKING` 时，**主动塞 `enable_thinking=false`**。Qwen 走非 thinking 路径，正文从 `delta.content` 出来，正常累加 fullText。
- 用户如果显式想用 qwen thinking，把 agent 的 `thinkingMode` 设成 `ENABLED`，原 toggle 路径继续生效。

**测试守卫**：`OpenAiProviderThinkingTest.qwen_auto_defaultsThinkingFalse` + `qwen_modeAuto_defaultsThinkingFalse`。

**残余风险**：
- `handler.onText(reasoning)` 的泄漏路径**还在**。当用户显式 `ENABLED` 时，思考链仍会被路由到 `onText` —— 历史遗留行为。后续 V2 应该把 reasoning 单独走 `onReasoning(text)` callback，让 caller 按需消费（**P1，待排期**）。

---

## 3. Qwen3.6-plus 工具调用合规弱（建议→不执行）

**触发版本**：2026-04-28（P0 修完之后才暴露）。
**触发条件**：qwen3.6-plus + 多步任务（如 WebFetch 撞反爬墙后需要 fallback 到 browser skill）。
**症状**：模型口头说"我去试 X 工具"，但**当轮就以纯文本结尾，不发出 tool_use**。

实例：session 6c403284 / 38539d47

```
seq 1  WebFetch(https://mp.weixin.qq.com/s/...)
seq 2  tool_result: "环境异常 ..."（微信反爬）
seq 3  assistant 文本: "微信公众号文章有访问验证，让我试试用浏览器工具来打开它。" ← 没下文
```

**判定**：**模型问题，不是框架问题**。同 prompt 在 claude / glm-5 下能正确发出第二个 tool_use。

**当前策略**：暂不框架兜底；记录在此供未来排查。如果 qwen 持续无法多步 dispatch，考虑：
- 在系统 prompt 末尾追加 "When you say 'I will use X', you MUST emit the tool_use call in the SAME response" 类的硬约束（成本：所有模型都吃 prompt 浪费）
- 或在 agent loop 检测"text-only response 且文本里有未兑现的工具承诺"时，自动补一轮 `[continue]` 推动（成本：增加轮次 + 检测启发式不可靠）

**短期 workaround**：用户切到强模型（claude / glm-5）；qwen 用于不需要多步 chain 的简单任务。

---

## 4. DeepSeek v4-pro — `tool_call_id` 严格唯一性

**触发版本**：commit `e9b48f3`（BUG-F，2026-04-26 修复）之前。
**触发条件**：cross-provider 切换（qwen3.5 用一段对话，再切 deepseek-v4-pro 续聊）/ full compaction 把 tool_result 边界切坏后续读。
**症状**：DeepSeek 返回 HTTP 400 `Duplicate value for 'tool_call_id' of in message[4]`，session 永久无法恢复（DB 里那条坏 message 永远在）。

**根因（两个 bug 叠加）**：
1. **Compact 摘要存储污染**：full compact 在 tool_result boundary 之后切割，`mergeSummaryIntoUser` 制造 mixed message（一个 user message 同时含 text + tool_result block），写进 `t_session_message`，reload 后永久重现。
2. **`OpenAiProvider.convertMessages` 翻译错误**：检测到 user message 含 tool_result block 时进入特殊分支，**遍历所有 block 不过滤类型**全部当 `role:tool` 翻译；text block 没有 `toolUseId` → 翻译出 `tool_call_id=null` / `"null"` 字面量；多条这种 message 累积 → DeepSeek 看到重复 `tool_call_id` → 400。

**Qwen 容忍 / DeepSeek 严格的差异**：Qwen 接收同样的非法 payload 时不报错；DeepSeek 严格校验。所以同一段对话历史在 qwen 下能跑、切到 deepseek 立刻 400 —— 这是跨 provider 切换 BUG 的一类典型表现。

**当前处理策略**：
- `convertMessages` 修复：tool_result 分支只翻译 type=tool_result 的 block，text block 仍走 user role（`OpenAiProvider.java` BUG-F fix）。
- 摘要存储重构：summary 不再 merge 进 user message，作为独立 user 消息单独存。
- 测试守卫：`FullCompactStrategyTest` + `OpenAiProviderConvertMessagesTest`（含 ContentBlock + Map 形态 + `tool_call_id:"null"` 体扫描）+ `CompactPersistenceIT` round-trip。

**残余风险**：
- 历史脏数据：BUG-F 修复前已写入 DB 的非法 message 不会自动清洗；老 session reload 后仍可能 400。建议出问题就开新 session。
- 跨 provider 切换：BUG-F 只解决了 SkillForge 自产污染，不防"用户先用 qwen 跑出畸形 tool_call_id 再切 deepseek"的情况。这条 todo 排在 thinking-mode V2（`docs/todo.md` `跨 Provider 切换历史消息兼容`）。

---

## 5. DeepSeek v4 / Qwen thinking — `reasoning_content` 必须原样回传

**触发版本**：commit `55969db`（thinking-mode v1）+ V22 migration（2026-04-24/25 落地）。
**触发条件**：`ThinkingMode.ENABLED` 模式下，assistant message 带 tool_use 时下一轮请求体里那条 assistant message 必须带 `reasoning_content` 字段（即使为空字符串）。
**症状**：DeepSeek HTTP 400 `An assistant message with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'`（错误文案具有迷惑性，根因其实是 reasoning_content 缺失）。

**当前处理策略**：
- 在内存链路上，`OpenAiProvider` SSE 累积 `fullReasoning` → `LlmResponse.reasoningContent` → `AgentLoopEngine.buildAssistantMessage` 写到 `Message.reasoningContent`。
- 持久化层 V22 migration 加 `t_session_message.reasoning_content TEXT NULL`，`SessionMessageEntity.reasoningContent` 字段；`appendRowsOnce` 单行写读 round-trip。
- 兜底策略：`resolveReplayReasoningContent` —— stored 空 + tool_calls 在场 + family.requiresReplay → emit `""`（DeepSeek 强约束、Qwen 也接受）；其它情况尽量保留原 reasoning。

**测试守卫**：`SessionServiceReasoningContentIT`（4 case：NORMAL with / without / mixed batch / SUMMARY round-trip）+ `OpenAiProviderThinkingTest` 的 family × thinkingMode × replay 矩阵。

---

## 6. SkillDefinition prompt-content 弱模型不识别

**触发版本**：常驻已知问题；2026-04-28 实测 P1 wrapper 改动**未通过验证已回滚**（agent 根本不 dispatch skill，包不包都没机会）。
**触发条件**：弱模型（qwen3.6-plus）调用 zip skill（B 类，agent-browser 等）。
**症状**：

- 在 14f8cd3c 这种**模型确实调了 skill** 的 case：拿到 SKILL.md 当成"动作结果"反复调同一 skill 试图得到不同结果，不发 Bash 调底层 CLI。
- 在 6c403284 这种**模型根本不调 skill** 的 case：模型口头说"我用 X" 然后不发 tool_use 直接 give up。

**当前处理策略**：暂无框架兜底。
- P1 框架包 framing 实验失败（包了 `[SKILL ACTIVATION GUIDE]` 标签也没用，弱模型不到那一步），已回滚。
- 短期 workaround：调 zip skill 多步任务用 claude / glm-5；qwen 系列在系统 prompt 里慎用 skill。

**未来方向**：
- 改 SkillDefinition.getPromptContent 写法本身（让 SKILL.md 第一行就是"Don't call this skill, run `npx ...` via Bash"），从 skill 资产侧让指令更显式。
- 考虑 ToolSchema 的 description 直接表达"this is a guide, not an action"，模型选 tool 之前就有上下文（成本：每个 zip skill 都要改）。

---

## 7. 一般规律 / 给 reviewer 的提示

| 提示 | 触发 |
| --- | --- |
| 改 `OpenAiProvider.buildRequestBody` / `convertMessages` 必须在 `OpenAiProviderThinkingTest` / `OpenAiProviderConvertMessagesTest` 加守护 | 这俩文件靠 reflection 锁请求 / 翻译矩阵，是 BUG-F / V22 之后唯一守护 protocol family 兼容性的回归网 |
| 新增 family 或新 dialect 时同步更新 `ProviderProtocolFamilyResolver`（longest-prefix-first + date-suffix strip + V4-reasoner guard）+ `ProviderProtocolFamily` enum capability flags | family 错判会导致 thinking toggle / reasoning_effort / replay 全部走错路径 |
| qwen 容忍的 payload 不代表合规，跨 provider 切到 deepseek 会暴露 | 写新的 message 翻译逻辑时不要"跑通了 qwen 就 ship" |
| reasoning_content 在持久化、replay、SSE 三个边界都要测，缺一会撞 400 | 任何 thinking-mode 相关 PR 必须跑 `SessionServiceReasoningContentIT` |
| 模型 alias 会被供应商**暗改**（qwen3.5-plus 同名指针指向更新的 snapshot） | 行为差异不一定是我们 regression；先用 curl 直打 API 复现，再判定责任归属 |
