# SkillForge 项目全貌与待办优先级

> 生成日期：2026-04-15  
> 来源：后端分析 + 前端分析 + 后端质询（17条）+ 前端质询（10条）四方综合裁判报告

---

## 1. 项目现状总结

SkillForge 是一个**本地优先的 AI Agent 平台**，目标是提供完整的 Agent 生命周期管理、多 Agent 协作编排、技能市场和会话记忆能力。

**定位**：面向开发者的自托管 Agent 运行时，对标 Claude Code / Dify 但更注重多 Agent 协作可观测性。

**当前成熟度**：功能原型完整，核心引擎可运行，但距生产就绪仍有明显缺口。具体而言：

| 维度            | 状态                                                        |
| ------------- | --------------------------------------------------------- |
| 后端核心引擎        | ✅ 可用（AgentLoop、CompactionService、SubAgent、CollabRun 均有代码） |
| 前端 Dashboard  | ✅ 9 个页面基本可用，但稳定性和类型安全较差                                   |
| 认证鉴权          | ❌ 完全缺失，userId 由客户端 query param 传入                         |
| 持久化选型         | ⚠️ H2 内嵌库，不适合生产                                           |
| 测试覆盖          | ❌ 前端零测试文件，后端测试覆盖未知                                        |
| 记忆系统          | ⚠️ 实体和 CRUD 存在，但核心调度缺失（半完成）                               |
| 多 Provider 支持 | ⚠️ 接口层支持，但上下文窗口/Token 计数未适配                               |

---

## 2. 已完成功能清单（有代码支撑）

### 后端

- **Agent 管理**：CRUD 完整，支持 YAML 导入导出
- **Session 全生命周期**：创建、消息列表、删除，含 WebSocket 实时推送（15+ 事件类型）
- **AgentLoopEngine**：tool_use → ask_user → compact → text 完整循环，迭代上限可配置
- **三级压缩（Compaction）**：
  - B1/B2：token 水位触发
  - B3：12h idle gap 触发（注：调度入口待确认）
  - C1：手动触发
- **SubAgent 异步派发**：含 SubAgentRunSweeper（60s 扫描卡死 run）
- **CollabRun 多 Agent 协作**：TeamCreateSkill + AgentRoster + depth-aware 过滤
- **链路追踪**：TraceSpanEntity 完整记录，Traces API 可查
- **技能市场（ClawHub）**：5 层安全扫描（zip bomb/zip slip/文件类型/SKILL.md/prompt injection）
- **记忆系统 CRUD**：MemoryEntity 存取、SQL LIKE 搜索（调度触发为半完成）
- **Dashboard 统计**：/api/dashboard 聚合数据
- **Session Replay**：只读展示（非状态恢复重执行）
- **ModelUsage 统计**：Token 用量记录

### 前端

- **Agent 列表与编辑**：AgentList 页面，含新建/编辑/删除
- **流式聊天**：Chat.tsx，SSE 流式响应，工具调用展示
- **Ask-User 交互流程**：用户输入确认/取消
- **压缩标记显示**：聊天历史中展示压缩事件
- **多 Agent 协作视图**：Teams 页面，CollabRun 可观测
- **Token 用量图表**：ModelUsage 页面，ECharts 渲染
- **记忆搜索**：MemoryList 页面
- **Traces 可观测**：Traces 页面，AntD Table 展示
- **SessionList 带 WS 重连**：指数退避重连策略

---

## 3. 待完成功能清单（按优先级排序）

### P0 — 阻断生产的必修项

| #    | 问题                                     | 影响                                                            |
| ---- | -------------------------------------- | ------------------------------------------------------------- |
| P0-1 | **无认证鉴权层**                             | 任何人可通过修改 userId 参数访问/篡改所有用户数据                                 |
| P0-2 | **H2 → 生产数据库迁移**                       | H2 CLOB 膨胀存在 OOM 风险；H2 不支持并发写，不可用于多用户生产                       |
| P0-3 | **DDL auto=update → Flyway/Liquibase** | 迁移到 MySQL/PostgreSQL 后，添加 NOT NULL 列会静默失败，造成数据损坏              |
| P0-4 | **Chat WS 重连机制**                       | 断线后永久 spinner，对话功能失效，用户需刷新页面才能恢复                              |
| P0-5 | **前端全局 ErrorBoundary**                 | 无 ErrorBoundary + 零测试 + normalizeMessages 复杂逻辑 = 任何数据异常导致全页白屏 |

### P1 — 核心功能正确性

