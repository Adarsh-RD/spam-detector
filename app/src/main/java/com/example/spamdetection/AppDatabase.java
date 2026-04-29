package com.example.spamdetection;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {SpamData.class}, version = 2)
public abstract class AppDatabase extends RoomDatabase {
    private static AppDatabase instance;

    public abstract SpamDao spamDao();

    public static synchronized AppDatabase getInstance(Context context) {
        if (instance == null) {
            instance = Room.databaseBuilder(context.getApplicationContext(),
                    AppDatabase.class, "spam_db")
                    .fallbackToDestructiveMigration()
                    .build();
        }
        return instance;
    }
}
