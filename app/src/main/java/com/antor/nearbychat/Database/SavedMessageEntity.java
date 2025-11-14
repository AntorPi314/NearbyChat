package com.antor.nearbychat.Database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "saved_messages")
public class SavedMessageEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public String chatType;
    public String chatId;
    public String senderId;
    public String message;
    public boolean isSelf;
    public String timestamp;
    public String messageId;
    public long savedTimestamp;
    public long originalTimestampMillis;
    public long senderTimestampBits = 0;
    public long messageTimestampBits = 0;

    public SavedMessageEntity() {}

    public static SavedMessageEntity fromMessageEntity(MessageEntity entity) {
        SavedMessageEntity saved = new SavedMessageEntity();
        saved.chatType = entity.chatType;
        saved.chatId = entity.chatId;
        saved.senderId = entity.senderId;
        saved.message = entity.message;
        saved.isSelf = entity.isSelf;
        saved.timestamp = entity.timestamp;
        saved.messageId = entity.messageId;
        saved.savedTimestamp = System.currentTimeMillis();
        saved.originalTimestampMillis = entity.timestampMillis;

        saved.senderTimestampBits = entity.senderTimestampBits;
        saved.messageTimestampBits = entity.messageTimestampBits;
        return saved;
    }

    public com.antor.nearbychat.MessageModel toMessageModel() {
        com.antor.nearbychat.MessageModel model = new com.antor.nearbychat.MessageModel(
                senderId,
                message,
                isSelf,
                timestamp,
                senderTimestampBits,
                messageTimestampBits
        );
        model.setMessageId(messageId);
        model.setChatType(chatType);
        model.setChatId(chatId);
        model.setSaved(true);
        return model;
    }
}