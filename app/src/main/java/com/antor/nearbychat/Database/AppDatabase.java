package com.antor.nearbychat.Database;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {MessageEntity.class},
        version = 4,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    public abstract MessageDao messageDao();
    private static volatile AppDatabase INSTANCE;

    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE messages ADD COLUMN senderTimestampBits INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE messages ADD COLUMN messageTimestampBits INTEGER NOT NULL DEFAULT 0");
        }
    };

    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE messages ADD COLUMN chatType TEXT");
            database.execSQL("ALTER TABLE messages ADD COLUMN chatId TEXT");
        }
    };

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "nearby_chat_database"
                            )
                            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
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