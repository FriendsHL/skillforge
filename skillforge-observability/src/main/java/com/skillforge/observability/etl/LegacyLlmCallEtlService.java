package com.skillforge.observability.etl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Plan §3.4 — Java-mode ETL service.
 *
 * <p>仅当 {@code skillforge.observability.etl.legacy.mode=java} 时启动批量异步抽取，
 * 通过 {@code t_llm_etl_progress} 断点续跑。{@code mode=off} / {@code mode=flyway} 时空操作。
 *
 * <p>当前是 stub —— 实际批量逻辑根据生产数据量再实装；MVP 默认 mode=off。
 */
@Service
public class LegacyLlmCallEtlService {

    private static final Logger log = LoggerFactory.getLogger(LegacyLlmCallEtlService.class);

    private final String mode;

    public LegacyLlmCallEtlService(
            @Value("${skillforge.observability.etl.legacy.mode:off}") String mode) {
        this.mode = mode;
    }

    public String getMode() { return mode; }

    /** Runs the next batch (no-op unless mode=java; production would implement here). */
    public void runBatch() {
        if (!"java".equals(mode)) {
            log.debug("LegacyLlmCallEtlService.runBatch skipped (mode={})", mode);
            return;
        }
        log.info("LegacyLlmCallEtlService.runBatch — Java-mode ETL stub");
        // Production implementation:
        // 1. Read t_llm_etl_progress for last batch_hi
        // 2. SELECT FROM t_trace_span WHERE id > last AND span_type IN ('AGENT_LOOP','LLM_CALL') LIMIT N
        // 3. Map rows via LegacyLlmCallMapper, INSERT INTO t_llm_trace + t_llm_span ON CONFLICT DO NOTHING
        // 4. UPDATE t_llm_etl_progress
    }
}
