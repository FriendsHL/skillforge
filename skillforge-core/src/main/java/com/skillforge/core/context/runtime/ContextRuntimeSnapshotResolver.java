package com.skillforge.core.context.runtime;

import com.skillforge.core.capability.ToolCatalog;
import com.skillforge.core.capability.ToolDescriptor;
import com.skillforge.core.context.PromptObservationHashes;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.view.SessionSkillView;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds a runtime checkpoint exclusively from the current authorized Tool and Skill views.
 * Snapshot references never grant access: missing IDs and changed hashes are dropped.
 */
public final class ContextRuntimeSnapshotResolver {

    private ContextRuntimeSnapshotResolver() {
    }

    public static ContextRuntimeSnapshot resolve(
            ContextRuntimeSnapshot snapshot,
            ToolCatalog authorizedTools,
            SessionSkillView authorizedSkills) {
        if (snapshot == null
                || snapshot.version() != ContextRuntimeSnapshot.CURRENT_VERSION
                || authorizedTools == null
                || authorizedSkills == null) {
            return ContextRuntimeSnapshot.empty();
        }

        Map<String, ToolDescriptor> currentTools = new LinkedHashMap<>();
        for (ToolDescriptor descriptor : authorizedTools.descriptors()) {
            if (descriptor != null && !descriptor.id().isBlank()) {
                currentTools.put(descriptor.id(), descriptor);
            }
        }
        Map<String, String> tools = new LinkedHashMap<>();
        snapshot.discoveredToolSchemaHashes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    ToolDescriptor current = currentTools.get(entry.getKey());
                    if (current != null && current.schemaHash().equals(entry.getValue())) {
                        tools.put(entry.getKey(), entry.getValue());
                    }
                });

        Map<String, SkillInvocationRef> latestSkills = new LinkedHashMap<>();
        for (SkillInvocationRef ref : snapshot.invokedSkills()) {
            if (!isCurrentAuthorizedSkill(ref, authorizedSkills)) continue;
            latestSkills.merge(
                    ref.skillId(),
                    ref,
                    (left, right) -> left.invocationSequence() >= right.invocationSequence()
                            ? left : right);
        }
        List<SkillInvocationRef> skills = latestSkills.values().stream()
                .sorted(Comparator.comparingLong(SkillInvocationRef::invocationSequence)
                        .thenComparing(SkillInvocationRef::skillId))
                .toList();
        return new ContextRuntimeSnapshot(
                ContextRuntimeSnapshot.CURRENT_VERSION, tools, skills);
    }

    private static boolean isCurrentAuthorizedSkill(
            SkillInvocationRef ref, SessionSkillView authorizedSkills) {
        if (ref == null || ref.skillId().isBlank() || ref.versionHash().isBlank()) {
            return false;
        }
        SkillDefinition current = authorizedSkills.resolve(ref.skillId()).orElse(null);
        if (current == null) return false;
        String content = current.getPromptContent() == null ? "" : current.getPromptContent();
        return PromptObservationHashes.sha256(content).equals(ref.versionHash());
    }
}
