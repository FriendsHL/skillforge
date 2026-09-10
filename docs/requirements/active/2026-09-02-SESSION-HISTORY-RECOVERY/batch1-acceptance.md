# Batch 1 验收记录

> 结果：`BATCH1_ACCEPTED`
> 日期：2026-09-02
> 责任方：root/Judge

## 独立复核

- Full reviewer Stage 1：`PASS`；
- Full reviewer Stage 2：`PASS_WITH_WARNINGS`；
- blocker：0；
- 候选状态：`BATCH1_ACCEPTED_CANDIDATE`。

root/Judge 已复核 reviewer 证据、冻结文档 hash、PostgreSQL 16 双角色执行结果、聚焦回归和未修改 Git HEAD 的
Docker 基线对照，接受候选状态并记录 `BATCH1_ACCEPTED`。

## 冻结输入保持不变

| 文档 | SHA-256 |
| --- | --- |
| `index.md` | `faccd0ef80f29f672fd3a2b1da80f3d537fd886cf4ca71ce2e71fc41b8be0ded` |
| `mrd.md` | `50838091d118e3c38e5809233613593b55a6e77b8af900a90790034604817cb9` |
| `prd.md` | `2061c3666302dbbee796d753d5d1d85a86af92ff2a003021b4881480eab8e711` |
| `tech-design.md` | `79454d1e53a9759526c95764fa553c00ef93c5258a89531dd6da1f2f6a27baf8` |
| `delivery-plan.md` | `8cf6eef2e53268a5d3d6ac16d418a993514358d219ef6c5ee8a77d5959129abb` |
| `research-report.md` | `c4c68be8379c30bb34048b634589e7879b339e0416eddf77ea67e95ae8f71bb6` |

## 验收范围

- 六项配置、默认关闭与 fail-closed availability policy；
- `HISTORY -> STORED_DATA` 低信任边界、全局恢复提示、Search/Read closed schema 和 raw-map validator；
- current-session scope，LLM schema 不暴露 `sessionId`/`userId`；
- V196–V198 clean/populated upgrade、JPA/DDL、inbox/attempt/audit/archive/message batch identity；
- Spring-managed `ObjectMapper` 的 `PersistedMessageCodec` byte-stable round trip；
- W-R6-1 immutable `SessionRunCoordinator` contract 和 reusable executable oracle；
- W-R6-2 migrator/runtime role 分离及 PostgreSQL append-only audit enforcement；
- whole-Session delete 作为 audit retention 的唯一 cascade 例外，并复用现有 Session-delete guard；
- 未创建 Tool grant migration，未提前接入 History registry、生产 dispatch、cursor 或 recovery attachment。

## 执行证据

- W-R6-1 contract：4/4 GREEN；
- History availability：3/3 GREEN；
- schema/validator/codec 聚焦组：29/29 GREEN；
- foundation/compact/migration/identity 聚焦组：83/83 GREEN；
- Zonky clean/populated migration：8/8 GREEN；
- PostgreSQL 16.14 双角色 fixture：3/3 GREEN、0 skip；
- PostgreSQL repository/rewrite：5/5 GREEN；
- Session 删除 guard：5/5 GREEN；
- PostgreSQL hard gate 合并执行：16/16 GREEN；
- `git diff --check`：通过；
- Testcontainers 残留：无。

Docker-enabled 全量 reactor 在当前实现上执行 3674 个 server tests，结果为 6 failures、27 errors、19 skipped。
为做因果归属，root 从未修改的 Git HEAD 导出临时基线，并在相同 Docker 29/API 1.44、Ryuk-disabled 条件下重跑：
3605 个 server tests 同样得到完全相同的 6 failures、27 errors、19 skipped，失败类和错误内容一致。故这些失败记录为仓库
既有 Docker integration-test isolation/fixture/configuration 债务，不归因于 Batch 1；新增的 69 个测试没有增加失败。

## 接受的 warning 与后续硬门

1. 现有 Session 删除授权使用 query `userId`，不是 server-authenticated principal/RBAC。Batch 1 只复用并刻画现有 guard；
   若产品要求不可伪造 owner 身份，必须单独返回需求设计，不在 History 包中暗改全局认证。
2. legacy rewrite 仅按 seq/index 保留 write-batch identity。开关关闭阶段接受该兼容路径；Batch 2/4 在启用 durable writer
   前必须改为精确 logical carrier，或对 shrink/reorder fail closed。
3. 本机 Docker Engine 29 需要 test-only `docker-java.properties` 固定 API 1.44；Flyway 9.22.3 对 PostgreSQL 16
   打印版本提示，但 V196–V198 已在 PostgreSQL 16.14 上实际迁移并完成权限验证。

## 放行结论

Batch 2 的 intent-before-execution、claim CAS 与 result close 可以开始。History master flag、checkpoint envelope、search
index、compact recovery 和 recovery eval 继续保持关闭；后续批次不得把 Batch 1 executable contract 当成生产实现已经完成。
