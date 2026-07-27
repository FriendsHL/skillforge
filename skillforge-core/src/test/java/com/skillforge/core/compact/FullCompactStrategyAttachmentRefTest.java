package com.skillforge.core.compact;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FullCompactStrategyAttachmentRefTest {

    @Test
    void serializeWindow_assistantRefs_usesAllFivePreviouslyDeliveredPlaceholders() throws Exception {
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setContent(List.of(
                ContentBlock.imageRef("1", "image/png", "chart.png"),
                ContentBlock.pdfRef("2", "report.pdf", 4),
                ContentBlock.wordRef("3", "notes.docx"),
                ContentBlock.excelRef("4", "data.xlsx", 2),
                ContentBlock.csvRef("5", "rows.csv")));
        Method serializeWindow = FullCompactStrategy.class.getDeclaredMethod("serializeWindow", List.class);
        serializeWindow.setAccessible(true);

        String text = (String) serializeWindow.invoke(new FullCompactStrategy(), List.of(assistant));

        assertThat(text).contains(
                "[Previously delivered image: chart.png; attachment_id=1]",
                "[Previously delivered PDF: report.pdf; attachment_id=2]",
                "[Previously delivered Word document: notes.docx; attachment_id=3]",
                "[Previously delivered Excel workbook: data.xlsx; attachment_id=4]",
                "[Previously delivered CSV: rows.csv; attachment_id=5]");
    }
}
