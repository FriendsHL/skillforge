package com.skillforge.server.service;

import com.skillforge.server.repository.ChatAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the filter normalization + hard-cap clamp + JPQL pass-through that moved
 * out of {@code ChatController.listAttachments} into
 * {@link ChatAttachmentService#listAttachmentsByFilters} during the layering refactor.
 */
@ExtendWith(MockitoExtension.class)
class ChatAttachmentServiceListByFiltersTest {

    @Mock private ChatAttachmentRepository attachmentRepository;

    private ChatAttachmentService service;

    @BeforeEach
    void setUp() {
        service = new ChatAttachmentService(attachmentRepository, "./data/chat-attachments");
    }

    @Test
    @DisplayName("filters pass through unchanged; limit becomes a 0-based PageRequest")
    void filtersPassedThrough() {
        when(attachmentRepository.findByFilters(
                eq("PDF_PARSE_FAILED"), eq("PDF_TEXT_EMPTY"), eq("sess-x"), any(Pageable.class)))
                .thenReturn(List.of());

        service.listAttachmentsByFilters("PDF_PARSE_FAILED", "PDF_TEXT_EMPTY", "sess-x", 50);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(attachmentRepository).findByFilters(
                eq("PDF_PARSE_FAILED"), eq("PDF_TEXT_EMPTY"), eq("sess-x"), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(0);
    }

    @Test
    @DisplayName("blank filter strings normalize to null (treat as 'not provided')")
    void blankFiltersNormalizedToNull() {
        when(attachmentRepository.findByFilters(isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        service.listAttachmentsByFilters("  ", "", "   ", 100);

        verify(attachmentRepository).findByFilters(isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    @DisplayName("limit > 500 is clamped to the hard cap")
    void limitClampedToHardCap() {
        when(attachmentRepository.findByFilters(isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        service.listAttachmentsByFilters(null, null, null, 1_000);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(attachmentRepository).findByFilters(isNull(), isNull(), isNull(), pageable.capture());
        assertThat(pageable.getValue().getPageSize())
                .isEqualTo(ChatAttachmentService.ADMIN_ATTACHMENTS_MAX_LIMIT);
    }
}
