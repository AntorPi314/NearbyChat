package com.antor.nearbychat.Database;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = {MessageEntity.class},
        version = 1,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    public abstract MessageDao messageDao();
    private static volatile AppDatabase INSTANCE;

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "nearby_chat_database"
                            )
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }

    public static void cleanupOldMessages(Context context, int maxMessages) {
        new Thread(() -> {
            try {
                MessageDao dao = getInstance(context).messageDao();
                int currentCount = dao.getMessageCount();
                if (currentCount > maxMessages) {
                    dao.deleteOldMessages(maxMessages);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}