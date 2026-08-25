package tr.izdeniz.signage;

import java.net.URI;

/** Pure validation helpers for the native media cache. */
public final class MediaCachePolicy {
    private MediaCachePolicy() {}

    public static boolean isAllowedRelativeMediaPath(String value) {
        if (value == null || value.isEmpty() || !value.startsWith("/media/") || value.startsWith("//")) return false;
        if (value.contains("\\") || value.contains("..")) return false;
        try {
            URI uri = URI.create(value);
            return uri.getScheme() == null && uri.getHost() == null && uri.getPath() != null && uri.getPath().startsWith("/media/");
        } catch (Exception e) {
            return false;
        }
    }

    public static String safeMediaId(String value) {
        if (value == null) return null;
        String s = value.trim();
        return s.matches("[A-Za-z0-9._-]{1,128}") && !s.contains("..") ? s : null;
    }
}
