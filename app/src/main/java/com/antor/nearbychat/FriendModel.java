package com.antor.nearbychat;

import java.io.Serializable;

public class FriendModel implements Serializable {
    private String displayId; // 8-character display ID
    private String name;

    public FriendModel(String displayId, String name) {
        this.displayId = displayId;
        this.name = name;
    }

    public String getDisplayId() { return displayId; }
    public void setDisplayId(String displayId) { this.displayId = displayId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}