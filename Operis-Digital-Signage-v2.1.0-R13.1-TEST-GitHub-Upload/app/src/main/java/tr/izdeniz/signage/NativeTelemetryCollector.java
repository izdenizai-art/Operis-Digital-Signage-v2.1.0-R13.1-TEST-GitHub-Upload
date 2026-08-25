package tr.izdeniz.signage;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.os.Build;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.DisplayMetrics;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;

/** Collects only values that can be read reliably on a normal Android 11+ TV device. */
public final class NativeTelemetryCollector {
    private final Context context;
    public NativeTelemetryCollector(Context context) {
        this.context = context.getApplicationContext();
    }

    public JSONObject collect(boolean deviceOwner, boolean powerOnSupported) {
        JSONObject out = new JSONObject();
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            out.put("apkVersion", pi.versionName == null ? "Desteklenmiyor" : pi.versionName);
            out.put("versionCode", pi.getLongVersionCode());
        } catch (Exception e) {
            safePut(out, "apkVersion", "Desteklenmiyor");
            safePut(out, "versionCode", "Desteklenmiyor");
        }

        safePut(out, "androidVersion", Build.VERSION.RELEASE == null ? "Desteklenmiyor" : Build.VERSION.RELEASE);
        safePut(out, "sdkLevel", Build.VERSION.SDK_INT);
        safePut(out, "deviceManufacturer", Build.MANUFACTURER == null ? "Desteklenmiyor" : Build.MANUFACTURER);
        safePut(out, "deviceModel", Build.MODEL == null ? "Desteklenmiyor" : Build.MODEL);
        safePut(out, "uptimeSeconds", SystemClock.elapsedRealtime() / 1000L);

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        if (am != null) {
            am.getMemoryInfo(mi);
            long used = Math.max(0L, mi.totalMem - mi.availMem);
            safePut(out, "ramTotalBytes", mi.totalMem);
            safePut(out, "ramUsedBytes", used);
            safePut(out, "ramUsedPercent", percent(used, mi.totalMem));
        } else {
            safePut(out, "ramTotalBytes", "Desteklenmiyor");
            safePut(out, "ramUsedBytes", "Desteklenmiyor");
            safePut(out, "ramUsedPercent", "Desteklenmiyor");
        }

        try {
            StatFs stat = new StatFs(context.getFilesDir().getAbsolutePath());
            safePut(out, "storageTotalBytes", stat.getTotalBytes());
            safePut(out, "storageFreeBytes", stat.getAvailableBytes());
        } catch (Exception e) {
            safePut(out, "storageTotalBytes", "Desteklenmiyor");
            safePut(out, "storageFreeBytes", "Desteklenmiyor");
        }

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        safePut(out, "resolution", dm.widthPixels + "x" + dm.heightPixels);
        safePut(out, "networkType", networkType());
        safePut(out, "ipAddress", ipv4Address());

        Double cpuUsage = NativeHardwareTelemetry.readCpuUsagePercent();
        Double cpuTemp = NativeHardwareTelemetry.readCpuTemperatureC();
        Double gpuUsage = NativeHardwareTelemetry.readGpuUsagePercent();
        Double gpuTemp = NativeHardwareTelemetry.readGpuTemperatureC();
        String wolMacAddress = NativeHardwareTelemetry.readNetworkMacAddress();
        safePut(out, "cpuUsagePercent", cpuUsage == null ? "Desteklenmiyor" : cpuUsage);
        safePut(out, "cpuTemperatureC", cpuTemp == null ? "Desteklenmiyor" : cpuTemp);
        safePut(out, "gpuUsagePercent", gpuUsage == null ? "Desteklenmiyor" : gpuUsage);
        safePut(out, "gpuTemperatureC", gpuTemp == null ? "Desteklenmiyor" : gpuTemp);
        safePut(out, "wolMacAddress", wolMacAddress == null ? "Desteklenmiyor" : wolMacAddress);
        safePut(out, "wolCandidate", wolMacAddress == null ? "Desteklenmiyor" : true);

        safePut(out, "deviceOwner", deviceOwner);
        safePut(out, "rebootSupported", deviceOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N);
        safePut(out, "kioskSupported", deviceOwner);
        safePut(out, "powerOnSupported", powerOnSupported);
        return out;
    }

    private String networkType() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "Desteklenmiyor";
            Network active = cm.getActiveNetwork();
            NetworkCapabilities nc = active == null ? null : cm.getNetworkCapabilities(active);
            if (nc == null) return "BAGLANTI_YOK";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ETHERNET";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WIFI";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "CELLULAR";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN";
            return "DIGER";
        } catch (Exception e) {
            return "Desteklenmiyor";
        }
    }

    private String ipv4Address() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "Desteklenmiyor";
            Network active = cm.getActiveNetwork();
            LinkProperties properties = active == null ? null : cm.getLinkProperties(active);
            if (properties == null) return "Desteklenmiyor";
            for (LinkAddress linkAddress : properties.getLinkAddresses()) {
                InetAddress address = linkAddress.getAddress();
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    return address.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "Desteklenmiyor";
    }

    private static Object percent(long used, long total) {
        if (total <= 0L) return "Desteklenmiyor";
        return Math.round((used * 1000.0d) / total) / 10.0d;
    }

    private static void safePut(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (Exception ignored) {
        }
    }
}
