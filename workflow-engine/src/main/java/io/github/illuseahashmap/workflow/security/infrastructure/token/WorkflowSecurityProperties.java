package io.github.illuseahashmap.workflow.security.infrastructure.token;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "workflow.security")
public class WorkflowSecurityProperties {

    private boolean enabled = true;

    private String masterKeyBase64;

    private long replayWindowSeconds = 300;

    private long clockSkewSeconds = 30;

    private List<String> publicPaths = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMasterKeyBase64() {
        return masterKeyBase64;
    }

    public void setMasterKeyBase64(String masterKeyBase64) {
        this.masterKeyBase64 = masterKeyBase64;
    }

    public long getReplayWindowSeconds() {
        return replayWindowSeconds;
    }

    public void setReplayWindowSeconds(long replayWindowSeconds) {
        this.replayWindowSeconds = replayWindowSeconds;
    }

    public long getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    public void setClockSkewSeconds(long clockSkewSeconds) {
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }
}
