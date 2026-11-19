package com.gamenews.ingestion;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ingestion.steam")
public class SteamProperties {
    private boolean enabled = true;
    private boolean schedulingEnabled = true;
    @Min(1) @Max(100) private int count = 20;
    @Min(100) @Max(30000) private int timeoutMs = 10000;
    @Min(0) @Max(2) private int maxRetries = 2;
    @Min(1000) private long intervalMs = 1800000;
    @Min(0) private long initialDelayMs = 10000;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isSchedulingEnabled() { return schedulingEnabled; }
    public void setSchedulingEnabled(boolean value) { schedulingEnabled = value; }
    public int getCount() { return count; }
    public void setCount(int value) { count = value; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int value) { timeoutMs = value; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int value) { maxRetries = value; }
    public long getIntervalMs() { return intervalMs; }
    public void setIntervalMs(long value) { intervalMs = value; }
    public long getInitialDelayMs() { return initialDelayMs; }
    public void setInitialDelayMs(long value) { initialDelayMs = value; }
}
