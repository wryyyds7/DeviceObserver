package com.wry.deviceobserver.activity;

import android.os.Bundle;

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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程列表页：按内存降序展示所有进程
 */
public class ProcessActivity extends AppCompatActivity {

    private ProcessMonitor processMonitor;
    private ProcessAdapter adapter;
    private ScheduledExecutorService scanExecutor;
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private boolean initialized = false;

    private static final int INTERVAL_MS = 2000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_process);

        RecyclerView recyclerView = findViewById(R.id.rv_processes);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ProcessAdapter();
        recyclerView.setAdapter(adapter);

        scanExecutor = Executors.newSingleThreadScheduledExecutor();

        // root 检测在子线程，使用 PermissionManager 缓存
        scanExecutor.execute(() -> {
            boolean hasRoot = PermissionManager.isRootAvailable();
            if (!isFinishing() && !isDestroyed()) {
                runOnUiThread(() -> {
                    processMonitor = new ProcessMonitor(hasRoot);
                    initialized = true;
                    startSampling();
                });
            }
        });
    }

    private void startSampling() {
        scanExecutor.scheduleAtFixedRate(() -> {
            if (!initialized || !scanning.compareAndSet(false, true)) {
                return;
            }
            try {
                List<ProcessMonitor.ProcessInfo> processes = processMonitor.scanAllProcesses();
                Collections.sort(processes, (a, b) -> Long.compare(b.vmRssKb, a.vmRssKb));
                if (!isFinishing() && !isDestroyed()) {
                    runOnUiThread(() -> adapter.setProcesses(processes));
                }
            } finally {
                scanning.set(false);
            }
        }, 0, INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanExecutor != null && !scanExecutor.isShutdown()) {
            scanExecutor.shutdownNow();
        }
    }
}
