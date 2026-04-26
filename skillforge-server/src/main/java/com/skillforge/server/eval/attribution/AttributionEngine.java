package com.skillforge.server.eval.attribution;

import org.springframework.stereotype.Component;

/**
 * Implements the weighted matrix for failure attribution.
 */
@Component
public class AttributionEngine {

    // Columns: SKILL_MISSING, SKILL_EXECUTION_FAILURE, PROMPT_QUALITY,
    // CONTEXT_OVERFLOW, PERFORMANCE, MEMORY_INTERFERENCE, MEMORY_MISSING.
    private static final int[][] WEIGHT_MATRIX = {
            // s1: !oraclePass
            {1, 1, 2, 1, 0, 1, 1},
            // s2: skillExecFailed
            {1, 3, 0, 0, 0, 0, 0},
            // s3: skillMalformed
            {0, 2, 1, 0, 0, 0, 0},
            // s4: hitLoopLimit
            {0, 0, 1, 3, 1, 0, 0},
            // s5: nearPass
            {0, 0, 2, 0, 0, 0, 0},
            // s6: !outputFormat
            {0, 0, 2, 0, 0, 0, 0},
            // s7: slowExecution
            {0, 0, 0, 1, 3, 0, 0},
            // s8: memorySkillCalled
            {0, 0, 1, 0, 0, 3, 1},
            // s9: memoryResultEmpty
            {1, 0, 0, 0, 0, 0, 3}
    };

    // Maps column index to FailureAttribution enum (excluding NONE and VETO_EXCEPTION).
    private static final FailureAttribution[] COLUMN_MAP = {
            FailureAttribution.SKILL_MISSING,
            FailureAttribution.SKILL_EXECUTION_FAILURE,
            FailureAttribution.PROMPT_QUALITY,
            FailureAttribution.CONTEXT_OVERFLOW,
            FailureAttribution.PERFORMANCE,
            FailureAttribution.MEMORY_INTERFERENCE,
            FailureAttribution.MEMORY_MISSING
    };

    public FailureAttribution compute(EvalSignals signals) {
        if (signals.isEngineThrewException()) {
            return FailureAttribution.VETO_EXCEPTION;
        }

        boolean[] activeSignals = {
                !signals.isTaskCompletionOraclePass(),     // s1: !oraclePass
                signals.isSkillExecutionFailed(),           // s2
                signals.isSkillOutputWasMalformed(),        // s3
                signals.isHitLoopLimit(),                   // s4
                signals.isNearPassOracle(),                 // s5
                !signals.isOutputFormatCorrect(),           // s6: !outputFormat
                signals.isSlowExecution(),                  // s7
                signals.isMemorySkillCalled(),              // s8
                signals.isMemoryResultEmpty()               // s9
        };

        int[] columnSums = new int[COLUMN_MAP.length];
        for (int row = 0; row < activeSignals.length; row++) {
            if (activeSignals[row]) {
                for (int col = 0; col < COLUMN_MAP.length; col++) {
                    columnSums[col] += WEIGHT_MATRIX[row][col];
                }
            }
        }

        int maxSum = 0;
        int maxCol = -1;
        for (int col = 0; col < COLUMN_MAP.length; col++) {
            if (columnSums[col] > maxSum) {
                maxSum = columnSums[col];
                maxCol = col;
            }
        }

        if (maxSum == 0) {
            return FailureAttribution.NONE;
        }
        return COLUMN_MAP[maxCol];
    }
}
