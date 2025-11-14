package com.antor.nearbychat.Database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface MessageDao {

    @Query("UPDATE messages SET message = :newMessage, isComplete = 1, isFailed = 0 WHERE " +
            "senderId = :senderId AND messageId = :messageId AND timestamp = :timestamp")
    void updateMessageContent(String senderId, String messageId, String timestamp, String newMessage);

    @Query("DELETE FROM messages WHERE chatType = :chatType AND chatId = :chatId")
    void deleteMessagesForChat(String chatType, String chatId);

    @Query("SELECT * FROM messages WHERE chatType = :chatType AND chatId = :chatId ORDER BY timestampMillis DESC LIMIT 1")
    MessageEntity getLastMessageForChat(String chatType, String chatId);

    @Query("SELECT * FROM messages WHERE " +
            "chatType = :chatType AND chatId = :chatId " +
            "ORDER BY timestampMillis ASC")
    LiveData<List<MessageEntity>> getMessagesForChat(String chatType, String chatId);

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

    @Query("SELECT * FROM messages WHERE message LIKE :searchQuery || '%' ORDER BY timestampMillis DESC LIMIT 500")
    LiveData<List<MessageEntity>> searchMessages(String searchQuery);

    @Query("SELECT * FROM messages WHERE " +
            "chatType = :chatType AND " +
            "chatId = :chatId AND " +
            "message LIKE :searchQuery || '%' " +
            "ORDER BY timestampMillis ASC LIMIT 500")
    LiveData<List<MessageEntity>> searchMessagesInChat(String chatType, String chatId, String searchQuery);

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

    @Query("SELECT COUNT(*) FROM messages WHERE chatType = :chatType AND chatId = :chatId AND isRead = 0 AND isSelf = 0")
    int getUnreadMessageCountForChat(String chatType, String chatId);

    @Query("UPDATE messages SET isRead = 1 WHERE chatType = :chatType AND chatId = :chatId AND isRead = 0")
    void markMessagesAsRead(String chatType, String chatId);

    @Query("SELECT COUNT(DISTINCT (chatType || chatId)) FROM messages WHERE isRead = 0 AND isSelf = 0")
    LiveData<Integer> getTotalUnreadMessageCount();

    @Query("SELECT * FROM messages WHERE senderId = :senderId AND messageId = :messageId AND timestamp = :timestamp LIMIT 1")
    MessageEntity getMessageEntity(String senderId, String messageId, String timestamp);
}