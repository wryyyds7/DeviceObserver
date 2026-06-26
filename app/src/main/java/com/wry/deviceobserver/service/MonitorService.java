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
import com.wry.deviceobserver.dao.SampleRecordDao;
import com.wry.deviceobserver.model.SampleRecord;
import com.wry.deviceobserver.monitor.SystemMonitor;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 前台服务：唯一采样源 + 后台持久化
 * - 采样频率自适应：前台 1s / 后台 5s
 * - 采样结果通过广播发送给 MainActivity
 * - 采样数据写入 Room 数据库（事务）
 * - CPU 使用率由 Service 统一计算，MainActivity 只负责展示
 */
public class MonitorService extends Service {

    public static final String ACTION_SAMPLE = "com.wry.deviceobserver.SAMPLE";
    public static final String EXTRA_CPU_USAGE = "cpu_usage";
    public static final String EXTRA_MEM_USAGE = "mem_usage";
    public static final String EXTRA_CPU_TEMP = "cpu_temp";
    public static final String EXTRA_NET_RX = "net_rx";
    public static final String EXTRA_NET_TX = "net_tx";

    private static final String TAG = "MonitorService";
    private static final String CHANNEL_ID = "monitor_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final float TEMP_ALERT_THRESHOLD = 60f;

    private Handler handler;
    private volatile boolean isForeground = true;
    private ScheduledExecutorService workExecutor;
    private final AtomicBoolean samplingInProgress = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean samplingRunnableStarted = new AtomicBoolean(false);

    private static final long FOREGROUND_INTERVAL = 1000;
    private static final long BACKGROUND_INTERVAL = 5000;

    // CPU 采样状态
    private long[] prevCpuStat = null;
    // 前一次网络流量
    private long prevRxBytes = 0;
    private long prevTxBytes = 0;
    // 缓存的 DAO 引用，避免每次采样调用 getInstance
    private SampleRecordDao cachedDao;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        workExecutor = Executors.newSingleThreadScheduledExecutor();
        createNotificationChannel();
        // 在 onCreate 中立即调用 startForeground，避免 5 秒超时崩溃
        startForeground(NOTIFICATION_ID, createNotification("DeviceObserver 监控中..."));
        // 缓存 DAO 引用
        cachedDao = AppDatabase.getInstance(this).sampleRecordDao();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 处理前台/后台切换
        if (intent != null && intent.hasExtra("foreground")) {
            boolean wasForeground = isForeground;
            isForeground = intent.getBooleanExtra("foreground", true);
            if (wasForeground != isForeground) {
                rescheduleSampling();
            }
        }

        // 首次启动时开始采样
        if (samplingRunnableStarted.compareAndSet(false, true)) {
            startSampling();
        }
        return START_NOT_STICKY;
    }

    private void startSampling() {
        long interval = isForeground ? FOREGROUND_INTERVAL : BACKGROUND_INTERVAL;
        workExecutor.scheduleAtFixedRate(() -> {
            if (!running.get()) return;
            if (!samplingInProgress.compareAndSet(false, true)) return;
            try {
                performSample();
            } finally {
                samplingInProgress.set(false);
            }
        }, 0, interval, TimeUnit.MILLISECONDS);
    }

    private void rescheduleSampling() {
        if (workExecutor != null && !workExecutor.isShutdown()) {
            workExecutor.shutdownNow();
            try {
                workExecutor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
        }
        workExecutor = Executors.newSingleThreadScheduledExecutor();
        running.set(true);
        samplingInProgress.set(false);
        startSampling();
    }

    private void performSample() {
        long now = System.currentTimeMillis();

        // CPU 使用率（统一计算）
        long[] curStat = SystemMonitor.readCpuStat();
        float cpuUsage = 0;
        if (prevCpuStat != null && curStat != null) {
            float[] usage = SystemMonitor.getCpuUsageRate(prevCpuStat, curStat);
            if (usage != null) cpuUsage = usage[1];
        }
        prevCpuStat = curStat;

        // 内存
        long[] memInfo = SystemMonitor.getMemoryInfo();
        long memTotal = memInfo[0];
        long memAvailable = memInfo[2] > 0 ? memInfo[2] : memInfo[1];
        float memUsagePct = memTotal > 0
            ? (float) (memTotal - memAvailable) / memTotal * 100f : 0;

        // 温度
        float cpuTemp = -1;
        List<SystemMonitor.ThermalZone> zones = SystemMonitor.getThermalZones();
        for (SystemMonitor.ThermalZone z : zones) {
            if (z.type.contains("cpu") || z.type.contains("CPU")) {
                cpuTemp = (float) z.tempCelsius;
                break;
            }
        }
        if (cpuTemp < 0 && !zones.isEmpty()) {
            cpuTemp = (float) zones.get(0).tempCelsius;
        }
        if (cpuTemp < 0) cpuTemp = 0;

        // 网络流量
        long netRx = 0, netTx = 0;
        List<SystemMonitor.NetworkInterface> netIfs = SystemMonitor.getNetworkInterfaces();
        for (SystemMonitor.NetworkInterface ni : netIfs) {
            if (!ni.name.equals("lo")) {
                netRx += ni.rxBytes;
                netTx += ni.txBytes;
            }
        }
        long rxRate = (prevRxBytes > 0 && netRx >= prevRxBytes) ? (netRx - prevRxBytes) : 0;
        long txRate = (prevTxBytes > 0 && netTx >= prevTxBytes) ? (netTx - prevTxBytes) : 0;
        prevRxBytes = netRx;
        prevTxBytes = netTx;

        // 温度告警
        if (cpuTemp > TEMP_ALERT_THRESHOLD) {
            final float alertTemp = cpuTemp;
            handler.post(() -> updateNotification("⚠ 温度告警: "
                + String.format("%.1f", alertTemp) + "°C"));
        }

        // 广播采样数据给 UI（限制包名，防止第三方 App 窃听）
        Intent sampleIntent = new Intent(ACTION_SAMPLE);
        sampleIntent.setPackage(getPackageName());
        sampleIntent.putExtra(EXTRA_CPU_USAGE, cpuUsage);
        sampleIntent.putExtra(EXTRA_MEM_USAGE, memUsagePct);
        sampleIntent.putExtra(EXTRA_CPU_TEMP, cpuTemp);
        sampleIntent.putExtra(EXTRA_NET_RX, rxRate);
        sampleIntent.putExtra(EXTRA_NET_TX, txRate);
        sendBroadcast(sampleIntent);

        // 持久化到 Room（事务）
        SampleRecord record = new SampleRecord();
        record.timestamp = now;
        record.cpuUsage = cpuUsage;
        record.memAvailableKb = memAvailable;
        record.cpuTemp = cpuTemp;
        record.netRxBytes = netRx;
        record.netTxBytes = netTx;
        record.processCount = 0;

        AppDatabase.getInstance(this).runInTransaction(() -> {
            cachedDao.insert(record);
            cachedDao.deleteBefore(now - 24 * 60 * 60 * 1000);
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
        rescheduleSampling();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        isForeground = false;
        rescheduleSampling();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running.set(false);
        if (workExecutor != null && !workExecutor.isShutdown()) {
            workExecutor.shutdownNow();
        }
        Log.i(TAG, "MonitorService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
