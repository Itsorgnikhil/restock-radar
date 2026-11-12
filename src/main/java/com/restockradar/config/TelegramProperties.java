package com.restockradar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "telegram")
public class TelegramProperties {

    private String botToken = "";
    private long ownerChatId = 0L;
    private int pollTimeoutSeconds = 25;

    public String getBotToken() {
        return botToken;
    }

    public void setBotToken(String botToken) {
        this.botToken = botToken;
    }

    public long getOwnerChatId() {
        return ownerChatId;
    }

    public void setOwnerChatId(long ownerChatId) {
        this.ownerChatId = ownerChatId;
    }

    public int getPollTimeoutSeconds() {
        return pollTimeoutSeconds;
    }

    public void setPollTimeoutSeconds(int pollTimeoutSeconds) {
        this.pollTimeoutSeconds = pollTimeoutSeconds;
    }
}
