package com.antor.nearbychat;

import java.io.Serializable;

public class GroupModel implements Serializable {
    private String id; // 5-character ASCII ID
    private String name;
    private String encryptionKey;
    // You can add a field for profile picture if needed

    public GroupModel(String id, String name, String encryptionKey) {
        this.id = id;
        this.name = name;
        this.encryptionKey = encryptionKey;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEncryptionKey() { return encryptionKey; }
    public void setEncryptionKey(String encryptionKey) { this.encryptionKey = encryptionKey; }
}