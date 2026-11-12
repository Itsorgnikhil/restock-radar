package com.restockradar.target;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "targets")
public class Target {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(name = "store_code", nullable = false, length = 64)
    private String storeCode;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 32)
    private String status; // IN_STOCK, OUT_OF_STOCK, or null (never checked)

    @Column(name = "alert_message_id")
    private Long alertMessageId;

    @Column(name = "out_count", nullable = false)
    private int outCount = 0;

    @Column(name = "interval_seconds", nullable = false)
    private int intervalSeconds = 600;

    @Column(name = "next_check_at", nullable = false)
    private Instant nextCheckAt = Instant.now();

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "last_price", precision = 12, scale = 2)
    private BigDecimal lastPrice;

    @Column(name = "last_evidence", length = 1000)
    private String lastEvidence;

    @Column(nullable = false)
    private boolean active = true;

    public Target() {
    }

    public Target(String storeCode, String url, String name, int intervalSeconds) {
        this.storeCode = storeCode;
        this.url = url;
        this.name = name;
        this.intervalSeconds = intervalSeconds;
        this.nextCheckAt = Instant.now();
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getStoreCode() {
        return storeCode;
    }

    public void setStoreCode(String storeCode) {
        this.storeCode = storeCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getAlertMessageId() {
        return alertMessageId;
    }

    public void setAlertMessageId(Long alertMessageId) {
        this.alertMessageId = alertMessageId;
    }

    public int getOutCount() {
        return outCount;
    }

    public void setOutCount(int outCount) {
        this.outCount = outCount;
    }

    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(int intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    public Instant getNextCheckAt() {
        return nextCheckAt;
    }

    public void setNextCheckAt(Instant nextCheckAt) {
        this.nextCheckAt = nextCheckAt;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(Instant lastCheckedAt) {
        this.lastCheckedAt = lastCheckedAt;
    }

    public BigDecimal getLastPrice() {
        return lastPrice;
    }

    public void setLastPrice(BigDecimal lastPrice) {
        this.lastPrice = lastPrice;
    }

    public String getLastEvidence() {
        return lastEvidence;
    }

    public void setLastEvidence(String lastEvidence) {
        this.lastEvidence = lastEvidence;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
