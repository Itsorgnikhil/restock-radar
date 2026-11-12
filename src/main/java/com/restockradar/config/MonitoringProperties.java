package com.restockradar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "monitoring")
public class MonitoringProperties {

    private int defaultIntervalSeconds = 60;
    private int rooterIntervalSeconds = 15;
    private int minIntervalSeconds = 10;
    private long requestSpacingMs = 1000L;
    private int outOfStockConfirmations = 2;
    private boolean alertOnFirstCheck = true;

    public int getIntervalForStore(String storeCode) {
        if ("ROOTER".equalsIgnoreCase(storeCode)) {
            return rooterIntervalSeconds;
        }
        return defaultIntervalSeconds;
    }

    public int getRooterIntervalSeconds() {
        return rooterIntervalSeconds;
    }

    public void setRooterIntervalSeconds(int rooterIntervalSeconds) {
        this.rooterIntervalSeconds = rooterIntervalSeconds;
    }

    public int getDefaultIntervalSeconds() {
        return defaultIntervalSeconds;
    }

    public void setDefaultIntervalSeconds(int defaultIntervalSeconds) {
        this.defaultIntervalSeconds = defaultIntervalSeconds;
    }

    public int getMinIntervalSeconds() {
        return minIntervalSeconds;
    }

    public void setMinIntervalSeconds(int minIntervalSeconds) {
        this.minIntervalSeconds = minIntervalSeconds;
    }

    public long getRequestSpacingMs() {
        return requestSpacingMs;
    }

    public void setRequestSpacingMs(long requestSpacingMs) {
        this.requestSpacingMs = requestSpacingMs;
    }

    public int getOutOfStockConfirmations() {
        return outOfStockConfirmations;
    }

    public void setOutOfStockConfirmations(int outOfStockConfirmations) {
        this.outOfStockConfirmations = outOfStockConfirmations;
    }

    public boolean isAlertOnFirstCheck() {
        return alertOnFirstCheck;
    }

    public void setAlertOnFirstCheck(boolean alertOnFirstCheck) {
        this.alertOnFirstCheck = alertOnFirstCheck;
    }
}
