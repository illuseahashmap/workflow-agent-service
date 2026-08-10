package io.github.illuseahashmap.agent.provider.application.port;

public interface AgentCredentialCipher {

    String encrypt(String tenantCode, long providerId, String plaintext);

    String decrypt(String tenantCode, long providerId, String ciphertext);
}
