package com.example.demo.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class McpSecretCipherTest {
    @Test
    void shouldEncryptWithRandomNonceAndDecrypt() {
        String key = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        McpSecretCipher cipher = new McpSecretCipher(key);
        String first = cipher.encrypt("Bearer secret");
        String second = cipher.encrypt("Bearer secret");
        assertThat(first).isNotEqualTo(second).doesNotContain("secret");
        assertThat(cipher.decrypt(first)).isEqualTo("Bearer secret");
    }

    @Test
    void shouldRequireMasterKeyOnlyWhenSecretIsUsed() {
        McpSecretCipher cipher = new McpSecretCipher("");
        assertThat(cipher.encrypt(null)).isNull();
        assertThatThrownBy(() -> cipher.encrypt("secret"))
                .isInstanceOf(IllegalStateException.class);
    }
}
