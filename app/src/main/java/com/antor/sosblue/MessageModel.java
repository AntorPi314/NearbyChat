package com.antor.sosblue;

public class MessageModel {
    private String senderId;
    private String message;
    private boolean isSelf;
    private String timestamp; // নতুন ফিল্ড

    public MessageModel(String senderId, String message, boolean isSelf, String timestamp) {
        this.senderId = senderId;
        this.message = message;
        this.isSelf = isSelf;
        this.timestamp = timestamp;
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
}
