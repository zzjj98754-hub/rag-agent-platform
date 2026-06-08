package com.example.demo.config;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 启动时验证 MySQL 连接 —— 仅校验连接可用性，不影响业务启动。
 *
 * 连接失败时打 ERROR 日志但不抛异常——现有 RAG 功能不依赖 MySQL，
 * 所以 DB 不可用时服务仍可正常对外提供问答能力。
 */
@Component
public class MysqlConnectionVerifier {

    private static final Logger log = LoggerFactory.getLogger(MysqlConnectionVerifier.class);

    @Autowired
    private DataSource dataSource;

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
            log.error("❌ MySQL 连接失败: {}。RAG 功能不受影响，但请检查 MySQL 是否已启动。", e.getMessage());
        }
    }

    private String getPoolInfo(String type) {
        // HikariCP 的 HikariPoolMXBean 可获取池状态，非关键路径，简单返回 "?"
        return "?";
    }
}
