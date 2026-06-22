package com.wry.deviceobserver.activity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.wry.deviceobserver.R;
import com.wry.deviceobserver.monitor.SystemMonitor;
import com.wry.deviceobserver.service.MonitorService;
import com.wry.deviceobserver.view.RealTimeChartView;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主界面：实时展示系统性能指标
 */
public class MainActivity extends AppCompatActivity {

    private RealTimeChartView chartCpu;
    private RealTimeChartView chartMemory;
    private RealTimeChartView chartTemp;
    private TextView tvSummary;
    private Handler handler;

    // CPU 采样状态
    private volatile long[] prevCpuStat = null;
    // 前一次网络流量
    private volatile long prevRxBytes = 0;
    private volatile long prevTxBytes = 0;

    private static final int INTERVAL_MS = 1000;
    private ScheduledExecutorService samplingExecutor;
    private final AtomicBoolean samplingInProgress = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        chartCpu = findViewById(R.id.chart_cpu);
        chartMemory = findViewById(R.id.chart_memory);
        chartTemp = findViewById(R.id.chart_temp);
        tvSummary = findViewById(R.id.tv_summary);

        chartCpu.setLabel("CPU");
        chartCpu.setMaxValue(100f);
        chartCpu.setLineColor(0xFF7C3AED);

        chartMemory.setLabel("Memory");
        chartMemory.setMaxValue(100f);
        chartMemory.setLineColor(0xFF10B981);

        chartTemp.setLabel("Temp");
        chartTemp.setMaxValue(80f);
        chartTemp.setUnit("°C");
        chartTemp.setLineColor(0xFFF59E0B);

        // 启动前台服务
        Intent serviceIntent = new Intent(this, MonitorService.class);
        startForegroundService(serviceIntent);

        handler = new Handler(Looper.getMainLooper());
        samplingExecutor = Executors.newSingleThreadScheduledExecutor();
        startSampling();
    }

    private void startSampling() {
        samplingExecutor.scheduleAtFixedRate(() -> {
            // 防止采样任务堆积：如果上一次还在跑，跳过本次
            if (!samplingInProgress.compareAndSet(false, true)) {
                return;
            }
            try {
                performSample();
            } finally {
                samplingInProgress.set(false);
            }
        }, 0, INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void performSample() {
        // CPU
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
            ? (float) (memTotal - memAvailable) / memTotal * 100f
            : 0;

        // 温度
        float cpuTemp = -1;  // 用 -1 表示未找到
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
        if (cpuTemp < 0) cpuTemp = 0;  // 全部失败时显示 0

        // 网络流量（增量）
        long netRx = 0, netTx = 0;
        List<SystemMonitor.NetworkInterface> ifs = SystemMonitor.getNetworkInterfaces();
        for (SystemMonitor.NetworkInterface ni : ifs) {
            if (!ni.name.equals("lo")) {
                netRx += ni.rxBytes;
                netTx += ni.txBytes;
            }
        }
        long rxRate = (prevRxBytes > 0 && netRx >= prevRxBytes) ? (netRx - prevRxBytes) : 0;
        long txRate = (prevTxBytes > 0 && netTx >= prevTxBytes) ? (netTx - prevTxBytes) : 0;
        prevRxBytes = netRx;
        prevTxBytes = netTx;

        // UI 更新回到主线程
        final float finalCpuUsage = cpuUsage;
        final float finalMemUsagePct = memUsagePct;
        final float finalCpuTemp = cpuTemp;
        final long finalTxRate = txRate;
        final long finalRxRate = rxRate;

        runOnUiThread(() -> {
            chartCpu.addPoint(finalCpuUsage);
            chartMemory.addPoint(finalMemUsagePct);
            chartTemp.addPoint(finalCpuTemp);

            String summary = String.format(
                "CPU: %.1f%%  |  Mem: %.1f%%  |  Temp: %.1f°C  |  ↑ %s  ↓ %s",
                finalCpuUsage,
                finalMemUsagePct,
                finalCpuTemp,
                formatBytes(finalTxRate),
                formatBytes(finalRxRate)
            );
            tvSummary.setText(summary);
        });
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / 1024.0 / 1024);
    }

    /**
     * 进程列表入口
     */
    public void onProcessClick(View view) {
        startActivity(new Intent(this, ProcessActivity.class));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (samplingExecutor != null && !samplingExecutor.isShutdown()) {
            samplingExecutor.shutdownNow();
        }
        // 停止前台服务
        stopService(new Intent(this, MonitorService.class));
    }
}
