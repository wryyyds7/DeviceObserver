package com.wry.deviceobserver.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.OnConflictStrategy;

import com.wry.deviceobserver.model.SampleRecord;

import java.util.List;

import io.reactivex.rxjava3.core.Flowable;

@Dao
public interface SampleRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(SampleRecord record);

    // 查询最近 24 小时的记录
    @Query("SELECT * FROM sample_records WHERE timestamp > :since ORDER BY timestamp ASC")
    Flowable<List<SampleRecord>> getRecordsSince(long since);

    // 查询最近 N 条记录
    @Query("SELECT * FROM sample_records ORDER BY timestamp DESC LIMIT :limit")
    List<SampleRecord> getRecentRecords(int limit);

    // 删除指定时间之前的记录（清理旧数据）
    @Query("DELETE FROM sample_records WHERE timestamp < :before")
    void deleteBefore(long before);

    // 统计记录总数
    @Query("SELECT COUNT(*) FROM sample_records")
    int count();
}
