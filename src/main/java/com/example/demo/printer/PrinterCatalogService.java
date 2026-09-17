package com.example.demo.printer;

import com.example.demo.persistence.entity.DocumentEntity;
import com.example.demo.persistence.service.DocumentPersistenceService;
import com.example.demo.service.DocumentIngestionService;
import com.example.demo.web.error.ResourceNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 打印机型号、资料关联及演示资料初始化。 */
@Service
public class PrinterCatalogService {

    private static final List<DemoProduct> DEMO_PRODUCTS = List.of(
            new DemoProduct("XH-A100", "虚构星河 A100", "虚构入门喷墨打印机（演示型号）", List.of(
                    "虚构-星河-A100-使用手册.md", "虚构-星河-A100-故障说明.md", "虚构-星河-A100-保修政策.md")),
            new DemoProduct("YS-B200", "虚构远山 B200", "虚构办公喷墨打印机（演示型号）", List.of(
                    "虚构-远山-B200-使用手册.md", "虚构-远山-B200-故障说明.md", "虚构-远山-B200-保修政策.md")));

    private final JdbcTemplate jdbc;
    private final DocumentIngestionService ingestion;
    private final DocumentPersistenceService documents;

    public PrinterCatalogService(
            JdbcTemplate jdbc,
            DocumentIngestionService ingestion,
            DocumentPersistenceService documents) {
        this.jdbc = jdbc;
        this.ingestion = ingestion;
        this.documents = documents;
    }

    public List<ProductView> listProducts() {
        return jdbc.query("SELECT id, product_code, model_name, description FROM printer_product ORDER BY id",
                (rs, rowNum) -> new ProductView(rs.getLong("id"), rs.getString("product_code"),
                        rs.getString("model_name"), rs.getString("description")));
    }

    public ProductView requireProduct(Long productId) {
        if (productId == null) throw new IllegalArgumentException("productId 不能为空");
        List<ProductView> products = jdbc.query(
                "SELECT id, product_code, model_name, description FROM printer_product WHERE id = ?",
                (rs, rowNum) -> new ProductView(rs.getLong("id"), rs.getString("product_code"),
                        rs.getString("model_name"), rs.getString("description")), productId);
        if (products.isEmpty()) throw new ResourceNotFoundException("打印机型号不存在: " + productId);
        return products.get(0);
    }

    public ProductScope requireScope(Long productId) {
        ProductView product = requireProduct(productId);
        List<SourceDocument> sources = jdbc.query("""
                SELECT d.id, d.title, d.document_version
                FROM printer_product_document pd
                JOIN document d ON d.id = pd.document_id
                WHERE pd.product_id = ? AND d.status = 'INDEXED'
                ORDER BY d.id
                """, (rs, rowNum) -> new SourceDocument(
                rs.getLong("id"), rs.getString("title"), rs.getInt("document_version")), productId);
        if (sources.isEmpty()) throw new IllegalStateException("该型号尚未完成资料索引，请先初始化演示资料");
        return new ProductScope(product, sources);
    }

    /** 读取仓库内虚构资料，交给既有文档切分与索引管线；重复执行只更新同名文档。 */
    @Transactional
    public InitializationResult initializeDemoData() {
        int indexed = 0;
        for (DemoProduct product : DEMO_PRODUCTS) {
            long productId = upsertProduct(product);
            for (String fileName : product.documents()) {
                try {
                    String content = new String(new ClassPathResource("demo-printer-docs/" + fileName)
                            .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    ingestion.ingestOne(fileName, content, null);
                    DocumentEntity document = documents.findByFilePath("upload://" + fileName);
                    if (document == null) throw new IllegalStateException("资料未写入数据库: " + fileName);
                    jdbc.update("""
                            INSERT INTO printer_product_document(product_id, document_id, document_version)
                            VALUES (?, ?, ?)
                            ON DUPLICATE KEY UPDATE document_version = VALUES(document_version)
                            """, productId, document.getId(), document.getDocumentVersion());
                    indexed++;
                } catch (IOException e) {
                    throw new IllegalStateException("读取演示资料失败: " + fileName, e);
                }
            }
        }
        return new InitializationResult(DEMO_PRODUCTS.size(), indexed);
    }

    private long upsertProduct(DemoProduct product) {
        jdbc.update("""
                INSERT INTO printer_product(product_code, model_name, description)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE model_name = VALUES(model_name), description = VALUES(description)
                """, product.code(), product.modelName(), product.description());
        return jdbc.queryForObject("SELECT id FROM printer_product WHERE product_code = ?", Long.class, product.code());
    }

    public record ProductView(Long id, String productCode, String modelName, String description) {}
    public record SourceDocument(Long id, String title, int version) {}
    public record ProductScope(ProductView product, List<SourceDocument> sources) {
        public ProductScope { sources = List.copyOf(sources); }
        public Map<String, SourceDocument> sourceByTitle() {
            Map<String, SourceDocument> result = new LinkedHashMap<>();
            sources.forEach(source -> result.put(source.title(), source));
            return result;
        }
    }
    public record InitializationResult(int products, int documents) {}

    private record DemoProduct(String code, String modelName, String description, List<String> documents) {}
}
