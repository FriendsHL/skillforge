# SkillForge Bug 历史修复记录 - 2026-04-21

> 整理于：2026-04-29
> 来源：`docs/bugs.md` 重整前的“已修复”列表。

本文件保留 2026-04-21 修复完成的 bug 长记录。新 bug 修复后，优先把交付事实写入 `docs/delivery-index.md` 或对应需求包；历史参考文件按修复日期拆分为 `bug-history-yyyy-mm-dd.md`。

## 已修复

### BUG-13

| 字段 | 内容 |
| --- | --- |
| 标题 | Session 列表中的 tokens、context、cost 等字段没有数据 |
| 修复日期 | 2026-04-21 |
| 修复方式 | `SessionList.tsx` `normalizeSession` 将 `raw.totalTokens` 改为 `totalInputTokens + totalOutputTokens`，对齐后端实际字段名 |

### BUG-14

| 字段 | 内容 |
| --- | --- |
| 标题 | Evals 页面看不到评测数据集，失败原因与待优化点信息缺失 |
| 修复日期 | 2026-04-21 |
| 修复方式 | `Eval.tsx` EvalDrawer Cases 标签补充：run 级 errorMessage alert、scenario 级 errorMessage/attribution 列、improvementSuggestions 区块 |

### BUG-15

| 字段 | 内容 |
| --- | --- |
| 标题 | Evals 测试集中缺少"将 session 信息加入"入口按钮 |
| 修复日期 | 2026-04-21 |
| 修复方式 | `Eval.tsx` EvalDrawer 新增 Scenarios 标签，集成已有 `ScenarioDraftPanel` 组件（含"Extract from Sessions"入口） |

### BUG-16

| 字段 | 内容 |
| --- | --- |
| 标题 | 飞书聊天中仅展示"要做的事情"一句，未返回最终结果 |
| 修复日期 | 2026-04-21 |
| 修复方式 | `ChatWebSocketHandler.java` 将 channel 回复从 `assistantStreamEnd` 延迟到 `sessionStatus("idle")`；`ChannelTurnContext` 改为在每次 streamEnd 时快照 `finalText`，idle 时发送最后一次 LLM stream 的完整文本 |

### BUG-17

| 字段 | 内容 |
| --- | --- |
| 标题 | 飞书消息路由崩溃（HibernateAssertionFailure）导致用户发消息永远无回复 |
| 修复日期 | 2026-04-21 |
| 修复方式 | 根因：Hibernate ActionQueue 先 INSERT 后 UPDATE，partial unique index 未释放引发约束冲突，catch 块在同一中毒 session 里重试触发 HibernateAssertionFailure；改用 `@Modifying @Query closeById` 直接执行 JPQL UPDATE，绕过 ActionQueue 顺序；`ChannelSessionRouter` 在非事务上下文里重试 resolveSession |

### BUG-18

| 字段 | 内容 |
| --- | --- |
| 标题 | 飞书收到消息无任何即时反馈 |
| 修复日期 | 2026-04-21 |
| 修复方式 | 收到消息后调用飞书 reaction API 加 Typing 表情（`sendAck`），回复发出前自动移除（`removeAck`）；通过 `ChannelSessionOutputEvent.ackReactionId` 跨异步边界传递 reaction ID |

### BUG-19

| 字段 | 内容 |
| --- | --- |
| 标题 | Chat 右栏 CONTEXT tab 名称被截断（只能看到 "CONTE…"） |
| 修复日期 | 2026-04-21 |
| 修复方式 | `index.css` `.chat-redesign .rail-tabs` padding 10→6、`.rail-tab` padding 12→8、字号 12→11、letter-spacing 0.08→0.04，让 4 个 tab 都能完整显示 |

### BUG-20

| 字段 | 内容 |
| --- | --- |
| 标题 | Chat 顶部 crumb 里 `depth 0` 对所有顶层 session 冗余显示 |
| 修复日期 | 2026-04-21 |
| 修复方式 | `Chat.tsx` 只在 `sessionDepth > 0` 时渲染；同时把 "depth" 换成 channel badge 指示会话来源渠道（web / 飞书 / telegram 等） |

### BUG-21

| 字段 | 内容 |
| --- | --- |
| 标题 | Chat 右栏 Context budget 卡片常年空白 |
| 修复日期 | 2026-04-21 |
| 修复方式 | `Chat.tsx` tokenUsage 读的是不存在的 `totalTokens` 字段；改为 `totalInputTokens + totalOutputTokens`，同时附带 input/output 明细 |

### BUG-22

| 字段 | 内容 |
| --- | --- |
| 标题 | Session 详情页 Context tab 显示 391K/200K（约 195% 被 clamp 到 100%） |
| 修复日期 | 2026-04-21 |
| 修复方式 | 原 panel 把“累计 LLM 调用 token”当“当前窗口占用”显示，并硬编码 200K 分母。改为调用新的 `/api/chat/sessions/{id}/context-breakdown`：分子为真实当前 context 占用，分母为 agent 实际模型窗口，lifetime 累计另起一行不再混淆 |

### BUG-23

| 字段 | 内容 |
| --- | --- |
| 标题 | Session 详情 drawer 的 title / tabs / body 三行左缘未对齐 |
| 修复日期 | 2026-04-21 |
| 修复方式 | `skills.css` `.sf-drawer-tabs` padding-left 20→12，配合每个 tab 自身的 12px 内边距刚好落在 24px，与 `.sf-drawer-head` / `.sf-drawer-body` 对齐 |

### BUG-24

| 字段 | 内容 |
| --- | --- |
| 标题 | Sessions 列表页 TOKENS / CONTEXT / COST / LAST 列和 header 错位 |
| 修复日期 | 2026-04-21 |
| 修复方式 | `.sess-row` 声明 `width: 100%` 但 box-sizing 仍是 content-box，`padding: 12px 16px` 让 row 实际渲染比 container 宽 32px；统一 `.sess-row` + `.sess-table-h` 为 `box-sizing: border-box` + `width: 100%`，fr 轨道一致，所有 cell `left` 对齐 |
