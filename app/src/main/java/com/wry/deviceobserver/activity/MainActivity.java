package com.wry.deviceobserver.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.wry.deviceobserver.R;
import com.wry.deviceobserver.service.MonitorService;
import com.wry.deviceobserver.view.RealTimeChartView;

/**
 * 主界面：接收 Service 广播的采样数据并展示
 * 不自己采样，不在 onDestroy 停止 Service（后台持续监控）
 */
public class MainActivity extends AppCompatActivity {

    private RealTimeChartView chartCpu;
    private RealTimeChartView chartMemory;
    private RealTimeChartView chartTemp;
    private TextView tvSummary;

    private SampleReceiver receiver;

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

        // 启动前台服务（唯一采样源）
        Intent serviceIntent = new Intent(this, MonitorService.class);
        startForegroundService(serviceIntent);

        // 注册广播接收器（Android 14+ 需要 RECEIVER_NOT_EXPORTED）
        receiver = new SampleReceiver();
        IntentFilter filter = new IntentFilter(MonitorService.ACTION_SAMPLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    /**
     * 广播接收器：接收 Service 的采样数据
     */
    private class SampleReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!MonitorService.ACTION_SAMPLE.equals(intent.getAction())) return;
            if (isFinishing() || isDestroyed()) return;

            float cpuUsage = intent.getFloatExtra(MonitorService.EXTRA_CPU_USAGE, 0);
            float memUsage = intent.getFloatExtra(MonitorService.EXTRA_MEM_USAGE, 0);
            float cpuTemp = intent.getFloatExtra(MonitorService.EXTRA_CPU_TEMP, 0);
            long netRx = intent.getLongExtra(MonitorService.EXTRA_NET_RX, 0);
            long netTx = intent.getLongExtra(MonitorService.EXTRA_NET_TX, 0);

            chartCpu.addPoint(cpuUsage);
            chartMemory.addPoint(memUsage);
            chartTemp.addPoint(cpuTemp);

            String summary = String.format(
                "CPU: %.1f%%  |  Mem: %.1f%%  |  Temp: %.1f°C  |  ↑ %s  ↓ %s",
                cpuUsage, memUsage, cpuTemp,
                formatBytes(netTx), formatBytes(netRx)
            );
            tvSummary.setText(summary);
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / 1024.0 / 1024);
    }

    public void onProcessClick(View view) {
        startActivity(new Intent(this, ProcessActivity.class));
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 通过广播通知 Service 切换到后台采样频率（避免 startService 在 Android 12+ 被限制）
        Intent intent = new Intent(MonitorService.ACTION_SET_FOREGROUND);
        intent.setPackage(getPackageName());
        intent.putExtra(MonitorService.EXTRA_FOREGROUND, false);
        sendBroadcast(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 通过广播通知 Service 切换到前台采样频率
        Intent intent = new Intent(MonitorService.ACTION_SET_FOREGROUND);
        intent.setPackage(getPackageName());
        intent.putExtra(MonitorService.EXTRA_FOREGROUND, true);
        sendBroadcast(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (receiver != null) {
            unregisterReceiver(receiver);
        }
        // 不停止 Service —— 后台持续监控
    }
}
