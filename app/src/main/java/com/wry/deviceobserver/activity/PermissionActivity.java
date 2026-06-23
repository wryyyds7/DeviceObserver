package com.wry.deviceobserver.activity;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.wry.deviceobserver.R;
import com.wry.deviceobserver.permission.PermissionManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * 权限引导页：App 启动时按权限层级引导授权
 */
public class PermissionActivity extends AppCompatActivity {

    private PermissionManager permissionManager;
    private TextView tvRootStatus;
    private TextView tvUsageStatsStatus;
    private TextView tvNotificationStatus;
    private TextView tvPermissionLevel;
    private Button btnGrantUsageStats;
    private Button btnStart;
    private ListView lvFeatures;

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            updateStatus();
        });

    private boolean cachedRoot = false;
    private boolean rootCheckDone = false;  // root 检测是否完成
    private ExecutorService rootExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission);

        permissionManager = new PermissionManager(this);
        rootExecutor = Executors.newSingleThreadExecutor();

        tvRootStatus = findViewById(R.id.tv_root_status);
        tvUsageStatsStatus = findViewById(R.id.tv_usage_stats_status);
        tvNotificationStatus = findViewById(R.id.tv_notification_status);
        tvPermissionLevel = findViewById(R.id.tv_permission_level);
        btnGrantUsageStats = findViewById(R.id.btn_grant_usage_stats);
        btnStart = findViewById(R.id.btn_start);
        lvFeatures = findViewById(R.id.lv_features);

        // 通知权限请求（Android 13+ 才需要运行时申请）
        Button btnNotification = findViewById(R.id.btn_grant_notification);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            btnNotification.setOnClickListener(v -> {
                notificationPermissionLauncher.launch(
                    android.Manifest.permission.POST_NOTIFICATIONS);
            });
        } else {
            btnNotification.setVisibility(android.view.View.GONE);
        }

        // 使用情况权限 → 跳设置页
        btnGrantUsageStats.setOnClickListener(v -> {
            permissionManager.requestUsageStatsPermission();
        });

        // 开始监控
        btnStart.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });

        // root 检测：仅一次，线程池执行，结果缓存
        rootExecutor.execute(() -> {
            cachedRoot = PermissionManager.isRootAvailable();
            rootCheckDone = true;
            if (!isFinishing() && !isDestroyed()) {
                runOnUiThread(this::updateStatus);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        // root：未检测完显示"检测中"，避免闪烁
        if (!rootCheckDone) {
            tvRootStatus.setText("检测中...");
        } else {
            tvRootStatus.setText(cachedRoot ? "✅ 已授权" : "❌ 未授权（降级模式）");
        }

        // 使用情况
        boolean hasUsageStats = permissionManager.hasUsageStatsPermission();
        tvUsageStatsStatus.setText(hasUsageStats ? "✅ 已授权" : "❌ 未授权");
        btnGrantUsageStats.setVisibility(hasUsageStats ? android.view.View.GONE : android.view.View.VISIBLE);

        // 通知
        boolean hasNotification = permissionManager.hasNotificationPermission();
        tvNotificationStatus.setText(hasNotification ? "✅ 已授权" : "❌ 未授权");

        // 权限等级（root 未检测完时显示 BASIC 避免闪烁）
        PermissionManager.PermissionLevel level = rootCheckDone ? determineLevel() : PermissionManager.PermissionLevel.BASIC;
        String levelText;
        switch (level) {
            case FULL: levelText = "FULL — 全功能模式"; break;
            case PARTIAL: levelText = "PARTIAL — 标准模式"; break;
            default: levelText = "BASIC — 基础模式"; break;
        }
        tvPermissionLevel.setText("当前等级: " + levelText);

        // 可用功能列表
        String[] features = permissionManager.getAvailableFeatures(level);
        lvFeatures.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_list_item_1, features));

        btnStart.setEnabled(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rootExecutor != null && !rootExecutor.isShutdown()) {
            rootExecutor.shutdown();
        }
    }

    /**
     * 使用缓存的 root 结果确定权限等级，不触发 su
     */
    private PermissionManager.PermissionLevel determineLevel() {
        boolean hasUsageStats = permissionManager.hasUsageStatsPermission();
        boolean hasNotification = permissionManager.hasNotificationPermission();

        if (cachedRoot && hasUsageStats && hasNotification) {
            return PermissionManager.PermissionLevel.FULL;
        } else if (hasUsageStats && hasNotification) {
            return PermissionManager.PermissionLevel.PARTIAL;
        } else {
            return PermissionManager.PermissionLevel.BASIC;
        }
    }
}
