package com.restockradar.telegram;

import com.restockradar.config.TelegramProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class UpdatePoller {

    private static final Logger log = LoggerFactory.getLogger(UpdatePoller.class);

    private final TelegramClient telegramClient;
    private final BotCommandHandler commandHandler;
    private final TelegramProperties telegramProperties;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService pollerExecutor;
    private long offset = 0L;

    public UpdatePoller(TelegramClient telegramClient,
                        BotCommandHandler commandHandler,
                        TelegramProperties telegramProperties) {
        this.telegramClient = telegramClient;
        this.commandHandler = commandHandler;
        this.telegramProperties = telegramProperties;
    }

    @PostConstruct
    public synchronized void start() {
        String token = telegramProperties.getBotToken();
        if (token == null || token.isBlank()) {
            log.info("Telegram bot token is not configured. Long polling is disabled.");
            return;
        }

        running.set(true);
        pollerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "telegram-poller");
            thread.setDaemon(true);
            return thread;
        });

        pollerExecutor.submit(this::pollLoop);
        log.info("Telegram long poller started for owner chat ID: {}", telegramProperties.getOwnerChatId());
    }

    private void pollLoop() {
        int consecutiveErrors = 0;
        int timeoutSeconds = telegramProperties.getPollTimeoutSeconds();

        while (running.get()) {
            try {
                List<TelegramUpdate> updates = telegramClient.getUpdates(offset, timeoutSeconds);
                consecutiveErrors = 0;

                for (TelegramUpdate update : updates) {
                    if (update.updateId() >= offset) {
                        offset = update.updateId() + 1;
                    }
                    try {
                        commandHandler.handleUpdate(update);
                    } catch (Exception e) {
                        log.error("Error processing update #{}: {}", update.updateId(), e.getMessage(), e);
                    }
                }
            } catch (Exception e) {
                consecutiveErrors++;
                long backoffMs = Math.min(30_000L, (long) Math.pow(2, Math.min(consecutiveErrors, 5)) * 1000L);
                log.warn("Telegram polling error (attempt {}): {}. Backing off for {} ms",
                        consecutiveErrors, e.getMessage(), backoffMs);

                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    if (!running.get()) {
                        break;
                    }
                }
            }
        }
        log.info("Telegram update poller loop exited.");
    }

    @PreDestroy
    public synchronized void stop() {
        running.set(false);
        if (pollerExecutor != null) {
            pollerExecutor.shutdownNow();
            try {
                if (!pollerExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    log.warn("Telegram poller did not terminate within timeout.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
