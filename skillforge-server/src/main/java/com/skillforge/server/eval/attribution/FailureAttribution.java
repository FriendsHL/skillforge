package com.skillforge.server.eval.attribution;

public enum FailureAttribution {
    NONE,
    SKILL_MISSING,
    SKILL_EXECUTION_FAILURE,
    PROMPT_QUALITY,
    CONTEXT_OVERFLOW,
    PERFORMANCE,
    VETO_EXCEPTION
}
