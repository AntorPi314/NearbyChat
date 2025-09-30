// Updated MessageModel.java

package com.antor.nearbychat;

import java.util.ArrayList;
import java.util.List;

public class MessageModel {
    private String senderId; // 8-character display format
    private String message;
    private boolean isSelf;
    private String timestamp;
    private int chunkCount = 1;
    private String messageId; // 8-character display format
    private boolean isComplete = true;
    private List<Integer> missingChunks = new ArrayList<>();

    private String chatType; // "N", "G", or "F"
    private String chatId;   // group_id or friend_id (5-char ASCI

    // Store timestamp bits for reconstruction
    private long senderTimestampBits = 0;
    private long messageTimestampBits = 0;

    public MessageModel(String senderId, String message, boolean isSelf, String timestamp) {
        this.senderId = senderId;
        this.message = message;
        this.isSelf = isSelf;
        this.timestamp = timestamp;
    }

    // New constructor with timestamp bits
    public MessageModel(String senderId, String message, boolean isSelf, String timestamp,
                        long senderTimestampBits, long messageTimestampBits) {
        this.senderId = senderId;
        this.message = message;
        this.isSelf = isSelf;
        this.timestamp = timestamp;
        this.senderTimestampBits = senderTimestampBits;
        this.messageTimestampBits = messageTimestampBits;
    }

    // Existing getters and setters
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getSenderId() { return senderId; }
    public String getMessage() { return message; }
    public boolean isSelf() { return isSelf; }
    public String getTimestamp() { return timestamp; }
    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }
    public boolean isComplete() { return isComplete; }
    public void setIsComplete(boolean isComplete) { this.isComplete = isComplete; }
    public List<Integer> getMissingChunks() { return missingChunks; }
    public void setMissingChunks(List<Integer> missingChunks) { this.missingChunks = missingChunks; }

    // New getters/setters for timestamp bits
    public long getSenderTimestampBits() { return senderTimestampBits; }
    public void setSenderTimestampBits(long bits) { this.senderTimestampBits = bits; }
    public long getMessageTimestampBits() { return messageTimestampBits; }
    public void setMessageTimestampBits(long bits) { this.messageTimestampBits = bits; }

    private String extractMessageIdFromTimestamp(String timestamp) {
        return null;
    }

    public String getChatType() { return chatType; }
    public void setChatType(String chatType) { this.chatType = chatType; }
    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
}