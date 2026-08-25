package tr.izdeniz.signage;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.Base64;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;

/** Captures only the Operis WebView surface for remote preview. */
public final class PlayerPreviewCapture {
    private PlayerPreviewCapture() {}

    public static int clampQuality(int value) {
        return PreviewPolicy.clampQuality(value);
    }

    public static Capture capture(WebView webView) {
        if (webView == null) throw new IllegalArgumentException("WebView yok.");
        int sourceWidth = webView.getWidth();
        int sourceHeight = webView.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) throw new IllegalStateException("Player yüzeyi henüz hazır değil.");

        int width = PreviewPolicy.previewWidth(sourceWidth); // maximum 1280 px wide
        int height = Math.max(1, (int) Math.round(sourceHeight * (width / (double) sourceWidth)));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(64 * 1024, width * height / 4));
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(width / (float) sourceWidth, height / (float) sourceHeight);
            webView.draw(canvas);
            int quality = clampQuality(70);
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bytes)) {
                throw new IllegalStateException("JPEG önizleme üretilemedi.");
            }
            byte[] jpeg = bytes.toByteArray();
            return new Capture(
                System.currentTimeMillis(),
                width,
                height,
                jpeg.length,
                Base64.encodeToString(jpeg, Base64.NO_WRAP)
            );
        } finally {
            bitmap.recycle();
            try { bytes.close(); } catch (Exception ignored) {}
        }
    }

    public static final class Capture {
        public final long capturedAtMs;
        public final int width;
        public final int height;
        public final int bytes;
        public final String jpegBase64;

        Capture(long capturedAtMs, int width, int height, int bytes, String jpegBase64) {
            this.capturedAtMs = capturedAtMs;
            this.width = width;
            this.height = height;
            this.bytes = bytes;
            this.jpegBase64 = jpegBase64;
        }
    }
}
