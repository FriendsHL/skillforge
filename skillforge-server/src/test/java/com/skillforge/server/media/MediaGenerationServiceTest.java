package com.skillforge.server.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.MediaGenerationJobEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MediaGenerationServiceTest {
    @Test
    void replaysPersistedJobWithoutSubmittingProviderAgain() {
        MediaJobStore store = mock(MediaJobStore.class);
        VideoGenerationProvider provider = mock(VideoGenerationProvider.class);
        when(provider.name()).thenReturn("ark");
        MediaGenerationJobEntity persisted = new MediaGenerationJobEntity();
        persisted.setId("job-1");
        persisted.setStatus("QUEUED");
        when(store.replay("session-1", "tool-1")).thenReturn(Optional.of(persisted));

        var service = new MediaGenerationService(store, List.of(provider), new ObjectMapper());
        var result = service.submitVideo(7L, "session-1", "tool-1",
                new VideoGenerationProvider.VideoRequest("cat", 5, "480p", "16:9", false));

        assertThat(result).isSameAs(persisted);
        verify(provider, never()).submit(any());
        verify(store, never()).createSubmitting(any(), any(), any(), any(), any(), any());
    }

    @Test
    void persistsProviderTaskBeforeReturningQueuedJob() {
        MediaJobStore store = mock(MediaJobStore.class);
        VideoGenerationProvider provider = mock(VideoGenerationProvider.class);
        when(provider.name()).thenReturn("ark");
        when(store.replay("session-1", "tool-1")).thenReturn(Optional.empty());
        MediaGenerationJobEntity submitting = new MediaGenerationJobEntity();
        submitting.setId("job-1");
        submitting.setStatus("SUBMITTING");
        when(store.createSubmitting(eq(7L), eq("session-1"), eq("tool-1"), eq("ark"), anyString(), anyString()))
                .thenReturn(submitting);
        var submitted = new VideoGenerationProvider.SubmittedVideo("provider-1", "request-1");
        when(provider.submit(any())).thenReturn(submitted);
        MediaGenerationJobEntity queued = new MediaGenerationJobEntity();
        queued.setId("job-1"); queued.setStatus("QUEUED");
        when(store.markQueued("job-1", submitted)).thenReturn(queued);

        var result = new MediaGenerationService(store, List.of(provider), new ObjectMapper())
                .submitVideo(7L, "session-1", "tool-1",
                        new VideoGenerationProvider.VideoRequest("cat", 5, "480p", "16:9", false));

        assertThat(result.getStatus()).isEqualTo("QUEUED");
        verify(store).markQueued("job-1", submitted);
    }
}
