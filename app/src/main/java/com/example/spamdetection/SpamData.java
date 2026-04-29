package com.example.spamdetection;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "spam_data")
public class SpamData {
    @PrimaryKey
    @NonNull
    public String message;
    
    public int label; // 1 for Spam, 0 for Ham

    public SpamData(@NonNull String message, int label) {
        this.message = message;
        this.label = label;
    }
}
