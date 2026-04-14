package com.skillforge.server.hook;

import com.skillforge.core.engine.SkillHook;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.service.ActivityLogService;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ActivityLogHook implements SkillHook {

    private final ActivityLogService activityLogService;

    public ActivityLogHook(ActivityLogService activityLogService) {
        this.activityLogService = activityLogService;
    }

    @Override
    public Map<String, Object> beforeSkillExecute(String skillName, Map<String, Object> input, SkillContext context) {
        return input;
    }

    @Override
    public void afterSkillExecute(String skillName, Map<String, Object> input, SkillResult result, SkillContext context) {
        if (context == null) return;
        // 只记录 input 的 key 列表（不记录 value，防止泄漏敏感信息）
        String inputSummary = input != null ? "keys=" + input.keySet() : "";
        // output 做截断摘要
        String outputSummary = "";
        if (result != null) {
            String raw = result.isSuccess() ? result.getOutput() : result.getError();
            if (raw != null) {
                outputSummary = raw.length() > 200 ? raw.substring(0, 200) + "..." : raw;
            }
        }
        activityLogService.log(
                context.getUserId(),
                context.getSessionId(),
                skillName,
                inputSummary,
                outputSummary,
                0, // duration 暂不可用，Phase 2 通过 SkillContext 携带 startTime 解决
                result != null && result.isSuccess()
        );
    }
}
