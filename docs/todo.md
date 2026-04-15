# SkillForge 待办任务

> 更新于：2026-04-15

---

## 待排期

| # | 任务 | 来源 | 优先级 |
|---|------|------|-------|
| 9 | 认证鉴权 MVP：JWT + Spring Security + 前端 Bearer token | 遗留 P0 | P0 |
| 10 | Context Window Token 按 Provider 动态配置（废弃硬编码 32000） | 遗留 P1 | P1 |
| 11 | Chat.tsx 重构：拆分 28 个 useState + 消除 TypeScript any | 遗留 P2 | P2 |
| 12 | 前端引入 TanStack Query 替换手动 useEffect 数据获取 | ECC web/patterns | P2 |
| 13 | 长列表虚拟滚动：Traces / Sessions / Teams 页面 | ECC performance | P2 |
| 14 | Dashboard 视觉升级：打破模板感，建立 SkillForge 视觉个性 | design-md + ECC | P2 |
| 15 | Chat 交互状态升级：hover / focus / active / loading 全部设计化 | ECC design-quality | P2 |
| 16 | 后端 Java 现代化：DTO 改用 records，大方法拆分到 <50 行 | ECC java/style | P2 |
| 17 | 后端集成测试：用 Testcontainers 覆盖核心 Service 层 | ECC java/testing | P2 |
| 18 | 前端安全加固：Zod schema 校验 + XSS/CSRF 防护审查 | ECC security | P2 |
| 19 | 前端测试基础建设：Vitest 单元测试 + Playwright E2E | ECC web/testing | P2 |
| 20 | API 响应格式统一 + 前端 Array.isArray 防御代码集中处理 | ECC common | P2 |

---

## 新方向（Self-Improve Pipeline）

| # | 任务 | 状态 |
|---|------|------|
| 5 | 设计 Scenario 格式 + 编写初始 eval 场景集（10-20 个） | ⬜ |
| 6 | 实现核心组件：ScenarioRunner Skill + EvalJudge Skill + collab run 模板 | ⬜ blocked by #5 |

---

## 已完成

| 任务 | 完成日期 |
|------|---------|
| Teams 页面（多 Agent 协作可观测 UI） | 2026-04-15 |
| ObjectMapper 全项目修复（10 处补 JavaTimeModule） | 2026-04-15 |
| @Transactional 修复（CollabRunService、SubAgentRunSweeper） | 2026-04-15 |
| H2 → PostgreSQL 迁移（embedded zonky PG，port 15432） | 2026-04-15 |
| EmbeddedPostgresConfig 重启修复（setCleanDataDirectory） | 2026-04-15 |
| Flyway schema 管理（ddl-auto: validate） | 2026-04-15 |
| .claude/ 规则/Agent/命令集成（ECC + design.md） | 2026-04-15 |
| #4 Commit 所有积压改动 | 2026-04-15 |
| #8 Chat 稳定性：ErrorBoundary + WS 断线重连（指数退避） | 2026-04-15 |
| #7 Memory 调度：session 完成后立即异步提取，补 @EnableAsync，加 /api/memories/refresh | 2026-04-15 |
