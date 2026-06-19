package com.wry.deviceobserver.activity;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wry.deviceobserver.R;
import com.wry.deviceobserver.adapter.ProcessAdapter;
import com.wry.deviceobserver.monitor.ProcessMonitor;
import com.wry.deviceobserver.permission.PermissionManager;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * 进程列表页：按内存降序展示所有进程
 */
public class ProcessActivity extends AppCompatActivity {

    private ProcessMonitor processMonitor;
    private ProcessAdapter adapter;
    private Handler handler;
    private Runnable samplingRunnable;
    private ExecutorService scanExecutor;

    private static final int INTERVAL_MS = 2000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_process);

        RecyclerView recyclerView = findViewById(R.id.rv_processes);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ProcessAdapter();
        recyclerView.setAdapter(adapter);

        // 先初始化 handler 和线程池，再启动子线程
        handler = new Handler(Looper.getMainLooper());
        scanExecutor = Executors.newSingleThreadExecutor();

        // root 检测在子线程，使用 PermissionManager 缓存
        new Thread(() -> {
            boolean hasRoot = PermissionManager.isRootAvailable();
            runOnUiThread(() -> {
                processMonitor = new ProcessMonitor(hasRoot);
                startSampling();
            });
        }).start();
    }

    private void startSampling() {
        samplingRunnable = new Runnable() {
            @Override
            public void run() {
                refreshProcesses();
                handler.postDelayed(this, INTERVAL_MS);
            }
        };
        handler.post(samplingRunnable);
    }

    private void refreshProcesses() {
        scanExecutor.execute(() -> {
            List<ProcessMonitor.ProcessInfo> processes = processMonitor.scanAllProcesses();
            // 按内存降序排序
            Collections.sort(processes, (a, b) -> Long.compare(b.vmRssKb, a.vmRssKb));
            runOnUiThread(() -> adapter.setProcesses(processes));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (samplingRunnable != null) {
            handler.removeCallbacks(samplingRunnable);
        }
        if (scanExecutor != null && !scanExecutor.isShutdown()) {
            scanExecutor.shutdown();
        }
    }
}
