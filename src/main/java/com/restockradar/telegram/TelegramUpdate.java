package com.restockradar.telegram;

public record TelegramUpdate(
        long updateId,
        long messageId,
        long chatId,
        String text,
        String fromUsername
) {
}
