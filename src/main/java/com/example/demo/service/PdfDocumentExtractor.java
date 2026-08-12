package com.example.demo.service;

import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class PdfDocumentExtractor implements DocumentContentExtractor {

    @Override
    public boolean supports(String extension) {
        return "pdf".equals(extension);
    }

    @Override
    public String extract(MultipartFile file) throws IOException {
        try (PDDocument document =
                     Loader.loadPDF(file.getBytes())) {
            return new PDFTextStripper().getText(document);
        }
    }
}
