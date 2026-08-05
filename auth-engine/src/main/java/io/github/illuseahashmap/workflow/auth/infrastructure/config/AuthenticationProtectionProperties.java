package io.github.illuseahashmap.workflow.auth.infrastructure.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "workflow.auth.protection")
@Validated
public class AuthenticationProtectionProperties {

    @Min(2)
    private int failureThreshold = 5;

    @Min(60)
    private long resetWindowSeconds = 900;

    @Min(1)
    private long baseLockSeconds = 30;

    @Min(30)
    private long maxLockSeconds = 900;

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public long getResetWindowSeconds() {
        return resetWindowSeconds;
    }

    public void setResetWindowSeconds(long resetWindowSeconds) {
        this.resetWindowSeconds = resetWindowSeconds;
    }

    public long getBaseLockSeconds() {
        return baseLockSeconds;
    }

    public void setBaseLockSeconds(long baseLockSeconds) {
        this.baseLockSeconds = baseLockSeconds;
    }

    public long getMaxLockSeconds() {
        return maxLockSeconds;
    }

    public void setMaxLockSeconds(long maxLockSeconds) {
        this.maxLockSeconds = maxLockSeconds;
    }
}
