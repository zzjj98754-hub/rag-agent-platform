package com.example.demo.config;

import com.example.demo.persistence.entity.UserEntity;
import com.example.demo.persistence.service.UserPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 仅在 loadtest Profile 下创建可读取 Actuator 指标的压测账号。
 *
 * <p>密码必须由 LOAD_TEST_PASSWORD 注入，默认 Profile 不会加载此组件。
 */
@Component
@Profile("loadtest")
public class LoadTestUserInitializer implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(LoadTestUserInitializer.class);

    private final UserPersistenceService userPersistenceService;
    private final String username;
    private final String password;

    public LoadTestUserInitializer(
            UserPersistenceService userPersistenceService,
            @Value("${app.load-test.username}") String username,
            @Value("${app.load-test.password}") String password) {
        this.userPersistenceService = userPersistenceService;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        UserEntity existing = userPersistenceService.findByUsername(username);
        if (existing == null) {
            userPersistenceService.createUser(username, password, "ADMIN");
            log.warn("已创建仅供压测使用的 ADMIN 账号 | username={}", username);
            return;
        }

        userPersistenceService.updatePassword(existing.getId(), password);
        userPersistenceService.updateRole(existing.getId(), "ADMIN");
        log.warn("已刷新仅供压测使用的 ADMIN 账号 | username={}", username);
    }
}
