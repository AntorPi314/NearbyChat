package com.antor.nearbychat.Database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Delete;

import java.util.List;

@Dao
public interface MessageDao {

    @Insert
    void insertMessage(com.antor.nearbychat.Database.MessageEntity message);

    @Query("DELETE FROM messages WHERE senderId = :senderId AND messageId = :messageId")
    void deletePartialMessage(String senderId, String messageId);

    @Query("SELECT COUNT(*) FROM messages WHERE senderId = :senderId AND messageId = :messageId AND isComplete = 0")
    int partialMessageExists(String senderId, String messageId);

    @Query("UPDATE messages SET message = :newMessage WHERE senderId = :senderId AND messageId = :messageId AND isComplete = 0")
    void updatePartialMessage(String senderId, String messageId, String newMessage);

    @Query("DELETE FROM messages WHERE senderId = :senderId AND messageId = :messageId AND timestamp = :timestamp")
    void deleteMessage(String senderId, String messageId, String timestamp);



    @Query("SELECT * FROM messages ORDER BY timestampMillis ASC")
    LiveData<List<MessageEntity>> getAllMessagesLive();

    @Query("SELECT * FROM messages ORDER BY timestampMillis ASC")
    List<MessageEntity> getAllMessages();

    @Query("SELECT * FROM messages WHERE message LIKE '%' || :searchQuery || '%' ORDER BY timestampMillis DESC")
    LiveData<List<MessageEntity>> searchMessages(String searchQuery);

    @Query("SELECT * FROM messages WHERE senderId = :senderId ORDER BY timestampMillis ASC")
    LiveData<List<MessageEntity>> getMessagesBySender(String senderId);

    @Query("SELECT COUNT(*) FROM messages")
    int getMessageCount();

    @Query("DELETE FROM messages WHERE id NOT IN (SELECT id FROM messages ORDER BY timestampMillis DESC LIMIT :limit)")
    void deleteOldMessages(int limit);

    @Query("SELECT COUNT(*) FROM messages WHERE senderId = :senderId AND message = :message AND timestamp = :timestamp")
    int messageExists(String senderId, String message, String timestamp);

    @Query("DELETE FROM messages")
    void deleteAllMessages();

    @Query("SELECT DISTINCT senderId FROM messages WHERE senderId != :currentUserId ORDER BY timestampMillis DESC LIMIT 50")
    List<String> getRecentSenders(String currentUserId);
}