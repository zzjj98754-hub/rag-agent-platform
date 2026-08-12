package com.example.demo.service;

import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentContentExtractor {

    boolean supports(String extension);

    String extract(MultipartFile file) throws IOException;
}
