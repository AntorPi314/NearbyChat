package com.antor.nearbychat;

import java.util.ArrayList;
import java.util.List;

public class MessageModel {
    private String senderId;
    private String message;
    private boolean isSelf;
    private String timestamp;
    private int chunkCount = 1;
    private String messageId;
    private boolean isComplete = true;
    private List<Integer> missingChunks = new ArrayList<>();

    public MessageModel(String senderId, String message, boolean isSelf, String timestamp) {
        this.senderId = senderId;
        this.message = message;
        this.isSelf = isSelf;
        this.timestamp = timestamp;
        this.messageId = extractMessageIdFromTimestamp(timestamp);
    }

    // Existing getters and setters...
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getSenderId() { return senderId; }
    public String getMessage() { return message; }
    public boolean isSelf() { return isSelf; }
    public String getTimestamp() { return timestamp; }
    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }

    // New methods for incomplete messages
    public boolean isComplete() { return isComplete; }
    public void setIsComplete(boolean isComplete) { this.isComplete = isComplete; }
    public List<Integer> getMissingChunks() { return missingChunks; }
    public void setMissingChunks(List<Integer> missingChunks) { this.missingChunks = missingChunks; }

    private String extractMessageIdFromTimestamp(String timestamp) {
        return null;
    }
}