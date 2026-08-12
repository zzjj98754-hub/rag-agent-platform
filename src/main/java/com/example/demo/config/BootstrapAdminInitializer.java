package com.example.demo.config;

import com.example.demo.persistence.entity.UserEntity;
import com.example.demo.persistence.service.UserPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 为全新部署创建一次初始管理员。
 *
 * <p>默认关闭，仅当部署环境显式开启时生效；已存在同名用户时不会重置密码或
 * 提升权限，避免容器重启覆盖人工修改。
 */
@Component
@ConditionalOnProperty(
        prefix = "app.security.bootstrap-admin",
        name = "enabled",
        havingValue = "true")
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final UserPersistenceService userPersistenceService;
    private final String username;
    private final String password;

    public BootstrapAdminInitializer(
            UserPersistenceService userPersistenceService,
            @Value("${app.security.bootstrap-admin.username}") String username,
            @Value("${app.security.bootstrap-admin.password}") String password) {
        this.userPersistenceService = userPersistenceService;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "启用初始管理员时必须配置 "
                            + "app.security.bootstrap-admin.password");
        }

        UserEntity existing = userPersistenceService.findByUsername(username);
        if (existing != null) {
            log.info(
                    "初始管理员已存在，跳过创建且不重置凭据 | username={} role={}",
                    username,
                    existing.getRole());
            return;
        }

        userPersistenceService.createUser(username, password, "ADMIN");
        log.warn(
                "已创建初始 ADMIN，请登录后立即更换部署凭据 | username={}",
                username);
    }
}
