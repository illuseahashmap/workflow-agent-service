package io.github.illuseahashmap.workflow.security.serviceauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.common.exception.BusinessException;
import io.github.illuseahashmap.workflow.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
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
    private final WorkflowSecurityProperties properties;

    public ServiceTokenCryptoService(ObjectMapper objectMapper, WorkflowSecurityProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public ServiceTokenPayload decrypt(String token) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Missing X-Workflow-Token");
        }
        if (!StringUtils.hasText(properties.getMasterKeyBase64())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Workflow token master key is not configured");
        }
        try {
            byte[] tokenBytes = Base64.getUrlDecoder().decode(token);
            if (tokenBytes.length <= GCM_IV_LENGTH) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid workflow token");
            }
            byte[] iv = Arrays.copyOfRange(tokenBytes, 0, GCM_IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(tokenBytes, GCM_IV_LENGTH, tokenBytes.length);
            byte[] key = Base64.getDecoder().decode(properties.getMasterKeyBase64());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plainText = cipher.doFinal(ciphertext);
            return objectMapper.readValue(new String(plainText, StandardCharsets.UTF_8), ServiceTokenPayload.class);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid workflow token");
        }
    }
}
