import tr.izdeniz.signage.MediaCachePolicy;

public final class MediaCachePolicyStandaloneTest {
    private static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }
    public static void main(String[] args) {
        check(MediaCachePolicy.isAllowedRelativeMediaPath("/media/a.mp4"), "relative media should pass");
        check(MediaCachePolicy.isAllowedRelativeMediaPath("/media/folder/a.jpg"), "nested media should pass");
        check(!MediaCachePolicy.isAllowedRelativeMediaPath("https://evil.example/a.mp4"), "absolute URL must fail");
        check(!MediaCachePolicy.isAllowedRelativeMediaPath("//evil.example/a.mp4"), "scheme relative URL must fail");
        check(!MediaCachePolicy.isAllowedRelativeMediaPath("../a.mp4"), "traversal must fail");
        check(!MediaCachePolicy.isAllowedRelativeMediaPath("/media/../secret"), "embedded traversal must fail");
        check("abc-123_X".equals(MediaCachePolicy.safeMediaId("abc-123_X")), "safe media id changed");
        check(MediaCachePolicy.safeMediaId("../x") == null, "unsafe media id must fail");
        System.out.println("MediaCachePolicyStandaloneTest: PASS");
    }
}
