from pathlib import Path
root = Path(__file__).resolve().parents[1]
index = (root/'app/src/main/assets/index.html').read_text(encoding='utf-8')
player = (root/'player.js').read_text(encoding='utf-8')
for token in ('#071B2A','#0D334B','#00B9B5','Operis Digital Signage'):
    assert token.lower() in index.lower() or token in player
assert 'İZDENİZ' not in index
assert 'İZDENİZ' not in player
for token in (
    'fontFamily','fontSize','textColor','fontWeight','align','lineHeight',
    'letterSpacing','textTransform','background','opacity','zIndex','componentStyle'
):
    assert token in player
for kind in (
    'LOGO','STATION_NAME','CLOCK','DATE','CLOCK_DATE','FIRST_TRIPS',
    'SCHEDULE_TABLE','ANNOUNCEMENT','TICKER','IMAGE','VIDEO','WEATHER',
    'FREE_TEXT','MEDIA_REGION'
):
    assert kind in player
for token in ('baseCanvas','scheduledCanvas','renderBaseLayout','renderScheduledLayer','clearScheduledLayer'):
    assert token in index or token in player
assert '<script src="schedule_engine.js"></script>' in index
assert '<script src="player.js"></script>' in index
assert 'type="module"' not in index
asset_player = root/'app/src/main/assets/player.js'
assert asset_player.exists()
assert asset_player.read_text(encoding='utf-8') == player
print('ui_contract_test: PASS')
