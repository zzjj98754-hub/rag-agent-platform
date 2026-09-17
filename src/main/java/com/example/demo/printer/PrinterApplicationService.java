package com.example.demo.printer;

import com.example.demo.security.AuthenticatedUser;
import com.example.demo.web.error.ResourceNotFoundException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 售后申请的创建、查询、幂等和管理状态流转。 */
@Service
public class PrinterApplicationService {

    private final JdbcTemplate jdbc;
    private final PrinterCatalogService catalog;

    public PrinterApplicationService(JdbcTemplate jdbc, PrinterCatalogService catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    @Transactional
    public ApplicationView create(AuthenticatedUser user, CreateApplication command, String idempotencyKey) {
        requireKey(idempotencyKey);
        catalog.requireProduct(command.productId());
        require(command.question(), "question", 2000);
        require(command.troubleshootingSteps(), "troubleshootingSteps", 8000);
        optional(command.additionalNote(), "additionalNote", 2000);
        String number = "PA" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        jdbc.update("""
                INSERT INTO after_sales_application
                    (application_no, user_id, product_id, question, troubleshooting_steps,
                     additional_note, status, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)
                ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
                """, number, user.id(), command.productId(), command.question().trim(),
                command.troubleshootingSteps().trim(), blankToNull(command.additionalNote()), idempotencyKey.trim());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return requireOwned(id, user);
    }

    public List<ApplicationView> listMine(AuthenticatedUser user) {
        return jdbc.query(baseSql() + " WHERE a.user_id = ? ORDER BY a.update_time DESC, a.id DESC",
                this::map, user.id());
    }

    public ApplicationView requireOwned(Long id, AuthenticatedUser user) {
        List<ApplicationView> result = jdbc.query(baseSql() + " WHERE a.id = ? AND a.user_id = ?",
                this::map, id, user.id());
        if (result.isEmpty()) throw new ResourceNotFoundException("售后申请不存在");
        return result.get(0);
    }

    public List<ApplicationView> listAll() {
        return jdbc.query(baseSql() + " ORDER BY a.update_time DESC, a.id DESC", this::map);
    }

    @Transactional
    public ApplicationView updateStatus(Long id, UpdateStatus command) {
        if (!List.of("PENDING", "PROCESSING", "COMPLETED").contains(command.status())) {
            throw new IllegalArgumentException("status 只能是 PENDING、PROCESSING 或 COMPLETED");
        }
        optional(command.processingNote(), "processingNote", 2000);
        if (jdbc.update("UPDATE after_sales_application SET status = ?, processing_note = ? WHERE id = ?",
                command.status(), blankToNull(command.processingNote()), id) == 0) {
            throw new ResourceNotFoundException("售后申请不存在");
        }
        return requireAny(id);
    }

    private ApplicationView requireAny(Long id) {
        List<ApplicationView> result = jdbc.query(baseSql() + " WHERE a.id = ?", this::map, id);
        if (result.isEmpty()) throw new ResourceNotFoundException("售后申请不存在");
        return result.get(0);
    }

    private String baseSql() {
        return """
                SELECT a.id, a.application_no, a.user_id, u.username, a.product_id,
                       p.product_code, p.model_name, a.question, a.troubleshooting_steps,
                       a.additional_note, a.status, a.processing_note, a.create_time, a.update_time
                FROM after_sales_application a
                JOIN printer_product p ON p.id = a.product_id
                JOIN `user` u ON u.id = a.user_id
                """;
    }

    private ApplicationView map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ApplicationView(rs.getLong("id"), rs.getString("application_no"), rs.getLong("user_id"),
                rs.getString("username"), rs.getLong("product_id"), rs.getString("product_code"),
                rs.getString("model_name"), rs.getString("question"), rs.getString("troubleshooting_steps"),
                rs.getString("additional_note"), rs.getString("status"), rs.getString("processing_note"),
                rs.getObject("create_time", LocalDateTime.class), rs.getObject("update_time", LocalDateTime.class));
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank() || key.trim().length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key 不能为空且不能超过 128 个字符");
        }
    }

    private String require(String value, String name, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        if (value.trim().length() > max) throw new IllegalArgumentException(name + " 不能超过 " + max + " 个字符");
        return value;
    }

    private void optional(String value, String name, int max) {
        if (value != null && value.trim().length() > max) throw new IllegalArgumentException(name + " 不能超过 " + max + " 个字符");
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record CreateApplication(Long productId, String question, String troubleshootingSteps, String additionalNote) {}
    public record UpdateStatus(String status, String processingNote) {}
    public record ApplicationView(Long id, String applicationNo, Long userId, String username, Long productId,
                                  String productCode, String modelName, String question, String troubleshootingSteps,
                                  String additionalNote, String status, String processingNote,
                                  LocalDateTime createTime, LocalDateTime updateTime) {}
}
