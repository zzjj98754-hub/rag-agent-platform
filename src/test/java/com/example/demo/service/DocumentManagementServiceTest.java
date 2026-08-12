package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.demo.dto.DocumentUploadResponse;
import com.example.demo.dto.DocumentView;
import com.example.demo.dto.IngestionResult;
import com.example.demo.security.AuthenticatedUser;
import com.example.demo.security.CurrentUserProvider;
import com.example.demo.security.UserRole;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class DocumentManagementServiceTest {

    private DocumentIngestionService ingestionService;
    private CurrentUserProvider currentUserProvider;
    private DocumentManagementService managementService;

    @BeforeEach
    void setUp() {
        ingestionService = mock(DocumentIngestionService.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        managementService = new DocumentManagementService(
                ingestionService,
                currentUserProvider,
                List.of(new PlainTextDocumentExtractor()),
                "txt,md,pdf");
    }

    @Test
    void shouldExtractTextAndIngestForAuthenticatedCreator() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "../knowledge.md",
                "text/markdown",
                "# Redis\n跳表".getBytes(StandardCharsets.UTF_8));
        when(currentUserProvider.requireCurrentUser())
                .thenReturn(new AuthenticatedUser(
                        7L,
                        "admin",
                        UserRole.ADMIN));
        when(ingestionService.ingestOne(
                "knowledge.md",
                "# Redis\n跳表",
                7L))
                .thenReturn(new IngestionResult(
                        List.of("knowledge.md"),
                        List.of(),
                        List.of(),
                        4,
                        25));

        DocumentUploadResponse response =
                managementService.upload(file);

        assertThat(response.success()).isTrue();
        assertThat(response.fileName()).isEqualTo("knowledge.md");
        assertThat(response.totalChunks()).isEqualTo(4);
        verify(ingestionService).ingestOne(
                "knowledge.md",
                "# Redis\n跳表",
                7L);
    }

    @Test
    void shouldRejectUnsupportedExtensionBeforeIngestion() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "script.exe",
                "application/octet-stream",
                new byte[] {1});

        assertThatThrownBy(() -> managementService.upload(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅支持");
        verifyNoInteractions(
                ingestionService,
                currentUserProvider);
    }

    @Test
    void shouldDelegateListAndDeleteToIngestionService() {
        DocumentView document = new DocumentView(
                9L,
                "java.txt",
                "java.txt",
                "INDEXED",
                7L,
                LocalDateTime.now(),
                12,
                "READY");
        when(ingestionService.listDocuments())
                .thenReturn(List.of(document));

        assertThat(managementService.listDocuments())
                .containsExactly(document);
        managementService.deleteDocument(9L);

        verify(ingestionService).deleteDocument(9L);
    }
}
