package com.skillforge.server.service;

import com.skillforge.core.capability.ToolCatalog;
import com.skillforge.core.context.runtime.ContextRuntimeSnapshot;
import com.skillforge.core.context.runtime.ContextRuntimeSnapshotResolver;
import com.skillforge.core.skill.view.SessionSkillView;
import org.springframework.stereotype.Service;

/**
 * Pure checkpoint runtime semantics shared by compact creation, branch, restore, and resume.
 * Callers persist the returned closed JSON in the appropriate Session/checkpoint transaction.
 */
@Service
public class ContextRuntimeCheckpointService {

    private final ContextRuntimeSnapshotCodec codec;

    public ContextRuntimeCheckpointService(ContextRuntimeSnapshotCodec codec) {
        this.codec = codec;
    }

    /** Resume/restart reloads the current Session value, never an older checkpoint. */
    public String runtimeForResume(String currentSessionRuntimeJson) {
        return codec.encode(codec.decodeOrEmpty(currentSessionRuntimeJson));
    }

    /** Branch rebuilds the checkpoint value under the child Session's current authority. */
    public String runtimeForBranch(
            String checkpointRuntimeJson,
            ToolCatalog childAuthorizedTools,
            SessionSkillView childAuthorizedSkills) {
        return rebuild(checkpointRuntimeJson, childAuthorizedTools, childAuthorizedSkills);
    }

    /** Restore replaces current runtime with the checkpoint value under current authority. */
    public String runtimeForRestore(
            String checkpointRuntimeJson,
            ToolCatalog currentAuthorizedTools,
            SessionSkillView currentAuthorizedSkills) {
        return rebuild(checkpointRuntimeJson, currentAuthorizedTools, currentAuthorizedSkills);
    }

    private String rebuild(
            String checkpointRuntimeJson,
            ToolCatalog authorizedTools,
            SessionSkillView authorizedSkills) {
        ContextRuntimeSnapshot checkpoint = codec.decodeOrEmpty(checkpointRuntimeJson);
        ContextRuntimeSnapshot rebuilt = ContextRuntimeSnapshotResolver.resolve(
                checkpoint, authorizedTools, authorizedSkills);
        return codec.encode(rebuilt);
    }
}
