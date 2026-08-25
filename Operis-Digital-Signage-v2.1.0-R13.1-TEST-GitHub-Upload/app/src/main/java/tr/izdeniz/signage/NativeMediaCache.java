package tr.izdeniz.signage;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;

/** App-private verified cache for scheduled signage images and videos. */
public final class NativeMediaCache {
    private static final int CONNECT_TIMEOUT_MS = 7000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final long MAX_MEDIA_BYTES = 1024L * 1024L * 1024L;
    private static final String LOCAL_PREFIX = "https://operis.local/media/";

    private final File dir;

    public NativeMediaCache(Context context) {
        dir = new File(context.getApplicationContext().getFilesDir(), "operis-media");
        if (!dir.exists()) dir.mkdirs();
    }

    public static boolean isAllowedRelativeMediaPath(String path) {
        return MediaCachePolicy.isAllowedRelativeMediaPath(path);
    }

    public String downloadAndVerify(
        String serverBase,
        String relativePath,
        String mediaId,
        String expectedSha256
    ) {
        JSONObject out = new JSONObject();
        HttpURLConnection connection = null;
        File part = null;
        File metaPart = null;
        try {
            String safeId = MediaCachePolicy.safeMediaId(mediaId);
            if (safeId == null) throw new IllegalArgumentException("Medya kimliği geçersiz.");
            if (!isAllowedRelativeMediaPath(relativePath)) throw new IllegalArgumentException("Medya yolu yalnız /media/ altında göreli olmalı.");
            String expected = normalizeDigest(expectedSha256);
            if (!expected.isEmpty() && expected.length() != 64) throw new IllegalArgumentException("Medya SHA-256 değeri geçersiz.");

            String resolved = NativeApiRequestPolicy.resolveSameOriginDownload(serverBase, relativePath);
            File finalFile = dataFile(safeId);
            File finalMeta = metaFile(safeId);
            part = new File(dir, safeId + ".part");
            metaPart = new File(dir, safeId + ".meta.part");
            if (part.exists()) part.delete();
            if (metaPart.exists()) metaPart.delete();

            connection = (HttpURLConnection) new URL(resolved).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Operis-Digital-Signage/2.1.0-r13.1");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("Medya indirme HTTP " + status);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0L;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = connection.getInputStream(); FileOutputStream fos = new FileOutputStream(part)) {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (n == 0) continue;
                    total += n;
                    if (total > MAX_MEDIA_BYTES) throw new IllegalStateException("Medya dosyası boyut sınırını aşıyor.");
                    digest.update(buffer, 0, n);
                    fos.write(buffer, 0, n);
                }
                fos.getFD().sync();
            }

            String actual = hex(digest.digest());
            if (!expected.isEmpty() && !expected.equals(actual)) throw new SecurityException("Medya SHA-256 eşleşmiyor.");
            String mime = mimeType(relativePath);
            JSONObject meta = new JSONObject();
            meta.put("mediaId", safeId);
            meta.put("sourcePath", relativePath);
            meta.put("sha256", actual);
            meta.put("bytes", total);
            meta.put("mimeType", mime);
            meta.put("cachedAt", System.currentTimeMillis());
            try (FileOutputStream mos = new FileOutputStream(metaPart)) {
                mos.write(meta.toString().getBytes(StandardCharsets.UTF_8));
                mos.getFD().sync();
            }

            Files.move(part.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.move(metaPart.toPath(), finalMeta.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            out.put("ok", true);
            out.put("mediaId", safeId);
            out.put("sha256", actual);
            out.put("bytes", total);
            out.put("mimeType", mime);
            out.put("url", cachedUrl(safeId));
        } catch (Exception e) {
            if (part != null && part.exists()) part.delete();
            if (metaPart != null && metaPart.exists()) metaPart.delete();
            safePut(out, "ok", false);
            safePut(out, "message", safeMessage(e));
        } finally {
            if (connection != null) connection.disconnect();
        }
        return out.toString();
    }

    public String cachedUrl(String mediaId) {
        String safeId = MediaCachePolicy.safeMediaId(mediaId);
        return safeId != null && dataFile(safeId).isFile() ? LOCAL_PREFIX + safeId : "";
    }

    public WebResourceResponse intercept(Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || !"operis.local".equalsIgnoreCase(uri.getHost())) return null;
        String path = uri.getPath();
        if (path == null || !path.startsWith("/media/")) return null;
        String safeId = MediaCachePolicy.safeMediaId(path.substring("/media/".length()));
        if (safeId == null) return null;
        File file = dataFile(safeId);
        if (!file.isFile()) return null;
        try {
            String mime = "application/octet-stream";
            JSONObject meta = readMeta(safeId);
            if (meta != null) mime = meta.optString("mimeType", mime);
            return new WebResourceResponse(mime, null, new FileInputStream(file));
        } catch (Exception e) {
            return null;
        }
    }

    public String statusJson() {
        JSONObject out = new JSONObject();
        JSONArray items = new JSONArray();
        long bytes = 0L;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".bin"));
        if (files != null) {
            for (File file : files) {
                String id = file.getName().substring(0, file.getName().length() - 4);
                bytes += Math.max(0L, file.length());
                JSONObject item = readMeta(id);
                if (item == null) item = new JSONObject();
                safePut(item, "mediaId", id);
                safePut(item, "bytes", file.length());
                safePut(item, "url", cachedUrl(id));
                items.put(item);
            }
        }
        safePut(out, "ok", true);
        safePut(out, "count", items.length());
        safePut(out, "bytes", bytes);
        safePut(out, "items", items);
        return out.toString();
    }

    private File dataFile(String safeId) { return new File(dir, safeId + ".bin"); }
    private File metaFile(String safeId) { return new File(dir, safeId + ".meta.json"); }

    private JSONObject readMeta(String safeId) {
        try {
            File file = metaFile(safeId);
            if (!file.isFile() || file.length() > 1024 * 1024) return null;
            return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static String mimeType(String path) {
        String mime = URLConnection.guessContentTypeFromName(path);
        if (mime != null) return mime;
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".mp4")) return "video/mp4";
        if (p.endsWith(".webm")) return "video/webm";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    private static String normalizeDigest(String value) {
        return value == null ? "" : value.replace(":", "").replace(" ", "").trim().toLowerCase(Locale.ROOT);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private static void safePut(JSONObject target, String key, Object value) {
        try { target.put(key, value); } catch (Exception ignored) {}
    }
}
