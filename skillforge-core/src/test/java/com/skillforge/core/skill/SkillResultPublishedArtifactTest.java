package com.skillforge.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillResultPublishedArtifactTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serialization_successWithArtifact_roundTripsTypedMetadata() throws Exception {
        SkillResult original = SkillResult.success("published", List.of(
                new PublishedArtifact("att-1", "pdf_ref", "report.pdf",
                        "application/pdf", 8, null, "Analysis report")));

        String json = objectMapper.writeValueAsString(original);
        SkillResult restored = objectMapper.readValue(json, SkillResult.class);

        assertThat(restored.getArtifacts()).containsExactly(
                new PublishedArtifact("att-1", "pdf_ref", "report.pdf",
                        "application/pdf", 8, null, "Analysis report"));
        assertThat(objectMapper.writeValueAsString(restored)).isEqualTo(json);
    }

    @Test
    void errorResult_hasNoArtifacts() {
        assertThat(SkillResult.error("failed").getArtifacts()).isEmpty();
        assertThat(SkillResult.validationError("invalid").getArtifacts()).isEmpty();
    }
}
