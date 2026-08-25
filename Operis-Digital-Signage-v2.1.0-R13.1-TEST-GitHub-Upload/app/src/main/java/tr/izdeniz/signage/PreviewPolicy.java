package tr.izdeniz.signage;

/** Pure sizing/quality policy for player previews. */
public final class PreviewPolicy {
    private PreviewPolicy() {}
    public static int clampQuality(int value) { return Math.max(40, Math.min(85, value)); }
    public static int previewWidth(int sourceWidth) { return Math.max(1, Math.min(1280, sourceWidth)); }
}
