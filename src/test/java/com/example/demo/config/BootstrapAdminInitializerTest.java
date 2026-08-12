package com.example.demo.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.demo.persistence.entity.UserEntity;
import com.example.demo.persistence.service.UserPersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

class BootstrapAdminInitializerTest {

    @Test
    void shouldCreateAdminOnlyWhenUsernameDoesNotExist() throws Exception {
        UserPersistenceService users = mock(UserPersistenceService.class);
        when(users.findByUsername("admin")).thenReturn(null);
        BootstrapAdminInitializer initializer =
                new BootstrapAdminInitializer(users, "admin", "strong-password");

        initializer.run(mock(ApplicationArguments.class));

        verify(users).createUser("admin", "strong-password", "ADMIN");
    }

    @Test
    void shouldNotResetExistingUser() throws Exception {
        UserPersistenceService users = mock(UserPersistenceService.class);
        UserEntity existing = new UserEntity();
        existing.setUsername("admin");
        existing.setRole("ADMIN");
        when(users.findByUsername("admin")).thenReturn(existing);
        BootstrapAdminInitializer initializer =
                new BootstrapAdminInitializer(users, "admin", "strong-password");

        initializer.run(mock(ApplicationArguments.class));

        verify(users, never()).createUser(
                "admin",
                "strong-password",
                "ADMIN");
        verify(users, never()).updatePassword(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
