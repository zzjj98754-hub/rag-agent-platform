package com.example.demo.printer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.persistence.service.UserPersistenceService;
import com.example.demo.security.AuthenticatedUser;
import com.example.demo.security.UserRole;
import com.example.demo.web.error.ResourceNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.ingestion.startup-enabled=false")
@Transactional
class PrinterApplicationServiceIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserPersistenceService users;
    @Autowired private PrinterApplicationService applications;

    @Test
    void idempotencyReturnsOneApplicationAndOtherUserCannotReadIt() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var owner = users.createUser("printer_owner_" + suffix, "printer-secret", "USER");
        var other = users.createUser("printer_other_" + suffix, "printer-secret", "USER");
        jdbc.update("INSERT INTO printer_product(product_code, model_name, description) VALUES (?, ?, ?)",
                "TEST-" + suffix, "虚构测试型号", "测试用虚构型号");
        Long productId = jdbc.queryForObject("SELECT id FROM printer_product WHERE product_code = ?", Long.class, "TEST-" + suffix);

        var ownerPrincipal = new AuthenticatedUser(owner.getId(), owner.getUsername(), UserRole.USER);
        var first = applications.create(ownerPrincipal,
                new PrinterApplicationService.CreateApplication(productId, "打印模糊", "已按手册完成清洁和校准", "仍未解决"), "same-click");
        var repeated = applications.create(ownerPrincipal,
                new PrinterApplicationService.CreateApplication(productId, "打印模糊", "已按手册完成清洁和校准", "仍未解决"), "same-click");

        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(applications.listMine(ownerPrincipal)).hasSize(1);
        assertThatThrownBy(() -> applications.requireOwned(first.id(),
                new AuthenticatedUser(other.getId(), other.getUsername(), UserRole.USER)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
