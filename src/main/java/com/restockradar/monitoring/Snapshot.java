package com.restockradar.monitoring;

import java.math.BigDecimal;

public record Snapshot(
        Availability status,
        BigDecimal price,
        String evidence
) {
    public static Snapshot unknown(String evidence) {
        return new Snapshot(Availability.UNKNOWN, null, evidence);
    }

    public static Snapshot blocked(String evidence) {
        return new Snapshot(Availability.BLOCKED, null, evidence);
    }

    public static Snapshot inStock(BigDecimal price, String evidence) {
        return new Snapshot(Availability.IN_STOCK, price, evidence);
    }

    public static Snapshot outOfStock(BigDecimal price, String evidence) {
        return new Snapshot(Availability.OUT_OF_STOCK, price, evidence);
    }
}
