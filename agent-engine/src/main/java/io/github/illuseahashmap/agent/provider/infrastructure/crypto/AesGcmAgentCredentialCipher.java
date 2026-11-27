package io.github.illuseahashmap.agent.provider.infrastructure.crypto;

import io.github.illuseahashmap.agent.provider.application.port.AgentCredentialCipher;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
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
import org.springframework.util.StringUtils;

@Component
public class AesGcmAgentCredentialCipher implements AgentCredentialCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final String masterKeyBase64;

    public AesGcmAgentCredentialCipher(
            @Value("${workflow.agent.credentials.master-key-base64:}") String masterKeyBase64
    ) {
        this.masterKeyBase64 = masterKeyBase64;
    }

    @Override
    public String encrypt(String tenantCode, long providerId, String plaintext) {
        try {
            byte[] key = masterKey();
            byte[] iv = new byte[IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD((tenantCode + ":" + providerId).getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array());
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Unable to encrypt Agent credential", exception);
        }
    }

    @Override
    public String decrypt(String tenantCode, long providerId, String ciphertext) {
        if (!StringUtils.hasText(ciphertext) || !ciphertext.startsWith("v1:")) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Unsupported Agent credential format");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(ciphertext.substring(3));
            if (payload.length <= IV_LENGTH) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Invalid Agent credential payload");
            }
            byte[] iv = java.util.Arrays.copyOfRange(payload, 0, IV_LENGTH);
            byte[] encrypted = java.util.Arrays.copyOfRange(payload, IV_LENGTH, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey(), "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD((tenantCode + ":" + providerId).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Unable to decrypt Agent credential", exception);
        }
    }

    private byte[] masterKey() {
        if (!StringUtils.hasText(masterKeyBase64)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "Agent credential encryption key is not configured");
        }
        try {
            byte[] key = Base64.getDecoder().decode(masterKeyBase64);
            if (key.length != 32) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "Agent credential encryption key must contain 32 bytes");
            }
            return key;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "Agent credential encryption key is not valid Base64", exception);
        }
    }
}
