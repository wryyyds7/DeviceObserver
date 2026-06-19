package com.wry.deviceobserver.permission;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;

/**
 * 分层权限管理：运行时权限 + 特殊权限 + root 检测
 * 未授权时自动降级，保证各权限状态下均可正常启动。
 */
public class PermissionManager {

    public enum PermissionLevel {
        FULL,        // root + 使用情况 + 通知 全部授权
        PARTIAL,     // 使用情况 + 通知，无 root
        BASIC        // 仅基础系统文件读取
    }

    private final Context context;

    // 缓存 root 检测结果，避免重复 exec su 阻塞主线程
    private static volatile Boolean cachedRootResult = null;
    private static volatile long cachedRootTimestamp = 0;
    private static final long ROOT_CACHE_TTL = 30_000; // 30 秒缓存

    public PermissionManager(Context context) {
        this.context = context;
    }

    /**
     * 检测 root 权限是否可用
     * 必须在子线程调用！exec su 可能阻塞 5-10 秒
     */
    public static boolean isRootAvailable() {
        // 使用缓存避免重复检测
        long now = System.currentTimeMillis();
        if (cachedRootResult != null && (now - cachedRootTimestamp) < ROOT_CACHE_TTL) {
            return cachedRootResult;
        }

        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            // 消费 stderr 防止 waitFor 阻塞
            java.io.InputStream stderr = process.getErrorStream();
            byte[] buffer = new byte[1024];
            while (stderr.read(buffer) != -1) { /* drain */ }

            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            String output = reader.readLine();

            // 设置 3 秒超时，防止 root 弹窗用户不操作导致永久阻塞
            long startTime = System.currentTimeMillis();
            boolean finished = false;
            while (System.currentTimeMillis() - startTime < 3000) {
                finished = process.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (finished) break;
            }
            if (!finished) {
                process.destroyForcibly();
                cachedRootResult = false;
                cachedRootTimestamp = now;
                return false;
            }

            boolean result = output != null && output.contains("uid=0");

            cachedRootResult = result;
            cachedRootTimestamp = now;
            return result;
        } catch (Exception e) {
            cachedRootResult = false;
            cachedRootTimestamp = now;
            return false;
        }
    }

    /**
     * 检测使用情况访问权限（PACKAGE_USAGE_STATS）
     */
    public boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) return false;
        int mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.getPackageName()
        );
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * 跳转到使用情况访问设置页
     */
    public void requestUsageStatsPermission() {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /**
     * 检测通知权限（Android 13+）
     */
    public boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true; // 13 以下不需要运行时申请
        }
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 根据当前权限状态确定功能等级
     * 注意：此方法调用 isRootAvailable()，不要在主线程调用！
     */
    public PermissionLevel getPermissionLevel() {
        boolean hasRoot = isRootAvailable();  // 使用缓存，不会每次 exec su
        boolean hasUsageStats = hasUsageStatsPermission();
        boolean hasNotification = hasNotificationPermission();

        if (hasRoot && hasUsageStats && hasNotification) {
            return PermissionLevel.FULL;
        } else if (hasUsageStats && hasNotification) {
            return PermissionLevel.PARTIAL;
        } else {
            return PermissionLevel.BASIC;
        }
    }

    /**
     * 根据权限等级返回可用功能列表
     */
    public String[] getAvailableFeatures(PermissionLevel level) {
        switch (level) {
            case FULL:
                return new String[]{
                    "CPU 多核频率监控",
                    "内存压力监控",
                    "温度监控",
                    "网络流量统计",
                    "全进程资源监控（root）",
                    "应用使用统计",
                    "进程杀除（root）",
                    "实时图表 + 告警",
                    "24h 历史回溯"
                };
            case PARTIAL:
                return new String[]{
                    "CPU 多核频率监控",
                    "内存压力监控",
                    "温度监控",
                    "网络流量统计",
                    "自身进程详情",
                    "应用使用统计",
                    "实时图表 + 告警",
                    "24h 历史回溯"
                };
            case BASIC:
            default:
                return new String[]{
                    "CPU 多核频率监控",
                    "内存压力监控",
                    "温度监控",
                    "网络流量统计"
                };
        }
    }
}
