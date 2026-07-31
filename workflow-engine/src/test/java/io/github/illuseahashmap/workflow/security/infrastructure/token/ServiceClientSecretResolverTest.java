package io.github.illuseahashmap.workflow.security.infrastructure.token;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.illuseahashmap.workflow.security.domain.ServiceClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class ServiceClientSecretResolverTest {

    @Test
    void decryptsDatabaseBackedClientSecret() throws Exception {
        byte[] masterKey = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        WorkflowSecurityProperties properties = new WorkflowSecurityProperties();
        properties.setMasterKeyBase64(Base64.getEncoder().encodeToString(masterKey));
        ServiceClientSecretResolver resolver = new ServiceClientSecretResolver(properties);
        ServiceClient client = new ServiceClient(
                "test", null, encrypt("client-secret", masterKey), 1, "*", "*", true, null);

        assertEquals("client-secret", resolver.resolve(client));
    }

    private String encrypt(String value, byte[] key) throws Exception {
        byte[] iv = new byte[12];
        java.util.Arrays.fill(iv, (byte) 7);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] envelope = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, envelope, 0, iv.length);
        System.arraycopy(ciphertext, 0, envelope, iv.length, ciphertext.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
    }
}
