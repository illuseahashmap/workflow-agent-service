package io.github.illuseahashmap.workflow.security.infrastructure.token;

import io.github.illuseahashmap.workflow.security.domain.ServiceClient;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ServiceClientSecretResolver {

    private static final String ENV_PREFIX = "env:";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final WorkflowSecurityProperties properties;

    public ServiceClientSecretResolver(WorkflowSecurityProperties properties) {
        this.properties = properties;
    }

    public String resolve(ServiceClient client) {
        if (StringUtils.hasText(client.secretCiphertext())) {
            return decryptDatabaseSecret(client.secretCiphertext());
        }
        if (StringUtils.hasText(client.secretKeyRef()) && client.secretKeyRef().startsWith(ENV_PREFIX)) {
            String environmentName = client.secretKeyRef().substring(ENV_PREFIX.length()).trim();
            String secret = System.getenv(environmentName);
            if (StringUtils.hasText(secret)) {
                return secret.trim();
            }
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                "Workflow service client secret is not configured: " + client.clientCode());
    }

    private String decryptDatabaseSecret(String encryptedSecret) {
        if (!StringUtils.hasText(properties.getMasterKeyBase64())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Workflow token master key is not configured");
        }
        try {
            byte[] encryptedBytes = Base64.getUrlDecoder().decode(encryptedSecret);
            if (encryptedBytes.length <= GCM_IV_LENGTH) {
                throw new IllegalArgumentException("Invalid encrypted secret");
            }
            byte[] iv = Arrays.copyOfRange(encryptedBytes, 0, GCM_IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(encryptedBytes, GCM_IV_LENGTH, encryptedBytes.length);
            byte[] masterKey = Base64.getDecoder().decode(properties.getMasterKeyBase64());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            String secret = new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8).trim();
            if (!StringUtils.hasText(secret)) {
                throw new IllegalArgumentException("Empty client secret");
            }
            return secret;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Workflow service client secret cannot be decrypted");
        }
    }
}
