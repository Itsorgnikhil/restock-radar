package com.restockradar.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restockradar.config.AppConfig;
import com.restockradar.config.TelegramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class DefaultTelegramClient implements TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultTelegramClient.class);
    private static final String TELEGRAM_API_BASE = "https://api.telegram.org";

    private final TelegramProperties telegramProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public DefaultTelegramClient(TelegramProperties telegramProperties, ObjectMapper objectMapper) {
        this.telegramProperties = telegramProperties;
        this.objectMapper = objectMapper;

        // Long polling can wait up to 25s, so client timeout should be at least 40s
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(40));

        this.restClient = RestClient.builder()
                .baseUrl(TELEGRAM_API_BASE)
                .requestFactory(requestFactory)
                .defaultHeader("User-Agent", AppConfig.USER_AGENT)
                .build();
    }

    private String getBotPath(String method) {
        String token = telegramProperties.getBotToken();
        if (token == null || token.isBlank()) {
            return null;
        }
        return "/bot" + token + "/" + method;
    }

    @Override
    public Long send(long chatId, String text) {
        String path = getBotPath("sendMessage");
        if (path == null) {
            log.warn("Cannot send message: TELEGRAM_BOT_TOKEN is not configured");
            return null;
        }

        try {
            Map<String, Object> body = Map.of(
                    "chat_id", chatId,
                    "text", text
            );

            String response = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            if (root.path("ok").asBoolean(false)) {
                return root.path("result").path("message_id").asLong();
            } else {
                log.warn("Telegram sendMessage error: {}", root.path("description").asText());
            }
        } catch (Exception e) {
            log.error("Failed to send Telegram message to chat {}: {}", chatId, e.getMessage());
        }
        return null;
    }

    @Override
    public boolean delete(long chatId, long messageId) {
        String path = getBotPath("deleteMessage");
        if (path == null) {
            return false;
        }

        try {
            Map<String, Object> body = Map.of(
                    "chat_id", chatId,
                    "message_id", messageId
            );

            String response = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            boolean ok = root.path("ok").asBoolean(false);
            if (!ok) {
                log.info("Telegram deleteMessage failed (messageId={}): {}",
                        messageId, root.path("description").asText());
            }
            return ok && root.path("result").asBoolean(false);
        } catch (Exception e) {
            log.warn("Could not delete message {} in chat {}: {}", messageId, chatId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean edit(long chatId, long messageId, String text) {
        String path = getBotPath("editMessageText");
        if (path == null) {
            return false;
        }

        try {
            Map<String, Object> body = Map.of(
                    "chat_id", chatId,
                    "message_id", messageId,
                    "text", text
            );

            String response = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            boolean ok = root.path("ok").asBoolean(false);
            if (!ok) {
                log.warn("Telegram editMessageText failed: {}", root.path("description").asText());
            }
            return ok;
        } catch (Exception e) {
            log.warn("Could not edit message {} in chat {}: {}", messageId, chatId, e.getMessage());
            return false;
        }
    }

    @Override
    public List<TelegramUpdate> getUpdates(long offset, int timeoutSeconds) {
        String path = getBotPath("getUpdates");
        if (path == null) {
            return Collections.emptyList();
        }

        try {
            Map<String, Object> body = Map.of(
                    "offset", offset,
                    "timeout", timeoutSeconds,
                    "allowed_updates", List.of("message")
            );

            String response = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            if (!root.path("ok").asBoolean(false)) {
                log.warn("Telegram getUpdates failed: {}", root.path("description").asText());
                return Collections.emptyList();
            }

            JsonNode results = root.path("result");
            if (!results.isArray()) {
                return Collections.emptyList();
            }

            List<TelegramUpdate> updates = new ArrayList<>();
            for (JsonNode item : results) {
                long updateId = item.path("update_id").asLong();
                JsonNode message = item.path("message");
                if (message.isMissingNode()) {
                    continue;
                }
                long messageId = message.path("message_id").asLong();
                long chatId = message.path("chat").path("id").asLong();
                String text = message.path("text").asText("");
                String username = message.path("from").path("username").asText("");

                updates.add(new TelegramUpdate(updateId, messageId, chatId, text, username));
            }
            return updates;
        } catch (Exception e) {
            log.warn("getUpdates call failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
