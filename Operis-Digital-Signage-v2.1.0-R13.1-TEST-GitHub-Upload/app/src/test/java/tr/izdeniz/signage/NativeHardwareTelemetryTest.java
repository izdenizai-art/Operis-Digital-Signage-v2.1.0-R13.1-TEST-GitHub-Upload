package tr.izdeniz.signage;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativeHardwareTelemetryTest {
    @Test public void parsesThermalMilliCelsius() {
        assertEquals(52.0, NativeHardwareTelemetry.parseTemperature("52000"), 0.01);
    }
    @Test public void rejectsNonsenseTemperature() {
        assertNull(NativeHardwareTelemetry.parseTemperature("not-a-number"));
    }
    @Test public void parsesKgslBusyPair() {
        assertEquals(25.0, NativeHardwareTelemetry.parseBusyPair("25 100"), 0.01);
    }
}
