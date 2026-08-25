package io.github.illuseahashmap.workflow.process.infrastructure.lock;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "workflow.lock")
public class WorkflowLockProperties {

    private String keyPrefix = "workflow-agent-service";
    private long waitSeconds = 3;
    private long ttlSeconds = 30;
    private int renewalThreads = 4;

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public long getWaitSeconds() {
        return waitSeconds;
    }

    public void setWaitSeconds(long waitSeconds) {
        this.waitSeconds = waitSeconds;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public int getRenewalThreads() {
        return renewalThreads;
    }

    public void setRenewalThreads(int renewalThreads) {
        this.renewalThreads = renewalThreads;
    }
}
