# SkillForge 待办任务

> 更新于：2026-04-15

---

## 待排期

| # | 任务 | 来源 | 优先级 |
|---|------|------|-------|
| P1-3 | CollabRun WS 广播缺失：`collabMemberFinished` 等事件只写 log 不广播，Teams 页面协作状态不实时 | P1 | P1 |
| #5 | Self-Improve Pipeline：设计 Scenario 格式 + 编写初始 eval 场景集（10-20 个） | 新方向 | P2 |
| #6 | Self-Improve Pipeline：ScenarioRunner Skill + EvalJudge Skill + collab run 模板（blocked by #5） | 新方向 | P2 |

---

## 已完成

| 任务 | 完成日期 |
|------|---------|
| P1-2 CompactionService 解锁 LLM 调用：3-phase split — Phase 1 guard/prepareCompact (stripe lock) → Phase 2 applyPrepared (LLM, no lock) → Phase 3 persist (stripe lock + tx)；fullCompactInFlight Set 防并发重入 | 2026-04-15 |
| P1-1 动态 Context Window：ModelConfig 静态模型表 + CompactionService 3 级解析链，废弃硬编码 32000 | 2026-04-15 |
| #9 认证鉴权 MVP：auto-token on startup，Login 页自动预填，Bearer 拦截器 + WS 握手鉴权 | 2026-04-15 |
| #20 API 响应格式统一 + extractList 防御代码集中 | 2026-04-15 |
| #19 前端测试基础建设：Vitest 单元测试 + Playwright E2E | 2026-04-15 |
| #18 前端安全加固：Zod schema 校验 + XSS/CSRF 防护 | 2026-04-15 |
| #17 后端集成测试：Testcontainers 覆盖核心 Repository | 2026-04-15 |
| #16 后端 Java 现代化：DTO 改用 records | 2026-04-15 |
| #15 Chat 交互状态升级：hover / focus / active / loading | 2026-04-15 |
| #14 Dashboard 视觉升级 | 2026-04-15 |
| #13 长列表虚拟滚动：Traces / Sessions / Teams | 2026-04-15 |
| #12 前端引入 TanStack Query 替换手动 useEffect | 2026-04-15 |
| #11 Chat.tsx 重构：拆分 28 个 useState | 2026-04-15 |
| #10 Context Window Token 按 Provider 动态配置 → 合并入 P1-1 | 2026-04-15 |
| Teams 页面（多 Agent 协作可观测 UI） | 2026-04-15 |
| ObjectMapper 全项目修复（10 处补 JavaTimeModule） | 2026-04-15 |
| @Transactional 修复（CollabRunService、SubAgentRunSweeper） | 2026-04-15 |
| H2 → PostgreSQL 迁移（embedded zonky PG，port 15432） | 2026-04-15 |
| EmbeddedPostgresConfig 重启修复（setCleanDataDirectory） | 2026-04-15 |
| Flyway schema 管理（ddl-auto: validate） | 2026-04-15 |
| .claude/ 规则/Agent/命令集成（ECC + design.md） | 2026-04-15 |
| #4 Commit 所有积压改动 | 2026-04-15 |
| #8 Chat 稳定性：ErrorBoundary + WS 断线重连（指数退避） | 2026-04-15 |
| #7 Memory 调度：session 完成后立即异步提取，补 @EnableAsync | 2026-04-15 |
