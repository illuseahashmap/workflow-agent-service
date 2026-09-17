package io.github.illuseahashmap.workflow.auth.infrastructure.config;

import io.github.illuseahashmap.workflow.auth.application.port.SelfRegistrationPolicy;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "workflow.auth.self-registration")
@Validated
public class SelfRegistrationProperties implements SelfRegistrationPolicy {

    private boolean enabled;

    @NotBlank
    private String tenantCode = "default";

    @Override
    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String tenantCode() {
        return tenantCode;
    }

    public void setTenantCode(String tenantCode) {
        this.tenantCode = tenantCode;
    }
}
