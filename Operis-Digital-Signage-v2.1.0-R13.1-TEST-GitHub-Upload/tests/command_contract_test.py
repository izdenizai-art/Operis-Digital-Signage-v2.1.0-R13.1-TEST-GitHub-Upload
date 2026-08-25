from pathlib import Path
root = Path(__file__).resolve().parents[1]
player = (root/'player.js').read_text(encoding='utf-8')
for command in (
    'CONNECTION_REFRESH','REFRESH','SCREEN_REFRESH','PROFILE_SYNC','TIME_REFRESH',
    'TELEMETRY_REFRESH','PLAYLIST_REFRESH','SCHEDULE_REFRESH','RETURN_TO_BASE_LAYOUT',
    'SCREEN_PREVIEW_REFRESH','DEVICE_REBOOT','KIOSK_LOCK','KIOSK_UNLOCK','POWER_ON'
):
    assert command in player
assert 'captureAndUploadPreviewAsync' in player
assert 'ackCommand' in player
print('command_contract_test: PASS')
