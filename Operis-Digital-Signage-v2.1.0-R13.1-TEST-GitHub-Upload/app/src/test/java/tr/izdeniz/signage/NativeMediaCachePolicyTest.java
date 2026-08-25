package tr.izdeniz.signage;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativeMediaCachePolicyTest {
    @Test public void mediaUrlMustBeSameOriginAndRelative() {
        assertTrue(NativeMediaCache.isAllowedRelativeMediaPath("/media/a.mp4"));
        assertFalse(NativeMediaCache.isAllowedRelativeMediaPath("https://evil.example/a.mp4"));
        assertFalse(NativeMediaCache.isAllowedRelativeMediaPath("//evil.example/a.mp4"));
        assertFalse(NativeMediaCache.isAllowedRelativeMediaPath("../a.mp4"));
        assertFalse(NativeMediaCache.isAllowedRelativeMediaPath("/media/../secret"));
    }
}
