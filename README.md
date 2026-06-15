# DeviceObserver — Android 系统性能与应用资源监控工具

基于 Java 的 Android 系统性能监控工具，通过读取 `/proc`、`/sys` 系统文件实时获取 CPU、内存、温度、网络流量等硬件指标，root 下可监控全部进程资源占用并杀除后台进程，非 root 降级为设备级监控。

## 功能概览

| 功能 | 非 root | root | 数据来源 |
|------|---------|------|---------|
| CPU 多核实时频率 | ✅ | ✅ | `/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq` |
| CPU 总体使用率 | ✅ | ✅ | `/proc/stat` 两次采样计算 |
| 内存压力监控 | ✅ | ✅ | `/proc/meminfo` |
| CPU/电池温度 | ✅ | ✅ | `/sys/class/thermal/thermal_zone*/temp` |
| 网络流量统计 | ✅ | ✅ | `/proc/net/dev` |
| 全进程列表 + 内存/CPU | ❌ | ✅ | `/proc/[pid]/status` + `/proc/[pid]/stat` |
| 进程内存泄漏嫌疑标记 | ❌ | ✅ | 连续 3 次 PSS 增长 >10MB |
| 杀除后台进程 | ❌ | ✅ | root shell `kill` |
| 24h 历史数据回溯 | ✅ | ✅ | Room 数据库持久化 |
| 温度超阈值告警通知 | ✅ | ✅ | NotificationManager |

## 架构

```
┌──────────────────────────────────────────────────────┐
│                    UI Layer (XML)                      │
│   ┌──────────────┐  ┌──────────────┐                │
│   │ Permission   │  │   Main       │                 │
│   │ Activity     │  │   Activity   │                 │
│   │ (权限引导)    │  │ (实时图表)   │                 │
│   └──────────────┘  └──────────────┘                │
│                      │                               │
│   ┌──────────────────┴───────────┐                  │
│   │     ProcessActivity          │                  │
│   │     (进程列表 + RecyclerView) │                  │
│   └──────────────────────────────┘                  │
├──────────────────────────────────────────────────────┤
│                Service Layer                           │
│   ┌──────────────────────────────────┐               │
│   │  MonitorService (Foreground)     │               │
│   │  采样频率自适应 (1s/5s)           │               │
│   │  WakeLock 按需持有                 │               │
│   └──────────────┬───────────────────┘               │
│                  │ 采样数据                            │
├──────────────────┼───────────────────────────────────┤
│          Monitor Layer                                │
│   ┌──────────────┴────────────────┐                  │
│   │  SystemMonitor                │                  │
│   │  (CPU/Mem/Temp/Net)            │                  │
│   └───────────────┬───────────────┘                  │
│   ┌───────────────┴───────────────┐                  │
│   │  ProcessMonitor               │                  │
│   │  (root: /proc 遍历)            │                  │
│   └───────────────────────────────┘                  │
├──────────────────────────────────────────────────────┤
│               Data Layer                               │
│   ┌──────────────┐  ┌──────────────┐                 │
│   │ Room DB      │  │ Custom View  │                  │
│   │ (24h 持久化)  │  │ (实时折线图)  │                  │
│   └──────────────┘  └──────────────┘                 │
└──────────────────────────────────────────────────────┘
```

## 分层权限架构

App 启动时按权限层级引导授权，未授权时自动降级：

```
App 启动 → 权限引导页
  │
  ├── 1. 运行时权限（系统弹窗）
  │     └── POST_NOTIFICATIONS (Android 13+)
  │         未授权 → 降级：不显示通知，静默监控
  │
  ├── 2. 特殊权限（跳转设置页）
  │     └── PACKAGE_USAGE_STATS (使用情况访问)
  │         未授权 → 降级：隐藏应用排行页
  │
  └── 3. Root 权限（Magisk/KernelSU 弹窗）
      └── su -c "id"
          授权 → FULL 模式：全进程监控 + 杀进程
          拒绝 → BASIC 模式：仅系统硬件指标
```

### 权限等级与可用功能

| 等级 | 条件 | 可用功能 |
|------|------|---------|
| FULL | root + 使用情况 + 通知 | 全部功能 |
| PARTIAL | 使用情况 + 通知（无 root） | 硬件指标 + 自身进程 + 应用统计 |
| BASIC | 仅基础 | CPU/内存/温度/网络 |

## 技术栈

| 层 | 技术 | 说明 |
|---|------|------|
| 语言 | Java 17 | 全 Java 实现，无 Kotlin |
| UI | XML 布局 + RecyclerView | 传统 Android View 体系 |
| 图表 | 自定义 View (Canvas) | 不依赖第三方图表库 |
| 后台 | Foreground Service | 前台服务保活 + 通知 |
| 调度 | WorkManager | 后台定时采样 |
| 持久化 | Room | 历史数据 24h 存储与回溯 |
| 异步 | RxJava 3 | Room 响应式查询 |
| 权限 | ActivityResult API | 运行时权限请求 |

## 项目结构

