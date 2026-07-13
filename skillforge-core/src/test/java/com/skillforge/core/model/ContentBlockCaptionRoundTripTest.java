package com.skillforge.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContentBlockCaptionRoundTripTest {

    @Test
    void messageWithCaptionedRef_serializeDeserializeSerialize_isByteStable() throws Exception {
        ContentBlock ref = ContentBlock.pdfRef("att-1", "report.pdf", 5);
        ref.setMimeType("application/pdf");
        ref.setCaption("Analysis report");
        Message message = new Message();
        message.setRole(Message.Role.ASSISTANT);
        message.setContent(List.of(ref));
        ObjectMapper mapper = new ObjectMapper();

        String first = mapper.writeValueAsString(message);
        Message restored = mapper.readValue(first, Message.class);

        assertThat(mapper.writeValueAsString(restored)).isEqualTo(first);
        assertThat(first).contains("\"caption\":\"Analysis report\"");
    }
}
