package com.wry.deviceobserver;

import android.app.Application;
import android.util.Log;

import androidx.work.Configuration;

/**
 * Application 入口
 * 实现 Configuration.Provider 接口让 WorkManager 自动初始化，
 * 不需要手动调用 WorkManager.initialize()。
 */
public class DeviceObserverApp extends Application implements Configuration.Provider {

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i("DeviceObserver", "Application started");
    }

    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build();
    }
}
