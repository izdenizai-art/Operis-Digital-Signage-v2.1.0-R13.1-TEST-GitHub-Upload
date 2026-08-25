package tr.izdeniz.signage;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlayerPreviewCapturePolicyTest {
    @Test public void jpegQualityIsBounded() {
        assertEquals(70, PlayerPreviewCapture.clampQuality(70));
        assertEquals(40, PlayerPreviewCapture.clampQuality(10));
        assertEquals(85, PlayerPreviewCapture.clampQuality(99));
    }
}
