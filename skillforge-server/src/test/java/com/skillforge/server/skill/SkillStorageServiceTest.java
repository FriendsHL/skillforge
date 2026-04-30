package com.skillforge.server.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillStorageServiceTest {

    private SkillStorageService storage;
    private Path runtimeRoot;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        SkillForgeHomeResolver resolver = new SkillForgeHomeResolver(tmp.toString());
        resolver.postConstruct();
        storage = new SkillStorageService(resolver);
        runtimeRoot = resolver.getRuntimeRoot();
    }

    @Test
    @DisplayName("UPLOAD path: upload/{ownerId}/{uuid}")
    void allocate_upload() {
        Path p = storage.allocate(SkillSource.UPLOAD, AllocationContext.forUpload("42", "u-1"));
        assertThat(p).isEqualTo(runtimeRoot.resolve("upload/42/u-1"));
        assertThat(p.startsWith(runtimeRoot)).isTrue();
    }

    @Test
    @DisplayName("SKILL_CREATOR path: skill-creator/{ownerId}/{uuid}")
    void allocate_skillCreator() {
        Path p = storage.allocate(SkillSource.SKILL_CREATOR,
                AllocationContext.forSkillCreator("7", "abc"));
        assertThat(p).isEqualTo(runtimeRoot.resolve("skill-creator/7/abc"));
    }

    @Test
    @DisplayName("DRAFT_APPROVE path: draft-approve/{ownerId}/{uuid}")
    void allocate_draftApprove() {
        Path p = storage.allocate(SkillSource.DRAFT_APPROVE,
                AllocationContext.forDraftApprove("1", "draft-id"));
        assertThat(p).isEqualTo(runtimeRoot.resolve("draft-approve/1/draft-id"));
    }

    @Test
    @DisplayName("EVOLUTION_FORK path: evolution-fork/{ownerId}/{parent}/{uuid}")
    void allocate_evolutionFork() {
        Path p = storage.allocate(SkillSource.EVOLUTION_FORK,
                AllocationContext.forEvolutionFork("1", "100", "200"));
        assertThat(p).isEqualTo(runtimeRoot.resolve("evolution-fork/1/100/200"));
    }

    @Test
    @DisplayName("SKILLHUB path: skillhub/{slug}/{version}")
    void allocate_skillhub() {
        Path p = storage.allocate(SkillSource.SKILLHUB,
                AllocationContext.forSkillhub("foo-bar", "1.2.3"));
        assertThat(p).isEqualTo(runtimeRoot.resolve("skillhub/foo-bar/1.2.3"));
    }

    @Test
    @DisplayName("CLAWHUB path: clawhub/{slug}/{version}")
    void allocate_clawhub() {
        Path p = storage.allocate(SkillSource.CLAWHUB,
                AllocationContext.forClawhub("foo", "v1"));
        assertThat(p).isEqualTo(runtimeRoot.resolve("clawhub/foo/v1"));
    }

    @Test
    @DisplayName("GITHUB path: github/{repoSlug}/{ref}")
    void allocate_github() {
        Path p = storage.allocate(SkillSource.GITHUB,
                AllocationContext.forGithub("owner-repo", "main"));
        assertThat(p).isEqualTo(runtimeRoot.resolve("github/owner-repo/main"));
    }

    @Test
    @DisplayName("FILESYSTEM path: filesystem/{ownerId}/{uuid}")
    void allocate_filesystem() {
        Path p = storage.allocate(SkillSource.FILESYSTEM,
                AllocationContext.forFilesystem("local", "f-1"));
        assertThat(p).isEqualTo(runtimeRoot.resolve("filesystem/local/f-1"));
    }

    @Test
    @DisplayName("path traversal in ownerId is rejected")
    void allocate_pathTraversalRejected() {
        assertThatThrownBy(() -> storage.allocate(SkillSource.UPLOAD,
                AllocationContext.forUpload("../etc", "uuid")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownerId");
    }

    @Test
    @DisplayName("separators in segments are rejected")
    void allocate_separatorRejected() {
        assertThatThrownBy(() -> storage.allocate(SkillSource.UPLOAD,
                AllocationContext.forUpload("1", "a/b")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uuid");
    }

    @Test
    @DisplayName("blank segments are rejected")
    void allocate_blankRejected() {
        assertThatThrownBy(() -> storage.allocate(SkillSource.UPLOAD,
                AllocationContext.forUpload("", "uuid")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownerId");
    }
}
