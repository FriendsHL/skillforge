package com.skillforge.server.media;

import java.nio.file.Path;

public interface VideoGenerationProvider {
    String name();
    SubmittedVideo submit(VideoRequest request);
    VideoStatus get(String providerJobId);
    void cancel(String providerJobId);
    void download(String url, Path target);

    record VideoRequest(String prompt, int durationSeconds, String resolution,
                        String ratio, boolean generateAudio) { }
    record SubmittedVideo(String providerJobId, String requestId) { }
    record VideoStatus(String state, String resultUrl, String errorCode, String errorMessage) { }
}

