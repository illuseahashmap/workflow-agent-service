package io.github.illuseahashmap.workflow.security.infrastructure.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.security.domain.ServiceClient;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ServiceTokenCryptoService {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final ObjectMapper objectMapper;
    private final ServiceClientSecretResolver secretResolver;

    public ServiceTokenCryptoService(ObjectMapper objectMapper, ServiceClientSecretResolver secretResolver) {
        this.objectMapper = objectMapper;
        this.secretResolver = secretResolver;
    }

    public ServiceTokenEnvelope parse(String token) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Missing X-Workflow-Token");
        }
        int separatorIndex = token.indexOf('.');
        if (separatorIndex <= 0 || separatorIndex == token.length() - 1) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid workflow token envelope");
        }
        return new ServiceTokenEnvelope(
                token.substring(0, separatorIndex).trim(),
                token.substring(separatorIndex + 1).trim());
    }

    public ServiceTokenPayload decrypt(ServiceTokenEnvelope envelope, ServiceClient client) {
        try {
            byte[] tokenBytes = Base64.getUrlDecoder().decode(envelope.encryptedToken());
            if (tokenBytes.length <= GCM_IV_LENGTH) {
                throw new IllegalArgumentException("Invalid encrypted token");
            }
            byte[] iv = Arrays.copyOfRange(tokenBytes, 0, GCM_IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(tokenBytes, GCM_IV_LENGTH, tokenBytes.length);
            byte[] key = MessageDigest.getInstance("SHA-256")
                    .digest(secretResolver.resolve(client).getBytes(StandardCharsets.UTF_8));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plainText = cipher.doFinal(ciphertext);
            return objectMapper.readValue(plainText, ServiceTokenPayload.class);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid workflow token");
        }
    }

    public record ServiceTokenEnvelope(String clientCode, String encryptedToken) {
    }
}
