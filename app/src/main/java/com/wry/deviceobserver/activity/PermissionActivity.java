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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission);

        permissionManager = new PermissionManager(this);

        tvRootStatus = findViewById(R.id.tv_root_status);
        tvUsageStatsStatus = findViewById(R.id.tv_usage_stats_status);
        tvNotificationStatus = findViewById(R.id.tv_notification_status);
        tvPermissionLevel = findViewById(R.id.tv_permission_level);
        btnGrantUsageStats = findViewById(R.id.btn_grant_usage_stats);
        btnStart = findViewById(R.id.btn_start);
        lvFeatures = findViewById(R.id.lv_features);

        // 通知权限请求
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            findViewById(R.id.btn_grant_notification).setOnClickListener(v -> {
                notificationPermissionLauncher.launch(
                    android.Manifest.permission.POST_NOTIFICATIONS);
            });
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

        // root 检测：仅一次，子线程执行，结果缓存
        new Thread(() -> {
            cachedRoot = PermissionManager.isRootAvailable();
            runOnUiThread(this::updateStatus);
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        // root：使用缓存结果，不再重复 exec su
        tvRootStatus.setText(cachedRoot ? "✅ 已授权" : "❌ 未授权（降级模式）");

        // 使用情况
        boolean hasUsageStats = permissionManager.hasUsageStatsPermission();
        tvUsageStatsStatus.setText(hasUsageStats ? "✅ 已授权" : "❌ 未授权");
        btnGrantUsageStats.setVisibility(hasUsageStats ? android.view.View.GONE : android.view.View.VISIBLE);

        // 通知
        boolean hasNotification = permissionManager.hasNotificationPermission();
        tvNotificationStatus.setText(hasNotification ? "✅ 已授权" : "❌ 未授权");

        // 权限等级（使用缓存的 root 结果，不在主线程 exec su）
        PermissionManager.PermissionLevel level = determineLevel();
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
