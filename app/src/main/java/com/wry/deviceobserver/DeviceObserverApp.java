package com.wry.deviceobserver;

import android.app.Application;
import android.util.Log;

/**
 * Application 入口
 */
public class DeviceObserverApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i("DeviceObserver", "Application started");
    }
}
