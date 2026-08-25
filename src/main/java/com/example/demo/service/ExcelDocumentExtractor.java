package com.example.demo.service;

import java.io.IOException;
import java.io.InputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** Converts XLSX sheets into a stable, line-oriented text representation. */
@Component
public class ExcelDocumentExtractor implements DocumentContentExtractor {

    private final DataFormatter formatter = new DataFormatter();

    @Override
    public boolean supports(String extension) {
        return "xlsx".equalsIgnoreCase(extension);
    }

    @Override
    public String extract(MultipartFile file) throws IOException {
        StringBuilder text = new StringBuilder();
        try (InputStream input = file.getInputStream();
                XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            for (Sheet sheet : workbook) {
                text.append("[Sheet: ").append(sheet.getSheetName()).append("]\n");
                for (Row row : sheet) {
                    boolean hasValue = false;
                    for (Cell cell : row) {
                        String value = formatter.formatCellValue(cell).trim();
                        if (!value.isEmpty()) {
                            hasValue = true;
                        }
                        text.append(value).append('\t');
                    }
                    if (hasValue) {
                        text.append('\n');
                    }
                }
                text.append('\n');
            }
        }
        return text.toString();
    }
}
