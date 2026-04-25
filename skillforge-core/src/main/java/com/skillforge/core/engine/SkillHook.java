package com.skillforge.core.engine;

import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;

import java.util.Map;

/**
 * Tool 执行级别的 Hook，每次 Tool 调用前后触发。
 */
public interface SkillHook {

    /**
     * Tool 执行前调用，可做权限校验、参数修改。返回 null 表示拒绝执行。
     */
    default Map<String, Object> beforeSkillExecute(String skillName, Map<String, Object> input, SkillContext context) {
        return input;
    }

    /**
     * Tool 执行后调用，可做结果审计、日志。
     */
    default void afterSkillExecute(String skillName, Map<String, Object> input, SkillResult result, SkillContext context) {
    }
}
