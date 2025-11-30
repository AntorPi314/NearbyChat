package com.antor.nearbychat.Database;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {MessageEntity.class, SavedMessageEntity.class},
        version = 14,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    public abstract MessageDao messageDao();
    public abstract SavedMessageDao savedMessageDao();
    private static volatile AppDatabase INSTANCE;

    static final Migration MIGRATION_13_14 = new Migration(13, 14) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE messages ADD COLUMN isAcknowledged INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE saved_messages ADD COLUMN isAcknowledged INTEGER NOT NULL DEFAULT 0");
        }
    };

    static final Migration MIGRATION_10_11 = new Migration(10, 11) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE INDEX IF NOT EXISTS index_messages_message ON messages(message)");
        }
    };

    static final Migration MIGRATION_12_13 = new Migration(12, 13) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE messages ADD COLUMN replyToUserId TEXT NOT NULL DEFAULT ''");
            database.execSQL("ALTER TABLE messages ADD COLUMN replyToMessageId TEXT NOT NULL DEFAULT ''");
            database.execSQL("ALTER TABLE messages ADD COLUMN replyToMessagePreview TEXT NOT NULL DEFAULT ''");

            database.execSQL("ALTER TABLE saved_messages ADD COLUMN replyToUserId TEXT NOT NULL DEFAULT ''");
            database.execSQL("ALTER TABLE saved_messages ADD COLUMN replyToMessageId TEXT NOT NULL DEFAULT ''");
            database.execSQL("ALTER TABLE saved_messages ADD COLUMN replyToMessagePreview TEXT NOT NULL DEFAULT ''");
        }
    };

    static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE messages ADD COLUMN isRead INTEGER NOT NULL DEFAULT 1");
        }
    };

    static final Migration MIGRATION_11_12 = new Migration(11, 12) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE saved_messages ADD COLUMN senderTimestampBits INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE saved_messages ADD COLUMN messageTimestampBits INTEGER NOT NULL DEFAULT 0");
        }
    };

    static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `saved_messages` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`chatType` TEXT, " +
                    "`chatId` TEXT, " +
                    "`senderId` TEXT, " +
                    "`message` TEXT, " +
                    "`isSelf` INTEGER NOT NULL, " +
                    "`timestamp` TEXT, " +
                    "`messageId` TEXT, " +
                    "`savedTimestamp` INTEGER NOT NULL, " +
                    "`originalTimestampMillis` INTEGER NOT NULL)");
        }
    };

    static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // This migration was for savedTimestamp in messages table
        }
    };

    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE messages ADD COLUMN isRead INTEGER NOT NULL DEFAULT 1");
        }
    };

    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE messages ADD COLUMN isSaved INTEGER NOT NULL DEFAULT 0");
        }
    };

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

    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE messages ADD COLUMN isFailed INTEGER NOT NULL DEFAULT 0");
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
                            .addMigrations(
                                    MIGRATION_2_3,
                                    MIGRATION_3_4,
                                    MIGRATION_4_5,
                                    MIGRATION_5_6,
                                    MIGRATION_6_7,
                                    MIGRATION_7_8,
                                    MIGRATION_8_9,
                                    MIGRATION_9_10,
                                    MIGRATION_10_11,
                                    MIGRATION_11_12,
                                    MIGRATION_12_13,
                                    MIGRATION_13_14
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