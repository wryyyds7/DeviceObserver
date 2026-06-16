package com.wry.deviceobserver.monitor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 系统硬件监控：CPU 多核频率、内存、温度、网络流量
 * 通过读取 /sys 和 /proc 系统文件获取硬件指标，适配高通/联发科/三星 SoC。
 */
public class SystemMonitor {

    // ===== CPU =====

    /**
     * 获取所有 CPU 核心的实时频率（kHz）
     */
    public static List<Integer> getCpuCoreFrequencies() {
        List<Integer> freqs = new ArrayList<>();
        // 先获取核心数
        int cores = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < cores; i++) {
            // 路径: /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq
            String path = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq";
            int freq = readIntFromFile(path);
            if (freq < 0) {
                // 降级：读 /proc/cpuinfo 中的 cpu MHz
                freq = readCpuFreqFromProc(i);
            }
            freqs.add(freq);
        }
        return freqs;
    }

    /**
     * 获取 CPU 总体使用率（通过 /proc/stat 两次采样计算）
     * @param prevStat 上一次的 /proc/stat 第一行数据
     * @return [0]=idle%, [1]=usage%，或 null 如果无法获取
     */
    public static float[] getCpuUsageRate(long[] prevStat) {
        long[] curStat = readCpuStat();
        if (prevStat == null || curStat == null || prevStat.length < 4 || curStat.length < 4) {
            return null;
        }
        long prevIdle = prevStat[3];
        long curIdle = curStat[3];
        long prevTotal = prevStat[0] + prevStat[1] + prevStat[2] + prevStat[3];
        long curTotal = curStat[0] + curStat[1] + curStat[2] + curStat[3];
        long totalDiff = curTotal - prevTotal;
        long idleDiff = curIdle - prevIdle;
        if (totalDiff == 0) return new float[]{0, 0};
        float idleRate = (float) idleDiff / totalDiff * 100f;
        float usageRate = 100f - idleRate;
        return new float[]{idleRate, usageRate};
    }

    /**
     * 读取 /proc/stat 第一行（CPU 总体统计）
     * 返回 [user, nice, system, idle, iowait, irq, softirq]
     */
    public static long[] readCpuStat() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = br.readLine();
            if (line == null || !line.startsWith("cpu ")) return null;
            String[] parts = line.split("\\s+");
            long[] stat = new long[7];
            for (int i = 1; i < Math.min(parts.length, 8); i++) {
                stat[i - 1] = Long.parseLong(parts[i]);
            }
            return stat;
        } catch (Exception e) {
            return null;
        }
    }

    // ===== 内存 =====

    /**
     * 读取 /proc/meminfo
     * 返回 [totalKB, freeKB, availableKB, buffersKB, cachedKB, swapTotalKB, swapFreeKB]
     */
    public static long[] getMemoryInfo() {
        long[] info = new long[7];
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:")) info[0] = parseKb(line);
                else if (line.startsWith("MemFree:")) info[1] = parseKb(line);
                else if (line.startsWith("MemAvailable:")) info[2] = parseKb(line);
                else if (line.startsWith("Buffers:")) info[3] = parseKb(line);
                else if (line.startsWith("Cached:")) info[4] = parseKb(line);
                else if (line.startsWith("SwapTotal:")) info[5] = parseKb(line);
                else if (line.startsWith("SwapFree:")) info[6] = parseKb(line);
            }
        } catch (Exception e) {
            // ignore
        }
        return info;
    }

    // ===== 温度 =====

    /**
     * 读取 CPU/电池温度
     * 适配高通（/sys/class/thermal/thermal_zone*）、联发科、三星路径差异
     */
    public static List<ThermalZone> getThermalZones() {
        List<ThermalZone> zones = new ArrayList<>();
        File thermalDir = new File("/sys/class/thermal");
        if (!thermalDir.exists()) return zones;

        File[] zoneDirs = thermalDir.listFiles(
            (dir, name) -> name.startsWith("thermal_zone"));
        if (zoneDirs == null) return zones;

        for (File zoneDir : zoneDirs) {
            String type = readStringFromFile(zoneDir.getAbsolutePath() + "/type");
            int temp = readIntFromFile(zoneDir.getAbsolutePath() + "/temp");
            if (temp > 0 && type != null) {
                zones.add(new ThermalZone(zoneDir.getName(), type, temp / 1000.0));
            }
        }
        return zones;
    }

    // ===== 网络流量 =====

    /**
     * 读取 /proc/net/dev 获取各网络接口收发字节数
     */
    public static List<NetworkInterface> getNetworkInterfaces() {
        List<NetworkInterface> interfaces = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/net/dev"))) {
            String line;
            // 跳过前两行表头
            br.readLine(); br.readLine();
            while ((line = br.readLine()) != null) {
                String[] parts = line.trim().split(":");
                if (parts.length < 2) continue;
                String name = parts[0].trim();
                String[] stats = parts[1].trim().split("\\s+");
                if (stats.length >= 16) {
                    long rxBytes = Long.parseLong(stats[0]);
                    long txBytes = Long.parseLong(stats[8]);
                    interfaces.add(new NetworkInterface(name, rxBytes, txBytes));
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return interfaces;
    }

    // ===== 工具方法 =====

    private static int readIntFromFile(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            return line != null ? Integer.parseInt(line.trim()) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String readStringFromFile(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            return line != null ? line.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int readCpuFreqFromProc(int core) {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            int currentCore = -1;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("processor")) currentCore++;
                if (currentCore == core && line.contains("MHz")) {
                    String[] parts = line.split(":");
                    if (parts.length >= 2) {
                        return (int) (Float.parseFloat(parts[1].trim()) * 1000);
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return -1;
    }

    private static long parseKb(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length >= 2) {
            return Long.parseLong(parts[1]);
        }
        return 0;
    }

    // ===== 数据类 =====

    public static class ThermalZone {
        public final String name;
        public final String type;
        public final double tempCelsius;

        public ThermalZone(String name, String type, double tempCelsius) {
            this.name = name;
            this.type = type;
            this.tempCelsius = tempCelsius;
        }
    }

    public static class NetworkInterface {
        public final String name;
        public final long rxBytes;
        public final long txBytes;

        public NetworkInterface(String name, long rxBytes, long txBytes) {
            this.name = name;
            this.rxBytes = rxBytes;
            this.txBytes = txBytes;
        }
    }
}
