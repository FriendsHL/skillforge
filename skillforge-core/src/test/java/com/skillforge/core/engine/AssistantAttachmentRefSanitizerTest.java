package com.skillforge.core.engine;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantAttachmentRefSanitizerTest {

    @Test
    void sanitize_assistantRefs_replacesAllFiveTypesWithoutMutatingHistory() {
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setContent(new ArrayList<>(List.of(
                ContentBlock.imageRef("i", "image/png", "chart.png"),
                ContentBlock.pdfRef("p", "report.pdf", 3),
                ContentBlock.wordRef("w", "notes.docx"),
                ContentBlock.excelRef("e", "data.xlsx", 2),
                ContentBlock.csvRef("c", "rows.csv"))));

        List<Message> sanitized = AssistantAttachmentRefSanitizer.sanitize(List.of(assistant));

        assertThat(sanitized).isNotSameAs(assistant);
        assertThat(sanitized.get(0).getTextContent()).isEqualTo(String.join("\n",
                "[Previously delivered image: chart.png]",
                "[Previously delivered PDF: report.pdf]",
                "[Previously delivered Word document: notes.docx]",
                "[Previously delivered Excel workbook: data.xlsx]",
                "[Previously delivered CSV: rows.csv]"));
        assertThat(((List<?>) assistant.getContent())).allMatch(ContentBlock.class::isInstance);
        assertThat(((ContentBlock) ((List<?>) assistant.getContent()).get(0)).getType()).isEqualTo("image_ref");
    }

    @Test
    void sanitize_userRefs_preservesMaterializableBlocksByIdentity() {
        Message user = new Message();
        user.setRole(Message.Role.USER);
        user.setContent(List.of(ContentBlock.imageRef("i", "image/png", "input.png")));

        List<Message> input = List.of(user);

        assertThat(AssistantAttachmentRefSanitizer.sanitize(input)).isSameAs(input);
    }
}
