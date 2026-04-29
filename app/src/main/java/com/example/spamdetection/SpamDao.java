package com.example.spamdetection;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface SpamDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(SpamData data);

    @Query("SELECT * FROM spam_data")
    List<SpamData> getAllData();

    @Query("SELECT * FROM spam_data WHERE message = :text LIMIT 1")
    SpamData findByMessage(String text);

    @Query("DELETE FROM spam_data")
    void deleteAll();
}
