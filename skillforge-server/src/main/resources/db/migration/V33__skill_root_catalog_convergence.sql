-- P1-D: Skill Root 与 Catalog 收口
-- 新增 4 个治理字段，支持 reconciler 输出 RescanReport 与冲突裁决
ALTER TABLE t_skill ADD COLUMN content_hash VARCHAR(128);
ALTER TABLE t_skill ADD COLUMN last_scanned_at TIMESTAMP;
ALTER TABLE t_skill ADD COLUMN artifact_status VARCHAR(32) NOT NULL DEFAULT 'active';
ALTER TABLE t_skill ADD COLUMN shadowed_by VARCHAR(255);
