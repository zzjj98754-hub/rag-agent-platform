package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class DocumentContentExtractorTest {

    @Test
    void plainTextExtractorShouldReadMarkdownAsUtf8()
            throws Exception {
        PlainTextDocumentExtractor extractor =
                new PlainTextDocumentExtractor();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "knowledge.md",
                "text/markdown",
                "# 知识库".getBytes(StandardCharsets.UTF_8));

        assertThat(extractor.supports("md")).isTrue();
        assertThat(extractor.extract(file))
                .isEqualTo("# 知识库");
    }

    @Test
    void pdfExtractorShouldReadPdfText() throws Exception {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content =
                         new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(
                        new PDType1Font(
                                Standard14Fonts.FontName.HELVETICA),
                        12);
                content.newLineAtOffset(72, 720);
                content.showText("RAG PDF knowledge");
                content.endText();
            }
            document.save(output);
        }
        PdfDocumentExtractor extractor =
                new PdfDocumentExtractor();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "knowledge.pdf",
                "application/pdf",
                output.toByteArray());

        assertThat(extractor.supports("pdf")).isTrue();
        assertThat(extractor.extract(file))
                .contains("RAG PDF knowledge");
    }
}
