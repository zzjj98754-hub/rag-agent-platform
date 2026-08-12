package com.example.demo.config;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 启动时记录 MySQL 连接信息。
 *
 * <p>MySQL 现在承载用户、文档和完整会话历史，属于业务依赖。
 * 数据库迁移和启动失败策略由 Flyway 统一负责。
 */
@Component
public class MysqlConnectionVerifier {

    private static final Logger log = LoggerFactory.getLogger(MysqlConnectionVerifier.class);

    private final DataSource dataSource;

    public MysqlConnectionVerifier(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void verifyConnection() {
        try (Connection conn = dataSource.getConnection()) {
            String url = conn.getMetaData().getURL();
            String dbProduct = conn.getMetaData().getDatabaseProductName();
            String dbVersion = conn.getMetaData().getDatabaseProductVersion();
            log.info("✅ MySQL 连接成功 | url={} | {} {} | HikariCP active={} idle={}",
                    url, dbProduct, dbVersion,
                    getPoolInfo("active"), getPoolInfo("idle"));
        } catch (Exception e) {
            log.error("❌ MySQL 连接失败，业务持久化不可用: {}", e.getMessage());
        }
    }

    private String getPoolInfo(String type) {
        // HikariCP 的 HikariPoolMXBean 可获取池状态，非关键路径，简单返回 "?"
        return "?";
    }
}
