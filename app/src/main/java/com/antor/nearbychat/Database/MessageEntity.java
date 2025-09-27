package com.antor.nearbychat.Database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.Index;

@Entity(tableName = "messages",
        indices = {@Index(value = {"senderId", "timestamp"}),
                @Index(value = {"messageId"})})
public class MessageEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public String senderId;
    public String message;
    public boolean isSelf;
    public String timestamp;
    public int chunkCount;
    public String messageId;
    public long timestampMillis;

    public MessageEntity() {}

    @androidx.room.Ignore
    public MessageEntity(String senderId, String message, boolean isSelf,
                         String timestamp, int chunkCount, String messageId) {
        this.senderId = senderId;
        this.message = message;
        this.isSelf = isSelf;
        this.timestamp = timestamp;
        this.chunkCount = chunkCount;
        this.messageId = messageId;
        this.timestampMillis = System.currentTimeMillis();
    }

    public static MessageEntity fromMessageModel(com.antor.nearbychat.MessageModel messageModel) {
        MessageEntity entity = new MessageEntity();
        entity.senderId = messageModel.getSenderId();
        entity.message = messageModel.getMessage();
        entity.isSelf = messageModel.isSelf();
        entity.timestamp = messageModel.getTimestamp();
        entity.chunkCount = messageModel.getChunkCount();
        entity.messageId = messageModel.getMessageId();
        entity.timestampMillis = System.currentTimeMillis();
        return entity;
    }

    public com.antor.nearbychat.MessageModel toMessageModel() {
        com.antor.nearbychat.MessageModel model = new com.antor.nearbychat.MessageModel(
                senderId, message, isSelf, timestamp
        );
        model.setChunkCount(chunkCount);
        model.setMessageId(messageId);
        return model;
    }
}