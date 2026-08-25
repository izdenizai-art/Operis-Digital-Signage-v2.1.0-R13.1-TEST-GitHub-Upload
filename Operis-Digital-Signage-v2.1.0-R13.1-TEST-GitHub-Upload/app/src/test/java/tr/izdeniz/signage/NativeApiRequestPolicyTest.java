package tr.izdeniz.signage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class NativeApiRequestPolicyTest {
    @Test public void buildsOnlyApprovedNativeApiPaths() {
        assertEquals(
            "http://10.20.128.14:8080/api/device/heartbeat?x=1",
            NativeApiRequestPolicy.buildApiUrl(
                "http://10.20.128.14:8080", "/api/device/heartbeat?x=1"));
        assertEquals(
            "http://10.20.128.14:8080/health",
            NativeApiRequestPolicy.buildApiUrl(
                "http://10.20.128.14:8080", "/health"));
    }

    @Test public void rejectsCrossOriginAndTraversalDownloads() {
        assertRejected(() -> NativeApiRequestPolicy.resolveSameOriginDownload(
            "http://10.20.128.14:8080", "https://evil.example/update.apk"));
        assertRejected(() -> NativeApiRequestPolicy.buildApiUrl(
            "http://10.20.128.14:8080", "/api/../admin"));
        assertRejected(() -> NativeApiRequestPolicy.buildApiUrl(
            "http://10.20.128.14:8080", "/api/%2e%2e/admin"));
    }

    private static void assertRejected(Action action) {
        try {
            action.run();
            fail("Expected request policy rejection");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private interface Action { void run(); }
}
