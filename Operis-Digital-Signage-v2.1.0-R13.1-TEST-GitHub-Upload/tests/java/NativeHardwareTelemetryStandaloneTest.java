import tr.izdeniz.signage.NativeHardwareTelemetry;

public final class NativeHardwareTelemetryStandaloneTest {
    private static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }
    public static void main(String[] args) {
        Double t = NativeHardwareTelemetry.parseTemperature("52000");
        check(t != null && Math.abs(t - 52.0) < 0.01, "milli Celsius parse failed");
        check(NativeHardwareTelemetry.parseTemperature("not-a-number") == null, "nonsense temp accepted");
        Double busy = NativeHardwareTelemetry.parseBusyPair("25 100");
        check(busy != null && Math.abs(busy - 25.0) < 0.01, "busy pair parse failed");
        check(NativeHardwareTelemetry.parseBusyPair("10 0") == null, "zero total accepted");
        System.out.println("NativeHardwareTelemetryStandaloneTest: PASS");
    }
}
