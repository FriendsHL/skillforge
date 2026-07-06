# Database Review Rules

Read this for Flyway migrations, JPA entity/schema changes, JPQL/native SQL,
repository query changes, and database performance work.

## Required Checks

- Use PostgreSQL-shaped thinking even when tests run on H2.
- Prefer `Instant` / `timestamptz` for new persisted time fields.
- Use lowercase snake_case identifiers and project table prefix `t_`.
- Add constraints for required invariants: `NOT NULL`, `CHECK`, FK, unique
  constraints, and bounded enum/status strings.
- Index columns used in WHERE, JOIN, ORDER BY, FK lookups, and queue polling.
- For composite indexes, equality columns come before range/order columns.
- Use partial indexes for soft-delete or status-filtered hot paths.
- Avoid `SELECT *` in production queries.
- Avoid unbounded list endpoints; add pagination or explicit limits.
- Keep transactions short and never hold DB locks while calling external APIs.
- Use parameter binding for SQL, JPQL, and native queries.

## Migration Rules

- Migrations must be idempotent where project history expects rerunnable DDL
  guards.
- Do not rewrite identity columns without understanding
  `identity-column-on-rewrite.md`.
- If adding `t_session_message` columns, classify them as identity, business, or
  audit/counter and implement rewrite preservation when needed.
- Verify Flyway applies in a real running server or with the repository's normal
  migration test path when practical.

## Query Review

- Run `EXPLAIN` / `EXPLAIN ANALYZE` for complex or hot queries when practical.
- Check for N+1 repository access in service loops.
- Prefer repository methods returning `Optional<T>` for single-row lookups.
- Mutating repository queries need `@Modifying` and a service-level transaction.

## Severity

- Blocker: data loss, unsafe migration, missing required constraint, SQL
  injection risk, transaction boundary bug, or rewrite identity wipe.
- Warning: likely table scan on growing data, weak index strategy, or H2-only
  assumptions.