| #    | 问题                                       | 影响                                                                   |
| ---- | ---------------------------------------- | -------------------------------------------------------------------- |
| P1-1 | **SessionDigestExtractor 调度触发缺失**        | 记忆系统实为半完成：CRUD 可用，但 Session 内容从未自动提炼进 Memory，核心卖点静默失效                |
| P1-2 | **CompactionService 持锁调用 LLM**           | Full compact 期间阻塞同 stripe 所有 session（head-of-line blocking），高并发下雪崩风险 |
| P1-3 | **CollabRun WS 广播缺失**                    | collabMemberFinished 等事件只写 log 不广播，客户端无法感知协作进度                       |
| P1-4 | **defaultContextWindowTokens 硬编码 32000** | 切换 Claude（200K）或 GPT-4-128K 时，压缩策略基于错误窗口计算，影响所有 Provider             |
| P1-5 | **handleDelete 等静默失败**                   | 前端删除操作无 try-catch，失败时用户无任何提示，数据状态不一致                                 |
| P1-6 | **SubAgentPendingResultEntity 生命周期未验证**  | 可能存在孤儿记录积压，尚未分析清理逻辑                                                  |

### P2 — 质量与可维护性

| #    | 问题                                  | 影响                                                |
| ---- | ----------------------------------- | ------------------------------------------------- |
| P2-1 | **Chat.tsx 28 个 useState 状态爆炸**     | 可维护性极差，任何新功能都要在状态迷宫中导航，bug 率高                     |
| P2-2 | **TypeScript any 80-100+ 处**        | Chat.tsx 类型安全几乎为零，重构和 IDE 辅助均无效                   |
| P2-3 | **skillIds 存 JSON 字符串无外键**          | 无孤儿清理逻辑；查询"某 Skill 被哪些 Agent 使用"需全表 LIKE          |
| P2-4 | **Session 切换无 AbortController**     | 快速切换 session 时，旧请求响应覆盖新 session 状态（竞态条件）          |
| P2-5 | **API 响应格式不统一**                     | Array.isArray 判断在 5+ 处重复，前端防御代码散乱                 |
| P2-6 | **Teams 页面大数据量性能风险**                | 纯 DOM 时间轴渲染，无虚拟化，CollabRun 条目多时页面卡顿（比 Traces 更严重） |
| P2-7 | **前端零测试文件**                         | 核心逻辑（normalizeMessages、WS 事件处理）完全无测试保障            |
| P2-8 | **多 Provider Token 计数/SSE 格式差异未适配** | 切换 Claude/OpenAI 时 token 统计和流式格式差异可能引发静默错误        |

### P3 — 增强与完善

| #    | 问题                                     |
| ---- | -------------------------------------- |
| P3-1 | Skill 版本管理（目前无版本控制）                    |
| P3-2 | Memory 语义向量搜索（目前 SQL LIKE）             |
| P3-3 | CLI 补全（memory/traces/collab-runs 命令缺失） |
| P3-4 | Agent/Session 搜索过滤（前端无搜索框）             |
| P3-5 | TanStack Query（目前无请求缓存/去重）             |
| P3-6 | 批量操作 / 消息导出                            |
| P3-7 | SubAgentRunSweeper 误杀阈值调优（卡死判定标准未明确）   |

---

## 4. 技术风险预警（综合质询修正版）

> ⚠️ 以下风险等级已根据质询报告修正，部分条目较原始分析有所升级。

### 🔴 Critical（可能造成数据泄露或服务崩溃）

**C1. 无认证鉴权**

- 15+ WS 事件类型中携带 session 完整消息内容，任何用户可通过伪造 userId 订阅任意用户的实时消息流
- 所有 REST API 无 token 验证，数据隔离完全依赖客户端自律

**C2. H2 CLOB OOM**

- 所有消息存 CLOB，单 session 无消息数上限
- 长对话场景下 JVM heap 可被单个 session 耗尽
- H2 不支持行级 CLOB lazy loading

**C3. CompactionService 持锁 LLM 调用（Head-of-Line Blocking）**

- 64-slot stripe lock 设计下，Full compact 期间同 stripe 的所有 session 请求全部阻塞
- LLM 调用延迟 p99 通常 >5s，阻塞时间不可预测

### 🟠 High（核心功能受损或生产事故高风险）

**H1. DDL auto=update 生产风险**（原评级 Medium → 升级 High）

- 迁移到 MySQL/PostgreSQL 时，Spring Boot auto=update 对"添加 NOT NULL 列"的行为是静默跳过而非报错，导致数据库 schema 与 Entity 定义不一致，运行时报错难以追溯

**H2. defaultContextWindowTokens 硬编码 32000**（原评级 Low → 升级 High）

- 当前压缩触发阈值基于此值计算，切换 Claude（200K window）时压缩过于激进（频繁触发）；切换小模型时压缩不及时（OOM 风险）
- 影响所有 Provider 的行为正确性

