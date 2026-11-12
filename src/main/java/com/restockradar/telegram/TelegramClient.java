package com.restockradar.telegram;

import java.util.List;

public interface TelegramClient {

    /**
     * Sends a text message to the specified chat.
     * @return The message_id of the sent message, or null if sending failed.
     */
    Long send(long chatId, String text);

    /**
     * Deletes a message from the specified chat.
     * @return true if successfully deleted, false otherwise.
     */
    boolean delete(long chatId, long messageId);

    /**
     * Edits the text of an existing message.
     * @return true if successfully edited, false otherwise.
     */
    boolean edit(long chatId, long messageId, String text);

    /**
     * Retrieves updates with long polling.
     * @param offset The update offset (highest update_id + 1).
     * @param timeoutSeconds Long poll timeout.
     * @return List of updates.
     */
    List<TelegramUpdate> getUpdates(long offset, int timeoutSeconds);
}
