from pathlib import Path
root=Path(__file__).resolve().parents[1]
text=(root/'app/src/main/java/tr/izdeniz/signage/NativeTelemetryCollector.java').read_text(encoding='utf-8')
for token in ('NativeHardwareTelemetry.readCpuUsagePercent','NativeHardwareTelemetry.readCpuTemperatureC','NativeHardwareTelemetry.readGpuUsagePercent','NativeHardwareTelemetry.readGpuTemperatureC','wolMacAddress','wolCandidate','gpuUsagePercent','gpuTemperatureC'):
    assert token in text
print('telemetry_contract_test: PASS')
main=(root/'app/src/main/java/tr/izdeniz/signage/MainActivity.java').read_text(encoding='utf-8')
for token in ('ramUsedPercent','cpuUsagePercent','cpuTemperatureC','gpuUsagePercent','gpuTemperatureC','wolMacAddress'):
    assert token in main
