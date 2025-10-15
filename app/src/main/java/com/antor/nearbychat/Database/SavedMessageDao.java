package com.antor.nearbychat.Database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface SavedMessageDao {

    @Insert
    void insertSavedMessage(SavedMessageEntity message);

    @Query("SELECT * FROM saved_messages ORDER BY savedTimestamp ASC")
    LiveData<List<SavedMessageEntity>> getAllSavedMessages();

    @Query("DELETE FROM saved_messages WHERE senderId = :senderId AND messageId = :messageId AND timestamp = :timestamp")
    void deleteSavedMessage(String senderId, String messageId, String timestamp);

    @Query("SELECT COUNT(*) FROM saved_messages WHERE senderId = :senderId AND messageId = :messageId AND timestamp = :timestamp")
    int savedMessageExists(String senderId, String messageId, String timestamp);

    @Query("SELECT COUNT(*) FROM saved_messages")
    int getSavedMessageCount();
}