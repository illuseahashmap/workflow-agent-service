package io.github.illuseahashmap.workflow.auth.application.port;

public interface SelfRegistrationPolicy {

    boolean enabled();

    String tenantCode();
}
