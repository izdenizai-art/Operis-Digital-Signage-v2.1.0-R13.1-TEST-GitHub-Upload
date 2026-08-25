package tr.izdeniz.signage;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Pure URL policy for the native API transport. */
public final class NativeApiRequestPolicy {
    private NativeApiRequestPolicy() {}

    public static String buildApiUrl(String serverBase, String pathAndQuery) {
        URI base = parseBase(serverBase);
        URI relative = parseRelative(pathAndQuery);
        String path = relative.getRawPath();
        if (!("/health".equals(path) || path.startsWith("/api/"))) {
            throw new IllegalArgumentException("Yalnız /health ve /api/ yollarına izin verilir.");
        }
        return origin(base) + relative.toASCIIString();
    }

    public static String resolveSameOriginDownload(String serverBase, String candidate) {
        URI base = parseBase(serverBase);
        String value = candidate == null ? "" : candidate.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("İndirme adresi boş.");

        URI target;
        if (value.startsWith("/")) {
            URI relative = parseRelative(value);
            target = URI.create(origin(base) + relative.toASCIIString());
        } else {
            try {
                target = new URI(value);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("İndirme adresi geçersiz.", e);
            }
            validateHttpUri(target, false);
        }

        if (!sameOrigin(base, target)) {
            throw new IllegalArgumentException("APK indirmesi yalnız aynı sunucu origin'inden yapılabilir.");
        }
        rejectTraversal(target.getRawPath());
        rejectTraversal(target.getPath());
        return target.toASCIIString();
    }

    private static URI parseBase(String serverBase) {
        String value = serverBase == null ? "" : serverBase.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) throw new IllegalArgumentException("Sunucu adresi boş.");
        try {
            URI base = new URI(value);
            validateHttpUri(base, true);
            String path = base.getRawPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                throw new IllegalArgumentException("Sunucu adresi path içermemeli.");
            }
            if (base.getRawQuery() != null || base.getRawFragment() != null) {
                throw new IllegalArgumentException("Sunucu adresi query/fragment içermemeli.");
            }
            return base;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Sunucu adresi geçersiz.", e);
        }
    }

    private static URI parseRelative(String pathAndQuery) {
        String value = pathAndQuery == null ? "" : pathAndQuery.trim();
        if (!value.startsWith("/") || value.startsWith("//")) {
            throw new IllegalArgumentException("API yolu / ile başlamalı.");
        }
        try {
            URI relative = new URI(value);
            if (relative.isAbsolute() || relative.getRawFragment() != null) {
                throw new IllegalArgumentException("API yolu göreli olmalı.");
            }
            rejectTraversal(relative.getRawPath());
            rejectTraversal(relative.getPath());
            return relative;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("API yolu geçersiz.", e);
        }
    }

    private static void validateHttpUri(URI uri, boolean rejectUserInfo) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
            throw new IllegalArgumentException("Adres http:// veya https:// olmalı.");
        }
        if (rejectUserInfo && uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Sunucu adresinde kullanıcı bilgisi kullanılamaz.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Adres kullanıcı bilgisi içeremez.");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Adres fragment içeremez.");
        }
    }

    private static void rejectTraversal(String rawPath) {
        if (rawPath == null) return;
        for (String segment : rawPath.split("/")) {
            if ("..".equals(segment) || ".".equals(segment)) {
                throw new IllegalArgumentException("Path traversal kabul edilmez.");
            }
        }
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme())
            && a.getHost().equalsIgnoreCase(b.getHost())
            && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String origin(URI uri) {
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null).toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Sunucu origin'i üretilemedi.", e);
        }
    }
}
