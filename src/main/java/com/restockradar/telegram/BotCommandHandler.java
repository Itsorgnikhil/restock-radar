package com.restockradar.telegram;

import com.restockradar.config.MonitoringProperties;
import com.restockradar.config.TelegramProperties;
import com.restockradar.monitoring.MonitoringService;
import com.restockradar.monitoring.Snapshot;
import com.restockradar.monitoring.StoreMonitor;
import com.restockradar.monitoring.adapters.GamesTheShopMonitor;
import com.restockradar.target.Target;
import com.restockradar.target.TargetRepository;
import com.restockradar.util.UrlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
public class BotCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(BotCommandHandler.class);
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final TargetRepository targetRepository;
    private final MonitoringService monitoringService;
    private final TelegramClient telegramClient;
    private final TelegramProperties telegramProperties;
    private final MonitoringProperties monitoringProperties;

    public BotCommandHandler(TargetRepository targetRepository,
                             MonitoringService monitoringService,
                             TelegramClient telegramClient,
                             TelegramProperties telegramProperties,
                             MonitoringProperties monitoringProperties) {
        this.targetRepository = targetRepository;
        this.monitoringService = monitoringService;
        this.telegramClient = telegramClient;
        this.telegramProperties = telegramProperties;
        this.monitoringProperties = monitoringProperties;
    }

    public void handleUpdate(TelegramUpdate update) {
        long ownerChatId = telegramProperties.getOwnerChatId();
        if (ownerChatId == 0 || update.chatId() != ownerChatId) {
            log.warn("Ignored message from unauthorized chat_id: {}", update.chatId());
            return;
        }

        String rawText = update.text();
        if (rawText == null || rawText.isBlank()) {
            return;
        }

        String text = rawText.trim();
        String reply = processCommand(text);
        if (reply != null && !reply.isBlank()) {
            telegramClient.send(ownerChatId, reply);
        }
    }

    public String processCommand(String text) {
        String[] parts = text.split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) {
            return null;
        }

        String command = parts[0].toLowerCase(Locale.ROOT);
        if (command.contains("@")) {
            command = command.substring(0, command.indexOf('@'));
        }

        return switch (command) {
            case "/start", "/help" -> handleHelp();
            case "/add" -> handleAdd(parts);
            case "/list" -> handleList();
            case "/status" -> handleStatus(parts);
            case "/check" -> handleCheck(parts);
            case "/pause" -> handlePause(parts);
            case "/resume" -> handleResume(parts);
            case "/remove" -> handleRemove(parts);
            case "/interval" -> handleInterval(parts);
            case "/stores" -> handleStores();
            default -> "Unknown command: " + command + "\nType /help to see all available commands.";
        };
    }

    private String handleHelp() {
        return """
                📡 *Restock Radar Bot Commands*
                
                /add <STORE_CODE> <url> <name...> - Add a product to monitor
                /list - Show all configured targets
                /status <id> - Show detailed check status and evidence
                /check <id> - Run immediate check on a target
                /pause <id> - Pause monitoring for a target
                /resume <id> - Resume monitoring for a target
                /remove <id> - Deactivate target and clean up alerts
                /interval <id> <seconds> - Set check interval (min 10s)
                /stores - List supported store codes
                /help - Show this help message
                """;
    }

    private String handleAdd(String[] parts) {
        if (parts.length < 4) {
            return "Usage: /add <STORE_CODE> <url> <name...>\n" +
                   "Example: /add GAMES_THE_SHOP https://www.gamestheshop.com/product/... PlayStation Card";
        }

        String storeCode = parts[1].toUpperCase(Locale.ROOT);
        String url = parts[2];
        String name = String.join(" ", Arrays.copyOfRange(parts, 3, parts.length)).trim();

        if (!UrlValidator.ALLOWED_DOMAINS.containsKey(storeCode)) {
            return "❌ Unsupported store code: " + storeCode + "\nUse /stores to list supported stores.";
        }

        try {
            UrlValidator.validate(storeCode, url);
        } catch (IllegalArgumentException e) {
            return "❌ URL validation failed: " + e.getMessage();
        }

        int interval = monitoringProperties.getIntervalForStore(storeCode);
        Target target = new Target(storeCode, url, name, interval);
        Target saved = targetRepository.save(target);

        return "✅ Target #" + saved.getId() + " added successfully!\n"
                + "Name: " + saved.getName() + "\n"
                + "Store: " + saved.getStoreCode() + "\n"
                + "Interval: " + saved.getIntervalSeconds() + "s";
    }

    private String handleList() {
        List<Target> targets = targetRepository.findAllByOrderByIdAsc();
        if (targets.isEmpty()) {
            return "No targets configured yet. Use /add to add your first product.";
        }

        StringBuilder sb = new StringBuilder("📋 *Monitored Targets:*\n\n");
        for (Target t : targets) {
            sb.append("#").append(t.getId()).append(" ")
                    .append(t.isActive() ? "🟢" : "⏸️").append(" ")
                    .append(t.getName()).append("\n")
                    .append("Store: ").append(t.getStoreCode()).append(" | ")
                    .append("Status: ").append(t.getStatus() != null ? t.getStatus() : "NEVER_CHECKED").append("\n");

            if (t.getLastPrice() != null) {
                sb.append("Price: ₹").append(t.getLastPrice()).append(" | ");
            }

            String lastChecked = t.getLastCheckedAt() != null
                    ? DATE_FORMAT.format(t.getLastCheckedAt())
                    : "Never";
            sb.append("Last checked: ").append(lastChecked).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String handleStatus(String[] parts) {
        if (parts.length < 2) {
            return "Usage: /status <id>";
        }

        Long id = parseId(parts[1]);
        if (id == null) {
            return "❌ Invalid target ID: " + parts[1];
        }

        Optional<Target> opt = targetRepository.findById(id);
        if (opt.isEmpty()) {
            return "❌ Target #" + id + " not found.";
        }

        Target t = opt.get();
        return "🔍 *Target #" + t.getId() + " Details:*\n"
                + "Name: " + t.getName() + "\n"
                + "Store: " + t.getStoreCode() + "\n"
                + "Active: " + (t.isActive() ? "Yes" : "Paused") + "\n"
                + "Status: " + (t.getStatus() != null ? t.getStatus() : "NEVER_CHECKED") + "\n"
                + "Price: " + (t.getLastPrice() != null ? "₹" + t.getLastPrice() : "N/A") + "\n"
                + "Interval: " + t.getIntervalSeconds() + "s\n"
                + "Out count: " + t.getOutCount() + "\n"
                + "Alert Message ID: " + (t.getAlertMessageId() != null ? t.getAlertMessageId() : "None") + "\n"
                + "Last checked: " + (t.getLastCheckedAt() != null ? DATE_FORMAT.format(t.getLastCheckedAt()) : "Never") + "\n"
                + "Next check: " + (t.getNextCheckAt() != null ? DATE_FORMAT.format(t.getNextCheckAt()) : "N/A") + "\n"
                + "Last evidence: " + (t.getLastEvidence() != null ? t.getLastEvidence() : "N/A") + "\n"
                + "URL: " + t.getUrl();
    }

    private String handleCheck(String[] parts) {
        if (parts.length < 2) {
            return "Usage: /check <id>";
        }

        Long id = parseId(parts[1]);
        if (id == null) {
            return "❌ Invalid target ID: " + parts[1];
        }

        try {
            Snapshot snapshot = monitoringService.checkTargetNow(id);
            Target t = targetRepository.findById(id).orElseThrow();
            return "🔍 Check complete for #" + id + " (" + t.getName() + "):\n"
                    + "Status: " + snapshot.status() + "\n"
                    + "Price: " + (snapshot.price() != null ? "₹" + snapshot.price() : "N/A") + "\n"
                    + "Evidence: " + snapshot.evidence();
        } catch (IllegalArgumentException e) {
            return "❌ " + e.getMessage();
        } catch (Exception e) {
            return "❌ Error checking target #" + id + ": " + e.getMessage();
        }
    }

    private String handlePause(String[] parts) {
        if (parts.length < 2) {
            return "Usage: /pause <id>";
        }

        Long id = parseId(parts[1]);
        if (id == null) {
            return "❌ Invalid target ID: " + parts[1];
        }

        Optional<Target> opt = targetRepository.findById(id);
        if (opt.isEmpty()) {
            return "❌ Target #" + id + " not found.";
        }

        Target t = opt.get();
        t.setActive(false);
        targetRepository.save(t);
        return "⏸️ Target #" + id + " (" + t.getName() + ") has been paused.";
    }

    private String handleResume(String[] parts) {
        if (parts.length < 2) {
            return "Usage: /resume <id>";
        }

        Long id = parseId(parts[1]);
        if (id == null) {
            return "❌ Invalid target ID: " + parts[1];
        }

        Optional<Target> opt = targetRepository.findById(id);
        if (opt.isEmpty()) {
            return "❌ Target #" + id + " not found.";
        }

        Target t = opt.get();
        t.setActive(true);
        t.setNextCheckAt(java.time.Instant.now());
        targetRepository.save(t);
        return "▶️ Target #" + id + " (" + t.getName() + ") resumed. Scheduled for immediate check.";
    }

    private String handleRemove(String[] parts) {
        if (parts.length < 2) {
            return "Usage: /remove <id>";
        }

        Long id = parseId(parts[1]);
        if (id == null) {
            return "❌ Invalid target ID: " + parts[1];
        }

        Optional<Target> opt = targetRepository.findById(id);
        if (opt.isEmpty()) {
            return "❌ Target #" + id + " not found.";
        }

        Target t = opt.get();
        long ownerChatId = telegramProperties.getOwnerChatId();
        if (t.getAlertMessageId() != null && ownerChatId != 0) {
            telegramClient.delete(ownerChatId, t.getAlertMessageId());
        }

        t.setActive(false);
        t.setAlertMessageId(null);
        targetRepository.save(t);

        return "🗑️ Target #" + id + " (" + t.getName() + ") deactivated and alerts cleared.";
    }

    private String handleInterval(String[] parts) {
        if (parts.length < 3) {
            return "Usage: /interval <id> <seconds>";
        }

        Long id = parseId(parts[1]);
        if (id == null) {
            return "❌ Invalid target ID: " + parts[1];
        }

        int seconds;
        try {
            seconds = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return "❌ Invalid seconds: " + parts[2];
        }

        int minSeconds = monitoringProperties.getMinIntervalSeconds();
        if (seconds < minSeconds) {
            return "❌ Interval cannot be less than " + minSeconds + " seconds.";
        }

        Optional<Target> opt = targetRepository.findById(id);
        if (opt.isEmpty()) {
            return "❌ Target #" + id + " not found.";
        }

        Target t = opt.get();
        t.setIntervalSeconds(seconds);
        targetRepository.save(t);
        return "⏱️ Target #" + id + " (" + t.getName() + ") interval set to " + seconds + " seconds.";
    }

    private String handleStores() {
        StringBuilder sb = new StringBuilder("🏪 *Supported Store Adapters:*\n\n");
        for (String storeCode : UrlValidator.ALLOWED_DOMAINS.keySet()) {
            StoreMonitor monitor = monitoringService.getMonitors().get(storeCode);
            boolean isImplemented = monitor != null;
            sb.append("• `").append(storeCode).append("`: ")
                    .append(isImplemented ? "✅ Implemented" : "⏳ Stub")
                    .append("\n");
        }
        return sb.toString();
    }

    private Long parseId(String s) {
        try {
            return Long.parseLong(s.replace("#", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
