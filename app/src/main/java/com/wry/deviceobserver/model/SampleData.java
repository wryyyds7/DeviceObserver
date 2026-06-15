package com.wry.deviceobserver.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次采样数据快照，包含所有监控指标
 */
public class SampleData {
    public long timestamp;

    // CPU
    public float cpuUsagePct;
    public List<Integer> coreFrequencies = new ArrayList<>();

    // 内存
    public long memTotalKb;
    public long memAvailableKb;
    public long memFreeKb;
    public long swapTotalKb;
    public long swapFreeKb;

    // 温度
    public float cpuTempCelsius;
    public float batteryTempCelsius;

    // 网络
    public long netRxBytes;
    public long netTxBytes;

    // 进程统计
    public int processCount;
    public int activeProcessCount;
}
