package com.skillforge.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InteractiveArtifactContentBlockRoundTripTest {

    @Test
    void serializeDeserializeSerialize_preservesExactJsonShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ContentBlock block = ContentBlock.interactiveArtifactRef(
                "artifact-1", "budget.html", "July budget", 1);
        block.setMimeType("text/html");
        block.setCaption("Offline adjustable budget planner.");

        String first = mapper.writeValueAsString(block);
        ContentBlock decoded = mapper.readValue(first, ContentBlock.class);
        String second = mapper.writeValueAsString(decoded);

        assertThat(second).isEqualTo(first);
        assertThat(first).isEqualTo("{\"type\":\"interactive_artifact_ref\","
                + "\"filename\":\"budget.html\",\"caption\":\"Offline adjustable budget planner.\","
                + "\"title\":\"July budget\",\"attachment_id\":\"artifact-1\","
                + "\"mime_type\":\"text/html\",\"artifact_schema_version\":1}");
    }
}
