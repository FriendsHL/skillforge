package com.skillforge.server.tool;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.PublishedArtifact;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.MediaGenerationJobEntity;
import com.skillforge.server.media.MediaGenerationService;
import com.skillforge.server.media.VideoGenerationProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GenerateVideoTool implements Tool {
    public static final String NAME = "GenerateVideo";
    private static final Set<String> RESOLUTIONS = Set.of("480p", "720p", "1080p");
    private static final Set<String> RATIOS = Set.of("16:9", "9:16", "1:1", "4:3", "3:4", "21:9");
    private final MediaGenerationService service;
    public GenerateVideoTool(MediaGenerationService service) { this.service = service; }
    @Override public String getName() { return NAME; }
    @Override public String getDescription() { return "Submit an asynchronous text-to-video generation job and publish a live media job card."; }
    @Override public boolean isReadOnly() { return false; }
    @Override public ToolSchema getToolSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("prompt", Map.of("type", "string", "description", "Detailed video description."));
        p.put("duration", Map.of("type", "integer", "enum", List.of(5, 10), "description", "Duration in seconds."));
        p.put("resolution", Map.of("type", "string", "enum", List.of("480p", "720p", "1080p")));
        p.put("ratio", Map.of("type", "string", "enum", List.copyOf(RATIOS)));
        p.put("generate_audio", Map.of("type", "boolean"));
        return new ToolSchema(NAME, getDescription(), Map.of("type", "object", "properties", p, "required", List.of("prompt")));
    }
    @Override public SkillResult execute(Map<String, Object> input, SkillContext context) {
        if (input == null || !(input.get("prompt") instanceof String prompt) || prompt.isBlank()) return SkillResult.validationError("prompt is required");
        if (prompt.length() > 4000) return SkillResult.validationError("prompt exceeds 4000 characters");
        int duration = input.get("duration") instanceof Number n ? n.intValue() : 5;
        String resolution = input.get("resolution") instanceof String s ? s : "720p";
        String ratio = input.get("ratio") instanceof String s ? s : "16:9";
        boolean audio = input.get("generate_audio") instanceof Boolean b && b;
        if (duration != 5 && duration != 10) return SkillResult.validationError("duration must be 5 or 10");
        if (!RESOLUTIONS.contains(resolution)) return SkillResult.validationError("unsupported resolution");
        if (!RATIOS.contains(ratio)) return SkillResult.validationError("unsupported ratio");
        if (context == null || context.getSessionId() == null || context.getUserId() == null || context.getToolUseId() == null)
            return SkillResult.error("GenerateVideo requires an active chat session");
        try {
            MediaGenerationJobEntity job = service.submitVideo(context.getUserId(), context.getSessionId(), context.getToolUseId(),
                    new VideoGenerationProvider.VideoRequest(prompt, duration, resolution, ratio, audio));
            PublishedArtifact ref = new PublishedArtifact(); ref.setBlockType("media_job_ref"); ref.setJobId(job.getId()); ref.setMediaType("video");
            return SkillResult.success("Video job submitted: " + job.getId(), List.of(ref));
        } catch (IllegalStateException e) { return SkillResult.error("GenerateVideo: " + e.getMessage()); }
    }
}
