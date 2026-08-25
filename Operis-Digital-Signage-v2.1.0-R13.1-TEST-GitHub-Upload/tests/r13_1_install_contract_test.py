from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
manifest_path = root / 'app/src/main/AndroidManifest.xml'
gradle_path = root / 'app/build.gradle'
manifest = manifest_path.read_text(encoding='utf-8')
gradle = gradle_path.read_text(encoding='utf-8')

assert "applicationId 'tr.izdeniz.signage'" in gradle
assert "versionCode 20103" in gradle
assert "versionName '2.1.0-r13.1-test'" in gradle
assert 'android:label="Operis Digital Signage"' in manifest
assert '<action android:name="android.intent.action.BOOT_COMPLETED"/>' in manifest
for forbidden in (
    'android.intent.action.USER_UNLOCKED',
    'android.intent.action.MY_PACKAGE_REPLACED',
    'android.intent.action.QUICKBOOT_POWERON',
    'com.htc.intent.action.QUICKBOOT_POWERON',
    'android:launchMode="singleTask"',
):
    assert forbidden not in manifest

ET.parse(manifest_path)
print('r13_1_install_contract: PASS')
boot = (root / 'app/src/main/java/tr/izdeniz/signage/BootReceiver.java').read_text(encoding='utf-8')
assert 'AlarmManager' in boot
assert '5000L' in boot
assert '15000L' in boot
assert 'FLAG_ACTIVITY_NEW_TASK' in boot
assert 'FLAG_ACTIVITY_CLEAR_TOP' in boot
main = (root / 'app/src/main/java/tr/izdeniz/signage/MainActivity.java').read_text(encoding='utf-8')
for label in (
    'Bağlantı Ayarları', 'Bağlantıyı Test Et', 'Merkeze Yeniden Bağlan',
    'Yayını Yenile', 'Telemetri Gönder', 'Cihaz Bilgileri',
    "Player\'ı Yeniden Başlat", 'Uygulamayı Kapat'
):
    assert label in main
assert 'finishAndRemoveTask()' in main
assert 'finishAffinity()' in main
workflow_path = root / '.github/workflows/build-r13-1-test.yml'
assert workflow_path.exists()
workflow = workflow_path.read_text(encoding='utf-8')
for token in (
    'python3 tests/r13_1_install_contract_test.py',
    'python3 tests/ui_contract_test.py',
    'python3 tests/offline_state_contract_test.py',
    'python3 tests/command_contract_test.py',
    'python3 tests/media_cache_contract_test.py',
    'python3 tests/telemetry_contract_test.py',
    'python3 tests/preview_contract_test.py',
    'node tests/schedule_engine_test.mjs',
    'gradle testDebugUnitTest --stacktrace',
    'gradle assembleDebug --stacktrace',
    'zipalign -c -v 4',
    'apksigner verify --verbose --print-certs',
    'aapt dump badging',
    'sha256sum',
):
    assert token in workflow
import re
assert re.search(r"debug\s*\{[^}]*hasReleaseSigning[^}]*signingConfig\s+signingConfigs\.release", gradle, re.S)