**H3. Memory 系统半完成**（原评为"已完成" → 修正为 High 风险）

- SessionDigestExtractor 有代码、无调度 → Memory 从未被自动填充
- 用户以为记忆在工作，实际只有手动写入的记忆有效
- 如果 B3 压缩的调度入口也缺失，则三级压缩实为二级

**H4. Chat WS 无重连 + 无 ErrorBoundary（叠加效应）**

- WS 断线 → 永久 spinner（功能性失效）
- normalizeMessages 异常 → 无 ErrorBoundary 兜底 → 白屏
- 两者独立触发概率已高，同时发生时必然白屏，且无任何恢复机制

### 🟡 Medium

**M1. CollabRun WS 广播缺失**

- collabMemberFinished / collabRunCompleted 等事件只写 log，客户端 Teams 页面展示的协作状态不实时

**M2. SubAgentRunSweeper 误杀风险**

- 60s 超时判定标准未明确，慢速 LLM 调用（如 Opus 复杂任务）可能被误判为卡死而终止

**M3. Chat.tsx 28 useState + TypeScript any 80-100+**

- 无法通过类型检查发现绑定错误，任何重构都需人工追踪所有状态依赖

**M4. skillIds JSON 字符串**

- 删除 Skill 后，Agent 的 skillIds 不会自动清理，孤儿引用在运行时才会暴露

### 🟢 Low（已确认非严重）

- window.dispatchEvent 仅用于 4 种 collab 事件，影响范围可控（原评 High → 降级 Low）
- 模型列表为"建议选项"非硬编码，用户可自由输入，非 bug（原评 Medium → 降级 Low）
- ECharts 无 useMemo（仅渲染性能微优化）
- 魔法数字散落前端

---

## 5. 下一步建议：最应该先做的 3 件事

### 第一件事：修复 Memory 系统调度（1-2天，高 ROI）

**为什么排第一**：这是 SkillForge 相对其他 Agent 框架最有差异化价值的特性，但当前静默失效。修复成本低（加调度触发即可），但不修用户对整个记忆系统的信任都会建立在错误假设上。

**具体动作**：

1. 为 `SessionDigestExtractor` 添加 `@Scheduled` 触发器（或接入 B3 compaction 完成回调）
2. 同步确认 B3 idle gap 调度入口是否真实存在
3. 写一个手动触发的 `/api/memories/refresh` 端点用于开发期验证

### 第二件事：Chat 稳定性加固（2-3天，用户体验决定留存）

**为什么排第二**：Chat 是 SkillForge 最核心的用户交互界面，现在有三个独立的崩溃路径，任何一个触发都是完整功能丧失。

**具体动作**：

1. 给 Chat.tsx 添加 `<ErrorBoundary>` 包裹，崩溃时展示错误信息 + 重新加载按钮
2. 实现 Chat WS 重连（参考 SessionList 的指数退避实现复用逻辑）
3. 给 `handleDelete` 等所有变更操作加 try-catch + toast 错误提示

### 第三件事：认证鉴权 MVP（1-2周，生产准入门槛）

**为什么排第三而非第一**：当前是本地开发/演示阶段，鉴权不影响功能验证；但若有任何外部展示或小范围试用需求，必须先做。同时鉴权架构决定后续多用户、多 Agent 权限模型的设计，越早做越省重构成本。

**最小可行方案**：

1. JWT token 或 session cookie（任选其一）
2. Spring Security 拦截所有 `/api/**` 和 `/ws/**`
3. 前端注入 Bearer token，删除 `userId=1` 硬编码
4. 暂不做多用户 RBAC，先做单用户认证即可

---

## 附录：关键不确定项（需代码确认后才能定论）

以下结论在报告中存在歧义或分析缺口，建议做针对性代码核查：

| 项目                          | 当前状态               | 需要确认的问题                                |
| --------------------------- | ------------------ | -------------------------------------- |
| AgentLoopEngine 迭代上限        | 描述"25次迭代上限200"自相矛盾 | 实际代码中 maxIterations 和"上限 200"是两个不同变量吗？ |
| Session Replay              | 只读展示               | 是否有"重执行"的接口或前端入口？如有，逻辑是否完整             |
| SubAgentPendingResultEntity | 生命周期不明             | 何时创建、何时清理？是否有孤儿清理 job                  |
| ClawHub prompt injection 检测 | 规则存在               | 具体检测逻辑是关键词匹配还是模型判断？可绕过率如何              |
| 后端测试覆盖                      | 未分析                | 有无集成测试？核心 Engine 逻辑是否有 unit test 覆盖    |

---

*本报告由 4-agent 分析流水线（2个 analyzer + 2个 questioner + 1个 judge）综合生成，可作为 Sprint Planning 或技术评审的输入文档。*
