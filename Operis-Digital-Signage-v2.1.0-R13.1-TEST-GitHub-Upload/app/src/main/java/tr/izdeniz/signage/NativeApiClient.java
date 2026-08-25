package tr.izdeniz.signage;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Native HTTP transport used by the WebView UI. No WebView cross-origin API fetch is required. */
public final class NativeApiClient {
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 12000;
    private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    private static final long MAX_APK_BYTES = 512L * 1024L * 1024L;

    private final Context context;

    public NativeApiClient(Context context) {
        this.context = context.getApplicationContext();
    }

    public String request(String method, String serverBase, String pathAndQuery, String body) {
        JSONObject out = new JSONObject();
        HttpURLConnection connection = null;
        try {
            String verb = normalizeMethod(method);
            String url = NativeApiRequestPolicy.buildApiUrl(serverBase, pathAndQuery);
            connection = open(url, verb);
            if ("POST".equals(verb)) {
                byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(bytes);
                }
            }

            int status = connection.getResponseCode();
            String responseBody = readText(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            out.put("ok", status >= 200 && status < 300);
            out.put("status", status);
            out.put("body", responseBody);
            out.put("errorCode", status >= 200 && status < 300 ? "" : "HTTP_" + status);
        } catch (IllegalArgumentException e) {
            safePut(out, "ok", false);
            safePut(out, "status", 0);
            safePut(out, "body", e.getMessage());
            safePut(out, "errorCode", "ISTEK_GECERSIZ");
        } catch (Exception e) {
            safePut(out, "ok", false);
            safePut(out, "status", 0);
            safePut(out, "body", "SUNUCUYA ULAŞILAMIYOR: " + safeMessage(e));
            safePut(out, "errorCode", "SUNUCUYA_ULASILAMIYOR");
        } finally {
            if (connection != null) connection.disconnect();
        }
        return out.toString();
    }

