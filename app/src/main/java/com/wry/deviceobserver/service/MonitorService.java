package com.wry.deviceobserver.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.wry.deviceobserver.R;
import com.wry.deviceobserver.activity.MainActivity;
import com.wry.deviceobserver.database.AppDatabase;
import com.wry.deviceobserver.model.SampleRecord;
import com.wry.deviceobserver.monitor.SystemMonitor;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 前台服务：持续后台监控 + 数据持久化
 * - 采样频率自适应：前台 1s / 后台 5s
 * - 采样 I/O 在子线程执行，不阻塞主线程
 * - 采样数据写入 Room 数据库（单线程池）
 * - 不做 CPU 使用率计算（由 MainActivity 负责），仅持久化
 */
public class MonitorService extends Service {

    private static final String TAG = "MonitorService";
    private static final String CHANNEL_ID = "monitor_channel";
    private static final int NOTIFICATION_ID = 1;

    private Handler handler;
    private Runnable samplingRunnable;
    private boolean isForeground = true;

    // 单线程池：采样 I/O + Room 写入
    private ExecutorService workExecutor;

    // 采样间隔
    private static final long FOREGROUND_INTERVAL = 1000;  // 1s
    private static final long BACKGROUND_INTERVAL = 5000;  // 5s

    private final AtomicBoolean running = new AtomicBoolean(true);

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        workExecutor = Executors.newSingleThreadExecutor();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createNotification("DeviceObserver 监控中..."));
        startSampling();
        return START_NOT_STICKY;
    }

    private void startSampling() {
        samplingRunnable = new Runnable() {
            @Override
            public void run() {
                if (!running.get()) return;
                performSample();
                long interval = isForeground ? FOREGROUND_INTERVAL : BACKGROUND_INTERVAL;
                handler.postDelayed(this, interval);
            }
        };
        handler.post(samplingRunnable);
    }

    /**
     * 执行一次采样 —— I/O 在子线程执行，不阻塞主线程
     */
    private void performSample() {
        workExecutor.execute(() -> {
            long now = System.currentTimeMillis();

            // 内存
            long[] memInfo = SystemMonitor.getMemoryInfo();
            long memAvailable = memInfo[2] > 0 ? memInfo[2] : memInfo[1];

            // 温度
            float cpuTemp = 0;
            List<SystemMonitor.ThermalZone> zones = SystemMonitor.getThermalZones();
            for (SystemMonitor.ThermalZone z : zones) {
                if (z.type.contains("cpu") || z.type.contains("CPU")) {
                    cpuTemp = (float) z.tempCelsius;
                    break;
                }
            }
            if (cpuTemp == 0 && !zones.isEmpty()) {
                cpuTemp = (float) zones.get(0).tempCelsius;
            }

            // 网络流量
            long netRx = 0, netTx = 0;
            List<SystemMonitor.NetworkInterface> netIfs = SystemMonitor.getNetworkInterfaces();
            for (SystemMonitor.NetworkInterface ni : netIfs) {
                if (!ni.name.equals("lo")) {
                    netRx += ni.rxBytes;
                    netTx += ni.txBytes;
                }
            }

            // 温度告警
            if (cpuTemp > 60) {
                handler.post(() -> updateNotification("⚠ 温度告警: "
                    + String.format("%.1f", cpuTemp) + "°C"));
            }

            // 持久化到 Room
            SampleRecord record = new SampleRecord();
            record.timestamp = now;
            record.cpuUsage = 0;  // CPU 使用率由 MainActivity 计算，Service 仅持久化
            record.memAvailableKb = memAvailable;
            record.cpuTemp = cpuTemp;
            record.netRxBytes = netRx;
            record.netTxBytes = netTx;
            record.processCount = 0;  // 进程数由 ProcessActivity 统计，Service 不计算

            AppDatabase.getInstance(this).sampleRecordDao().insert(record);
            // 清理 24h 前的旧数据
            AppDatabase.getInstance(this).sampleRecordDao()
                .deleteBefore(now - 24 * 60 * 60 * 1000);
        });
    }

    // ===== 前台通知 =====

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "监控服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("DeviceObserver 后台监控通知");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DeviceObserver")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createNotification(text));
        }
    }

    // ===== 生命周期 =====

    public void setForeground(boolean foreground) {
        this.isForeground = foreground;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        isForeground = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running.set(false);
        if (samplingRunnable != null) {
            handler.removeCallbacks(samplingRunnable);
        }
        if (workExecutor != null && !workExecutor.isShutdown()) {
            workExecutor.shutdown();
        }
        Log.i(TAG, "MonitorService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
