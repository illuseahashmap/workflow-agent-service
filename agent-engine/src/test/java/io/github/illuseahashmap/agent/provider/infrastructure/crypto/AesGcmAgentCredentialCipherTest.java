package io.github.illuseahashmap.agent.provider.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class AesGcmAgentCredentialCipherTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void encryptsCredentialWithVersionAndTenantProviderBinding() throws Exception {
        AesGcmAgentCredentialCipher cipher = new AesGcmAgentCredentialCipher(
                Base64.getEncoder().encodeToString(KEY));

        String encrypted = cipher.encrypt("tenant-a", 42L, "secret-value");

        assertThat(encrypted).startsWith("v1:");
        assertThat(cipher.decrypt("tenant-a", 42L, encrypted)).isEqualTo("secret-value");
        assertThat(decrypt(encrypted, "tenant-a", 42L)).isEqualTo("secret-value");
        assertThatThrownBy(() -> cipher.decrypt("tenant-b", 42L, encrypted))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Unable to decrypt Agent credential");
        assertThatThrownBy(() -> decrypt(encrypted, "tenant-b", 42L))
                .isInstanceOf(Exception.class);
    }

    @Test
    void rejectsMissingMasterKey() {
        AesGcmAgentCredentialCipher cipher = new AesGcmAgentCredentialCipher("");

        assertThatThrownBy(() -> cipher.encrypt("tenant-a", 42L, "secret-value"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Agent credential encryption key is not configured");
    }

    private String decrypt(String encrypted, String tenantCode, long providerId) throws Exception {
        byte[] payload = Base64.getDecoder().decode(encrypted.substring("v1:".length()));
        byte[] iv = new byte[12];
        byte[] ciphertext = new byte[payload.length - iv.length];
        ByteBuffer.wrap(payload).get(iv).get(ciphertext);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD((tenantCode + ":" + providerId).getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
}
