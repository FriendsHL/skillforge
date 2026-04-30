package com.skillforge.server.skill;

/**
 * P1-D — Inputs for {@link SkillStorageService#allocate(SkillSource, AllocationContext)}.
 *
 * <p>Different {@link SkillSource} values use different subsets of these fields.
 * Use the static factory methods rather than the canonical constructor — they
 * encode which fields are required for each source and leave the others null.
 *
 * <p>All non-null inputs are validated for path traversal in
 * {@link SkillStorageService}; callers don't need to pre-sanitize.
 */
public record AllocationContext(
        String ownerId,
        String uuid,
        String parentSkillId,
        String slug,
        String version,
        String repoSlug,
        String ref) {

    public static AllocationContext forUpload(String ownerId, String uuid) {
        return new AllocationContext(ownerId, uuid, null, null, null, null, null);
    }

    public static AllocationContext forSkillCreator(String ownerId, String uuid) {
        return new AllocationContext(ownerId, uuid, null, null, null, null, null);
    }

    public static AllocationContext forDraftApprove(String ownerId, String uuid) {
        return new AllocationContext(ownerId, uuid, null, null, null, null, null);
    }

    public static AllocationContext forEvolutionFork(String ownerId,
                                                     String parentSkillId,
                                                     String uuid) {
        return new AllocationContext(ownerId, uuid, parentSkillId, null, null, null, null);
    }

    public static AllocationContext forSkillhub(String slug, String version) {
        return new AllocationContext(null, null, null, slug, version, null, null);
    }

    public static AllocationContext forClawhub(String slug, String version) {
        return new AllocationContext(null, null, null, slug, version, null, null);
    }

    public static AllocationContext forGithub(String repoSlug, String ref) {
        return new AllocationContext(null, null, null, null, null, repoSlug, ref);
    }

    public static AllocationContext forFilesystem(String ownerId, String uuid) {
        return new AllocationContext(ownerId, uuid, null, null, null, null, null);
    }
}
