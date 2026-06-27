package com.wry.deviceobserver.monitor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程监控：root 下遍历 /proc/[pid]/ 获取所有进程的 CPU、内存、网络信息。
 * 非 root 下只能获取自身进程信息。
 */
public class ProcessMonitor {

    private final boolean isRoot;
    // PID → 历次 VmRSS 记录，用于检测内存泄漏
    private final Map<Integer, long[]> pidRssHistory = new ConcurrentHashMap<>();
    private static final int LEAK_CHECK_COUNT = 3;
    private static final long LEAK_THRESHOLD_KB = 10 * 1024;
    private static final int MAX_HISTORY_SIZE = 512; // 防止无限增长

    public ProcessMonitor(boolean isRoot) {
        this.isRoot = isRoot;
    }

    /**
     * 扫描所有进程
     * root: 遍历 /proc 目录所有数字子目录
     * 非 root: 只返回当前进程
     */
    public List<ProcessInfo> scanAllProcesses() {
        List<ProcessInfo> processes = new ArrayList<>();

        // 清理已退出进程的历史记录，防止 Map 无限增长
        cleanStaleHistory();

        if (isRoot) {
            File procDir = new File("/proc");
            File[] pidDirs = procDir.listFiles(
                (dir, name) -> name.matches("\\d+"));
            if (pidDirs == null) return processes;

            for (File pidDir : pidDirs) {
                int pid;
                try {
                    pid = Integer.parseInt(pidDir.getName());
                } catch (NumberFormatException e) {
                    continue;
                }
                ProcessInfo info = readProcessInfo(pid);
                if (info != null) {
                    processes.add(info);
                }
            }
        } else {
            // 非 root：只能看自己
            int myPid = android.os.Process.myPid();
            ProcessInfo info = readProcessInfo(myPid);
            if (info != null) {
                info.packageName = "self";
                processes.add(info);
            }
        }

        return processes;
    }

    /**
     * 读取单个进程信息
     * /proc/[pid]/cmdline  → 进程名
     * /proc/[pid]/status   → VmRSS, VmSwap, Threads
     * /proc/[pid]/stat     → CPU jiffies
     */
    public ProcessInfo readProcessInfo(int pid) {
        ProcessInfo info = new ProcessInfo();
        info.pid = pid;

        // 进程名 / 包名
        info.name = readFirstLine("/proc/" + pid + "/cmdline");
        if (info.name != null) {
            // cmdline 以 \0 分隔参数，只取第一个之前的部分作为进程名
            int nullIdx = info.name.indexOf('\0');
            if (nullIdx > 0) {
                info.name = info.name.substring(0, nullIdx);
            } else {
                info.name = info.name.trim();
            }
        }
        if (info.name == null || info.name.isEmpty()) {
            info.name = readFirstLine("/proc/" + pid + "/comm");
            if (info.name == null) return null;
        }

        // 内存：VmRSS + VmSwap + Threads（从 /proc/[pid]/status 读取）
        readMemoryInfo(pid, info);

        // CPU：jiffies（需要两次采样计算）
        info.cpuJiffies = readCpuJiffies(pid);

        // 内存泄漏嫌疑检测：连续 3 次 PSS 增长 > 10MB
        checkMemoryLeak(pid, info.vmRssKb, info);

        return info;
    }

    /**
     * 计算两个采样点之间的 CPU 使用率
     */
    public static float calculateCpuUsage(long[] prev, long[] cur, long clockTicksPerSec) {
        if (prev == null || cur == null || prev.length < 2 || cur.length < 2) return 0;
        long diff = (cur[0] + cur[1]) - (prev[0] + prev[1]);
        if (diff < 0) return 0;
        return (float) diff / clockTicksPerSec * 100f;
    }

    /**
     * 读取 /proc/[pid]/stat 的 utime + stime（单位：clock ticks）
     */
    private long[] readCpuJiffies(int pid) {
        try (BufferedReader br = new BufferedReader(
                new FileReader("/proc/" + pid + "/stat"))) {
            String line = br.readLine();
            if (line == null) return null;
            // stat 格式: pid (comm) state ppid ... utime stime ...
            // utime 和 stime 在第 14 和 15 个字段，但 comm 可能含空格和括号
            int lastParen = line.lastIndexOf(')');
            if (lastParen < 0) return null;
            String[] fields = line.substring(lastParen + 2).trim().split("\\s+");
            // fields[0]=state, fields[1]=ppid, ..., fields[11]=utime, fields[12]=stime
            if (fields.length < 13) return null;
            long utime = Long.parseLong(fields[11]);
            long stime = Long.parseLong(fields[12]);
            return new long[]{utime, stime};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读取 /proc/[pid]/status 获取 VmRSS, VmSwap, VmSize
     */
    private void readMemoryInfo(int pid, ProcessInfo info) {
        try (BufferedReader br = new BufferedReader(
                new FileReader("/proc/" + pid + "/status"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("VmRSS:")) info.vmRssKb = parseKb(line);
                else if (line.startsWith("VmSwap:")) info.vmSwapKb = parseKb(line);
                else if (line.startsWith("VmSize:")) info.vmSizeKb = parseKb(line);
                else if (line.startsWith("Threads:")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) info.threads = Integer.parseInt(parts[1]);
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * 清理已退出进程的历史记录 + 限制 Map 大小
     */
    private void cleanStaleHistory() {
        // 超过上限时清除全部历史（简单策略，避免遍历 /proc 检查存活）
        if (pidRssHistory.size() > MAX_HISTORY_SIZE) {
            pidRssHistory.clear();
        }
    }

    /**
     * 检测内存泄漏嫌疑：连续 3 次 VmRSS 增长 > 10MB
     */
    private void checkMemoryLeak(int pid, long currentRssKb, ProcessInfo info) {
        long[] history = pidRssHistory.get(pid);
        if (history == null) {
            // 首次：分配数组并记录采样次数
            history = new long[LEAK_CHECK_COUNT + 1]; // 最后一位存计数
            history[0] = currentRssKb;
            history[LEAK_CHECK_COUNT] = 1; // 已采样 1 次
            pidRssHistory.put(pid, history);
            return;
        }

        int count = (int) history[LEAK_CHECK_COUNT];
        // 左移历史，填入新值
        System.arraycopy(history, 1, history, 0, LEAK_CHECK_COUNT - 1);
        history[LEAK_CHECK_COUNT - 1] = currentRssKb;
        count++;
        history[LEAK_CHECK_COUNT] = count;

        // 不足 3 次采样时不做检测
        if (count < LEAK_CHECK_COUNT) {
            return;
        }

        // 检查是否连续 3 次增长 > 10MB
        boolean allIncreasing = true;
        for (int i = 1; i < LEAK_CHECK_COUNT; i++) {
            if (history[i] - history[i - 1] < LEAK_THRESHOLD_KB) {
                allIncreasing = false;
                break;
            }
        }
        info.suspicious = allIncreasing && history[0] > 0;
    }

    private String readFirstLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return br.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private long parseKb(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length >= 2) {
            return Long.parseLong(parts[1]);
        }
        return 0;
    }

    // ===== 数据类 =====

    public static class ProcessInfo {
        public int pid;
        public String name = "";
        public String packageName = null;
        public long vmRssKb;       // 物理内存（常驻集大小）
        public long vmSwapKb;      // 交换分区
        public long vmSizeKb;      // 虚拟内存总量
        public int threads = 1;
        public long[] cpuJiffies;  // [utime, stime] 用于计算 CPU 占用
        public float cpuUsagePct;  // 计算后的 CPU 使用率

        // 内存泄漏嫌疑标记
        public boolean suspicious;
    }
}
