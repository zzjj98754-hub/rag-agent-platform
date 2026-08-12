package com.example.demo.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class PlainTextDocumentExtractor implements DocumentContentExtractor {

    private static final Set<String> SUPPORTED = Set.of("txt", "md");

    @Override
    public boolean supports(String extension) {
        return SUPPORTED.contains(extension);
    }

    @Override
    public String extract(MultipartFile file) throws IOException {
        return new String(file.getBytes(), StandardCharsets.UTF_8);
    }
}
