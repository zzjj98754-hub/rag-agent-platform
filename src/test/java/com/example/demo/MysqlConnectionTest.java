package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * MySQL 连接验证测试 —— 验证 DataSource 可用且能获取到有效连接。
 *
 * 仅测试连接，不创建任何业务表，不插入数据。
 */
@SpringBootTest
public class MysqlConnectionTest {

    private static final Logger log = LoggerFactory.getLogger(MysqlConnectionTest.class);

    @Autowired
    private DataSource dataSource;

    @Test
    public void shouldConnectToMysql() throws Exception {
        assertThat(dataSource).isNotNull();

        try (Connection conn = dataSource.getConnection()) {
            assertThat(conn).isNotNull();
            assertThat(conn.isValid(3)).isTrue();

            String url = conn.getMetaData().getURL();
            String product = conn.getMetaData().getDatabaseProductName();

            log.info("✅ 测试连接成功 | {} | {}", url, product);
        }
    }
}
