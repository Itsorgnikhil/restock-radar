package com.restockradar.monitoring;

import com.restockradar.config.MonitoringProperties;
import com.restockradar.config.TelegramProperties;
import com.restockradar.target.Target;
import com.restockradar.target.TargetRepository;
import com.restockradar.telegram.TelegramClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

    private static final Map<String, String> STORE_NAMES = Map.of(
            "GAMES_THE_SHOP", "Games The Shop",
            "AMAZON_IN", "Amazon India",
            "FLIPKART", "Flipkart",
            "CROMA", "Croma",
            "ROOTER", "Rooter",
            "STANSHOP", "Stan Shop",
            "BLINKIT", "Blinkit"
    );

    private final TargetRepository targetRepository;
    private final TelegramClient telegramClient;
    private final TelegramProperties telegramProperties;
    private final MonitoringProperties monitoringProperties;
    private final Map<String, StoreMonitor> monitors;

    // Track consecutive blocked checks to alert the owner once at threshold
    private final Map<Long, Integer> consecutiveBlockedCounts = new ConcurrentHashMap<>();

    public MonitoringService(TargetRepository targetRepository,
                             TelegramClient telegramClient,
                             TelegramProperties telegramProperties,
                             MonitoringProperties monitoringProperties,
                             List<StoreMonitor> monitorList) {
        this.targetRepository = targetRepository;
        this.telegramClient = telegramClient;
        this.telegramProperties = telegramProperties;
        this.monitoringProperties = monitoringProperties;
        this.monitors = monitorList.stream()
                .collect(Collectors.toMap(
                        m -> m.storeCode().toUpperCase(Locale.ROOT),
                        Function.identity(),
                        (existing, replacement) -> existing
                ));
    }

    @PostConstruct
    public void alignTargetIntervals() {
        try {
            List<Target> all = targetRepository.findAll();
            for (Target t : all) {
                int expected = monitoringProperties.getIntervalForStore(t.getStoreCode());
                if (t.getIntervalSeconds() != expected) {
                    t.setIntervalSeconds(expected);
                    targetRepository.save(t);
                    log.info("Aligned target #{} ({}) interval to {}s", t.getId(), t.getStoreCode(), expected);
                }
            }
        } catch (Exception e) {
            log.warn("Could not align existing target intervals on startup: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 3_000)
    public void pollDueTargets() {
        Instant now = Instant.now();
        List<Target> dueTargets = targetRepository.findByActiveTrueAndNextCheckAtBefore(now);

        for (Target target : dueTargets) {
            try {
                processTarget(target);
            } catch (Exception e) {
                log.error("Failed processing target ID {}: {}", target.getId(), e.getMessage(), e);
            }

            if (monitoringProperties.getRequestSpacingMs() > 0) {
                try {
                    Thread.sleep(monitoringProperties.getRequestSpacingMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Monitoring loop sleep interrupted");
                    break;
                }
            }
        }
    }

    /**
     * Executes an immediate check for a target (used by /check command).
     */
    @Transactional
    public Snapshot checkTargetNow(Long targetId) {
        Target target = targetRepository.findById(targetId)
                .orElseThrow(() -> new IllegalArgumentException("Target not found with ID: " + targetId));
        return processTarget(target);
    }

    @Transactional
    public Snapshot processTarget(Target target) {
        StoreMonitor monitor = monitors.get(target.getStoreCode().toUpperCase(Locale.ROOT));
        Snapshot snapshot;
        if (monitor == null) {
            snapshot = Snapshot.unknown("No adapter configured for store: " + target.getStoreCode());
        } else {
            try {
                snapshot = monitor.check(target);
            } catch (Exception e) {
                log.error("Error executing monitor check for target {}: {}", target.getId(), e.getMessage(), e);
                snapshot = Snapshot.unknown("Check exception: " + e.getMessage());
            }
        }

        applyTransition(target, snapshot);

        Instant now = Instant.now();
        target.setLastCheckedAt(now);
        target.setNextCheckAt(now.plusSeconds(target.getIntervalSeconds()));
        targetRepository.save(target);

        return snapshot;
    }

    /**
     * Applies state machine rules to the target based on the snapshot.
     */
    public void applyTransition(Target target, Snapshot snapshot) {
        Availability status = snapshot.status();
        String previousStatus = target.getStatus();
        long ownerChatId = telegramProperties.getOwnerChatId();

        // 1. Handle BLOCKED / UNKNOWN
        if (status == Availability.UNKNOWN || status == Availability.BLOCKED) {
            target.setLastEvidence(snapshot.evidence());
            if (snapshot.price() != null) {
                target.setLastPrice(snapshot.price());
            }

            if (status == Availability.BLOCKED) {
                int blockedCount = consecutiveBlockedCounts.merge(target.getId(), 1, Integer::sum);
                if (blockedCount == 5 && ownerChatId != 0) {
                    telegramClient.send(ownerChatId, "⚠️ Warning: Target '" + target.getName()
                            + "' (" + target.getStoreCode() + ") has been BLOCKED for 5 consecutive checks.\nEvidence: "
                            + snapshot.evidence());
                }
            } else {
                consecutiveBlockedCounts.remove(target.getId());
            }
            return;
        }

        // Target was checked successfully, reset blocked count
        consecutiveBlockedCounts.remove(target.getId());
        target.setLastEvidence(snapshot.evidence());
        if (snapshot.price() != null) {
            target.setLastPrice(snapshot.price());
        }

        // 2. Handle IN_STOCK
        if (status == Availability.IN_STOCK) {
            target.setOutCount(0);

            if (previousStatus == null) {
                // First check
                if (monitoringProperties.isAlertOnFirstCheck() && ownerChatId != 0) {
                    Long msgId = telegramClient.send(ownerChatId, buildAlertMessage(target, snapshot.price()));
                    target.setAlertMessageId(msgId);
                }
                target.setStatus("IN_STOCK");
            } else if ("OUT_OF_STOCK".equalsIgnoreCase(previousStatus)) {
                // Restocked
                if (ownerChatId != 0) {
                    Long msgId = telegramClient.send(ownerChatId, buildAlertMessage(target, snapshot.price()));
                    target.setAlertMessageId(msgId);
                }
                target.setStatus("IN_STOCK");
            } else if ("IN_STOCK".equalsIgnoreCase(previousStatus)) {
                // Still in stock: do nothing (prevent duplicate alerts)
            }
            return;
        }

        // 3. Handle OUT_OF_STOCK
        if (status == Availability.OUT_OF_STOCK) {
            if (previousStatus == null) {
                // Initial check baseline: set status, no alert
                target.setStatus("OUT_OF_STOCK");
                target.setOutCount(0);
            } else if ("OUT_OF_STOCK".equalsIgnoreCase(previousStatus)) {
                // Still out of stock: do nothing
            } else if ("IN_STOCK".equalsIgnoreCase(previousStatus)) {
                // Possible transition to OUT_OF_STOCK
                int count = target.getOutCount() + 1;
                target.setOutCount(count);

                if (count >= monitoringProperties.getOutOfStockConfirmations()) {
                    Long alertMessageId = target.getAlertMessageId();
                    if (alertMessageId != null && ownerChatId != 0) {
                        boolean deleted = telegramClient.delete(ownerChatId, alertMessageId);
                        if (!deleted) {
                            telegramClient.edit(ownerChatId, alertMessageId, "🔴 Sold out: " + target.getName());
                        }
                        target.setAlertMessageId(null);
                    }
                    target.setStatus("OUT_OF_STOCK");
                    target.setOutCount(0);
                }
            }
        }
    }

    public String buildAlertMessage(Target target, BigDecimal price) {
        StringBuilder sb = new StringBuilder();
        sb.append("🟢 IN STOCK\n");
        sb.append(target.getName()).append("\n");

        String storeDisplayName = STORE_NAMES.getOrDefault(
                target.getStoreCode().toUpperCase(Locale.ROOT),
                target.getStoreCode()
        );
        sb.append("Store: ").append(storeDisplayName).append("\n");

        BigDecimal displayPrice = price != null ? price : target.getLastPrice();
        if (displayPrice != null) {
            sb.append("Price: ₹").append(formatPrice(displayPrice)).append("\n");
        }

        sb.append(target.getUrl());
        return sb.toString();
    }

    private String formatPrice(BigDecimal price) {
        if (price.scale() <= 0 || price.stripTrailingZeros().scale() <= 0) {
            return price.toBigInteger().toString();
        }
        return price.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    public Map<String, StoreMonitor> getMonitors() {
        return monitors;
    }
}
