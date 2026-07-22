package com.skillforge.server.tool;

import com.skillforge.core.skill.SkillContext;
import com.skillforge.server.entity.MediaGenerationJobEntity;
import com.skillforge.server.media.MediaGenerationService;
import com.skillforge.server.media.VideoGenerationProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateVideoToolTest {
    @Test void execute_validInput_publishesMediaJobRef() {
        MediaGenerationService service = mock(MediaGenerationService.class);
        MediaGenerationJobEntity job = new MediaGenerationJobEntity(); job.setId("job-1"); job.setStatus("QUEUED");
        when(service.submitVideo(any(), any(), any(), any())).thenReturn(job);
        SkillContext context = new SkillContext(); context.setUserId(7L); context.setSessionId("session-1"); context.setToolUseId("tool-1");
        var result = new GenerateVideoTool(service).execute(Map.of("prompt", "cat", "resolution", "480p"), context);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getArtifacts()).singleElement().satisfies(ref -> {
            assertThat(ref.getBlockType()).isEqualTo("media_job_ref"); assertThat(ref.getJobId()).isEqualTo("job-1");
        });
        verify(service).submitVideo(any(), any(), any(), any());
    }
    @Test void execute_invalidDuration_doesNotSubmit() {
        var result = new GenerateVideoTool(mock(MediaGenerationService.class)).execute(Map.of("prompt", "cat", "duration", 7), new SkillContext());
        assertThat(result.isSuccess()).isFalse();
    }
}
