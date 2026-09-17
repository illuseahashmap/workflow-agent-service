package io.github.illuseahashmap.workflow.auth.application.port;

public interface AuthenticationAttemptGuard {

    void assertAllowed(String operation, String account, String sourceAddress);

    void recordFailure(String operation, String account, String sourceAddress);

    void recordSuccess(String operation, String account, String sourceAddress);
}
