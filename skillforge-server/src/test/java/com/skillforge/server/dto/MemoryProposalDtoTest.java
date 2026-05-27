package com.skillforge.server.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.entity.MemoryProposalEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * r2 fix R2-2 regression coverage: {@link MemoryProposalDto#sourceMemoryIds()} must be
 * a parsed {@code List<Long>}, not the entity's raw jsonb string. Before the fix Jackson
 * serialized the string as-is and FE TypeScript consumed it as text, which made the
 * B-3 mass-delete confirmation modal fire on every dedup with a misleading row count
 * (counting characters instead of memory ids).
 */
@DisplayName("MemoryProposalDto")
class MemoryProposalDtoTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static MemoryProposalEntity proposal(String sourceIdsJson) {
        MemoryProposalEntity p = new MemoryProposalEntity();
        p.setId(1L);
        p.setUserId(7L);
        p.setSynthesisRunId("r1");
        p.setProposalType(MemoryProposalEntity.TYPE_DEDUP);
        p.setSourceMemoryIds(sourceIdsJson);
        p.setWinnerMemoryId(101L);
        p.setStatus(MemoryProposalEntity.STATUS_PROPOSED);
        p.setCreatedAt(Instant.now());
        return p;
    }

    @Test
    @DisplayName("R2-2: from() parses jsonb sourceMemoryIds string into List<Long>")
    void from_jsonbSourceIds_parsedToLongList() {
        MemoryProposalEntity entity = proposal("[101,102,103]");

        MemoryProposalDto dto = MemoryProposalDto.from(entity, null, mapper);

        assertThat(dto.sourceMemoryIds())
                .isInstanceOf(List.class)
                .containsExactly(101L, 102L, 103L)
                .hasSize(3);
    }

    @Test
    @DisplayName("from() returns empty list for null sourceMemoryIds")
    void from_nullJson_returnsEmpty() {
        MemoryProposalEntity entity = proposal(null);
        MemoryProposalDto dto = MemoryProposalDto.from(entity, null, mapper);
        assertThat(dto.sourceMemoryIds()).isEmpty();
    }

    @Test
    @DisplayName("from() returns empty list for malformed json (does not throw)")
    void from_malformedJson_returnsEmpty() {
        MemoryProposalEntity entity = proposal("[not, json");
        MemoryProposalDto dto = MemoryProposalDto.from(entity, null, mapper);
        assertThat(dto.sourceMemoryIds()).isEmpty();
    }

    @Test
    void from_parsesEvidenceJson() {
        MemoryProposalEntity entity = new MemoryProposalEntity();
        entity.setId(1L);
        entity.setUserId(42L);
        entity.setSynthesisRunId("dream-abc");
        entity.setProposalType("reflection");
        entity.setSourceMemoryIds("[]");
        entity.setReasoning("observed");
        entity.setEvidenceJson("[{\"source\":\"session\",\"sessionId\":\"sess-1\",\"quote\":\"plan first\"}]");
        entity.setStatus(MemoryProposalEntity.STATUS_PROPOSED);

        MemoryProposalDto dto = MemoryProposalDto.from(entity, new ObjectMapper());

        assertThat(dto.evidence()).isNotNull();
        assertThat(dto.evidence().get(0).path("sessionId").asText()).isEqualTo("sess-1");
    }

    @Test
    @DisplayName("from() preserves all other fields verbatim")
    void from_otherFields_passthrough() {
        MemoryProposalEntity entity = proposal("[5,6]");
        entity.setSuggestedTitle("title");
        entity.setSuggestedContent("content");
        entity.setReasoning("reason");

        MemoryProposalDto dto = MemoryProposalDto.from(entity, null, mapper);

        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.userId()).isEqualTo(7L);
        assertThat(dto.proposalType()).isEqualTo("dedup");
        assertThat(dto.winnerMemoryId()).isEqualTo(101L);
        assertThat(dto.suggestedTitle()).isEqualTo("title");
        assertThat(dto.suggestedContent()).isEqualTo("content");
        assertThat(dto.reasoning()).isEqualTo("reason");
        assertThat(dto.sourceMemoryIds()).containsExactly(5L, 6L);
    }

    @Test
    @DisplayName("backward-compat 1-arg from(entity) still parses sourceMemoryIds correctly")
    void from_backwardCompat_singleArg_parses() {
        MemoryProposalEntity entity = proposal("[42]");
        MemoryProposalDto dto = MemoryProposalDto.from(entity);
        assertThat(dto.sourceMemoryIds()).containsExactly(42L);
    }
}
