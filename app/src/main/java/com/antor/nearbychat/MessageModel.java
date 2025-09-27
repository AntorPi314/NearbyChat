package com.antor.nearbychat;

public class MessageModel {
    private String senderId;
    private String message;
    private boolean isSelf;
    private String timestamp;
    private int chunkCount = 1;
    private String messageId;

    public MessageModel(String senderId, String message, boolean isSelf, String timestamp) {
        this.senderId = senderId;
        this.message = message;
        this.isSelf = isSelf;
        this.timestamp = timestamp;
        this.messageId = extractMessageIdFromTimestamp(timestamp);
    }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    private String extractMessageIdFromTimestamp(String timestamp) {
        // If timestamp format is "hh:mm a | dd-MM-yyyy | 1C"
        // You could store message ID in timestamp or generate from timestamp
        // For now, return null and handle in service
        return null;
    }

    public String getSenderId() {
        return senderId;
    }

    public String getMessage() {
        return message;
    }

    public boolean isSelf() {
        return isSelf;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
    }
}