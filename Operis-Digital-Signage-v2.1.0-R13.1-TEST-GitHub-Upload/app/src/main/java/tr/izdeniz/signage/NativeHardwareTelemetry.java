package tr.izdeniz.signage;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

/** Best-effort read-only hardware probes. Unsupported values are returned as null. */
public final class NativeHardwareTelemetry {
    private static long previousCpuTotal = -1L;
    private static long previousCpuIdle = -1L;

    private NativeHardwareTelemetry() {}

    public static Double parseTemperature(String raw) {
        try {
            double value = Double.parseDouble(String.valueOf(raw).trim());
            if (Math.abs(value) >= 1000.0d) value /= 1000.0d;
            if (!Double.isFinite(value) || value < -30.0d || value > 200.0d) return null;
            return Math.round(value * 10.0d) / 10.0d;
        } catch (Exception e) {
            return null;
        }
    }

    public static Double parseBusyPair(String raw) {
        try {
            String[] parts = String.valueOf(raw).trim().split("\\s+");
            if (parts.length < 2) return null;
            double busy = Double.parseDouble(parts[0]);
            double total = Double.parseDouble(parts[1]);
            if (!Double.isFinite(busy) || !Double.isFinite(total) || busy < 0 || total <= 0) return null;
            double pct = Math.max(0.0d, Math.min(100.0d, (busy * 100.0d) / total));
            return Math.round(pct * 10.0d) / 10.0d;
        } catch (Exception e) {
            return null;
        }
    }

    public static synchronized Double readCpuUsagePercent() {
        try {
            String first = readSmall(new File("/proc/stat"));
            if (first == null) return null;
            String line = first.split("\\r?\\n", 2)[0].trim();
            String[] p = line.split("\\s+");
            if (p.length < 5 || !"cpu".equals(p[0])) return null;
            long total = 0L;
            for (int i = 1; i < p.length; i++) total += Long.parseLong(p[i]);
            long idle = Long.parseLong(p[4]) + (p.length > 5 ? Long.parseLong(p[5]) : 0L);
            if (previousCpuTotal < 0L || total <= previousCpuTotal) {
                previousCpuTotal = total;
                previousCpuIdle = idle;
                return null;
            }
            long totalDelta = total - previousCpuTotal;
            long idleDelta = idle - previousCpuIdle;
            previousCpuTotal = total;
            previousCpuIdle = idle;
            if (totalDelta <= 0L) return null;
            double pct = 100.0d * Math.max(0L, totalDelta - idleDelta) / totalDelta;
            return Math.round(Math.min(100.0d, pct) * 10.0d) / 10.0d;
        } catch (Exception e) {
            return null;
        }
    }

    public static Double readCpuTemperatureC() {
        return readThermalByType(new String[]{"cpu", "soc", "package", "ap"}, false);
    }

    public static Double readGpuTemperatureC() {
        return readThermalByType(new String[]{"gpu", "kgsl"}, true);
    }

    public static Double readGpuUsagePercent() {
        String raw = readSmall(new File("/sys/class/kgsl/kgsl-3d0/gpubusy"));
        return raw == null ? null : parseBusyPair(raw);
    }

    public static String readNetworkMacAddress() {
        try {
            Enumeration<NetworkInterface> enumeration = NetworkInterface.getNetworkInterfaces();
            if (enumeration == null) return null;
            String fallback = null;
            for (NetworkInterface ni : Collections.list(enumeration)) {
                if (ni == null || !ni.isUp() || ni.isLoopback()) continue;
                byte[] hardware = ni.getHardwareAddress();
                if (hardware == null || hardware.length != 6) continue;
                String mac = formatMac(hardware);
                if (mac == null) continue;
                String name = String.valueOf(ni.getName()).toLowerCase(Locale.ROOT);
                if (name.startsWith("eth") || name.startsWith("en")) return mac;
                if (fallback == null) fallback = mac;
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private static Double readThermalByType(String[] hints, boolean strict) {
        try {
            File root = new File("/sys/class/thermal");
            File[] zones = root.listFiles((dir, name) -> name.startsWith("thermal_zone"));
            if (zones == null) return null;
            Double fallback = null;
            for (File zone : zones) {
                String type = readSmall(new File(zone, "type"));
                String raw = readSmall(new File(zone, "temp"));
                Double value = raw == null ? null : parseTemperature(raw);
                if (value == null) continue;
                String lower = type == null ? "" : type.toLowerCase(Locale.ROOT);
                boolean match = false;
                for (String hint : hints) if (lower.contains(hint)) { match = true; break; }
                if (match) return value;
                if (!strict && fallback == null && !lower.contains("battery")) fallback = value;
            }
            return strict ? null : fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readSmall(File file) {
        try {
            if (!file.isFile() || !file.canRead() || file.length() > 64 * 1024) return null;
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatMac(byte[] bytes) {
        boolean any = false;
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            int v = b & 0xff;
            if (v != 0) any = true;
            if (out.length() > 0) out.append(':');
            out.append(String.format(Locale.ROOT, "%02X", v));
        }
        return any ? out.toString() : null;
    }
}
