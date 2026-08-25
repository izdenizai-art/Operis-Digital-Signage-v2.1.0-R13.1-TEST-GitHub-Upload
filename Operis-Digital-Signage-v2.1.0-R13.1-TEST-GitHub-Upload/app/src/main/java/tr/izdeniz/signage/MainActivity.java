package tr.izdeniz.signage;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;

import org.json.JSONObject;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Native shell used by the Operis TV Player web asset. */
public final class MainActivity extends Activity {
    private static final String PREFS = "operis_player_native";
    private static final String DEFAULT_EXIT_PIN = "1453";
    private static final String KEY_EXIT_PIN = "exit_pin";
    private static final String KEY_SERVER = "server";
    private static final String KEY_DEVICE_CODE = "device_code";
    private static final String KEY_DEVICE_TOKEN = "device_token";

    private WebView webView;
    private int backCount;
    private long lastBackAt;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;
    private NativeTelemetryCollector telemetryCollector;
    private NativeApiClient apiClient;
    private NativeMediaCache mediaCache;
    private PowerManager.WakeLock playbackWakeLock;
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(2);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();

        devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, OperisDeviceAdminReceiver.class);
        telemetryCollector = new NativeTelemetryCollector(this);
        apiClient = new NativeApiClient(this);
        mediaCache = new NativeMediaCache(this);
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            playbackWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Operis:PlayerWake");
            playbackWakeLock.setReferenceCounted(false);
        }

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return true;
                return !isTrustedAssetUrl(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !isTrustedAssetUrl(Uri.parse(url));
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse local = mediaCache == null ? null : mediaCache.intercept(request.getUrl());
                return local != null ? local : super.shouldInterceptRequest(view, request);
            }
        });
        webView.addJavascriptInterface(new NativeBridge(), "OperisNative");
        setContentView(webView);
        openLocal(false);
    }

    @Override protected void onStart() {
        super.onStart();
        if (playbackWakeLock != null && !playbackWakeLock.isHeld()) {
            playbackWakeLock.acquire();
        }
    }

    @Override protected void onStop() {
        if (playbackWakeLock != null && playbackWakeLock.isHeld()) {
            playbackWakeLock.release();
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        ioExecutor.shutdownNow();
        if (playbackWakeLock != null && playbackWakeLock.isHeld()) {
            playbackWakeLock.release();
        }
        if (webView != null) {
            webView.removeJavascriptInterface("OperisNative");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private boolean isTrustedAssetUrl(Uri uri) {
        return uri != null
            && "file".equalsIgnoreCase(uri.getScheme())
            && "/android_asset/index.html".equals(uri.getPath());
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }
    private String exitPin() { return prefs().getString(KEY_EXIT_PIN, DEFAULT_EXIT_PIN); }
    private String savedServer() { return prefs().getString(KEY_SERVER, ""); }
    private void openLocal(boolean setup) {
        if (webView != null) webView.loadUrl("file:///android_asset/index.html" + (setup ? "?setup=1" : ""));
    }
    private boolean isDeviceOwner() {
        return devicePolicyManager != null && devicePolicyManager.isDeviceOwnerApp(getPackageName());
    }
    private boolean powerOnSupported() {
        // A powered-off Android APK cannot power the box on by itself. This stays false until
        // a real WOL/manufacturer/MDM/external-relay adapter is configured.
        return false;
    }
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    @Override public void onResume() { super.onResume(); immersive(); }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive(); }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            long now = System.currentTimeMillis();
            if (now - lastBackAt > 4000) backCount = 0;
            lastBackAt = now;
            backCount++;
            if (backCount >= 5) { backCount = 0; showMaintenanceAuth(); }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showMaintenanceAuth() {
        EditText pin = new EditText(this);
        pin.setSingleLine(true);
        pin.setHint("Bakım parolası");
        new AlertDialog.Builder(this).setTitle("Operis Digital Signage - Bakım")
            .setView(pin).setNegativeButton("İptal", null)
            .setPositiveButton("Aç", (d,w) -> { if (exitPin().equals(pin.getText().toString())) showMaintenanceMenu(); })
            .show();
    }

    private void showMaintenanceMenu() {
        String[] items = {
            "Bağlantı Ayarları",
            "Bağlantıyı Test Et",
            "Merkeze Yeniden Bağlan",
            "Yayını Yenile",
            "Telemetri Gönder",
            "Cihaz Bilgileri",
            "Player'ı Yeniden Başlat",
            "Uygulamayı Kapat"
        };
        new AlertDialog.Builder(this).setTitle("Operis Digital Signage - Servis")
            .setItems(items, (d, which) -> {
                switch (which) {
                    case 0: openLocal(true); break;
                    case 1: testConnection(); break;
                    case 2: callStage2("reconnect"); break;
                    case 3: callStage2("refreshState"); break;
                    case 4: callStage2("sendTelemetry"); break;
                    case 5: showDeviceInfo(); break;
                    case 6: recreate(); break;
                    case 7: closeApplication(); break;
                    default: break;
                }
            }).show();
    }

    private void closeApplication() {
        finishAndRemoveTask();
        finishAffinity();
    }

    private void testConnection() {
        String server = savedServer();
        if (server.trim().isEmpty()) {
            showInfo("Bağlantı Testi", "Sunucu adresi henüz kaydedilmemiş.");
            return;
        }
        ioExecutor.execute(() -> {
            String raw = apiClient.request("GET", server, "/health", "");
            String message;
            try {
                JSONObject result = new JSONObject(raw);
                message = result.optBoolean("ok", false)
                    ? "BAĞLANTI BAŞARILI\nHTTP " + result.optInt("status", 0)
                    : "SUNUCUYA ULAŞILAMIYOR\n" + result.optString("body", "Bağlantı kurulamadı.");
            } catch (Exception e) {
                message = "SUNUCUYA ULAŞILAMIYOR\nBağlantı sonucu okunamadı.";
            }
            final String text = message;
            runOnUiThread(() -> showInfo("Bağlantı Testi", text));
        });
    }

    private void showDeviceInfo() {
        JSONObject t = telemetryCollector.collect(isDeviceOwner(), powerOnSupported());
        String text =
            "Üretici / Model: " + display(t, "deviceManufacturer") + " / " + display(t, "deviceModel") + "\n" +
            "Android / SDK: " + display(t, "androidVersion") + " / " + display(t, "sdkLevel") + "\n" +
            "APK: " + display(t, "apkVersion") + " (" + display(t, "versionCode") + ")\n" +
            "Çözünürlük: " + display(t, "resolution") + "\n" +
            "Ağ / IP: " + display(t, "networkType") + " / " + display(t, "ipAddress") + "\n" +
            "RAM kullanım: %" + display(t, "ramUsedPercent") + "\n" +
            "CPU kullanım / sıcaklık: " + display(t, "cpuUsagePercent") + "% / " + display(t, "cpuTemperatureC") + " °C\n" +
            "GPU kullanım / sıcaklık: " + display(t, "gpuUsagePercent") + "% / " + display(t, "gpuTemperatureC") + " °C\n" +
            "WOL MAC adayı: " + display(t, "wolMacAddress") + "\n" +
            "Uptime: " + display(t, "uptimeSeconds") + " sn\n" +
            "Device Owner: " + (isDeviceOwner() ? "Evet" : "Hayır") + "\n" +
            "Reboot: " + (isDeviceOwner() ? "Destekleniyor" : "Desteklenmiyor") + "\n" +
            "Power-on: " + (powerOnSupported() ? "Destekleniyor" : "Desteklenmiyor");
        showInfo("Cihaz Bilgileri", text);
    }

    private String display(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value == null || JSONObject.NULL.equals(value) || String.valueOf(value).trim().isEmpty()) return "Desteklenmiyor";
        return String.valueOf(value);
    }

    private void showInfo(String title, String message) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Tamam", null).show();
    }

    private void callStage2(String method) {
        if (webView == null) return;
        String script = "window.OperisStage2&&window.OperisStage2." + method + "&&window.OperisStage2." + method + "();";
        webView.evaluateJavascript(script, null);
    }

    private void dispatchBridgeCallback(String requestId, String resultJson) {
        runOnUiThread(() -> {
            if (webView == null) return;
            String script = "window.OperisNativeCallbacks&&window.OperisNativeCallbacks.resolve(" +
                JSONObject.quote(requestId == null ? "" : requestId) + "," +
                JSONObject.quote(resultJson == null ? "{}" : resultJson) + ");";
            webView.evaluateJavascript(script, null);
        });
    }

    private String executeDeviceCommand(String rawType) {
        String type = rawType == null ? "" : rawType.trim().toUpperCase(Locale.ROOT);
        boolean owner = isDeviceOwner();
        boolean power = powerOnSupported();
        if (!CommandCapabilityPolicy.supports(owner, power, type)) {
            return commandResult(false, false, type, owner, power,
                "Komut bu cihaz çalışma modunda desteklenmiyor.");
        }

        try {
            switch (type) {
                case "DEVICE_REBOOT":
                    // JS writes the pending command id before entering this method. A successful
                    // reboot is completed only after a post-boot heartbeat reaches the server.
                    devicePolicyManager.reboot(adminComponent);
                    return commandResult(true, true, type, owner, power,
                        "Cihaz yeniden başlatma komutu işletim sistemine iletildi.");
                case "KIOSK_LOCK":
                    devicePolicyManager.setLockTaskPackages(adminComponent, new String[]{getPackageName()});
                    runUiBlocking(this::startLockTask);
                    return commandResult(true, true, type, owner, power, "Kiosk kilidi etkinleştirildi.");
                case "KIOSK_UNLOCK":
                    runUiBlocking(() -> {
                        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                        if (am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                            stopLockTask();
                        }
                    });
                    devicePolicyManager.setLockTaskPackages(adminComponent, new String[0]);
                    return commandResult(true, true, type, owner, power, "Kiosk kilidi kaldırıldı.");
                case "POWER_ON":
                    return commandResult(false, false, type, owner, power,
                        "POWER_ON için WOL, üretici API'si, MDM özelliği veya harici röle gerekir.");
                default:
                    return commandResult(true, true, type, owner, power, "Komut player katmanı tarafından işlenecek.");
            }
        } catch (SecurityException e) {
            return commandResult(false, true, type, owner, power,
                "Device Owner yetkisi doğrulanamadı: " + e.getMessage());
        } catch (Exception e) {
            return commandResult(false, true, type, owner, power,
                "Komut uygulanamadı: " + e.getMessage());
        }
    }

    private void runUiBlocking(UiAction action) throws Exception {
        if (Thread.currentThread() == getMainLooper().getThread()) {
            action.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        runOnUiThread(() -> {
            try {
                action.run();
            } catch (Exception e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("UI komut zaman aşımı");
        if (error.get() != null) throw error.get();
    }

    private String commandResult(boolean ok, boolean supported, String type, boolean owner, boolean power, String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", ok);
            o.put("supported", supported);
            o.put("command", type);
            o.put("deviceOwner", owner);
            o.put("powerOnSupported", power);
            o.put("message", message == null ? "" : message);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    private String capabilitiesJson() {
        boolean owner = isDeviceOwner();
        boolean power = powerOnSupported();
        JSONObject o = new JSONObject();
        try {
            o.put("deviceOwner", owner);
            o.put("rebootSupported", CommandCapabilityPolicy.supports(owner, power, "DEVICE_REBOOT"));
            o.put("kioskLockSupported", CommandCapabilityPolicy.supports(owner, power, "KIOSK_LOCK"));
            o.put("kioskUnlockSupported", CommandCapabilityPolicy.supports(owner, power, "KIOSK_UNLOCK"));
            o.put("powerOnSupported", CommandCapabilityPolicy.supports(owner, power, "POWER_ON"));
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    private interface UiAction { void run() throws Exception; }

    public final class NativeBridge {
        @JavascriptInterface public void updateExitPassword(String value) {
            if (value != null && value.matches("\\d{4,12}")) prefs().edit().putString(KEY_EXIT_PIN, value).apply();
        }

        @JavascriptInterface public void setConnectionSettings(String server, String code) {
            String cleanServer = server == null ? "" : server.trim().replaceAll("/+$", "");
            String cleanCode = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
            prefs().edit().putString(KEY_SERVER, cleanServer).putString(KEY_DEVICE_CODE, cleanCode).apply();
        }

        @JavascriptInterface public String getDeviceToken() {
            return prefs().getString(KEY_DEVICE_TOKEN, "");
        }

        @JavascriptInterface public void setDeviceToken(String token) {
            prefs().edit().putString(KEY_DEVICE_TOKEN, token == null ? "" : token).apply();
        }

        @JavascriptInterface public void clearDeviceToken() {
            prefs().edit().remove(KEY_DEVICE_TOKEN).apply();
        }

        @JavascriptInterface public String getTelemetryJson() {
            return telemetryCollector.collect(isDeviceOwner(), powerOnSupported()).toString();
        }

        @JavascriptInterface public String getCapabilitiesJson() {
            return capabilitiesJson();
        }

        @JavascriptInterface public void apiRequestAsync(
            String requestId, String method, String serverBase, String pathAndQuery, String body
        ) {
            ioExecutor.execute(() -> dispatchBridgeCallback(
                requestId, apiClient.request(method, serverBase, pathAndQuery, body)));
        }

        @JavascriptInterface public void downloadAndVerifyApkAsync(
            String requestId,
            String serverBase,
            String candidateUrl,
            String sha256,
            String commandId,
            String expectedMetadataJson
        ) {
            ioExecutor.execute(() -> dispatchBridgeCallback(
                requestId,
                apiClient.downloadAndVerifyApk(
                    serverBase, candidateUrl, sha256, commandId, expectedMetadataJson)));
        }


        @JavascriptInterface public void downloadMediaAsync(
            String requestId, String serverBase, String relativeUrl, String mediaId, String expectedSha256
        ) {
            ioExecutor.execute(() -> dispatchBridgeCallback(
                requestId, mediaCache.downloadAndVerify(serverBase, relativeUrl, mediaId, expectedSha256)));
        }

        @JavascriptInterface public String getCachedMediaUrl(String mediaId) {
            return mediaCache.cachedUrl(mediaId);
        }

        @JavascriptInterface public String getMediaCacheStatusJson() {
            return mediaCache.statusJson();
        }


        @JavascriptInterface public void captureAndUploadPreviewAsync(
            String requestId, String serverBase, String code, String token
        ) {
            runOnUiThread(() -> {
                try {
                    final PlayerPreviewCapture.Capture capture = PlayerPreviewCapture.capture(webView);
                    ioExecutor.execute(() -> {
                        JSONObject result = new JSONObject();
                        try {
                            String capturedAt = Instant.ofEpochMilli(capture.capturedAtMs).toString();
                            JSONObject payload = new JSONObject();
                            payload.put("Code", code == null ? "" : code);
                            payload.put("Token", token == null ? "" : token);
                            payload.put("CapturedAt", capturedAt);
                            payload.put("Width", capture.width);
                            payload.put("Height", capture.height);
                            payload.put("JpegBase64", capture.jpegBase64);
                            JSONObject transport = new JSONObject(
                                apiClient.request("POST", serverBase, "/api/device/preview", payload.toString()));
                            result.put("ok", transport.optBoolean("ok", false));
                            result.put("status", transport.optInt("status", 0));
                            result.put("capturedAt", capturedAt);
                            result.put("width", capture.width);
                            result.put("height", capture.height);
                            result.put("bytes", capture.bytes);
                            result.put("serverBody", transport.optString("body", ""));
                            if (!transport.optBoolean("ok", false)) {
                                result.put("message", transport.optString("body", "Preview sunucuya gönderilemedi."));
                            }
                        } catch (Exception e) {
                            try {
                                result.put("ok", false);
                                result.put("status", 0);
                                result.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                            } catch (Exception ignored) {}
                        }
                        dispatchBridgeCallback(requestId, result.toString());
                    });
                } catch (Exception e) {
                    JSONObject result = new JSONObject();
                    try {
                        result.put("ok", false);
                        result.put("status", 0);
                        result.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    } catch (Exception ignored) {}
                    dispatchBridgeCallback(requestId, result.toString());
                }
            });
        }

        @JavascriptInterface public String executeDeviceCommand(String type) {
            return MainActivity.this.executeDeviceCommand(type);
        }
    }
}
