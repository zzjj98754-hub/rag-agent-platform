package com.example.demo.mcp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-GCM envelope for MCP credentials; the master key is never persisted. */
@Component
public class McpSecretCipher {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public McpSecretCipher(
            @Value("${app.mcp.config-encryption-key}") String encodedKey) {
        this.key = parseKey(encodedKey);
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return null;
        }
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(
                    plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer
                    .allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to encrypt MCP credential", ex);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        requireKey();
        try {
            byte[] envelope = Base64.getDecoder().decode(encoded);
            if (envelope.length <= IV_BYTES) {
                throw new IllegalArgumentException("Invalid MCP credential envelope");
            }
            byte[] iv = java.util.Arrays.copyOfRange(envelope, 0, IV_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(
                    envelope, IV_BYTES, envelope.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Unable to decrypt MCP credential", ex);
        }
    }

    private byte[] parseKey(String value) {
        if (value == null || value.isBlank()) {
            return new byte[0];
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "MCP_CONFIG_ENCRYPTION_KEY must be Base64", ex);
        }
        if (decoded.length != 16 && decoded.length != 24 && decoded.length != 32) {
            throw new IllegalStateException(
                    "MCP_CONFIG_ENCRYPTION_KEY must decode to 16, 24 or 32 bytes");
        }
        return decoded;
    }

    private void requireKey() {
        if (key.length == 0) {
            throw new IllegalStateException(
                    "MCP_CONFIG_ENCRYPTION_KEY is required when MCP credentials are used");
        }
    }
}
