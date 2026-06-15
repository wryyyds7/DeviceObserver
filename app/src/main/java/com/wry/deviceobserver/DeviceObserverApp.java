package com.wry.deviceobserver;

import android.app.Application;

import androidx.work.Configuration;
import androidx.work.WorkManager;

/**
 * Application 入口，初始化 WorkManager
 */
public class DeviceObserverApp extends Application implements Configuration.Provider {

    @Override
    public void onCreate() {
        super.onCreate();
        WorkManager.initialize(this, getWorkManagerConfiguration());
    }

    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build();
    }
}
