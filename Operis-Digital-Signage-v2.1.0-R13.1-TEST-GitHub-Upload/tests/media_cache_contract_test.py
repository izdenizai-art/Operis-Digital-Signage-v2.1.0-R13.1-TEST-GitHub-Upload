from pathlib import Path
root = Path(__file__).resolve().parents[1]
main = (root/'app/src/main/java/tr/izdeniz/signage/MainActivity.java').read_text(encoding='utf-8')
cache_path = root/'app/src/main/java/tr/izdeniz/signage/NativeMediaCache.java'
assert cache_path.exists()
cache = cache_path.read_text(encoding='utf-8')
for token in ('downloadAndVerify','https://operis.local/media/','MessageDigest','ATOMIC_MOVE','isAllowedRelativeMediaPath'):
    assert token in cache
for token in ('downloadMediaAsync','getCachedMediaUrl','getMediaCacheStatusJson','shouldInterceptRequest'):
    assert token in main
print('media_cache_contract_test: PASS')
