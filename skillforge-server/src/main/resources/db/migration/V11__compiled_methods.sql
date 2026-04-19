-- P4 Phase 2: Compiled methods — Code Agent generated Java classes registered as hook methods
CREATE TABLE t_compiled_method (
    id                      BIGSERIAL PRIMARY KEY,
    ref                     VARCHAR(128) NOT NULL UNIQUE,
    display_name            VARCHAR(256),
    description             TEXT,
    source_code             TEXT NOT NULL,
    compiled_class_bytes    BYTEA,
    status                  VARCHAR(16) NOT NULL DEFAULT 'pending_review',
    compile_error           TEXT,
    args_schema             TEXT,
    generated_by_session_id VARCHAR(36),
    generated_by_agent_id   BIGINT,
    reviewed_by_user_id     BIGINT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_compiled_method_status ON t_compiled_method(status);

COMMENT ON TABLE t_compiled_method IS
    'Dynamically registered Java hook methods, compiled and loaded into a custom ClassLoader. ref must start with "agent." (builtin.* reserved).';
COMMENT ON COLUMN t_compiled_method.status IS 'Lifecycle: pending_review → compiled → active / rejected';
COMMENT ON COLUMN t_compiled_method.compiled_class_bytes IS 'Raw .class bytes produced by javax.tools.JavaCompiler; loaded at approve time via GeneratedMethodClassLoader';
