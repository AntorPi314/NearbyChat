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
    private boolean isRead = true;
    private boolean isFailed = false;
    private boolean isSaved = false;

    private int chunkCount = 1;

    private boolean isComplete = true;
    private List<Integer> missingChunks = new ArrayList<>();

    private long senderTimestampBits = 0;
    private long messageTimestampBits = 0;

    public MessageModel() {}

    private String replyToUserId = "";
    private String replyToMessageId = "";
    private String replyToMessagePreview = "";
    private boolean isAcknowledged = false;

    public boolean isAcknowledged() { return isAcknowledged; }
    public void setAcknowledged(boolean acknowledged) { isAcknowledged = acknowledged; }

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

    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }

    public boolean isFailed() { return isFailed; }
    public void setFailed(boolean failed) { isFailed = failed; }

    public boolean isRead() { return isRead; }
    public void setRead(boolean read) { isRead = read; }

    public boolean isSaved() { return isSaved; }
    public void setSaved(boolean saved) { isSaved = saved; }

    public String getReplyToUserId() { return replyToUserId; }
    public void setReplyToUserId(String replyToUserId) { this.replyToUserId = replyToUserId; }

    public String getReplyToMessageId() { return replyToMessageId; }
    public void setReplyToMessageId(String replyToMessageId) { this.replyToMessageId = replyToMessageId; }

    public String getReplyToMessagePreview() { return replyToMessagePreview; }
    public void setReplyToMessagePreview(String preview) { this.replyToMessagePreview = preview; }

    public boolean isReply() {
        return replyToUserId != null && !replyToUserId.isEmpty();
    }
}