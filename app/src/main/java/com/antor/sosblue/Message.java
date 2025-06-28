package com.antor.sosblue;

class Message {
    private String sender;
    private String content;
    private boolean isSent;

    public Message(String sender, String content, boolean isSent) {
        this.sender = sender;
        this.content = content;
        this.isSent = isSent;
    }

    public String getSender() {
        return sender;
    }

    public String getContent() {
        return content;
    }

    public boolean isSent() {
        return isSent;
    }
}
