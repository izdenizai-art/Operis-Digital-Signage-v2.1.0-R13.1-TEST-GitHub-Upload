from pathlib import Path
root = Path(__file__).resolve().parents[1]
player = (root/'player.js').read_text(encoding='utf-8')
index = (root/'app/src/main/assets/index.html').read_text(encoding='utf-8')
for token in (
    'operis_trusted_time_anchor_v2',
    'operis_last_publication_v1',
    'operis_schedule_runtime_v1',
    'restoreTrustedTimeAnchor',
    'savePublicationState',
    'loadPublicationState',
    'PASSENGER','ADVERTISING','MIXED','PLAYLIST',
    'RETURN_TO_BASE_LAYOUT','renderScheduledLayer','renderBaseLayout',
):
    assert token in player
for token in ('baseCanvas','scheduledCanvas'):
    assert token in index
print('offline_state_contract_test: PASS')
