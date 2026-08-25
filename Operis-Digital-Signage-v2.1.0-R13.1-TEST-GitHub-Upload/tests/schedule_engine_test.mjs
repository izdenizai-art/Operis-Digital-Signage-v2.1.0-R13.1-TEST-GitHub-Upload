import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const code = fs.readFileSync(new URL('../app/src/main/assets/schedule_engine.js', import.meta.url), 'utf8');
const context = { window: {} };
vm.createContext(context);
vm.runInContext(code, context);
const { resolveSchedule, playlistItemAt } = context.window.OperisScheduleEngine;
assert.equal(typeof resolveSchedule, 'function');
assert.equal(typeof playlistItemAt, 'function');

const now = Date.parse('2026-08-25T10:05:00+03:00');
const state = {
  operatingMode: 'PASSENGER',
  baseLayout: { id: 'passenger-main' },
  playlists: [{ id: 'p1', items: [
    { type: 'IMAGE', mediaId: 'a', durationMs: 5000 },
    { type: 'VIDEO', mediaId: 'b', durationMs: 10000 }
  ] }],
  schedules: [{ id: 's1', playlistId: 'p1', startAt: '2026-08-25T10:00:00+03:00', endAt: '2026-08-25T10:15:00+03:00', priority: 10, target: 'FULLSCREEN' }]
};
let r = resolveSchedule(now, state);
assert.equal(r.mode, 'PASSENGER');
assert.equal(r.activeSchedule.id, 's1');
assert.equal(r.playlist.id, 'p1');
assert.equal(r.baseLayout.id, 'passenger-main');
assert.equal(r.target, 'FULLSCREEN');
assert.equal(resolveSchedule(Date.parse('2026-08-25T10:16:00+03:00'), state).activeSchedule, null);
const priorityState = structuredClone(state);
priorityState.schedules.push({ id: 's2', playlistId: 'p1', startAt: '2026-08-25T10:00:00+03:00', endAt: '2026-08-25T10:15:00+03:00', priority: 50, target: 'REGION:RIGHT' });
r = resolveSchedule(now, priorityState);
assert.equal(r.activeSchedule.id, 's2');
assert.equal(r.target, 'REGION:RIGHT');
const dailyState = { operatingMode:'ADVERTISING', baseLayout:null, playlists:[{id:'ads',items:[{type:'IMAGE',mediaId:'x',durationMs:3000}]}], schedules:[{id:'daily',playlistId:'ads',dailyStart:'10:00',dailyEnd:'10:10',timezoneOffsetMinutes:180,priority:1}] };
assert.equal(resolveSchedule(now,dailyState).activeSchedule.id,'daily');
assert.equal(resolveSchedule(Date.parse('2026-08-25T10:11:00+03:00'),dailyState).activeSchedule,null);
const mixedState = structuredClone(state); mixedState.operatingMode='MIXED'; mixedState.schedules[0].target='REGION:MEDIA1';
r=resolveSchedule(now,mixedState);assert.equal(r.mode,'MIXED');assert.equal(r.target,'REGION:MEDIA1');
const playlist={id:'loop',loop:true,items:[{mediaId:'a',durationMs:5000},{mediaId:'b',durationMs:10000}]};
assert.equal(playlistItemAt(1000,0,playlist).item.mediaId,'a');
assert.equal(playlistItemAt(6000,0,playlist).item.mediaId,'b');
assert.equal(playlistItemAt(16000,0,playlist).item.mediaId,'a');
console.log('schedule_engine_test: PASS');
