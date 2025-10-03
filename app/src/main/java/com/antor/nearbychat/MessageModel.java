package com.antor.nearbychat;

import java.util.ArrayList;
import java.util.List;

public class MessageModel {
    private String senderId;
    private String message;
    private boolean isSelf;
    private String timestamp;
    private String messageId;
    private String chatType;
    private String chatId;

    // --- FIXED: Added chunkCount ---
    private int chunkCount = 1;

    // Fields for new features
    private boolean isComplete = true;
    private List<Integer> missingChunks = new ArrayList<>();

    // Timestamp bits for reconstruction and identification
    private long senderTimestampBits = 0;
    private long messageTimestampBits = 0;

    // Default constructor
    public MessageModel() {}

    public MessageModel(String senderId, String message, boolean isSelf, String timestamp) {
        this.senderId = senderId;
        this.message = message;
        this.isSelf = isSelf;
        this.timestamp = timestamp;
    }

    public MessageModel(String senderId, String message, boolean isSelf, String timestamp,
                        long senderTimestampBits, long messageTimestampBits) {
        this.senderId = senderId;
        this.message = message;
        this.isSelf = isSelf;
        this.isSelf = isSelf;
        this.timestamp = timestamp;
        this.senderTimestampBits = senderTimestampBits;
        this.messageTimestampBits = messageTimestampBits;
    }

    // --- Getters and Setters ---

    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public boolean isSelf() { return isSelf; }
    public void setSelf(boolean self) { isSelf = self; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getChatType() { return chatType; }
    public void setChatType(String chatType) { this.chatType = chatType; }

    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }

    public boolean isComplete() { return isComplete; }
    public void setIsComplete(boolean complete) { isComplete = complete; }

    public List<Integer> getMissingChunks() { return missingChunks; }
    public void setMissingChunks(List<Integer> missingChunks) { this.missingChunks = missingChunks; }

    public long getSenderTimestampBits() { return senderTimestampBits; }
    public void setSenderTimestampBits(long senderTimestampBits) { this.senderTimestampBits = senderTimestampBits; }

    public long getMessageTimestampBits() { return messageTimestampBits; }
    public void setMessageTimestampBits(long messageTimestampBits) { this.messageTimestampBits = messageTimestampBits; }

    // --- FIXED: Added getter and setter for chunkCount ---
    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }
}