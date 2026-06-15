package com.wry.deviceobserver.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Room 实体：历史采样记录，用于 24h 回溯
 * 每条记录存储一次采样的核心指标
 */
@Entity(tableName = "sample_records")
public class SampleRecord {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long timestamp;        // 采样时间戳（毫秒）
    public float cpuUsage;         // CPU 使用率
    public long memAvailableKb;   // 可用内存
    public float cpuTemp;          // CPU 温度
    public long netRxBytes;       // 网络接收字节
    public long netTxBytes;       // 网络发送字节
    public int processCount;      // 进程数
}