```
DeviceObserver/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/wry/deviceobserver/
│   │   ├── DeviceObserverApp.java         # Application 入口
│   │   ├── activity/
│   │   │   ├── PermissionActivity.java     # 权限引导页
│   │   │   ├── MainActivity.java           # 主界面（实时图表）
│   │   │   └── ProcessActivity.java        # 进程列表页
│   │   ├── service/
│   │   │   └── MonitorService.java          # 前台服务
│   │   ├── monitor/
│   │   │   ├── SystemMonitor.java           # 系统硬件监控
│   │   │   └── ProcessMonitor.java          # 进程监控
│   │   ├── permission/
│   │   │   └── PermissionManager.java       # 分层权限管理
│   │   ├── view/
│   │   │   └── RealTimeChartView.java        # 自定义 View 实时折线图
│   │   ├── adapter/
│   │   │   └── ProcessAdapter.java          # 进程列表 Adapter
│   │   ├── model/
│   │   │   ├── SampleData.java               # 采样数据快照
│   │   │   └── SampleRecord.java            # Room 实体
│   │   ├── dao/
│   │   │   └── SampleRecordDao.java          # Room DAO
│   │   └── database/
│   │       └── AppDatabase.java             # Room 数据库
│   └── res/
│       ├── layout/
│       │   ├── activity_permission.xml
│       │   ├── activity_main.xml
│       │   ├── activity_process.xml
│       │   └── item_process.xml
│       └── values/
│           ├── strings.xml
│           └── themes.xml
├── build.gradle
├── settings.gradle
└── Makefile
```

## 核心实现

### 1. 系统硬件监控 — /sys + /proc 文件读取

```java
// CPU 频率：遍历各核心
public static List<Integer> getCpuCoreFrequencies() {
    int cores = Runtime.getRuntime().availableProcessors();
    List<Integer> freqs = new ArrayList<>();
    for (int i = 0; i < cores; i++) {
        String path = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq";
        int freq = readIntFromFile(path);
        if (freq < 0) freq = readCpuFreqFromProc(i);  // 降级
        freqs.add(freq);
    }
    return freqs;
}

// CPU 使用率：/proc/stat 两次采样
public static float[] getCpuUsageRate(long[] prevStat) {
    long[] curStat = readCpuStat();
    long totalDiff = curTotal - prevTotal;
    long idleDiff = curIdle - prevIdle;
    float usageRate = 100f - (float) idleDiff / totalDiff * 100f;
    return new float[]{idleRate, usageRate};
}
```

### 2. 全进程监控 — /proc 遍历

```java
// root 下遍历 /proc 目录所有数字子目录
File[] pidDirs = new File("/proc").listFiles((dir, name) -> name.matches("\\d+"));
for (File pidDir : pidDirs) {
    int pid = Integer.parseInt(pidDir.getName());
    // 进程名：/proc/[pid]/cmdline
    // 内存：/proc/[pid]/status → VmRSS, VmSwap
    // CPU：/proc/[pid]/stat → utime, stime (jiffies)
}
```

### 3. SoC 厂商适配

```java
// 温度：高通/联发科/三星路径差异，遍历 thermal_zone 降级
File[] zoneDirs = new File("/sys/class/thermal")
    .listFiles((dir, name) -> name.startsWith("thermal_zone"));
for (File zone : zoneDirs) {
    String type = readFile(zone + "/type");  // "cpu_thermal" / "mtktscpu" 等
    double temp = readInt(zone + "/temp") / 1000.0;
}
```

### 4. 前台服务保活

```java
// Foreground Service + 采样频率自适应
@Override
public int onStartCommand(Intent intent, int flags, int startId) {
    startForeground(NOTIFICATION_ID, createNotification("监控中..."));
    startSampling();  // 前台 1s / 后台 5s
    return START_STICKY;
}
```

### 5. 自定义 View 实时折线图

```java
// Canvas drawPath 绘制 60fps 实时曲线
@Override
protected void onDraw(Canvas canvas) {
    float stepX = chartW / (MAX_POINTS - 1);
    Path linePath = new Path();
    for (int i = 0; i < dataPoints.size(); i++) {
        float x = PADDING + i * stepX;
        float y = PADDING + chartH * (1 - value / maxValue);
        if (i == 0) linePath.moveTo(x, y);
        else linePath.lineTo(x, y);
    }
    canvas.drawPath(linePath, linePaint);
}
```

### 6. Room 历史持久化

```java
@Entity(tableName = "sample_records")
public class SampleRecord {
    @PrimaryKey(autoGenerate = true) public long id;
    public long timestamp;
    public float cpuUsage;
    public long memAvailableKb;
    public float cpuTemp;
}

// DAO: 查询 24h 数据
@Query("SELECT * FROM sample_records WHERE timestamp > :since ORDER BY timestamp ASC")
Flowable<List<SampleRecord>> getRecordsSince(long since);
```

## 快速开始

### 环境要求

| 依赖 | 版本 |
|------|------|
| Android SDK | API 26+ (Android 8.0) |
| Java | 17 |
| Gradle | 8.4 |
| AGP | 8.1.0 |

### 编译

```bash
# 使用 Gradle 编译
./gradlew assembleDebug

# 生成 APK 位置
app/build/outputs/apk/debug/app-debug.apk
```

### 安装运行

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 使用

1. 打开 App → 权限引导页
2. 授权通知权限（系统弹窗）
3. 去设置开启使用情况访问（可选）
4. 如果有 root，Magisk/KernelSU 会弹窗确认
5. 点击"开始监控" → 进入实时图表界面

## 和 eBPF 项目的对比

| | Look_observer (eBPF) | DeviceObserver (Android) |
|---|---|---|
| 平台 | Linux 服务端 | Android 移动端 |
| 语言 | C (BPF) + Go | Java |
| 数据来源 | kprobe + tracepoint + BPF maps | /proc + /sys 文件系统 |
| 进程监控 | 全进程（BPF 事件） | 全进程（root）/ 自身（非 root） |
| 权限 | CAP_BPF / root | 分层：运行时 + 特殊 + root |
| 部署 | `bpftool` 加载 | APK 安装 |

## License

MIT
