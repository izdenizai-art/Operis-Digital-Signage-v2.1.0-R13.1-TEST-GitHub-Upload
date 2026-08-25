from pathlib import Path
root=Path(__file__).resolve().parents[1]
cap=root/'app/src/main/java/tr/izdeniz/signage/PlayerPreviewCapture.java'
assert cap.exists()
text=cap.read_text(encoding='utf-8')
for token in ('Bitmap.createBitmap','webView.draw','JPEG','1280','clampQuality'):
    assert token in text
main=(root/'app/src/main/java/tr/izdeniz/signage/MainActivity.java').read_text(encoding='utf-8')
for token in ('captureAndUploadPreviewAsync','/api/device/preview','JpegBase64','CapturedAt','Width','Height'):
    assert token in main
print('preview_contract_test: PASS')
