package com.restockradar.monitoring;

import com.restockradar.target.Target;

public interface StoreMonitor {

    /**
     * Store code identifying this adapter, e.g. "GAMES_THE_SHOP", "AMAZON_IN".
     */
    String storeCode();

    /**
     * Checks the target page and returns the current stock snapshot.
     */
    Snapshot check(Target target);
}
