package com.example.demo.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.demo.persistence.entity.UserEntity;
import com.example.demo.persistence.mapper.UserMapper;
import com.example.demo.persistence.service.UserPersistenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserPersistenceServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Captor
    private ArgumentCaptor<UserEntity> userCaptor;

    @Test
    void createUserShouldHashPasswordAndNormalizeRole() {
        when(userMapper.findByUsername("alice")).thenReturn(null);
        when(passwordEncoder.encode("secret")).thenReturn("bcrypt-hash");
        when(userMapper.insert(org.mockito.ArgumentMatchers.any(UserEntity.class)))
                .thenAnswer(invocation -> {
                    UserEntity user = invocation.getArgument(0);
                    user.setId(7L);
                    return 1;
                });
        UserEntity stored = new UserEntity();
        stored.setId(7L);
        stored.setUsername("alice");
        stored.setPassword("bcrypt-hash");
        stored.setRole("ADMIN");
        when(userMapper.findById(7L)).thenReturn(stored);

        UserPersistenceService service =
                new UserPersistenceService(userMapper, passwordEncoder);

        UserEntity created = service.createUser(" alice ", "secret", "admin");

        verify(userMapper).insert(userCaptor.capture());
        assertThat(userCaptor.getValue().getPassword()).isEqualTo("bcrypt-hash");
        assertThat(userCaptor.getValue().getRole()).isEqualTo("ADMIN");
        assertThat(created.getId()).isEqualTo(7L);
    }
}
