package com.wry.deviceobserver.database;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.wry.deviceobserver.dao.SampleRecordDao;
import com.wry.deviceobserver.model.SampleRecord;

@Database(entities = {SampleRecord.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase INSTANCE;

    public abstract SampleRecordDao sampleRecordDao();

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                        context.getApplicationContext(),
                        AppDatabase.class,
                        "device_observer.db"
                    ).build();
                }
            }
        }
        return INSTANCE;
    }
}
