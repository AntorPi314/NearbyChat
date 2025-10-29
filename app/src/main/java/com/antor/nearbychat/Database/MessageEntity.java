package com.antor.nearbychat.Database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.Index;

@Entity(tableName = "messages",
        indices = {
                @Index(value = {"senderId", "timestamp"}),
                @Index(value = {"messageId"}),
                @Index(value = {"chatType", "chatId", "timestampMillis"})
        })
public class MessageEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;
    public String chatType;
    public String chatId;
    public String senderId;
    public String message;
    public boolean isSelf;
    public String timestamp;
    public int chunkCount;
    public String messageId;
    public long timestampMillis;
    public boolean isComplete = true;
    public String missingChunksJson = "[]";

    public boolean isFailed = false;
    public boolean isSaved = false;
    public boolean isRead = true;

    public long senderTimestampBits = 0;
    public long messageTimestampBits = 0;

    public MessageEntity() {}

    @androidx.room.Ignore
    public MessageEntity(String senderId, String message, boolean isSelf,
                         String timestamp, int chunkCount, String messageId,
                         long senderTimestampBits, long messageTimestampBits) {
        this.senderId = senderId;
        this.message = message;
        this.isSelf = isSelf;
        this.timestamp = timestamp;
        this.chunkCount = chunkCount;
        this.messageId = messageId;
        this.timestampMillis = System.currentTimeMillis();
        this.isComplete = true;
        this.missingChunksJson = "[]";
        this.senderTimestampBits = senderTimestampBits;
        this.messageTimestampBits = messageTimestampBits;
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
        entity.isComplete = messageModel.isComplete();
        entity.missingChunksJson = new com.google.gson.Gson().toJson(messageModel.getMissingChunks());
        entity.senderTimestampBits = messageModel.getSenderTimestampBits();
        entity.messageTimestampBits = messageModel.getMessageTimestampBits();
        entity.chatType = messageModel.getChatType();
        entity.chatId = messageModel.getChatId();
        entity.isFailed = messageModel.isFailed();
        entity.isSaved = messageModel.isSaved();
        entity.isRead = messageModel.isRead();
        return entity;
    }

    public com.antor.nearbychat.MessageModel toMessageModel() {
        com.antor.nearbychat.MessageModel model = new com.antor.nearbychat.MessageModel(
                senderId, message, isSelf, timestamp, senderTimestampBits, messageTimestampBits
        );
        model.setChunkCount(chunkCount);
        model.setMessageId(messageId);
        model.setIsComplete(isComplete);
        try {
            java.lang.reflect.Type listType = new com.google.gson.reflect.TypeToken<java.util.List<Integer>>(){}.getType();
            java.util.List<Integer> missingChunks = new com.google.gson.Gson().fromJson(missingChunksJson, listType);
            model.setMissingChunks(missingChunks != null ? missingChunks : new java.util.ArrayList<>());
        } catch (Exception e) {
            model.setMissingChunks(new java.util.ArrayList<>());
        }
        model.setChatType(chatType);
        model.setChatId(chatId);
        model.setFailed(isFailed);
        model.setSaved(isSaved);
        model.setRead(isRead);
        return model;
    }
}