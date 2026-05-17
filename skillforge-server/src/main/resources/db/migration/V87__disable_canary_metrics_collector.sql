-- V87__disable_canary_metrics_collector.sql

-- 2026-05-16: FLYWHEEL-LOOP-CLOSURE dogfood 单用户阶段暂时砍掉 canary 路径，
-- metrics-collector cron 跑出来的 t_canary_metric_snapshot 没人用。disable cron
-- 防 LLM cost / DB 写浪费。t_canary_rollout / t_canary_metric_snapshot schema +
-- V2 CanaryRolloutService / CanaryMetricsService code 保留 dormant，未来加灰度
-- 时 UPDATE enabled=true 一行 reverse。

UPDATE t_scheduled_task SET enabled = false WHERE name = 'metrics-collector-hourly';
