package com.antor.sosblue;

public class MessageModel {
    private String senderId;
    private String message;
    private boolean isSelf;

    public MessageModel(String senderId, String message, boolean isSelf) {
        this.senderId = senderId;
        this.message = message;
        this.isSelf = isSelf;
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
}