    public String downloadAndVerifyApk(
        String serverBase,
        String candidateUrl,
        String expectedSha256,
        String commandId,
        String expectedMetadataJson
    ) {
        JSONObject out = new JSONObject();
        HttpURLConnection connection = null;
        File candidate = null;
        try {
            String expectedHash = normalizeDigest(expectedSha256);
            if (expectedHash.length() != 64) throw new IllegalArgumentException("APK SHA-256 değeri zorunlu ve 64 hex karakter olmalı.");

            String url = NativeApiRequestPolicy.resolveSameOriginDownload(serverBase, candidateUrl);
            String safeId = commandId == null ? "unknown" : commandId.replaceAll("[^A-Za-z0-9._-]", "_");
            File dir = new File(context.getCacheDir(), "apk-candidates");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("APK aday klasörü oluşturulamadı.");
            candidate = new File(dir, "candidate-" + safeId + ".apk");

            connection = open(url, "GET");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("APK indirme HTTP " + status + ": " + readText(connection.getErrorStream()));
            }

            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            long total = 0L;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = connection.getInputStream(); FileOutputStream fileOut = new FileOutputStream(candidate)) {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (n == 0) continue;
                    total += n;
                    if (total > MAX_APK_BYTES) throw new IllegalStateException("APK izin verilen boyut sınırını aşıyor.");
                    sha256.update(buffer, 0, n);
                    fileOut.write(buffer, 0, n);
                }
                fileOut.getFD().sync();
            }

            String actualHash = hex(sha256.digest());
            if (!actualHash.equals(expectedHash)) {
                throw new SecurityException("APK SHA-256 eşleşmiyor.");
            }

            JSONObject metadata = expectedMetadataJson == null || expectedMetadataJson.trim().isEmpty()
                ? new JSONObject() : new JSONObject(expectedMetadataJson);
            ApkInfo verified = verifyArchive(candidate, metadata);

            out.put("ok", true);
            out.put("status", 200);
            out.put("sha256", actualHash);
            out.put("bytes", total);
            out.put("packageName", verified.packageName);
            out.put("versionName", verified.versionName);
            out.put("versionCode", verified.versionCode);
            out.put("signatureDigest", verified.signatureDigest);
            out.put("candidateId", safeId);
            out.put("message", "APK indirildi; hash, paket kimliği ve imza doğrulandı.");
        } catch (Exception e) {
            if (candidate != null && candidate.exists()) candidate.delete();
            safePut(out, "ok", false);
            safePut(out, "status", 0);
            safePut(out, "message", safeMessage(e));
        } finally {
            if (connection != null) connection.disconnect();
        }
        return out.toString();
    }

    private HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Operis-TV-Player/2.1.0 Android");
        return connection;
    }

    private String normalizeMethod(String method) {
        String value = method == null ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        if (!("GET".equals(value) || "POST".equals(value))) {
            throw new IllegalArgumentException("Yalnız GET ve POST desteklenir.");
        }
        return value;
    }

    private String readText(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            int total = 0;
            while ((n = in.read(buffer)) >= 0) {
                if (n == 0) continue;
                total += n;
                if (total > MAX_JSON_BYTES) throw new IllegalStateException("Sunucu yanıtı beklenen boyutu aşıyor.");
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private ApkInfo verifyArchive(File apk, JSONObject expected) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null || archive.signingInfo == null) throw new SecurityException("APK paket bilgisi/imzası okunamadı.");

        String packageName = archive.packageName == null ? "" : archive.packageName;
        String versionName = archive.versionName == null ? "" : archive.versionName;
        long versionCode = archive.getLongVersionCode();
        if (!context.getPackageName().equals(packageName)) {
            throw new SecurityException("APK packageName mevcut Operis uygulamasıyla eşleşmiyor.");
        }
        Set<String> archiveSigners = signerDigests(archive.signingInfo);
        if (archiveSigners.isEmpty()) throw new SecurityException("APK imza sertifikası bulunamadı.");

        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        Set<String> installedSigners = signerDigests(installed.signingInfo);
        boolean sameSigner = false;
        for (String digest : archiveSigners) {
            if (installedSigners.contains(digest)) {
                sameSigner = true;
                break;
            }
        }
        if (!sameSigner) throw new SecurityException("APK mevcut Operis uygulamasıyla aynı signing identity'ye sahip değil.");

        String expectedPackage = expected.optString("packageName", "").trim();
        if (!expectedPackage.isEmpty() && !expectedPackage.equals(packageName)) {
            throw new SecurityException("APK packageName beklenen değerle eşleşmiyor.");
        }
        String expectedVersionName = expected.optString("versionName", "").trim();
        if (!expectedVersionName.isEmpty() && !expectedVersionName.equals(versionName)) {
            throw new SecurityException("APK versionName beklenen değerle eşleşmiyor.");
        }
        if (expected.has("versionCode") && expected.optLong("versionCode", -1L) >= 0L
            && expected.optLong("versionCode", -1L) != versionCode) {
            throw new SecurityException("APK versionCode beklenen değerle eşleşmiyor.");
        }
        String expectedSigner = normalizeDigest(expected.optString("signatureDigest", ""));
        if (!expectedSigner.isEmpty() && !archiveSigners.contains(expectedSigner)) {
            throw new SecurityException("APK signatureDigest beklenen değerle eşleşmiyor.");
        }

        return new ApkInfo(packageName, versionName, versionCode, archiveSigners.iterator().next());
    }

    private static Set<String> signerDigests(SigningInfo signingInfo) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        if (signingInfo == null) return out;
        Signature[] signatures = signingInfo.hasMultipleSigners()
            ? signingInfo.getApkContentsSigners()
            : signingInfo.getSigningCertificateHistory();
        if (signatures == null) return out;
        for (Signature signature : signatures) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            out.add(hex(digest.digest(signature.toByteArray())));
        }
        return out;
    }

    private static String normalizeDigest(String value) {
        if (value == null) return "";
        return value.replace(":", "").replace(" ", "").trim().toLowerCase(Locale.ROOT);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static void safePut(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (Exception ignored) {
        }
    }

    private static final class ApkInfo {
        final String packageName;
        final String versionName;
        final long versionCode;
        final String signatureDigest;

        ApkInfo(String packageName, String versionName, long versionCode, String signatureDigest) {
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.signatureDigest = signatureDigest;
        }
    }
}
